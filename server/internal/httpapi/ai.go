package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"time"
)

// AIProxy forwards the assistant's chat requests to an OpenAI-compatible
// endpoint (docs/11-assistant.md). The key stays on the server, and the
// browser needs no CORS settings for a model on the home network. Request and
// response bodies are never logged: they contain the user's financial data.
type AIProxy struct {
	BaseURL string // up to /v1
	APIKey  string
	Model   string // used when the request names none
	Timeout time.Duration
	Client  *http.Client
}

const (
	aiRequestLimit  = 2 << 20 // a conversation with tool results
	aiResponseLimit = 4 << 20
)

// info describes the proxy for /meta without revealing the key or full URL.
func (p *AIProxy) info() map[string]any {
	if p == nil {
		return map[string]any{"enabled": false}
	}
	host := ""
	if u, err := url.Parse(p.BaseURL); err == nil {
		host = u.Host
	}
	return map[string]any{"enabled": true, "model": p.Model, "host": host}
}

// RedactURL drops credentials and query strings from a URL for logging.
func RedactURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "(invalid URL)"
	}
	u.User, u.RawQuery = nil, ""
	return u.String()
}

func (s *Server) aiChat(w http.ResponseWriter, r *http.Request) {
	if s.AI == nil {
		writeError(w, http.StatusServiceUnavailable, "ai_disabled", "the assistant is not set up on this server (LEKHAMINT_AI_BASE_URL)", nil)
		return
	}
	var body map[string]any
	if err := decode(w, r, aiRequestLimit, &body); err != nil {
		s.fail(w, r, err)
		return
	}
	if _, ok := body["messages"].([]any); !ok {
		writeError(w, http.StatusBadRequest, "bad_request", "messages is required", nil)
		return
	}
	if m, _ := body["model"].(string); m == "" {
		if s.AI.Model == "" {
			writeError(w, http.StatusBadRequest, "bad_request", "no model given and the server has no default (LEKHAMINT_AI_MODEL)", nil)
			return
		}
		body["model"] = s.AI.Model
	}
	body["stream"] = false // the proxy relays one response; streaming is not supported
	payload, err := json.Marshal(body)
	if err != nil {
		s.fail(w, r, err)
		return
	}

	timeout := s.AI.Timeout
	if timeout <= 0 {
		timeout = 2 * time.Minute
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.AI.BaseURL+"/chat/completions", bytes.NewReader(payload))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-Title", "LekhaMint")
	if s.AI.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.AI.APIKey)
	}
	client := s.AI.Client
	if client == nil {
		client = http.DefaultClient
	}
	res, err := client.Do(req)
	if err != nil {
		msg := "could not reach the assistant model"
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			msg = "the assistant model took too long to answer"
		}
		s.Log.Warn("assistant upstream failed", "endpoint", RedactURL(s.AI.BaseURL), "err", err)
		writeError(w, http.StatusBadGateway, "ai_upstream", msg, nil)
		return
	}
	defer res.Body.Close()
	out, err := io.ReadAll(io.LimitReader(res.Body, aiResponseLimit+1))
	if err != nil || len(out) > aiResponseLimit {
		writeError(w, http.StatusBadGateway, "ai_upstream", "the assistant model sent an unreadable or oversized answer", nil)
		return
	}
	if res.StatusCode >= 400 {
		s.Log.Warn("assistant upstream error", "endpoint", RedactURL(s.AI.BaseURL), "status", res.StatusCode)
	}
	// A 401/403 from the model service means the server's AI key is wrong. Passing it on
	// would look like the user's own LekhaMint session had expired.
	if res.StatusCode == http.StatusUnauthorized || res.StatusCode == http.StatusForbidden {
		writeError(w, http.StatusBadGateway, "ai_upstream_auth",
			"the model service refused this server's API key; ask the admin to check LEKHAMINT_AI_API_KEY", nil)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(res.StatusCode)
	_, _ = w.Write(out)
}
