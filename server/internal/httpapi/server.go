// Package httpapi exposes the services over a JSON HTTP API (see docs/07-api.md).
// It uses only the standard library router.
package httpapi

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/kosh-app/kosh/server/internal/auth"
	"github.com/kosh-app/kosh/server/internal/db"
	"github.com/kosh-app/kosh/server/internal/ids"
	"github.com/kosh-app/kosh/server/internal/ledger"
	"github.com/kosh-app/kosh/server/internal/report"
	"github.com/kosh-app/kosh/server/internal/store"
	"github.com/kosh-app/kosh/server/internal/syncer"
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

	authLimiter *rateLimiter
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
	mux := http.NewServeMux()
	public := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, h) }
	private := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, s.requireAuth(h)) }

	public("GET /healthz", s.healthz)
	public("GET /readyz", s.readyz)
	public("GET /api/v1/meta", s.meta)

	public("POST /api/v1/auth/register", s.limited(s.register))
	public("POST /api/v1/auth/login", s.limited(s.login))
	private("POST /api/v1/auth/logout", s.logout)
	private("GET /api/v1/auth/me", s.me)
	private("GET /api/v1/auth/sessions", s.listSessions)
	private("DELETE /api/v1/auth/sessions/{id}", s.deleteSession)
	private("POST /api/v1/auth/password", s.changePassword)

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
)

func currentUser(r *http.Request) store.User {
	u, _ := r.Context().Value(userKey).(store.User)
	return u
}

func currentSession(r *http.Request) store.Session {
	s, _ := r.Context().Value(sessionKey).(store.Session)
	return s
}

func (s *Server) requireAuth(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthenticated", "missing bearer token", nil)
			return
		}
		u, sess, err := s.Auth.Authenticate(r.Context(), strings.TrimSpace(token))
		if err != nil {
			s.fail(w, r, err)
			return
		}
		ctx := context.WithValue(r.Context(), userKey, u)
		ctx = context.WithValue(ctx, sessionKey, sess)
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
			first, _, _ := strings.Cut(xff, ",")
			return strings.TrimSpace(first)
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
				s.Log.Error("panic", "err", p, "path", r.URL.Path)
				writeError(w, http.StatusInternalServerError, "internal", "internal server error", nil)
			}
		}()
		next.ServeHTTP(w, r)
	})
}
