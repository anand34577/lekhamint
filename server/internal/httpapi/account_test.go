package httpapi_test

import (
	"net/http"
	"testing"

	"github.com/anand34577/lekhamint/server/internal/ids"
)

func TestDeleteAccount(t *testing.T) {
	base := newServer(t, true)
	alice := signUp(t, base, "alice@example.com", http.StatusCreated)
	bob := signUp(t, base, "bob@example.com", http.StatusCreated)

	// Alice alone owns "Mine"; "Shared" is co-owned with Bob.
	mine, shared := ids.NewV7(), ids.NewV7()
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": mine, "name": "Mine", "base_currency": "INR"}, http.StatusCreated)
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": shared, "name": "Shared", "base_currency": "INR"}, http.StatusCreated)
	alice.do("POST", "/api/v1/ledgers/"+shared+"/members", map[string]any{"email": "bob@example.com", "role": "owner"}, http.StatusOK)

	alice.do("DELETE", "/api/v1/auth/me", map[string]any{"password": "wrong password"}, http.StatusUnauthorized)
	res := alice.do("DELETE", "/api/v1/auth/me", map[string]any{"password": "correct horse battery"}, http.StatusOK)
	if got := res["deleted_ledgers"].([]any); len(got) != 1 || got[0] != mine {
		t.Fatalf("deleted_ledgers = %v, want [%s]", got, mine)
	}

	// Her session is gone, she cannot sign in, and her email is free again.
	alice.do("GET", "/api/v1/auth/me", nil, http.StatusUnauthorized)
	anon := &client{t: t, base: base}
	anon.do("POST", "/api/v1/auth/login", map[string]any{"email": "alice@example.com", "password": "correct horse battery"}, http.StatusUnauthorized)

	// Bob keeps the shared ledger, now without Alice.
	bob.do("GET", "/api/v1/ledgers/"+shared, nil, http.StatusOK)
	members := bob.do("GET", "/api/v1/ledgers/"+shared+"/members", nil, http.StatusOK)["members"].([]any)
	if len(members) != 1 {
		t.Fatalf("shared ledger members = %d, want 1", len(members))
	}
	signUp(t, base, "alice@example.com", http.StatusCreated)
}
