// Package ids generates and validates the UUIDs used as record identifiers.
//
// Clients create records offline, so IDs must be globally unique without
// coordination. UUIDv7 is time-ordered, which keeps B-tree inserts local.
package ids

import (
	"crypto/rand"
	"crypto/sha1"
	"encoding/binary"
	"encoding/hex"
	"strings"
	"time"
)

// NewV7 returns a new random, time-ordered UUIDv7 in canonical lowercase form.
func NewV7() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("ids: crypto/rand failed: " + err.Error())
	}
	var ts [8]byte
	binary.BigEndian.PutUint64(ts[:], uint64(time.Now().UnixMilli()))
	copy(b[0:6], ts[2:8])       // 48-bit big-endian millisecond timestamp
	b[6] = (b[6] & 0x0f) | 0x70 // version 7
	b[8] = (b[8] & 0x3f) | 0x80 // RFC 4122 variant
	return format(b)
}

// RecurringNamespace is the UUIDv5 namespace for transactions generated from
// recurring rules. Every client must use the same value so that two devices
// generating the same occurrence offline produce the same ID.
var RecurringNamespace = mustParse("6f1a3c52-8d2e-4b7a-9c41-0e5d7b2a9f13")

// V5 returns the name-based UUIDv5 of name within namespace.
func V5(namespace [16]byte, name string) string {
	h := sha1.New()
	h.Write(namespace[:])
	h.Write([]byte(name))
	var b [16]byte
	copy(b[:], h.Sum(nil))
	b[6] = (b[6] & 0x0f) | 0x50
	b[8] = (b[8] & 0x3f) | 0x80
	return format(b)
}

// Normalize lowercases s and reports whether it is a canonical UUID string
// (8-4-4-4-12 hex digits).
func Normalize(s string) (string, bool) {
	if len(s) != 36 {
		return "", false
	}
	s = strings.ToLower(s)
	for i := 0; i < 36; i++ {
		c := s[i]
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return "", false
			}
		default:
			if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
				return "", false
			}
		}
	}
	return s, true
}

// Valid reports whether s is a canonical lowercase UUID.
func Valid(s string) bool {
	n, ok := Normalize(s)
	return ok && n == s
}

func format(b [16]byte) string {
	var out [36]byte
	hex.Encode(out[0:8], b[0:4])
	out[8] = '-'
	hex.Encode(out[9:13], b[4:6])
	out[13] = '-'
	hex.Encode(out[14:18], b[6:8])
	out[18] = '-'
	hex.Encode(out[19:23], b[8:10])
	out[23] = '-'
	hex.Encode(out[24:], b[10:])
	return string(out[:])
}

func mustParse(s string) [16]byte {
	n, ok := Normalize(s)
	if !ok {
		panic("ids: invalid UUID " + s)
	}
	raw, err := hex.DecodeString(strings.ReplaceAll(n, "-", ""))
	if err != nil {
		panic(err)
	}
	var b [16]byte
	copy(b[:], raw)
	return b
}
