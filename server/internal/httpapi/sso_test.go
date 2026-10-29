package httpapi_test

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/httpapi"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/notify"
	"github.com/anand34577/lekhamint/server/internal/oidc"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

// fakeProvider is a minimal OpenID Connect provider: discovery, token and userinfo.
func fakeProvider(t *testing.T, email string, verified bool) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{"issuer": srv.URL, "authorization_endpoint": srv.URL + "/auth",
			"token_endpoint": srv.URL + "/token", "userinfo_endpoint": srv.URL + "/userinfo"})
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		if r.Form.Get("code") != "good-code" || r.Form.Get("code_verifier") == "" || r.Form.Get("client_secret") != "shh" {
			w.WriteHeader(http.StatusBadRequest)
			io.WriteString(w, `{"error":"invalid_grant"}`)
			return
		}
		io.WriteString(w, `{"access_token":"at-1","token_type":"Bearer"}`)
	})
	mux.HandleFunc("/userinfo", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer at-1" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"sub": "u-1", "email": email, "email_verified": verified, "name": "Asha Rao"})
	})
	srv = httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func ssoServer(t *testing.T, idp string, auto, only bool, gotify *httptest.Server) (string, *notify.Notifier) {
	t.Helper()
	d := testutil.NewDB(t)
	sync := syncer.New(d)
	n := &notify.Notifier{}
	if gotify != nil {
		n.Gotify = &notify.GotifyConfig{URL: gotify.URL}
	}
	api := &httpapi.Server{
		DB: d, Auth: auth.NewService(d, false, time.Hour), Sync: sync,
		Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test", MaxImportSize: 32 << 20,
		OIDCAutoCreate: auto, OIDCOnly: only,
		Notice: &notify.Dispatcher{DB: d, Notifier: n, Log: slog.New(slog.NewTextHandler(io.Discard, nil))},
	}
	srv := httptest.NewServer(api.Handler())
	t.Cleanup(srv.Close)
	api.OIDC = &oidc.Client{Config: oidc.Config{Issuer: idp, ClientID: "kosh", ClientSecret: "shh",
		RedirectURL: srv.URL + "/api/v1/auth/oidc/callback", Scopes: []string{"openid", "email"}, DisplayName: "Keycloak"}}
	return srv.URL, n
}

// noRedirect returns the response of the first hop only.
func noRedirect() *http.Client {
	return &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func TestSingleSignOnFlow(t *testing.T) {
	idp := fakeProvider(t, "asha@example.com", true)
	base, _ := ssoServer(t, idp.URL, true, false, nil)
	hc := noRedirect()

	// /meta announces the provider.
	c := &client{t: t, base: base}
	meta := c.do("GET", "/api/v1/meta", nil, 200)
	if o, _ := meta["oidc"].(map[string]any); o["enabled"] != true || o["name"] != "Keycloak" {
		t.Fatalf("meta.oidc = %v", meta["oidc"])
	}

	// Start sends the browser to the provider with PKCE and a state.
	resp, err := hc.Get(base + "/api/v1/auth/oidc/start")
	if err != nil || resp.StatusCode != http.StatusFound {
		t.Fatalf("start: %v %v", resp, err)
	}
	loc, _ := url.Parse(resp.Header.Get("Location"))
	q := loc.Query()
	if !strings.HasPrefix(loc.String(), idp.URL+"/auth") || q.Get("code_challenge_method") != "S256" || q.Get("state") == "" || q.Get("client_id") != "kosh" {
		t.Fatalf("authorization URL = %s", loc)
	}

	// A callback with an unknown state is refused.
	resp, _ = hc.Get(base + "/api/v1/auth/oidc/callback?state=bogus&code=good-code")
	if got := resp.Header.Get("Location"); !strings.Contains(got, "sso_error") {
		t.Fatalf("unknown state should fail, got %q", got)
	}

	// The real callback creates the account and hands back a one-time code.
	resp, _ = hc.Get(base + "/api/v1/auth/oidc/callback?state=" + q.Get("state") + "&code=good-code")
	loc, _ = url.Parse(resp.Header.Get("Location"))
	frag, _ := url.Parse(strings.TrimPrefix(loc.Fragment, "/"))
	code := frag.Query().Get("code")
	if !strings.HasPrefix(loc.Fragment, "/sso") || code == "" {
		t.Fatalf("callback redirect = %s", resp.Header.Get("Location"))
	}
	out := c.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": code}, 200)
	user := out["user"].(map[string]any)
	if user["email"] != "asha@example.com" || user["is_admin"] != true || user["name"] != "Asha Rao" {
		t.Fatalf("user = %v", user)
	}
	token := out["token"].(string)
	// The code works once.
	c.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": code}, 401)
	// The session works; the account has no password, so it cannot be changed or used to log in.
	c.token = token
	c.do("GET", "/api/v1/auth/me", nil, 200)
	c.do("POST", "/api/v1/auth/password", map[string]string{"current_password": "x", "new_password": "longenough1"}, 400)
	(&client{t: t, base: base}).do("POST", "/api/v1/auth/login", map[string]string{"email": "asha@example.com", "password": NoPW}, 401)
	// Typing the email confirms deletion of a single sign-on account.
	c.do("DELETE", "/api/v1/auth/me", map[string]string{"password": "wrong@example.com"}, 401)
	c.do("DELETE", "/api/v1/auth/me", map[string]string{"password": "asha@example.com"}, 200)
}

const NoPW = auth.NoPassword

func TestSingleSignOnRefusals(t *testing.T) {
	hc := noRedirect()
	callback := func(base string) string {
		resp, _ := hc.Get(base + "/api/v1/auth/oidc/start")
		state := func() string { l, _ := url.Parse(resp.Header.Get("Location")); return l.Query().Get("state") }()
		resp, _ = hc.Get(base + "/api/v1/auth/oidc/callback?state=" + state + "&code=good-code")
		return resp.Header.Get("Location")
	}
	t.Run("unverified email", func(t *testing.T) {
		base, _ := ssoServer(t, fakeProvider(t, "a@example.com", false).URL, true, false, nil)
		if got := callback(base); !strings.Contains(got, "sso_error") || !strings.Contains(got, "not+verified") {
			t.Fatalf("got %s", got)
		}
	})
	t.Run("auto-create off", func(t *testing.T) {
		base, _ := ssoServer(t, fakeProvider(t, "a@example.com", true).URL, false, false, nil)
		// Someone else already owns the server, so an unknown person is not let in.
		(&client{t: t, base: base}).do("POST", "/api/v1/auth/register", map[string]string{"email": "owner@example.com", "password": "longenough1"}, 201)
		if got := callback(base); !strings.Contains(got, "sso_error") || !strings.Contains(got, "invite") {
			t.Fatalf("got %s", got)
		}
	})
	t.Run("password login off", func(t *testing.T) {
		base, _ := ssoServer(t, fakeProvider(t, "a@example.com", true).URL, true, true, nil)
		c := &client{t: t, base: base}
		c.do("POST", "/api/v1/auth/login", map[string]string{"email": "a@example.com", "password": "whatever123"}, 403)
		c.do("POST", "/api/v1/auth/register", map[string]string{"email": "a@example.com", "password": "whatever123"}, 403)
		if m := c.do("GET", "/api/v1/meta", nil, 200); m["signup_allowed"] != false {
			t.Fatalf("signup_allowed = %v", m["signup_allowed"])
		}
	})
}

func TestNotificationPreferences(t *testing.T) {
	var got atomic.Value
	gotify := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Gotify-Key") != "tok123" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		b, _ := io.ReadAll(r.Body)
		got.Store(string(b))
		io.WriteString(w, `{"id":1}`)
	}))
	defer gotify.Close()
	base, _ := ssoServer(t, fakeProvider(t, "a@example.com", true).URL, true, false, gotify)
	c := &client{t: t, base: base}
	reg := c.do("POST", "/api/v1/auth/register", map[string]string{"email": "n@example.com", "password": "longenough1"}, 201)
	c.token = reg["token"].(string)

	p := c.do("GET", "/api/v1/notifications", nil, 200)
	if ch := p["channels"].(map[string]any); ch["gotify"] != true || ch["email"] != false {
		t.Fatalf("channels = %v", ch)
	}
	// Turning on a channel that is missing, or Gotify without a token, is refused.
	c.do("PUT", "/api/v1/notifications", map[string]any{"notify_email": true}, 400)
	c.do("PUT", "/api/v1/notifications", map[string]any{"notify_gotify": true}, 400)
	c.do("PUT", "/api/v1/notifications", map[string]any{"gotify_token": "has space"}, 400)
	// A bad token fails the test; a good one is delivered and then saved.
	c.do("POST", "/api/v1/notifications/test", map[string]any{"channel": "gotify", "gotify_token": "nope"}, 502)
	c.do("POST", "/api/v1/notifications/test", map[string]any{"channel": "gotify", "gotify_token": "tok123"}, 200)
	if s, _ := got.Load().(string); !strings.Contains(s, "LekhaMint test") {
		t.Fatalf("gotify body = %q", s)
	}
	saved := c.do("PUT", "/api/v1/notifications", map[string]any{"notify_gotify": true, "gotify_token": "tok123", "ev_budget": false}, 200)
	if saved["gotify_token_set"] != true || saved["ev_budget"] != false || saved["notify_gotify"] != true {
		t.Fatalf("saved = %v", saved)
	}
	if strings.Contains(saved["_raw"].(string), "tok123") || strings.Contains(c.do("GET", "/api/v1/notifications", nil, 200)["_raw"].(string), "tok123") {
		t.Fatal("the Gotify token must never be returned")
	}
	// Omitting the token keeps it.
	c.do("PUT", "/api/v1/notifications", map[string]any{"ev_due": false}, 200)
	c.do("POST", "/api/v1/notifications/test", map[string]any{"channel": "gotify"}, 200)
}

// ssoCode runs the provider round trip and returns the one-time code for the exchange.
func ssoCode(t *testing.T, base string) string {
	t.Helper()
	hc := noRedirect()
	resp, err := hc.Get(base + "/api/v1/auth/oidc/start")
	if err != nil {
		t.Fatal(err)
	}
	loc, _ := url.Parse(resp.Header.Get("Location"))
	resp, err = hc.Get(base + "/api/v1/auth/oidc/callback?state=" + loc.Query().Get("state") + "&code=good-code")
	if err != nil {
		t.Fatal(err)
	}
	loc, _ = url.Parse(resp.Header.Get("Location"))
	frag, _ := url.Parse(strings.TrimPrefix(loc.Fragment, "/"))
	code := frag.Query().Get("code")
	if code == "" {
		t.Fatalf("callback redirect = %s", resp.Header.Get("Location"))
	}
	return code
}

// Single sign-on stands in for the password, not for two-step sign-in: an account
// with it on is asked for a code before any session exists.
func TestSingleSignOnAsksForTwoStepCode(t *testing.T) {
	idp := fakeProvider(t, "asha@example.com", true)
	base, _ := ssoServer(t, idp.URL, true, false, nil)
	c := &client{t: t, base: base}
	c.token = c.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": ssoCode(t, base)}, 200)["token"].(string)

	secret := c.do("POST", "/api/v1/auth/2fa/setup", map[string]any{}, 200)["secret"].(string)
	now, _ := auth.TOTPCode(secret, time.Now())
	recovery := c.do("POST", "/api/v1/auth/2fa/enable", map[string]string{"code": now}, 200)["recovery_codes"].([]any)

	anon := &client{t: t, base: base}
	code := ssoCode(t, base)
	if e := anon.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": code}, 401)["error"].(map[string]any); e["code"] != "otp_required" {
		t.Fatalf("without a code: %v", e)
	}
	if e := anon.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": code, "otp": "000000"}, 401)["error"].(map[string]any); e["code"] != "invalid_otp" {
		t.Fatalf("wrong code: %v", e)
	}
	out := anon.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": code, "otp": recovery[0].(string)}, 200)
	if out["token"] == "" {
		t.Fatalf("exchange = %v", out)
	}
	// The sign-in code works once.
	anon.do("POST", "/api/v1/auth/oidc/exchange", map[string]string{"code": code, "otp": recovery[1].(string)}, 401)
}
