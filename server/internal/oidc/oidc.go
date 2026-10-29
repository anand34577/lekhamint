// Package oidc signs people in through any OpenID Connect provider (Keycloak,
// Authentik, Authelia, Google, ...) using the authorization-code flow with PKCE.
//
// The server talks to the provider's token and userinfo endpoints directly over
// TLS, so the identity it learns comes straight from the provider; it does not
// need to parse or verify an ID token.
package oidc

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Config is the provider registration.
type Config struct {
	Issuer       string // e.g. https://auth.example.com/realms/home
	ClientID     string
	ClientSecret string // empty for a public client (PKCE only)
	RedirectURL  string // PublicURL + /api/v1/auth/oidc/callback
	Scopes       []string
	DisplayName  string // shown on the sign-in button
	// AllowUnverifiedEmail accepts identities whose provider never sends the
	// email_verified claim. Accounts are additionally bound to the provider's
	// subject on first sign-in, so this is safe only for providers whose
	// addresses the administrator trusts.
	AllowUnverifiedEmail bool
}

// Identity is who the provider says signed in.
type Identity struct {
	Subject       string
	Email         string
	EmailVerified bool
	Name          string
}

// Client runs the flow. It keeps in-memory state for flows in progress, which
// is enough for one server process; a restart only cancels sign-ins mid-way.
type Client struct {
	Config
	HTTP *http.Client

	mu      sync.Mutex
	meta    *metadata
	metaAt  time.Time
	pending map[string]pending // by state
	codes   map[string]grant   // one-time codes handed to the browser
}

type metadata struct {
	AuthorizationEndpoint string `json:"authorization_endpoint"`
	TokenEndpoint         string `json:"token_endpoint"`
	UserinfoEndpoint      string `json:"userinfo_endpoint"`
	Issuer                string `json:"issuer"`
}

type pending struct {
	verifier, device string
	at               time.Time
}

type grant struct {
	userID, device string
	at             time.Time
}

// ErrFlow is returned for a sign-in attempt that cannot continue. Its message
// is safe to show to the person.
var ErrFlow = errors.New("sign-in failed")

func (c *Client) client() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{Timeout: 15 * time.Second}
}

func randString(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func (c *Client) discover(ctx context.Context) (*metadata, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.meta != nil && time.Since(c.metaAt) < time.Hour {
		return c.meta, nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(c.Issuer, "/")+"/.well-known/openid-configuration", nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("reach identity provider: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("identity provider discovery answered %d", resp.StatusCode)
	}
	var m metadata
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&m); err != nil {
		return nil, fmt.Errorf("identity provider discovery: %w", err)
	}
	if m.AuthorizationEndpoint == "" || m.TokenEndpoint == "" || m.UserinfoEndpoint == "" {
		return nil, errors.New("identity provider discovery is missing authorization, token or userinfo endpoints")
	}
	c.meta, c.metaAt = &m, time.Now()
	return c.meta, nil
}

// Check fetches the provider's discovery document; used at startup to catch a
// wrong issuer early.
func (c *Client) Check(ctx context.Context) error { _, err := c.discover(ctx); return err }

// Start returns the provider URL to send the browser to. [device] is the name
// the new session will carry.
func (c *Client) Start(ctx context.Context, device string) (string, error) {
	m, err := c.discover(ctx)
	if err != nil {
		return "", err
	}
	state, verifier := randString(24), randString(32)
	sum := sha256.Sum256([]byte(verifier))
	c.mu.Lock()
	now := time.Now()
	for k, p := range c.pending {
		if now.Sub(p.at) > 10*time.Minute {
			delete(c.pending, k)
		}
	}
	if len(c.pending) > 1000 {
		c.mu.Unlock()
		return "", errors.New("too many sign-ins in progress")
	}
	if c.pending == nil {
		c.pending = map[string]pending{}
	}
	c.pending[state] = pending{verifier: verifier, device: device, at: now}
	c.mu.Unlock()
	q := url.Values{
		"response_type": {"code"}, "client_id": {c.ClientID}, "redirect_uri": {c.RedirectURL},
		"scope": {strings.Join(c.Scopes, " ")}, "state": {state},
		"code_challenge": {base64.RawURLEncoding.EncodeToString(sum[:])}, "code_challenge_method": {"S256"},
	}
	sep := "?"
	if strings.Contains(m.AuthorizationEndpoint, "?") {
		sep = "&"
	}
	return m.AuthorizationEndpoint + sep + q.Encode(), nil
}

// Finish completes the flow from the provider's redirect and returns who signed in
// and the device name chosen at Start.
func (c *Client) Finish(ctx context.Context, state, code string) (Identity, string, error) {
	c.mu.Lock()
	p, ok := c.pending[state]
	delete(c.pending, state) // a state works once
	c.mu.Unlock()
	if !ok || time.Since(p.at) > 10*time.Minute || code == "" {
		return Identity{}, "", fmt.Errorf("%w: the sign-in link expired; try again", ErrFlow)
	}
	m, err := c.discover(ctx)
	if err != nil {
		return Identity{}, "", err
	}
	form := url.Values{"grant_type": {"authorization_code"}, "code": {code}, "redirect_uri": {c.RedirectURL},
		"client_id": {c.ClientID}, "code_verifier": {p.verifier}}
	if c.ClientSecret != "" {
		form.Set("client_secret", c.ClientSecret)
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, m.TokenEndpoint, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	resp, err := c.client().Do(req)
	if err != nil {
		return Identity{}, "", fmt.Errorf("reach identity provider: %w", err)
	}
	defer resp.Body.Close()
	var tok struct {
		AccessToken string `json:"access_token"`
		Error       string `json:"error"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tok); err != nil || resp.StatusCode != http.StatusOK || tok.AccessToken == "" {
		return Identity{}, "", fmt.Errorf("%w: the identity provider refused the sign-in (%s)", ErrFlow, firstNonEmpty(tok.Error, resp.Status))
	}

	ureq, _ := http.NewRequestWithContext(ctx, http.MethodGet, m.UserinfoEndpoint, nil)
	ureq.Header.Set("Authorization", "Bearer "+tok.AccessToken)
	ureq.Header.Set("Accept", "application/json")
	uresp, err := c.client().Do(ureq)
	if err != nil {
		return Identity{}, "", fmt.Errorf("reach identity provider: %w", err)
	}
	defer uresp.Body.Close()
	var info struct {
		Sub               string `json:"sub"`
		Email             string `json:"email"`
		EmailVerified     any    `json:"email_verified"` // bool, or "true" from some providers
		Name              string `json:"name"`
		PreferredUsername string `json:"preferred_username"`
	}
	if uresp.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(uresp.Body, 1<<20)).Decode(&info) != nil || info.Sub == "" {
		return Identity{}, "", fmt.Errorf("%w: could not read your profile from the identity provider", ErrFlow)
	}
	id := Identity{Subject: info.Sub, Email: strings.TrimSpace(info.Email), Name: firstNonEmpty(info.Name, info.PreferredUsername)}
	switch v := info.EmailVerified.(type) {
	case bool:
		id.EmailVerified = v
	case string:
		id.EmailVerified = strings.EqualFold(v, "true")
	case nil:
		// The provider does not send the claim. Treat it as unverified unless
		// the administrator explicitly opted in for this provider; a provider
		// that sends an explicit false is always refused by the caller.
		id.EmailVerified = c.AllowUnverifiedEmail
	}
	if id.Email == "" {
		return Identity{}, "", fmt.Errorf("%w: the identity provider did not share an email address (request the email scope)", ErrFlow)
	}
	return id, p.device, nil
}

// GrantTTL is how long the browser has to trade a one-time sign-in code, long enough
// to type a two-step code when the account has one.
const GrantTTL = 5 * time.Minute

// StashUser remembers who the provider signed in, on which device, and returns the
// one-time code the browser trades for a session. No session exists until then, so
// nothing usable appears in a URL, and an account with two-step sign-in can be asked
// for its code first.
func (c *Client) StashUser(userID, device string) string {
	code := randString(24)
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.codes == nil {
		c.codes = map[string]grant{}
	}
	now := time.Now()
	for k, g := range c.codes {
		if now.Sub(g.at) > GrantTTL {
			delete(c.codes, k)
		}
	}
	c.codes[code] = grant{userID: userID, device: device, at: now}
	return code
}

// TakeUser returns who a one-time code signs in and ends the code, so two requests
// cannot both use it. PutBack returns it for a retry (after a wrong two-step code)
// with its original expiry.
func (c *Client) TakeUser(code string) (Grant, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	g, ok := c.codes[code]
	delete(c.codes, code)
	if !ok || time.Since(g.at) > GrantTTL {
		return Grant{}, false
	}
	return Grant{UserID: g.userID, Device: g.device, at: g.at}, true
}

// PutBack makes a code taken by TakeUser usable again until it would have expired.
func (c *Client) PutBack(code string, g Grant) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.codes == nil {
		c.codes = map[string]grant{}
	}
	c.codes[code] = grant{userID: g.UserID, device: g.Device, at: g.at}
}

// Grant is who a one-time sign-in code is for.
type Grant struct {
	UserID, Device string
	at             time.Time
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}
