package httpapi_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/httpapi"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

func newFeatureServer(t *testing.T) (*httpapi.Server, string) {
	t.Helper()
	d := testutil.NewDB(t)
	sync := syncer.New(d)
	api := &httpapi.Server{
		DB: d, Auth: auth.NewService(d, true, time.Hour), Sync: sync,
		Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test", MaxImportSize: 32 << 20,
	}
	srv := httptest.NewServer(api.Handler())
	t.Cleanup(srv.Close)
	return api, srv.URL
}

func TestTwoStepSignIn(t *testing.T) {
	_, base := newFeatureServer(t)
	alice := signUp(t, base, "alice@example.com", http.StatusCreated)
	login := func(otp string, want int) map[string]any {
		c := &client{t: t, base: base}
		return c.do("POST", "/api/v1/auth/login", map[string]any{"email": "alice@example.com", "password": "correct horse battery", "otp": otp}, want)
	}

	setup := alice.do("POST", "/api/v1/auth/2fa/setup", map[string]any{}, http.StatusOK)
	secret := setup["secret"].(string)
	if !strings.HasPrefix(setup["otpauth_url"].(string), "otpauth://totp/") {
		t.Fatal(setup)
	}
	// Not on until a code proves the app was set up; a wrong code is a 400, not a sign-out.
	login("", http.StatusOK)
	alice.do("POST", "/api/v1/auth/2fa/enable", map[string]any{"code": "000000"}, http.StatusBadRequest)
	code, _ := auth.TOTPCode(secret, time.Now())
	on := alice.do("POST", "/api/v1/auth/2fa/enable", map[string]any{"code": code}, http.StatusOK)
	recovery := on["recovery_codes"].([]any)
	if len(recovery) != 10 {
		t.Fatalf("recovery codes: %v", on)
	}
	st := alice.do("GET", "/api/v1/auth/2fa", nil, http.StatusOK)
	if st["enabled"] != true || num(st["recovery_codes_left"]) != 10 {
		t.Fatal(st)
	}

	// The password alone is no longer enough, and the same code cannot be used twice.
	if res := login("", http.StatusUnauthorized); !strings.Contains(res["_raw"].(string), "otp_required") {
		t.Fatal(res["_raw"])
	}
	login(code, http.StatusUnauthorized)
	// A recovery code works once.
	login(recovery[0].(string), http.StatusOK)
	login(recovery[0].(string), http.StatusUnauthorized)

	// Turning it off needs the password and a code.
	alice.do("POST", "/api/v1/auth/2fa/disable", map[string]any{"password": "wrong", "code": recovery[1]}, http.StatusUnauthorized)
	alice.do("POST", "/api/v1/auth/2fa/disable", map[string]any{"password": "correct horse battery", "code": recovery[1]}, http.StatusOK)
	login("", http.StatusOK)
}

func TestPasswordReset(t *testing.T) {
	api, base := newFeatureServer(t)
	signUp(t, base, "bob@example.com", http.StatusCreated)
	anon := &client{t: t, base: base}
	// Without email and a public address the server says it cannot send links.
	anon.do("POST", "/api/v1/auth/forgot", map[string]any{"email": "bob@example.com"}, http.StatusNotImplemented)

	ctx := context.Background()
	_, token, ok, err := api.Auth.RequestPasswordReset(ctx, "bob@example.com")
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	if _, _, ok, _ := api.Auth.RequestPasswordReset(ctx, "nobody@example.com"); ok {
		t.Fatal("reset made for an unknown email")
	}
	anon.do("POST", "/api/v1/auth/reset", map[string]any{"token": token, "new_password": "short"}, http.StatusBadRequest)
	anon.do("POST", "/api/v1/auth/reset", map[string]any{"token": token, "new_password": "a brand new secret"}, http.StatusOK)
	// Links work once.
	anon.do("POST", "/api/v1/auth/reset", map[string]any{"token": token, "new_password": "another new secret"}, http.StatusBadRequest)
	anon.do("POST", "/api/v1/auth/login", map[string]any{"email": "bob@example.com", "password": "a brand new secret"}, http.StatusOK)
	anon.do("POST", "/api/v1/auth/login", map[string]any{"email": "bob@example.com", "password": "correct horse battery"}, http.StatusUnauthorized)
}

func TestAttachmentsHistoryAndValuations(t *testing.T) {
	_, base := newFeatureServer(t)
	alice := signUp(t, base, "alice@example.com", http.StatusCreated)
	lid := ids.NewV7()
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": lid, "name": "Home", "base_currency": "INR"}, http.StatusCreated)
	L := "/api/v1/ledgers/" + lid

	// A file upload is checked by content: a PNG is accepted, an HTML page is not.
	png := append([]byte("\x89PNG\r\n\x1a\n"), bytes.Repeat([]byte{0}, 64)...)
	sum := sha256.Sum256(png)
	up := alice.do("POST", L+"/blobs", png, http.StatusCreated)
	if up["sha256"] != hex.EncodeToString(sum[:]) || up["mime"] != "image/png" {
		t.Fatal(up)
	}
	alice.do("POST", L+"/blobs", []byte("<html><script>alert(1)</script></html>"), http.StatusBadRequest)
	req, _ := http.NewRequest("GET", base+L+"/blobs/"+up["sha256"].(string), nil)
	req.Header.Set("Authorization", "Bearer "+alice.token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Equal(got, png) || res.Header.Get("Content-Type") != "image/png" || !strings.Contains(res.Header.Get("Content-Security-Policy"), "sandbox") {
		t.Fatalf("download: %d %q", res.StatusCode, res.Header)
	}
	// Someone outside the book cannot read it.
	bob := signUp(t, base, "bob@example.com", http.StatusCreated)
	bob.do("GET", L+"/blobs/"+up["sha256"].(string), nil, http.StatusNotFound)

	// An investment account: 1,00,000 put in, valued at 1,12,000, then 10,000 more put in.
	acc, inv := ids.NewV7(), ids.NewV7()
	alice.do("POST", L+"/records/accounts", map[string]any{"id": acc, "data": map[string]any{"name": "Bank", "type": "bank", "currency": "INR", "opening_balance": 50000000}}, http.StatusCreated)
	alice.do("POST", L+"/records/accounts", map[string]any{"id": inv, "data": map[string]any{"name": "Index fund", "type": "investment", "currency": "INR"}}, http.StatusCreated)
	move := func(day string, amount int64) {
		alice.do("POST", L+"/records/transactions", map[string]any{"id": ids.NewV7(), "data": map[string]any{
			"type": "transfer", "account_id": acc, "to_account_id": inv, "amount": amount, "to_amount": amount,
			"occurred_at": time.Now().UnixMilli(), "local_date": day}}, http.StatusCreated)
	}
	move("2026-01-10", 10000000)
	alice.do("POST", L+"/records/valuations", map[string]any{"id": ids.NewV7(), "data": map[string]any{"account_id": inv, "date": "2026-06-30", "value": 11200000}}, http.StatusCreated)
	move("2026-07-05", 1000000)
	pos := alice.do("GET", L+"/reports/position?as_of=2026-07-31", nil, http.StatusOK)["positions"].([]any)[0].(map[string]any)
	if num(pos["investments"]) != 12200000 || num(pos["invested_cost"]) != 11000000 || num(pos["gain"]) != 1200000 {
		t.Fatalf("position with valuation: %v", pos)
	}
	// Before the valuation, the balance is used.
	pos = alice.do("GET", L+"/reports/position?as_of=2026-03-31", nil, http.StatusOK)["positions"].([]any)[0].(map[string]any)
	if num(pos["investments"]) != 10000000 {
		t.Fatalf("position before valuation: %v", pos)
	}

	// Change history: who wrote each version, newest first.
	alice.do("PATCH", L+"/records/accounts/"+inv, map[string]any{"data": map[string]any{"name": "Nifty fund"}}, http.StatusOK)
	h := alice.do("GET", L+"/records/accounts/"+inv+"/history", nil, http.StatusOK)["history"].([]any)
	if len(h) != 2 || h[0].(map[string]any)["data"].(map[string]any)["name"] != "Nifty fund" || h[0].(map[string]any)["user_email"] != "alice@example.com" {
		t.Fatalf("history: %v", h)
	}

	// Goals and attachments are ordinary synced records.
	alice.do("POST", L+"/records/goals", map[string]any{"id": ids.NewV7(), "data": map[string]any{"name": "Goa trip", "target_amount": 6000000, "currency": "INR", "account_ids": []string{acc}}}, http.StatusCreated)
	alice.do("POST", L+"/records/attachments", map[string]any{"id": ids.NewV7(), "data": map[string]any{"transaction_id": ids.NewV7(), "name": "bill.png", "mime": "image/png", "size": len(png), "sha256": "nothex"}}, http.StatusUnprocessableEntity)
}

func TestTrashPurgeNeedsResync(t *testing.T) {
	api, base := newFeatureServer(t)
	alice := signUp(t, base, "alice@example.com", http.StatusCreated)
	lid := ids.NewV7()
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": lid, "name": "Home", "base_currency": "INR"}, http.StatusCreated)
	L := "/api/v1/ledgers/" + lid
	tag := ids.NewV7()
	alice.do("POST", L+"/records/tags", map[string]any{"id": tag, "data": map[string]any{"name": "trip"}}, http.StatusCreated)
	first := alice.do("GET", L+"/sync/pull?since=0", nil, http.StatusOK)
	cursor := num(first["next_cursor"])
	alice.do("DELETE", L+"/records/tags/"+tag, nil, http.StatusOK)
	alice.do("POST", L+"/records/tags", map[string]any{"id": ids.NewV7(), "data": map[string]any{"name": "work"}}, http.StatusCreated)

	n, err := store.PurgeTombstones(context.Background(), api.DB, time.Now().Add(time.Hour).UnixMilli(), time.Now().UnixMilli())
	if err != nil || n != 1 {
		t.Fatalf("purged %d, %v", n, err)
	}
	// The device that never saw the delete must start over; a full pull still works.
	if res := alice.do("GET", L+"/sync/pull?since="+itoa(cursor), nil, http.StatusGone); !strings.Contains(res["_raw"].(string), "resync_required") {
		t.Fatal(res["_raw"])
	}
	full := alice.do("GET", L+"/sync/pull?since=0", nil, http.StatusOK)
	if len(full["changes"].([]any)) != 1 {
		t.Fatalf("full pull: %v", full["changes"])
	}
	alice.do("GET", L+"/sync/pull?since="+itoa(num(full["next_cursor"])), nil, http.StatusOK)
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func TestFetchRates(t *testing.T) {
	api, base := newFeatureServer(t)
	fx := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/latest" || r.URL.Query().Get("from") != "INR" {
			http.Error(w, "bad", 400)
			return
		}
		io.WriteString(w, `{"amount":1.0,"base":"INR","date":"2026-10-23","rates":{"EUR":0.01098,"USD":1.19e-2}}`)
	}))
	defer fx.Close()
	alice := signUp(t, base, "alice@example.com", http.StatusCreated)
	lid := ids.NewV7()
	alice.do("POST", "/api/v1/ledgers", map[string]any{"id": lid, "name": "Home", "base_currency": "INR"}, http.StatusCreated)
	L := "/api/v1/ledgers/" + lid
	alice.do("POST", L+"/rates/fetch", map[string]any{"base": "INR", "quotes": []string{"EUR"}}, http.StatusNotImplemented)
	api.FXURL = fx.URL
	for i := 0; i < 2; i++ { // fetching twice on one day updates the same records
		res := alice.do("POST", L+"/rates/fetch", map[string]any{"base": "inr", "quotes": []string{"EUR", "USD", "GBP"}}, http.StatusOK)
		if res["date"] != "2026-10-23" || len(res["missing"].([]any)) != 1 {
			t.Fatal(res)
		}
	}
	recs := alice.do("GET", L+"/records/exchange_rates", nil, http.StatusOK)["records"].([]any)
	if len(recs) != 2 {
		t.Fatalf("rates: %v", recs)
	}
	for _, r := range recs {
		data := r.(map[string]any)["data"].(map[string]any)
		if data["source"] != "imported" || (data["quote"] == "USD" && data["rate"] != "0.0119") {
			t.Fatalf("rate: %v", data)
		}
	}
}
