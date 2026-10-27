package auth

import (
	"context"
	"crypto/rand"
	"errors"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/store"
)

// Pairing lets a signed-in browser connect a phone without typing a password
// on it: the browser asks for a short code (shown as a QR code and as text),
// and the phone trades the code for its own session. A code works once, for
// PairingTTL, and only the newest code of a user is valid.
//
// Codes live in memory, like the single sign-on hand-off codes: with several
// server processes the phone has to reach the process that issued the code.

// PairingTTL is how long a pairing code can be used.
const PairingTTL = 5 * time.Minute

// pairingAlphabet is Crockford's base 32: no I, L, O or U, so a code read
// off a screen cannot be mistyped as a similar letter or digit.
const pairingAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// PairingCodeLen is the length of a code without its separator (50 bits).
const PairingCodeLen = 10

// ErrPairingInvalid means the code is unknown, used or expired.
var ErrPairingInvalid = errors.New("this code is not valid or has expired; show a new one on the web and try again")

type pairing struct {
	userID  string
	expires time.Time
	claimed bool
	device  string // the phone that used it, for the browser to show
}

// PairingStatus is what the browser that asked for a code sees.
type PairingStatus struct {
	Claimed bool   `json:"claimed"`
	Device  string `json:"device,omitempty"`
	Expired bool   `json:"expired"`
}

// StartPairing makes a new code for userID, replacing any earlier one.
func (s *Service) StartPairing(userID string) (string, time.Time, error) {
	b := make([]byte, PairingCodeLen)
	if _, err := rand.Read(b); err != nil {
		return "", time.Time{}, err
	}
	for i := range b {
		b[i] = pairingAlphabet[int(b[i])%len(pairingAlphabet)] // 256 is a multiple of 32: no bias
	}
	code := string(b)
	exp := s.Now().Add(PairingTTL)
	s.pairMu.Lock()
	defer s.pairMu.Unlock()
	s.prunePairings()
	for k, p := range s.pairs {
		if p.userID == userID {
			delete(s.pairs, k)
		}
	}
	s.pairs[code] = &pairing{userID: userID, expires: exp}
	return code, exp, nil
}

// PairingState reports whether userID's code has been used yet.
func (s *Service) PairingState(userID, code string) PairingStatus {
	s.pairMu.Lock()
	defer s.pairMu.Unlock()
	p, ok := s.pairs[NormalizePairingCode(code)]
	if !ok || p.userID != userID {
		return PairingStatus{Expired: true}
	}
	return PairingStatus{Claimed: p.claimed, Device: p.device, Expired: !p.claimed && s.Now().After(p.expires)}
}

// ClaimPairing trades a code for a new session on device.
func (s *Service) ClaimPairing(ctx context.Context, code, device string) (store.User, string, error) {
	code = NormalizePairingCode(code)
	s.pairMu.Lock()
	p, ok := s.pairs[code]
	if !ok || p.claimed || s.Now().After(p.expires) {
		s.pairMu.Unlock()
		return store.User{}, "", ErrPairingInvalid
	}
	p.claimed = true // used once, even if what follows fails
	p.device = strings.TrimSpace(device)
	userID := p.userID
	s.pairMu.Unlock()

	u, err := store.UserByID(ctx, s.DB, userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return store.User{}, "", ErrPairingInvalid
		}
		return store.User{}, "", err
	}
	if u.Disabled {
		return store.User{}, "", ErrAccountDisabled
	}
	token, err := s.newSession(ctx, u.ID, device)
	return u, token, err
}

// NormalizePairingCode accepts what people type: lower case, spaces and
// dashes, and the letters Crockford base 32 reads as digits (O, I, L).
func NormalizePairingCode(code string) string {
	r := strings.NewReplacer("-", "", " ", "", "O", "0", "I", "1", "L", "1")
	return r.Replace(strings.ToUpper(strings.TrimSpace(code)))
}

// prunePairings drops codes that ended more than a minute ago (kept that
// long so the browser can still learn that its code was used). Holds pairMu.
func (s *Service) prunePairings() {
	if s.pairs == nil {
		s.pairs = map[string]*pairing{}
	}
	cut := s.Now().Add(-time.Minute)
	for k, p := range s.pairs {
		if p.expires.Before(cut) {
			delete(s.pairs, k)
		}
	}
}
