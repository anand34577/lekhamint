package auth

import (
	"encoding/base32"
	"strings"
	"testing"
	"time"
)

// RFC 6238 appendix B, SHA-1, truncated to 6 digits.
func TestTOTPVectors(t *testing.T) {
	key := []byte("12345678901234567890")
	cases := map[int64]string{59: "287082", 1111111109: "081804", 1111111111: "050471", 1234567890: "005924", 2000000000: "279037"}
	for ts, want := range cases {
		if got := totpCode(key, ts/30, 6); got != want {
			t.Errorf("t=%d: got %s, want %s", ts, got, want)
		}
	}
}

func TestCheckTOTP(t *testing.T) {
	secret := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString([]byte("12345678901234567890"))
	now := time.Unix(1111111111, 0)
	code, _ := TOTPCode(secret, now)
	step, ok := CheckTOTP(secret, code, now, 0)
	if !ok || step != 1111111111/30 {
		t.Fatalf("current code refused: %v %d", ok, step)
	}
	// Spaces are forgiven; the previous step is still accepted (clock drift).
	if _, ok := CheckTOTP(secret, code[:3]+" "+code[3:], now.Add(30*time.Second), 0); !ok {
		t.Fatal("code from the previous step should be accepted")
	}
	// A used step cannot be used again, and old codes are refused.
	if _, ok := CheckTOTP(secret, code, now, step); ok {
		t.Fatal("replayed code accepted")
	}
	if _, ok := CheckTOTP(secret, code, now.Add(2*time.Minute), 0); ok {
		t.Fatal("two-minute-old code accepted")
	}
	if _, ok := CheckTOTP(secret, "12345", now, 0); ok {
		t.Fatal("short code accepted")
	}
	if !strings.HasPrefix(TOTPURL(secret, "a@b.c", "LekhaMint"), "otpauth://totp/LekhaMint:a@b.c?") {
		t.Fatal(TOTPURL(secret, "a@b.c", "LekhaMint"))
	}
}

func TestRecoveryCodes(t *testing.T) {
	codes, err := NewRecoveryCodes(10)
	if err != nil || len(codes) != 10 || len(codes[0]) != 9 || codes[0][4] != '-' {
		t.Fatalf("codes %v %v", codes, err)
	}
	stored := HashRecoveryCodes(codes)
	rest, ok := UseRecoveryCode(stored, strings.ToUpper(strings.ReplaceAll(codes[3], "-", " ")))
	if !ok || RecoveryCodesLeft(rest) != 9 {
		t.Fatalf("recovery code not accepted: %v %d", ok, RecoveryCodesLeft(rest))
	}
	if _, ok := UseRecoveryCode(rest, codes[3]); ok {
		t.Fatal("recovery code worked twice")
	}
	if _, ok := UseRecoveryCode(stored, ""); ok {
		t.Fatal("empty code accepted")
	}
}
