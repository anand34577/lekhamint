package httpapi

import (
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/oidc"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// oidcInfo describes single sign-on for /meta.
func (s *Server) oidcInfo() map[string]any {
	if s.OIDC == nil {
		return map[string]any{"enabled": false}
	}
	return map[string]any{"enabled": true, "name": s.OIDC.DisplayName, "only": s.OIDCOnly}
}

var errPasswordOff = errors.New("password sign-in is turned off on this server; use single sign-on")

// ssoRedirect sends the browser back to the web app with a result in the URL fragment,
// which browsers never send to servers or put in access logs.
func ssoRedirect(w http.ResponseWriter, r *http.Request, route string, q url.Values) {
	http.Redirect(w, r, "/#/"+route+"?"+q.Encode(), http.StatusSeeOther)
}

func (s *Server) oidcStart(w http.ResponseWriter, r *http.Request) {
	if s.OIDC == nil {
		writeError(w, http.StatusNotFound, "not_found", "single sign-on is not set up on this server", nil)
		return
	}
	device := strings.TrimSpace(r.URL.Query().Get("device"))
	if device == "" {
		device = "Web browser (" + s.OIDC.DisplayName + ")"
	}
	dest, err := s.OIDC.Start(r.Context(), device)
	if err != nil {
		s.Log.Warn("single sign-on start failed", "err", err)
		ssoRedirect(w, r, "login", url.Values{"sso_error": {"Could not reach the identity provider. Try again, or ask the administrator."}})
		return
	}
	http.Redirect(w, r, dest, http.StatusFound)
}

func (s *Server) oidcCallback(w http.ResponseWriter, r *http.Request) {
	if s.OIDC == nil {
		writeError(w, http.StatusNotFound, "not_found", "single sign-on is not set up on this server", nil)
		return
	}
	q := r.URL.Query()
	fail := func(msg string) { ssoRedirect(w, r, "login", url.Values{"sso_error": {msg}}) }
	if e := q.Get("error"); e != "" {
		fail("The identity provider did not sign you in (" + e + ").")
		return
	}
	id, device, err := s.OIDC.Finish(r.Context(), q.Get("state"), q.Get("code"))
	if err != nil {
		if errors.Is(err, oidc.ErrFlow) {
			fail(strings.TrimPrefix(err.Error(), oidc.ErrFlow.Error()+": "))
		} else {
			s.Log.Warn("single sign-on failed", "err", err)
			fail("Could not complete sign-in with the identity provider.")
		}
		return
	}
	if !id.EmailVerified {
		fail("Your email address is not verified at the identity provider.")
		return
	}
	u, token, err := s.Auth.SSOLogin(r.Context(), id.Email, id.Name, device, s.OIDCAutoCreate)
	if err != nil {
		switch {
		case errors.Is(err, auth.ErrSSOClosed), errors.Is(err, auth.ErrAccountDisabled), errors.Is(err, auth.ErrInvalidEmail):
			fail(err.Error())
		default:
			s.Log.Error("single sign-on account failed", "err", err)
			fail("Could not open your account.")
		}
		return
	}
	s.securityNotice(u, "New sign-in", "Your account was just signed in through "+s.OIDC.DisplayName+" on "+device+" at "+time.Now().Format("2 Jan 2006 15:04 MST")+". If this was not you, sign out other devices under Settings and change your provider password.")
	ssoRedirect(w, r, "sso", url.Values{"code": {s.OIDC.StashToken(token)}})
}

// oidcExchange trades the one-time code from the redirect for the session token.
func (s *Server) oidcExchange(w http.ResponseWriter, r *http.Request) {
	if s.OIDC == nil {
		writeError(w, http.StatusNotFound, "not_found", "single sign-on is not set up on this server", nil)
		return
	}
	var in struct {
		Code string `json:"code"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	token, ok := s.OIDC.TakeToken(in.Code)
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "the sign-in link expired; try again", nil)
		return
	}
	u, _, err := s.Auth.Authenticate(r.Context(), token)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"user": u, "token": token})
}

// securityNotice tells the person about a security-relevant event, if they asked for such messages.
func (s *Server) securityNotice(u store.User, subject, body string) {
	if s.Notice == nil {
		return
	}
	go s.Notice.NotifyUser(u, "security", subject, body)
}
