// Package auth handles password hashing, session tokens and login.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

// Argon2id parameters: the OWASP-recommended minimum (19 MiB, t=2, p=1),
// which stays light enough for a small home server.
const (
	argonMemoryKiB = 19 * 1024
	argonTime      = 2
	argonThreads   = 1
	argonKeyLen    = 32
	saltLen        = 16
)

// Password length limits. No composition rules, per NIST SP 800-63B.
const (
	MinPasswordLen = 8
	MaxPasswordLen = 256 // bounds hashing cost per request
)

// ErrWeakPassword is returned for passwords outside the length limits.
var ErrWeakPassword = fmt.Errorf("password must be %d to %d characters", MinPasswordLen, MaxPasswordLen)

// CheckPasswordPolicy validates a new password.
func CheckPasswordPolicy(pw string) error {
	if n := len([]rune(pw)); n < MinPasswordLen || len(pw) > MaxPasswordLen {
		return ErrWeakPassword
	}
	return nil
}

// HashPassword returns a PHC-format Argon2id hash.
func HashPassword(pw string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := argon2.IDKey([]byte(pw), salt, argonTime, argonMemoryKiB, argonThreads, argonKeyLen)
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, argonMemoryKiB, argonTime, argonThreads, b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

var errBadHash = errors.New("auth: unrecognised password hash")

// VerifyPassword reports whether pw matches the encoded hash. Parameters are
// read from the hash, so older hashes keep working after a parameter change.
func VerifyPassword(encoded, pw string) (bool, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, errBadHash
	}
	var (
		version   int
		mem, iter uint32
		threads   uint8
	)
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false, errBadHash
	}
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &mem, &iter, &threads); err != nil {
		return false, errBadHash
	}
	b64 := base64.RawStdEncoding
	salt, err := b64.DecodeString(parts[4])
	if err != nil {
		return false, errBadHash
	}
	want, err := b64.DecodeString(parts[5])
	if err != nil {
		return false, errBadHash
	}
	got := argon2.IDKey([]byte(pw), salt, iter, mem, threads, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

// TokenPrefix marks LekhaMint session tokens, which helps secret scanners.
const TokenPrefix = "kosh_"

// NewToken returns a random session token and the SHA-256 hash to store.
// Only the hash is kept server-side, so a database leak exposes no tokens.
func NewToken() (token, hash string, err error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", "", err
	}
	token = TokenPrefix + base64.RawURLEncoding.EncodeToString(b)
	return token, HashToken(token), nil
}

// HashToken returns the stored form of a token.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
