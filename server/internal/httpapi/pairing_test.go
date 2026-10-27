package httpapi_test

import (
	"net/http"
	"strings"
	"testing"
)

// A signed-in browser connects a phone with a one-time code; the code works once and only for its owner.
func TestPairingConnectsAPhone(t *testing.T) {
	base := newServer(t, true)
	web := signUp(t, base, "alice@example.com", http.StatusCreated)
	bob := signUp(t, base, "bob@example.com", http.StatusCreated)
	anon := &client{t: t, base: base}

	anon.do("POST", "/api/v1/auth/pair", nil, http.StatusUnauthorized)
	res := web.do("POST", "/api/v1/auth/pair", nil, http.StatusOK)
	code, _ := res["code"].(string)
	if len(code) != 10 {
		t.Fatalf("code %q", code)
	}
	// Someone else cannot watch the code.
	if st := bob.do("GET", "/api/v1/auth/pair/"+code, nil, http.StatusOK); st["expired"] != true {
		t.Fatalf("another user sees the code: %v", st)
	}
	if st := web.do("GET", "/api/v1/auth/pair/"+code, nil, http.StatusOK); st["claimed"] != false || st["expired"] != false {
		t.Fatalf("fresh code: %v", st)
	}

	// Typed by hand: lower case, with a dash.
	typed := strings.ToLower(code[:5] + "-" + code[5:])
	got := anon.do("POST", "/api/v1/auth/pair/claim", map[string]any{"code": typed, "device_name": "Pixel 9"}, http.StatusOK)
	phone := &client{t: t, base: base, token: got["token"].(string)}
	me := phone.do("GET", "/api/v1/auth/me", nil, http.StatusOK)
	if me["user"].(map[string]any)["email"] != "alice@example.com" {
		t.Fatalf("phone signed in as %v", me["user"])
	}
	if st := web.do("GET", "/api/v1/auth/pair/"+code, nil, http.StatusOK); st["claimed"] != true || st["device"] != "Pixel 9" {
		t.Fatalf("browser does not see the phone: %v", st)
	}

	// Once only, and a wrong code says so plainly.
	anon.do("POST", "/api/v1/auth/pair/claim", map[string]any{"code": code, "device_name": "Other"}, http.StatusUnauthorized)
	anon.do("POST", "/api/v1/auth/pair/claim", map[string]any{"code": "0000000000", "device_name": "Other"}, http.StatusUnauthorized)

	// A new code replaces the old one.
	first := web.do("POST", "/api/v1/auth/pair", nil, http.StatusOK)["code"].(string)
	web.do("POST", "/api/v1/auth/pair", nil, http.StatusOK)
	anon.do("POST", "/api/v1/auth/pair/claim", map[string]any{"code": first, "device_name": "Old"}, http.StatusUnauthorized)
}
