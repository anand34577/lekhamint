package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"net/url"
	"strings"
	"time"
)

// Two-step sign-in with time-based one-time codes (TOTP, RFC 6238): the
// 6-digit codes that authenticator apps (Google Authenticator, Aegis, 1Password,
// Microsoft Authenticator...) show and change every 30 seconds.

const (
	totpPeriod = 30
	totpDigits = 6
	// totpSkew accepts the code before and after the current one, so a phone
	// clock that is a little off, or a code typed as it changes, still works.
	totpSkew = 1
)

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// NewTOTPSecret returns a random 160-bit secret in base32, the form
// authenticator apps accept when typed in by hand.
func NewTOTPSecret() (string, error) {
	b := make([]byte, 20)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return b32.EncodeToString(b), nil
}

// TOTPURL is the otpauth:// link authenticator apps read from a QR code.
func TOTPURL(secret, account, issuer string) string {
	label := url.PathEscape(issuer + ":" + account)
	q := url.Values{"secret": {secret}, "issuer": {issuer}, "algorithm": {"SHA1"}, "digits": {fmt.Sprint(totpDigits)}, "period": {fmt.Sprint(totpPeriod)}}
	return "otpauth://totp/" + label + "?" + q.Encode()
}

// totpCode is the code for one 30-second step (RFC 4226 dynamic truncation).
func totpCode(key []byte, step int64, digits int) string {
	var msg [8]byte
	binary.BigEndian.PutUint64(msg[:], uint64(step))
	mac := hmac.New(sha1.New, key)
	mac.Write(msg[:])
	sum := mac.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	bin := binary.BigEndian.Uint32(sum[off:off+4]) & 0x7fffffff
	mod := uint32(1)
	for i := 0; i < digits; i++ {
		mod *= 10
	}
	return fmt.Sprintf("%0*d", digits, bin%mod)
}

// TOTPCode returns the 6-digit code for secret at t (what the app shows).
func TOTPCode(secret string, t time.Time) (string, error) {
	key, err := b32.DecodeString(strings.ToUpper(strings.TrimRight(secret, "=")))
	if err != nil {
		return "", err
	}
	return totpCode(key, t.Unix()/totpPeriod, totpDigits), nil
}

// CheckTOTP verifies code against secret at now and returns the matched step.
// A step at or before lastStep is refused, so a code that was already used
// (seen over a shoulder, or replayed) cannot sign in again.
func CheckTOTP(secret, code string, now time.Time, lastStep int64) (int64, bool) {
	code = strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, code)
	if len(code) != totpDigits {
		return 0, false
	}
	key, err := b32.DecodeString(strings.ToUpper(strings.TrimRight(secret, "=")))
	if err != nil || len(key) == 0 {
		return 0, false
	}
	cur := now.Unix() / totpPeriod
	for d := int64(-totpSkew); d <= totpSkew; d++ {
		step := cur + d
		if step <= lastStep {
			continue
		}
		if subtle.ConstantTimeCompare([]byte(totpCode(key, step, totpDigits)), []byte(code)) == 1 {
			return step, true
		}
	}
	return 0, false
}

// NewRecoveryCodes returns n one-time codes like "k7m2-9xqp" for when the
// phone with the authenticator app is lost. Only their hashes are stored.
func NewRecoveryCodes(n int) ([]string, error) {
	const alphabet = "abcdefghjkmnpqrstuvwxyz23456789" // no 0/o, 1/l/i
	out := make([]string, n)
	buf := make([]byte, 8)
	for i := range out {
		if _, err := rand.Read(buf); err != nil {
			return nil, err
		}
		var sb strings.Builder
		for j, b := range buf {
			if j == 4 {
				sb.WriteByte('-')
			}
			sb.WriteByte(alphabet[int(b)%len(alphabet)])
		}
		out[i] = sb.String()
	}
	return out, nil
}

// normRecovery makes recovery codes forgiving to type: case, spaces and the
// dash do not matter.
func normRecovery(code string) string {
	code = strings.ToLower(code)
	return strings.Map(func(r rune) rune {
		if r == '-' || r == ' ' {
			return -1
		}
		return r
	}, code)
}

// HashRecoveryCodes returns the stored form: comma-separated hashes.
func HashRecoveryCodes(codes []string) string {
	h := make([]string, len(codes))
	for i, c := range codes {
		h[i] = HashToken(normRecovery(c))
	}
	return strings.Join(h, ",")
}

// UseRecoveryCode reports whether code is one of the stored codes and returns
// the stored list without it (each code works once).
func UseRecoveryCode(stored, code string) (string, bool) {
	want := HashToken(normRecovery(code))
	if normRecovery(code) == "" || stored == "" {
		return stored, false
	}
	list := strings.Split(stored, ",")
	for i, h := range list {
		if subtle.ConstantTimeCompare([]byte(h), []byte(want)) == 1 {
			return strings.Join(append(list[:i:i], list[i+1:]...), ","), true
		}
	}
	return stored, false
}

// RecoveryCodesLeft counts unused recovery codes.
func RecoveryCodesLeft(stored string) int {
	if stored == "" {
		return 0
	}
	return len(strings.Split(stored, ","))
}
