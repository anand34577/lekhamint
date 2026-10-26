package auth_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

// Wrong two-step codes are limited per account: after five, even the right code is refused
// for 15 minutes; afterwards it works again.
func TestOTPLockoutPerAccount(t *testing.T) {
	ctx := context.Background()
	d := testutil.NewDB(t)
	now := time.Date(2026, 10, 26, 10, 0, 0, 0, time.UTC)
	s := auth.NewService(d, true, time.Hour)
	s.Now = func() time.Time { return now }
	u, err := s.CreateUser(ctx, "a@example.com", "A", "password123")
	if err != nil {
		t.Fatal(err)
	}
	secret, _, err := s.StartTOTP(ctx, u, "test")
	if err != nil {
		t.Fatal(err)
	}
	u, _ = store.UserByEmail(ctx, d, u.Email)
	code, _ := auth.TOTPCode(secret, now)
	if _, err := s.EnableTOTP(ctx, u, "", code); err != nil {
		t.Fatal(err)
	}
	now = now.Add(2 * time.Minute) // a fresh code step

	for i := 0; i < 5; i++ {
		if _, _, err := s.LoginOTP(ctx, u.Email, "password123", "000000", "test"); !errors.Is(err, auth.ErrInvalidOTP) {
			t.Fatalf("attempt %d: %v", i, err)
		}
	}
	good, _ := auth.TOTPCode(secret, now)
	if _, _, err := s.LoginOTP(ctx, u.Email, "password123", good, "test"); !errors.Is(err, auth.ErrOTPLocked) {
		t.Fatalf("locked account accepted a code: %v", err)
	}
	// A wrong password is still just a wrong password (the lock is not a password oracle).
	if _, _, err := s.LoginOTP(ctx, u.Email, "wrong-password", good, "test"); !errors.Is(err, auth.ErrInvalidCredentials) {
		t.Fatalf("wrong password: %v", err)
	}
	now = now.Add(16 * time.Minute)
	good, _ = auth.TOTPCode(secret, now)
	if _, _, err := s.LoginOTP(ctx, u.Email, "password123", good, "test"); err != nil {
		t.Fatalf("after the lock: %v", err)
	}
}
