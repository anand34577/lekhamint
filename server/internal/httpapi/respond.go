package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/kosh-app/kosh/server/internal/auth"
	"github.com/kosh-app/kosh/server/internal/entity"
	"github.com/kosh-app/kosh/server/internal/ledger"
	"github.com/kosh-app/kosh/server/internal/store"
	"github.com/kosh-app/kosh/server/internal/syncer"
)

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string            `json:"code"`
	Message string            `json:"message"`
	Fields  map[string]string `json:"fields,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, msg string, fields map[string]string) {
	writeJSON(w, status, errorBody{Error: errorDetail{Code: code, Message: msg, Fields: fields}})
}

// fail maps a domain error to an HTTP response. Unknown errors become 500
// and are logged; their details never reach the client.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	var (
		ve  *entity.ValidationError
		rej *syncer.RejectionError
		be  *badRequest
	)
	switch {
	case errors.As(err, &be):
		writeError(w, http.StatusBadRequest, "bad_request", be.msg, nil)
	case errors.As(err, &ve):
		writeError(w, http.StatusUnprocessableEntity, "validation", ve.Error(), ve.Fields)
	case errors.As(err, &rej):
		status := http.StatusUnprocessableEntity
		if rej.Rejection.Error == syncer.CodeUnknownEntity {
			status = http.StatusNotFound
		}
		writeError(w, status, rej.Rejection.Error, rej.Message, rej.Fields)
	case errors.Is(err, auth.ErrUnauthenticated):
		writeError(w, http.StatusUnauthorized, "unauthenticated", err.Error(), nil)
	case errors.Is(err, auth.ErrInvalidCredentials):
		writeError(w, http.StatusUnauthorized, "invalid_credentials", err.Error(), nil)
	case errors.Is(err, auth.ErrSignupClosed):
		writeError(w, http.StatusForbidden, "signup_closed", err.Error(), nil)
	case errors.Is(err, auth.ErrEmailTaken), errors.Is(err, syncer.ErrExists), errors.Is(err, store.ErrDuplicate):
		writeError(w, http.StatusConflict, "conflict", err.Error(), nil)
	case errors.Is(err, auth.ErrWeakPassword), errors.Is(err, auth.ErrInvalidEmail), errors.Is(err, auth.ErrInvalidName),
		errors.Is(err, ledger.ErrInvalid), errors.Is(err, ledger.ErrInvalidRole), errors.Is(err, store.ErrBadCursor):
		writeError(w, http.StatusBadRequest, "bad_request", err.Error(), nil)
	case errors.Is(err, ledger.ErrLastOwner):
		writeError(w, http.StatusConflict, "last_owner", err.Error(), nil)
	case errors.Is(err, ledger.ErrForbidden):
		writeError(w, http.StatusForbidden, "forbidden", err.Error(), nil)
	case errors.Is(err, ledger.ErrUserNotFound):
		writeError(w, http.StatusNotFound, "user_not_found", err.Error(), nil)
	case errors.Is(err, ledger.ErrNotFound), errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "not found", nil)
	default:
		s.Log.Error("request failed", "err", err, "method", r.Method, "path", r.URL.Path,
			"request_id", r.Context().Value(requestIDKey))
		writeError(w, http.StatusInternalServerError, "internal", "internal server error", nil)
	}
}

type badRequest struct{ msg string }

func (e *badRequest) Error() string { return e.msg }

func badReq(msg string) error { return &badRequest{msg: msg} }

// decode reads a JSON body of at most limit bytes into v. Numbers decode as
// json.Number so large integers stay exact.
func decode(w http.ResponseWriter, r *http.Request, limit int64, v any) error {
	body := http.MaxBytesReader(w, r.Body, limit)
	dec := json.NewDecoder(body)
	dec.UseNumber()
	if err := dec.Decode(v); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			return badReq("request body too large")
		}
		if errors.Is(err, io.EOF) {
			return badReq("request body is empty")
		}
		return badReq("invalid JSON: " + err.Error())
	}
	return nil
}

func queryInt(r *http.Request, key string, def, max int) (int, error) {
	v := r.URL.Query().Get(key)
	if v == "" {
		return def, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return 0, badReq(key + " must be a non-negative integer")
	}
	if n > max {
		n = max
	}
	return n, nil
}

// queryAmount reads an optional amount in minor units (0 when absent). Amounts are
// int64 on every platform, including 32-bit builds where int is too small.
func queryAmount(r *http.Request, key string) (int64, error) {
	v := r.URL.Query().Get(key)
	if v == "" {
		return 0, nil
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 {
		return 0, badReq(key + " must be a non-negative amount in minor units")
	}
	return n, nil
}

func queryDate(r *http.Request, key string, required bool) (string, error) {
	v := r.URL.Query().Get(key)
	if v == "" {
		if required {
			return "", badReq(key + " is required (YYYY-MM-DD)")
		}
		return "", nil
	}
	if _, err := time.Parse(time.DateOnly, v); err != nil || len(v) != 10 {
		return "", badReq(key + " must be a date in YYYY-MM-DD form")
	}
	return v, nil
}

// rateLimiter is a fixed-window counter per key, pruned on every window.
type rateLimiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	start  time.Time
	counts map[string]int
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{limit: limit, window: window, start: time.Now(), counts: map[string]int{}}
}

func (l *rateLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if now := time.Now(); now.Sub(l.start) >= l.window {
		l.start, l.counts = now, map[string]int{}
	}
	l.counts[key]++
	return l.counts[key] <= l.limit
}
