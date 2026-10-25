package httpapi

import (
	"net/http"
	"strings"

	"github.com/anand34577/lekhamint/server/internal/notify"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// prefsView is the preferences as the browser sees them: the Gotify token is
// reported only as set or not.
func prefsView(p store.Prefs) map[string]any {
	return map[string]any{
		"notify_email": p.NotifyEmail, "notify_gotify": p.NotifyGotify, "gotify_token_set": p.GotifyToken != "",
		"ev_security": p.EvSecurity, "ev_due": p.EvDue, "ev_budget": p.EvBudget,
	}
}

func (s *Server) getPrefs(w http.ResponseWriter, r *http.Request) {
	p, err := store.GetPrefs(r.Context(), s.DB, currentUser(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := prefsView(p)
	out["channels"] = s.notifier().Info()
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) notifier() *notify.Notifier {
	if s.Notice == nil {
		return nil
	}
	return s.Notice.Notifier
}

func (s *Server) putPrefs(w http.ResponseWriter, r *http.Request) {
	var in struct {
		NotifyEmail  *bool   `json:"notify_email"`
		NotifyGotify *bool   `json:"notify_gotify"`
		GotifyToken  *string `json:"gotify_token"` // nil keeps the saved one; "" clears it
		EvSecurity   *bool   `json:"ev_security"`
		EvDue        *bool   `json:"ev_due"`
		EvBudget     *bool   `json:"ev_budget"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u := currentUser(r)
	p, err := store.GetPrefs(r.Context(), s.DB, u.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	set := func(dst *bool, v *bool) {
		if v != nil {
			*dst = *v
		}
	}
	set(&p.NotifyEmail, in.NotifyEmail)
	set(&p.NotifyGotify, in.NotifyGotify)
	set(&p.EvSecurity, in.EvSecurity)
	set(&p.EvDue, in.EvDue)
	set(&p.EvBudget, in.EvBudget)
	if in.GotifyToken != nil {
		tok := strings.TrimSpace(*in.GotifyToken)
		if len(tok) > 200 || strings.ContainsAny(tok, " \r\n\t") {
			s.fail(w, r, badReq("the Gotify token looks wrong: it is a short code without spaces"))
			return
		}
		p.GotifyToken = tok
	}
	if p.NotifyEmail && !s.notifier().HasEmail() {
		s.fail(w, r, badReq("email is not set up on this server"))
		return
	}
	if p.NotifyGotify {
		if !s.notifier().HasGotify() {
			s.fail(w, r, badReq("Gotify is not set up on this server"))
			return
		}
		if p.GotifyToken == "" {
			s.fail(w, r, badReq("add your Gotify application token to turn Gotify on"))
			return
		}
	}
	if err := store.PutPrefs(r.Context(), s.DB, p); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, prefsView(p))
}

// testNotification sends a sample to one channel so the person can see it works and
// read the exact error when it does not.
func (s *Server) testNotification(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Channel     string  `json:"channel"`
		GotifyToken *string `json:"gotify_token"` // test a token before saving it
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	n := s.notifier()
	u := currentUser(r)
	var err error
	switch in.Channel {
	case "email":
		err = n.Email(r.Context(), u.Email, "Test message", "Hi "+u.Name+",\n\nThis is a test message. Email notifications from LekhaMint are working.")
	case "gotify":
		p, perr := store.GetPrefs(r.Context(), s.DB, u.ID)
		if perr != nil {
			s.fail(w, r, perr)
			return
		}
		tok := p.GotifyToken
		if in.GotifyToken != nil && strings.TrimSpace(*in.GotifyToken) != "" {
			tok = strings.TrimSpace(*in.GotifyToken)
		}
		err = n.GotifyMessage(r.Context(), tok, "LekhaMint test", "Gotify notifications from LekhaMint are working.", 4)
	default:
		s.fail(w, r, badReq(`channel must be "email" or "gotify"`))
		return
	}
	if err != nil {
		// The message names the failing step (connect, login, recipient...) and carries no secrets.
		writeError(w, http.StatusBadGateway, "delivery_failed", err.Error(), nil)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sent": true})
}
