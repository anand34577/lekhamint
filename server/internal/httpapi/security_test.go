package httpapi_test

import (
	"context"
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
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

// loginCookie registers a user over HTTP and returns the session cookie jar.
func loginCookie(t *testing.T, base string) *http.Cookie {
	t.Helper()
	body := `{"email":"cookie@example.com","password":"longenough1","name":"Cookie","device_name":"Test"}`
	resp, err := http.Post(base+"/api/v1/auth/register", "application/json", strings.NewReader(body))
	if err != nil || resp.StatusCode != http.StatusCreated {
		t.Fatalf("register: %v %v", resp, err)
	}
	defer resp.Body.Close()
	for _, c := range resp.Cookies() {
		if c.Name == httpapi.SessionCookie {
			return c
		}
	}
	t.Fatalf("no %s cookie set on register", httpapi.SessionCookie)
	return nil
}

func TestCookieSessionAuth(t *testing.T) {
	d := testutil.NewDB(t)
	sync := syncer.New(d)
	api := &httpapi.Server{DB: d, Auth: auth.NewService(d, true, time.Hour), Sync: sync,
		Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test", MaxImportSize: 32 << 20}
	srv := httptest.NewServer(api.Handler())
	t.Cleanup(srv.Close)

	cookie := loginCookie(t, srv.URL)
	if !cookie.HttpOnly {
		t.Error("session cookie must be HttpOnly")
	}

	get, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/auth/me", nil)
	get.AddCookie(cookie)
	resp, err := http.DefaultClient.Do(get)
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("cookie GET /me: %v %v", resp, err)
	}
	resp.Body.Close()

	// A write with the cookie but without the client header is refused (CSRF defence).
	del, _ := http.NewRequest(http.MethodDelete, srv.URL+"/api/v1/auth/me", nil)
	del.AddCookie(cookie)
	del.Body = io.NopCloser(strings.NewReader(`{"password":"longenough1"}`))
	del.Header.Set("Content-Type", "application/json")
	resp, err = http.DefaultClient.Do(del)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("cookie write without %s header: got %d, want 401", httpapi.ClientHeader, resp.StatusCode)
	}

	// With the web client header the same write is authenticated (it deletes
	// the account, which is fine: the password matches).
	del2, _ := http.NewRequest(http.MethodDelete, srv.URL+"/api/v1/auth/me", nil)
	del2.AddCookie(cookie)
	del2.Body = io.NopCloser(strings.NewReader(`{"password":"longenough1"}`))
	del2.Header.Set("Content-Type", "application/json")
	del2.Header.Set(httpapi.ClientHeader, "web")
	resp, err = http.DefaultClient.Do(del2)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("cookie write with %s header: got %d, want 200", httpapi.ClientHeader, resp.StatusCode)
	}
}

func TestSSOSubjectBinding(t *testing.T) {
	d := testutil.NewDB(t)
	svc := auth.NewService(d, true, time.Hour)
	ctx := context.Background()

	// The provider's first identity creates the account and binds to sub "one".
	u, _, err := svc.SSOLogin(ctx, "asha@example.com", "Asha", "one", "web", true)
	if err != nil || u.Email != "asha@example.com" {
		t.Fatalf("first SSO sign-in: %v %v", u, err)
	}
	// The same subject signs in again: fine.
	if _, _, err := svc.SSOLogin(ctx, "asha@example.com", "Asha", "one", "web", true); err != nil {
		t.Fatalf("same subject should sign in: %v", err)
	}
	// A different subject bearing the same email is refused.
	if _, _, err := svc.SSOLogin(ctx, "asha@example.com", "Evil", "two", "web", true); err != auth.ErrSSOMismatch {
		t.Fatalf("different subject: got %v, want ErrSSOMismatch", err)
	}
	// A password account is bound on its first SSO sign-in and then locked to that subject.
	if _, err := svc.CreateUser(ctx, "priya@example.com", "Priya", "longenough1"); err != nil {
		t.Fatal(err)
	}
	if _, _, err := svc.SSOLogin(ctx, "priya@example.com", "Priya", "p1", "web", true); err != nil {
		t.Fatalf("bind password account: %v", err)
	}
	if _, _, err := svc.SSOLogin(ctx, "priya@example.com", "Priya", "p2", "web", true); err != auth.ErrSSOMismatch {
		t.Fatalf("password account rebind attempt: got %v, want ErrSSOMismatch", err)
	}
	// The binding is visible server-side only.
	got, err := store.UserByEmail(ctx, d, "asha@example.com")
	if err != nil || got.SSOSub != "one" {
		t.Fatalf("stored binding: %v %v", got, err)
	}
}

func TestAbsoluteSessionCap(t *testing.T) {
	d := testutil.NewDB(t)
	now := time.Now()
	svc := &auth.Service{DB: d, AllowSignup: true, SessionTTL: 24 * time.Hour, Now: func() time.Time { return now }}
	ctx := context.Background()
	if _, err := svc.CreateUser(ctx, "old@example.com", "Old", "longenough1"); err != nil {
		t.Fatal(err)
	}
	u, token, err := svc.Login(ctx, "old@example.com", "longenough1", "test")
	if err != nil {
		t.Fatal(err)
	}
	// A year and a day later the session is dead even though its sliding
	// expiry was refreshed yesterday.
	now = now.AddDate(1, 0, 1)
	if _, _, err := svc.Authenticate(ctx, token); err != auth.ErrUnauthenticated {
		t.Fatalf("ancient session: got %v, want ErrUnauthenticated", err)
	}
	_ = u
}

// Behind a reverse proxy that ends TLS, the session cookie must still be Secure, but only when the
// proxy is trusted: anyone can send X-Forwarded-Proto.
func TestSessionCookieSecureBehindProxy(t *testing.T) {
	for _, tc := range []struct {
		name       string
		trustProxy bool
		proto      string
		want       bool
	}{
		{"trusted proxy over https", true, "https", true},
		{"trusted proxy over http", true, "http", false},
		{"untrusted header", false, "https", false},
		{"plain LAN", false, "", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := testutil.NewDB(t)
			sync := syncer.New(d)
			api := &httpapi.Server{DB: d, Auth: auth.NewService(d, true, time.Hour), Sync: sync,
				Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d}, TrustProxy: tc.trustProxy,
				Log: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test", MaxImportSize: 32 << 20}
			req := httptest.NewRequest(http.MethodPost, "http://money.example.com/api/v1/auth/register",
				strings.NewReader(`{"email":"proxy@example.com","password":"longenough1","device_name":"Test"}`))
			req.RemoteAddr = "192.168.1.10:5555"
			if tc.proto != "" {
				req.Header.Set("X-Forwarded-Proto", tc.proto)
			}
			rec := httptest.NewRecorder()
			api.Handler().ServeHTTP(rec, req)
			if rec.Code != http.StatusCreated {
				t.Fatalf("register: %d %s", rec.Code, rec.Body)
			}
			var got *http.Cookie
			for _, c := range rec.Result().Cookies() {
				if c.Name == httpapi.SessionCookie {
					got = c
				}
			}
			if got == nil || got.Secure != tc.want {
				t.Fatalf("cookie %+v, want Secure=%v", got, tc.want)
			}
		})
	}
}
