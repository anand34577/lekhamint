package httpapi_test

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/httpapi"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

func TestAssistantProxy(t *testing.T) {
	var got struct {
		auth  string
		model string
	}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		got.auth, got.model = r.Header.Get("Authorization"), body["model"].(string)
		if got.model == "bad-key" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = io.WriteString(w, `{"error":{"message":"Incorrect API key"}}`)
			return
		}
		if body["stream"] == true {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"You spent\"}}]}\n\ndata: [DONE]\n\n")
			return
		}
		_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"You spent ₹1,250.00."}}]}`)
	}))
	defer upstream.Close()

	d := testutil.NewDB(t)
	sync := syncer.New(d)
	api := &httpapi.Server{
		DB: d, Auth: auth.NewService(d, true, time.Hour), Sync: sync,
		Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test", MaxImportSize: 1 << 20,
		AI: &httpapi.AIProxy{BaseURL: upstream.URL + "/v1", APIKey: "sk-secret", Model: "llama3.1:8b", Models: []string{"qwen2.5:7b", "bad-key"}, Timeout: 5 * time.Second},
	}
	srv := httptest.NewServer(api.Handler())
	defer srv.Close()

	anon := &client{t: t, base: srv.URL}
	meta := anon.do("GET", "/api/v1/meta", nil, http.StatusOK)
	if strings.Contains(meta["_raw"].(string), "sk-secret") {
		t.Fatal("meta leaks the API key")
	}
	if ai := meta["ai"].(map[string]any); ai["enabled"] != true || ai["model"] != "llama3.1:8b" {
		t.Fatalf("meta ai = %v", ai)
	}
	anon.do("POST", "/api/v1/ai/chat/completions", map[string]any{"messages": []any{}}, http.StatusUnauthorized)

	c := signUp(t, srv.URL, "ai@example.com", http.StatusCreated)
	res := c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"messages": []any{map[string]any{"role": "user", "content": "How much?"}}}, http.StatusOK)
	if !strings.Contains(res["_raw"].(string), "₹1,250.00") || got.auth != "Bearer sk-secret" || got.model != "llama3.1:8b" {
		t.Fatalf("proxy: %v auth=%q model=%q", res["_raw"], got.auth, got.model)
	}
	c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"model": "qwen2.5:7b", "messages": []any{}}, http.StatusOK)
	if got.model != "qwen2.5:7b" {
		t.Fatalf("model override ignored: %q", got.model)
	}
	// A model the administrator did not list is not used; the request goes to the default.
	c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"model": "gpt-expensive", "messages": []any{}}, http.StatusOK)
	if got.model != "llama3.1:8b" {
		t.Fatalf("unlisted model %q reached the upstream", got.model)
	}
	// A streamed answer is relayed as server-sent events.
	streamed := c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"stream": true, "messages": []any{}}, http.StatusOK)
	if raw := streamed["_raw"].(string); !strings.Contains(raw, `"content":"You spent"`) || !strings.Contains(raw, "[DONE]") {
		t.Fatalf("stream relay: %q", raw)
	}
	c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"nope": 1}, http.StatusBadRequest)
	// A rejected server key is a gateway error, never a 401 that looks like an expired session.
	c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"model": "bad-key", "messages": []any{}}, http.StatusBadGateway)
	c.do("GET", "/api/v1/auth/me", nil, http.StatusOK)

	// Disabled: a clear 503, and meta says so.
	api.AI = nil
	c.do("POST", "/api/v1/ai/chat/completions", map[string]any{"messages": []any{}}, http.StatusServiceUnavailable)
	if ai := anon.do("GET", "/api/v1/meta", nil, http.StatusOK)["ai"].(map[string]any); ai["enabled"] != false {
		t.Fatalf("disabled meta ai = %v", ai)
	}
}
