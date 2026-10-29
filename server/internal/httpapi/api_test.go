package httpapi_test

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/hlc"
	"github.com/anand34577/lekhamint/server/internal/httpapi"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

type client struct {
	t     *testing.T
	base  string
	token string
}

func newServer(t *testing.T, allowSignup bool) string {
	t.Helper()
	d := testutil.NewDB(t)
	sync := syncer.New(d)
	api := &httpapi.Server{
		DB: d, Auth: auth.NewService(d, allowSignup, time.Hour), Sync: sync,
		Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test",
		CORSOrigins: []string{"http://localhost:5173"}, MaxImportSize: 32 << 20,
	}
	srv := httptest.NewServer(api.Handler())
	t.Cleanup(srv.Close)
	return srv.URL
}

// do sends a request and decodes the JSON response into a map.
func (c *client) do(method, path string, body any, wantStatus int) map[string]any {
	c.t.Helper()
	var rd io.Reader
	if b, ok := body.([]byte); ok {
		rd = bytes.NewReader(b)
	} else if body != nil {
		raw, _ := json.Marshal(body)
		rd = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != wantStatus {
		c.t.Fatalf("%s %s: status %d, want %d: %s", method, path, resp.StatusCode, wantStatus, raw)
	}
	out := map[string]any{}
	if len(raw) > 0 && raw[0] == '{' {
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		if err := dec.Decode(&out); err != nil {
			c.t.Fatalf("decode %s: %v", raw, err)
		}
	}
	out["_raw"] = string(raw)
	return out
}

func signUp(t *testing.T, base, email string, want int) *client {
	c := &client{t: t, base: base}
	res := c.do("POST", "/api/v1/auth/register", map[string]any{
		"email": email, "password": "correct horse battery", "device_name": "Pixel 9",
	}, want)
	if tok, ok := res["token"].(string); ok {
		c.token = tok
	}
	return c
}

func num(v any) int64 {
	n, _ := v.(json.Number).Int64()
	return n
}

func TestFullJourney(t *testing.T) {
	base := newServer(t, false)

	// First user may register even with sign-up closed, and becomes admin.
	alice := signUp(t, base, "Alice@Example.com", http.StatusCreated)
	me := alice.do("GET", "/api/v1/auth/me", nil, http.StatusOK)
	if u := me["user"].(map[string]any); u["email"] != "alice@example.com" || u["is_admin"] != true {
		t.Fatalf("me = %v", u)
	}
	// Further sign-ups are closed.
	signUp(t, base, "mallory@example.com", http.StatusForbidden)

	// Create a ledger (client-chosen ID) with default categories; repeat is idempotent.
	lid := ids.NewV7()
	body := map[string]any{"id": lid, "name": "Personal", "base_currency": "INR", "seed_default_categories": true}
	alice.do("POST", "/api/v1/ledgers", body, http.StatusCreated)
	alice.do("POST", "/api/v1/ledgers", body, http.StatusOK)
	L := "/api/v1/ledgers/" + lid

	cats := alice.do("GET", L+"/records/categories?limit=2000", nil, http.StatusOK)["records"].([]any)
	catID := map[string]string{}
	for _, c := range cats {
		rec := c.(map[string]any)
		catID[rec["data"].(map[string]any)["name"].(string)] = rec["id"].(string)
	}
	if len(cats) < 60 || catID["Groceries"] == "" || catID["Salary"] == "" {
		t.Fatalf("default categories missing: %d", len(cats))
	}

	// An offline phone pushes a month of activity in one batch.
	clock := hlc.NewClock("")
	bank, card := ids.NewV7(), ids.NewV7()
	ch := func(entityName, id string, data map[string]any) map[string]any {
		return map[string]any{"entity": entityName, "id": id, "hlc": clock.Now(), "data": data}
	}
	tx := func(typ, acct string, amount int64, cat, date string, extra map[string]any) map[string]any {
		d := map[string]any{"type": typ, "account_id": acct, "amount": amount, "local_date": date,
			"occurred_at": time.Now().UnixMilli(), "tz_offset_min": 330}
		if cat != "" {
			d["category_id"] = catID[cat]
		}
		for k, v := range extra {
			d[k] = v
		}
		return ch("transactions", ids.NewV7(), d)
	}
	grocery := ids.NewV7()
	changes := []any{
		ch("accounts", bank, map[string]any{"name": "HDFC Savings", "type": "bank", "currency": "INR", "opening_balance": 5000000}),
		ch("accounts", card, map[string]any{"name": "Amex", "type": "credit_card", "currency": "INR", "credit_limit": 20000000}),
		tx("income", bank, 10000000, "Salary", "2026-10-01", nil),                                                  // +1,00,000
		tx("expense", bank, 2500000, "Rent", "2026-10-02", nil),                                                    // -25,000
		tx("expense", card, 300000, "Dining out", "2026-10-05", nil),                                               // -3,000 on card
		tx("transfer", bank, 300000, "", "2026-10-20", map[string]any{"to_account_id": card, "to_amount": 300000}), // pay card bill
		tx("income", card, 50000, "Dining out", "2026-10-21", nil),                                                 // refund 500
		tx("expense", bank, 100000, "", "2026-10-22", map[string]any{"is_adjustment": true}),                       // reconciliation
		tx("expense", bank, 999999, "Rent", "2026-10-23", map[string]any{"status": "planned"}),                     // future, ignored
	}
	g := ch("transactions", grocery, map[string]any{"type": "expense", "account_id": bank, "amount": 300000,
		"category_id": catID["Groceries"], "local_date": "2026-10-10", "occurred_at": time.Now().UnixMilli(), "payee": "Big Bazaar"})
	changes = append(changes, g,
		ch("transaction_splits", ids.NewV7(), map[string]any{"transaction_id": grocery, "category_id": catID["Groceries"], "amount": 240000}),
		ch("transaction_splits", ids.NewV7(), map[string]any{"transaction_id": grocery, "category_id": catID["Household supplies"], "amount": 60000}),
	)
	push := alice.do("POST", L+"/sync/push", map[string]any{"schema_version": 1, "device_id": "phone", "changes": changes}, http.StatusOK)
	if n := len(push["accepted"].([]any)); n != len(changes) {
		t.Fatalf("accepted %d of %d: %s", n, len(changes), push["_raw"])
	}

	// Balances: bank = 50,000 + 1,00,000 − 25,000 − 3,000 (transfer) − 1,000 (adj) − 3,000 (grocery) = 1,18,000.
	// Card = −3,000 + 3,000 + 500 = +500. Planned rows are not counted.
	bal := alice.do("GET", L+"/reports/balances", nil, http.StatusOK)["balances"].([]any)
	got := map[string]int64{}
	for _, b := range bal {
		m := b.(map[string]any)
		got[m["name"].(string)] = num(m["balance"])
	}
	if got["HDFC Savings"] != 11800000 || got["Amex"] != 50000 {
		t.Fatalf("balances = %v", got)
	}

	// Summary: income 1,00,000; expense = 25,000 + 3,000 − 500 (refund) + 3,000 = 30,500.
	// Transfers, adjustments and planned rows are excluded; splits are used.
	sum := alice.do("GET", L+"/reports/summary?from=2026-10-01&to=2026-10-31", nil, http.StatusOK)
	inr := sum["currencies"].([]any)[0].(map[string]any)
	if num(inr["income"]) != 10000000 || num(inr["expense"]) != 3050000 {
		t.Fatalf("summary = %s", sum["_raw"])
	}
	byCat := map[string]int64{}
	for _, c := range inr["expense_by_category"].([]any) {
		m := c.(map[string]any)
		byCat[m["name"].(string)] = num(m["amount"])
	}
	if byCat["Dining out"] != 250000 || byCat["Groceries"] != 240000 || byCat["Household supplies"] != 60000 {
		t.Fatalf("by category = %v", byCat)
	}

	trend := alice.do("GET", L+"/reports/trend?from=2026-10-01&to=2026-10-31&interval=month", nil, http.StatusOK)
	pt := trend["points"].([]any)[0].(map[string]any)
	if pt["period"] != "2026-10" || num(pt["expense"]) != 3050000 {
		t.Fatalf("trend = %s", trend["_raw"])
	}

	// Transaction list: search, filters and keyset pagination.
	found := alice.do("GET", L+"/transactions?q=bazaar", nil, http.StatusOK)["transactions"].([]any)
	if len(found) != 1 {
		t.Fatalf("search found %d", len(found))
	}
	var seen int
	cursor := ""
	for page := 0; page < 10; page++ {
		res := alice.do("GET", L+"/transactions?limit=3&cursor="+cursor, nil, http.StatusOK)
		seen += len(res["transactions"].([]any))
		next, _ := res["next_cursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if seen != 8 {
		t.Fatalf("paginated through %d transactions, want 8", seen)
	}

	// REST edit from the web, then the phone pulls it.
	alice.do("PATCH", L+"/records/accounts/"+bank, map[string]any{"data": map[string]any{"name": "HDFC Salary"}}, http.StatusOK)
	pull := alice.do("GET", L+"/sync/pull?since="+push["cursor"].(json.Number).String(), nil, http.StatusOK)
	if chs := pull["changes"].([]any); len(chs) != 1 || chs[0].(map[string]any)["data"].(map[string]any)["name"] != "HDFC Salary" {
		t.Fatalf("pull after edit = %s", pull["_raw"])
	}

	// Delete, list Trash, restore.
	alice.do("DELETE", L+"/records/transactions/"+grocery, nil, http.StatusOK)
	trash := alice.do("GET", L+"/transactions?deleted=true", nil, http.StatusOK)["transactions"].([]any)
	if len(trash) != 1 {
		t.Fatalf("trash has %d", len(trash))
	}
	alice.do("POST", L+"/records/transactions/"+grocery+"/restore", nil, http.StatusOK)

	// Validation errors name the field.
	bad := alice.do("POST", L+"/records/transactions", map[string]any{"data": map[string]any{"type": "expense", "amount": -5}}, http.StatusUnprocessableEntity)
	if !strings.Contains(bad["_raw"].(string), `"amount"`) {
		t.Fatalf("validation error = %s", bad["_raw"])
	}

	// Export, then import into a fresh ledger: identical balances.
	export := alice.do("GET", L+"/export", nil, http.StatusOK)["_raw"].(string)
	lid2 := ids.NewV7()
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": lid2, "name": "Restored", "base_currency": "INR"}, http.StatusCreated)
	imp := alice.do("POST", "/api/v1/ledgers/"+lid2+"/import", []byte(export), http.StatusOK)
	if num(imp["accepted"]) < int64(len(changes)) || len(imp["rejected"].([]any)) != 0 {
		t.Fatalf("import = %s", imp["_raw"])
	}
	bal2 := alice.do("GET", "/api/v1/ledgers/"+lid2+"/reports/balances", nil, http.StatusOK)["balances"].([]any)
	for _, b := range bal2 {
		m := b.(map[string]any)
		if m["name"] == "HDFC Salary" && num(m["balance"]) != 11800000 {
			t.Fatalf("restored balance = %v", m["balance"])
		}
	}
	alice.do("POST", "/api/v1/ledgers/"+lid2+"/import", []byte(`{"hello":"world"}`), http.StatusBadRequest)

	// Logout revokes the token.
	alice.do("POST", "/api/v1/auth/logout", nil, http.StatusNoContent)
	alice.do("GET", "/api/v1/ledgers", nil, http.StatusUnauthorized)
}

func TestLedgerIsolationAndRoles(t *testing.T) {
	base := newServer(t, true)
	alice := signUp(t, base, "alice@example.com", http.StatusCreated)
	bob := signUp(t, base, "bob@example.com", http.StatusCreated)
	signUp(t, base, "bob@example.com", http.StatusConflict)

	lid := ids.NewV7()
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": lid, "name": "Home", "base_currency": "INR"}, http.StatusCreated)
	L := "/api/v1/ledgers/" + lid

	// Bob cannot see, sync or claim Alice's ledger.
	bob.do("GET", L, nil, http.StatusNotFound)
	bob.do("GET", L+"/sync/pull", nil, http.StatusNotFound)
	bob.do("POST", "/api/v1/ledgers", map[string]any{"id": lid, "name": "Mine", "base_currency": "INR"}, http.StatusNotFound)

	// As a viewer he can read but not write.
	alice.do("POST", L+"/members", map[string]any{"email": "bob@example.com", "role": "viewer"}, http.StatusOK)
	bob.do("GET", L+"/sync/pull", nil, http.StatusOK)
	bob.do("POST", L+"/sync/push", map[string]any{"changes": []any{}}, http.StatusForbidden)
	bob.do("DELETE", L+"/members/"+memberID(t, alice, L, "alice@example.com"), nil, http.StatusForbidden)

	// The last owner cannot leave.
	alice.do("DELETE", L+"/members/"+memberID(t, alice, L, "alice@example.com"), nil, http.StatusConflict)

	// Deleting needs explicit confirmation.
	alice.do("DELETE", L, nil, http.StatusBadRequest)
	alice.do("DELETE", L+"?confirm="+lid, nil, http.StatusNoContent)
	alice.do("GET", L, nil, http.StatusNotFound)
}

func memberID(t *testing.T, c *client, L, email string) string {
	for _, m := range c.do("GET", L+"/members", nil, http.StatusOK)["members"].([]any) {
		mm := m.(map[string]any)
		if mm["email"] == email {
			return mm["user_id"].(string)
		}
	}
	t.Fatalf("member %s not found", email)
	return ""
}

func TestAuthHardening(t *testing.T) {
	base := newServer(t, true)
	signUp(t, base, "alice@example.com", http.StatusCreated)
	anon := &client{t: t, base: base}

	anon.do("POST", "/api/v1/auth/register", map[string]any{"email": "x@example.com", "password": "short"}, http.StatusBadRequest)
	anon.do("POST", "/api/v1/auth/register", map[string]any{"email": "not-an-email", "password": "long enough pw"}, http.StatusBadRequest)
	anon.do("GET", "/api/v1/ledgers", nil, http.StatusUnauthorized)

	// Wrong password and unknown user give the same answer.
	a := anon.do("POST", "/api/v1/auth/login", map[string]any{"email": "alice@example.com", "password": "wrong password"}, http.StatusUnauthorized)
	b := anon.do("POST", "/api/v1/auth/login", map[string]any{"email": "nobody@example.com", "password": "wrong password"}, http.StatusUnauthorized)
	if a["_raw"] != b["_raw"] {
		t.Fatalf("login errors differ: %s vs %s", a["_raw"], b["_raw"])
	}
	// Brute force gets rate limited (10 per minute per IP, 6 used so far).
	status := 0
	for i := 0; i < 10 && status != http.StatusTooManyRequests; i++ {
		req, _ := http.NewRequest("POST", base+"/api/v1/auth/login", strings.NewReader(`{"email":"alice@example.com","password":"nope nope"}`))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		status = resp.StatusCode
	}
	if status != http.StatusTooManyRequests {
		t.Fatal("login was never rate limited")
	}

	// CORS preflight for an allowed origin only.
	req, _ := http.NewRequest("OPTIONS", base+"/api/v1/ledgers", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	resp, _ := http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent || resp.Header.Get("Access-Control-Allow-Origin") != "http://localhost:5173" {
		t.Fatalf("preflight: %d %q", resp.StatusCode, resp.Header.Get("Access-Control-Allow-Origin"))
	}
	req.Header.Set("Origin", "https://evil.example")
	resp, _ = http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("unknown origin allowed")
	}

	meta := anon.do("GET", "/api/v1/meta", nil, http.StatusOK)
	wantDB := "sqlite"
	if os.Getenv("LEKHAMINT_TEST_PG_DSN") != "" {
		wantDB = "postgres"
	}
	if meta["signup_allowed"] != true || meta["database"] != wantDB {
		t.Fatalf("meta = %s", meta["_raw"])
	}
	anon.do("GET", "/api/v1/nope", nil, http.StatusNotFound)
}
