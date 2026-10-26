package httpapi_test

import (
	"strings"
	"testing"
)

// Settings are per person, survive across sessions (another browser) and are validated.
func TestUserSettings(t *testing.T) {
	base := newServer(t, true)
	a := signUp(t, base, "a@example.com", 201)
	b := signUp(t, base, "b@example.com", 201)

	if got := a.do("GET", "/api/v1/settings", nil, 200)["settings"].(map[string]any); len(got) != 0 {
		t.Fatalf("new user has settings: %v", got)
	}
	a.do("PUT", "/api/v1/settings/theme", map[string]any{"value": "dark"}, 200)
	a.do("PUT", "/api/v1/settings/insights.hidden.l1", map[string]any{"value": []string{"pace", "big"}}, 200)
	a.do("PUT", "/api/v1/settings/theme", map[string]any{"value": "light"}, 200) // overwrite

	got := a.do("GET", "/api/v1/settings", nil, 200)["settings"].(map[string]any)
	if got["theme"] != "light" || len(got["insights.hidden.l1"].([]any)) != 2 {
		t.Fatalf("settings: %v", got)
	}
	// Another person sees none of them.
	if other := b.do("GET", "/api/v1/settings", nil, 200)["settings"].(map[string]any); len(other) != 0 {
		t.Fatalf("settings leaked to another user: %v", other)
	}
	// null and DELETE both remove.
	a.do("PUT", "/api/v1/settings/theme", map[string]any{"value": nil}, 204)
	a.do("DELETE", "/api/v1/settings/insights.hidden.l1", nil, 204)
	if got := a.do("GET", "/api/v1/settings", nil, 200)["settings"].(map[string]any); len(got) != 0 {
		t.Fatalf("after delete: %v", got)
	}
	// Bad names and oversized values are refused.
	a.do("PUT", "/api/v1/settings/Bad%20Key", map[string]any{"value": 1}, 400)
	a.do("PUT", "/api/v1/settings/big", map[string]any{"value": strings.Repeat("x", 20<<10)}, 400)
	// Not signed in: refused.
	anon := &client{t: t, base: base}
	anon.do("GET", "/api/v1/settings", nil, 401)
}
