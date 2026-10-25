// Package httpapi exposes the services over a JSON HTTP API (see docs/07-api.md).
// It uses only the standard library router.
package httpapi

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"runtime/debug"
	"slices"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/notify"
	"github.com/anand34577/lekhamint/server/internal/oidc"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

// Server holds the HTTP layer's dependencies.
type Server struct {
	DB            *db.DB
	Auth          *auth.Service
	Ledgers       *ledger.Service
	Sync          *syncer.Service
	Reports       *report.Service
	Log           *slog.Logger
	Version       string
	CORSOrigins   []string
	TrustProxy    bool
	MaxImportSize int64
	// Web serves the browser app for non-API paths; nil disables it.
	Web http.Handler
	// AI forwards assistant requests to a configured model; nil disables it.
	AI *AIProxy
	// OIDC enables single sign-on; OIDCAutoCreate lets new people create their account on first
	// sign-in, and OIDCOnly turns password sign-in off.
	OIDC           *oidc.Client
	OIDCAutoCreate bool
	OIDCOnly       bool
	// Notice delivers email and Gotify messages; nil disables notifications.
	Notice *notify.Dispatcher

	authLimiter *rateLimiter
	aiLimiter   *rateLimiter
}

// Body size limits.
const (
	smallBody = 64 << 10 // auth and ledger requests
	pushBody  = 16 << 20 // a full push batch
)

// Handler builds the routed, middleware-wrapped handler.
func (s *Server) Handler() http.Handler {
	if s.authLimiter == nil {
		// 10 attempts per minute per IP, bursts of 10.
		s.authLimiter = newRateLimiter(10, time.Minute)
	}
	if s.aiLimiter == nil {
		// 30 assistant questions per minute per signed-in person, so one
		// account cannot spend the server's model budget unbounded.
		s.aiLimiter = newRateLimiter(30, time.Minute)
	}
	mux := http.NewServeMux()
	public := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, h) }
	private := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, s.requireAuth(h)) }

	public("GET /healthz", s.healthz)
	public("GET /readyz", s.readyz)
	public("GET /api/v1/meta", s.meta)
	private("POST /api/v1/ai/chat/completions", s.aiChat)

	public("POST /api/v1/auth/register", s.limited(s.register))
	public("POST /api/v1/auth/login", s.limited(s.login))
	private("POST /api/v1/auth/logout", s.logout)
	private("GET /api/v1/auth/me", s.me)
	private("DELETE /api/v1/auth/me", s.limited(s.deleteMe))
	private("GET /api/v1/auth/sessions", s.listSessions)
	private("DELETE /api/v1/auth/sessions/{id}", s.deleteSession)
	private("POST /api/v1/auth/password", s.changePassword)
	public("GET /api/v1/auth/oidc/start", s.limited(s.oidcStart))
	public("GET /api/v1/auth/oidc/callback", s.limited(s.oidcCallback))
	public("POST /api/v1/auth/oidc/exchange", s.limited(s.oidcExchange))

	private("GET /api/v1/notifications", s.getPrefs)
	private("PUT /api/v1/notifications", s.putPrefs)
	private("POST /api/v1/notifications/test", s.limited(s.testNotification))

	private("GET /api/v1/ai/key", s.getAiKey)
	private("PUT /api/v1/ai/key", s.putAiKey)
	private("DELETE /api/v1/ai/key", s.deleteAiKey)

	private("GET /api/v1/ledgers", s.listLedgers)
	private("POST /api/v1/ledgers", s.createLedger)
	private("GET /api/v1/ledgers/{lid}", s.getLedger)
	private("PATCH /api/v1/ledgers/{lid}", s.updateLedger)
	private("DELETE /api/v1/ledgers/{lid}", s.deleteLedger)
	private("GET /api/v1/ledgers/{lid}/members", s.listMembers)
	private("POST /api/v1/ledgers/{lid}/members", s.addMember)
	private("DELETE /api/v1/ledgers/{lid}/members/{uid}", s.removeMember)

	private("POST /api/v1/ledgers/{lid}/sync/push", s.push)
	private("GET /api/v1/ledgers/{lid}/sync/pull", s.pull)

	private("GET /api/v1/ledgers/{lid}/records/{entity}", s.listRecords)
	private("POST /api/v1/ledgers/{lid}/records/{entity}", s.createRecord)
	private("GET /api/v1/ledgers/{lid}/records/{entity}/{id}", s.getRecord)
	private("PATCH /api/v1/ledgers/{lid}/records/{entity}/{id}", s.updateRecord)
	private("DELETE /api/v1/ledgers/{lid}/records/{entity}/{id}", s.deleteRecord)
	private("POST /api/v1/ledgers/{lid}/records/{entity}/{id}/restore", s.restoreRecord)
	private("GET /api/v1/ledgers/{lid}/transactions", s.listTransactions)

	private("GET /api/v1/ledgers/{lid}/reports/balances", s.balances)
	private("GET /api/v1/ledgers/{lid}/reports/summary", s.summary)
	private("GET /api/v1/ledgers/{lid}/reports/trend", s.trend)
	private("GET /api/v1/ledgers/{lid}/reports/budgets", s.budgetProgress)
	private("GET /api/v1/ledgers/{lid}/reports/tags", s.tagTotals)
	private("GET /api/v1/ledgers/{lid}/reports/payees", s.payeeTotals)
	private("GET /api/v1/ledgers/{lid}/reports/position", s.position)
	private("GET /api/v1/ledgers/{lid}/reports/networth", s.netWorth)
	private("GET /api/v1/ledgers/{lid}/reports/debts", s.debtTotals)
	private("POST /api/v1/ledgers/{lid}/batch", s.batch)

	private("GET /api/v1/ledgers/{lid}/export", s.exportLedger)
	private("POST /api/v1/ledgers/{lid}/import", s.importLedger)

	public("/", func(w http.ResponseWriter, r *http.Request) {
		if s.Web != nil && !strings.HasPrefix(r.URL.Path, "/api/") {
			s.Web.ServeHTTP(w, r)
			return
		}
		writeError(w, http.StatusNotFound, "not_found", "no such endpoint", nil)
	})

	var h http.Handler = mux
	h = s.cors(h)
	h = securityHeaders(h)
	h = s.logRequests(h)
	h = s.recoverPanics(h)
	return h
}

// --- middleware ---

type ctxKey int

const (
	userKey ctxKey = iota
	sessionKey
	requestIDKey
	cookieAuthKey
)

// SessionCookie is the HttpOnly cookie the web app signs in with. The Android
// app keeps using the Authorization header; the cookie exists so a browser
// never has to keep the session token in localStorage, where any script on the
// page could read it.
const SessionCookie = "kosh_session"

// ClientHeader marks requests the web app makes. Cross-site forms and simple
// requests cannot set a custom header, so its presence on cookie-authenticated
// writes is the CSRF defence (together with SameSite=Strict).
const ClientHeader = "X-Kosh-Client"

func currentUser(r *http.Request) store.User {
	u, _ := r.Context().Value(userKey).(store.User)
	return u
}

func currentSession(r *http.Request) store.Session {
	s, _ := r.Context().Value(sessionKey).(store.Session)
	return s
}

// bearerToken reads the session token from the Authorization header or, for
// browsers, the session cookie.
func bearerToken(r *http.Request) (token string, viaCookie bool) {
	if tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
		return strings.TrimSpace(tok), false
	}
	if c, err := r.Cookie(SessionCookie); err == nil && c.Value != "" {
		return c.Value, true
	}
	return "", false
}

// setSessionCookie issues the HttpOnly session cookie. Secure is set whenever
// the request came over TLS or is a loopback address (a secure context), so a
// plain-HTTP LAN deployment still works.
func (s *Server) setSessionCookie(w http.ResponseWriter, r *http.Request, token string) {
	http.SetCookie(w, &http.Cookie{
		Name: SessionCookie, Value: token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode,
		MaxAge: int(s.Auth.SessionTTL.Seconds()), Secure: r.TLS != nil || isLoopback(r.Host),
	})
}

func clearSessionCookie(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name: SessionCookie, Value: "", Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode,
		MaxAge: -1, Secure: r.TLS != nil || isLoopback(r.Host),
	})
}

func isLoopback(hostPort string) bool {
	host, _, err := net.SplitHostPort(hostPort)
	if err != nil {
		host = hostPort
	}
	return host == "localhost" || host == "127.0.0.1" || host == "::1"
}

func (s *Server) requireAuth(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, viaCookie := bearerToken(r)
		if token == "" {
			writeError(w, http.StatusUnauthorized, "unauthenticated", "missing bearer token", nil)
			return
		}
		if viaCookie && r.Method != http.MethodGet && r.Method != http.MethodHead && r.Header.Get(ClientHeader) == "" {
			// A cookie-authenticated write without the web client header is
			// either a CSRF attempt or a non-web client that should use the
			// bearer token instead.
			writeError(w, http.StatusUnauthorized, "unauthenticated", "missing "+ClientHeader+" header", nil)
			return
		}
		u, sess, err := s.Auth.Authenticate(r.Context(), token)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		ctx := context.WithValue(r.Context(), userKey, u)
		ctx = context.WithValue(ctx, sessionKey, sess)
		if viaCookie {
			ctx = context.WithValue(ctx, cookieAuthKey, true)
		}
		next(w, r.WithContext(ctx))
	})
}

func (s *Server) limited(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.authLimiter.allow(s.clientIP(r)) {
			w.Header().Set("Retry-After", "60")
			writeError(w, http.StatusTooManyRequests, "rate_limited", "too many attempts; try again in a minute", nil)
			return
		}
		next(w, r)
	}
}

func (s *Server) clientIP(r *http.Request) string {
	if s.TrustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			// Our one trusted proxy appends the address it saw; anything to the
			// left of it was supplied by the client and can be forged.
			parts := strings.Split(xff, ",")
			last := strings.TrimSpace(parts[len(parts)-1])
			if last != "" {
				return last
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (s *Server) cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && (slices.Contains(s.CORSOrigins, "*") || slices.Contains(s.CORSOrigins, origin)) {
			h := w.Header()
			h.Set("Access-Control-Allow-Origin", origin)
			h.Add("Vary", "Origin")
			h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			h.Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
			h.Set("Access-Control-Max-Age", "600")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += int64(n)
	return n, err
}

// Unwrap lets http.ResponseController reach the underlying writer.
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		reqID := r.Header.Get("X-Request-ID")
		if reqID == "" || len(reqID) > 64 {
			reqID = ids.NewV7()
		}
		w.Header().Set("X-Request-ID", reqID)
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r.WithContext(context.WithValue(r.Context(), requestIDKey, reqID)))
		level := slog.LevelInfo
		if sw.status >= 500 {
			level = slog.LevelError
		} else if r.URL.Path == "/healthz" || r.URL.Path == "/readyz" {
			level = slog.LevelDebug
		}
		s.Log.Log(r.Context(), level, "request",
			"method", r.Method, "path", r.URL.Path, "status", sw.status,
			"bytes", sw.bytes, "duration_ms", time.Since(start).Milliseconds(), "request_id", reqID)
	})
}

func (s *Server) recoverPanics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if p := recover(); p != nil {
				if p == http.ErrAbortHandler {
					panic(p)
				}
				s.Log.Error("panic", "err", p, "method", r.Method, "path", r.URL.Path,
					"request_id", w.Header().Get("X-Request-ID"), "stack", string(debug.Stack()))
				writeError(w, http.StatusInternalServerError, "internal", "internal server error", nil)
			}
		}()
		next.ServeHTTP(w, r)
	})
}
