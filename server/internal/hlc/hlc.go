// Package hlc implements hybrid logical clock timestamps.
//
// The string form is fixed-width, so comparing two timestamps as plain strings
// (in Go or in SQL) gives the correct causal order:
//
//	<13-digit unix ms>-<4 hex counter>-<16 hex node id>
//	1729771234567-0000-9f2c4a1b7e3d5c60
package hlc

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"
)

// Len is the length of an encoded timestamp.
const Len = 13 + 1 + 4 + 1 + 16

// Timestamp is a decoded HLC value.
type Timestamp struct {
	Wall    int64  // unix milliseconds
	Counter uint16 // logical counter within the same millisecond
	Node    string // 16 lowercase hex chars
}

// ErrFormat is returned when a timestamp string is malformed.
var ErrFormat = errors.New("hlc: malformed timestamp")

// Parse decodes and validates an encoded timestamp.
func Parse(s string) (Timestamp, error) {
	if len(s) != Len || s[13] != '-' || s[18] != '-' {
		return Timestamp{}, ErrFormat
	}
	for i := 0; i < 13; i++ {
		if s[i] < '0' || s[i] > '9' {
			return Timestamp{}, ErrFormat
		}
	}
	if !isLowerHex(s[14:18]) || !isLowerHex(s[19:]) {
		return Timestamp{}, ErrFormat
	}
	wall, _ := strconv.ParseInt(s[:13], 10, 64)
	ctr, _ := strconv.ParseUint(s[14:18], 16, 16)
	return Timestamp{Wall: wall, Counter: uint16(ctr), Node: s[19:]}, nil
}

// String encodes the timestamp.
func (t Timestamp) String() string {
	return fmt.Sprintf("%013d-%04x-%s", t.Wall, t.Counter, t.Node)
}

// Clock issues monotonically increasing timestamps for one node.
type Clock struct {
	mu   sync.Mutex
	last Timestamp
	now  func() time.Time
}

// NewClock returns a clock with the given 16-hex-char node ID. An empty node
// ID gets a random one.
func NewClock(node string) *Clock {
	if node == "" {
		node = RandomNode()
	}
	return &Clock{last: Timestamp{Node: node}, now: time.Now}
}

// RandomNode returns a random 16-hex-char node ID.
func RandomNode() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("hlc: crypto/rand failed: " + err.Error())
	}
	return hex.EncodeToString(b[:])
}

// Now returns a timestamp greater than every timestamp issued or observed so far.
func (c *Clock) Now() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	wall := c.now().UnixMilli()
	if wall > c.last.Wall {
		c.last.Wall, c.last.Counter = wall, 0
	} else {
		c.advanceCounter()
	}
	return c.last.String()
}

// Observe moves the clock forward so that the next Now() is greater than remote.
// Malformed input is ignored.
func (c *Clock) Observe(remote string) {
	r, err := Parse(remote)
	if err != nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if r.Wall > c.last.Wall || (r.Wall == c.last.Wall && r.Counter > c.last.Counter) {
		c.last.Wall, c.last.Counter = r.Wall, r.Counter
	}
}

func (c *Clock) advanceCounter() {
	if c.last.Counter == 0xffff {
		// Counter overflow within one millisecond: borrow the next millisecond.
		c.last.Wall++
		c.last.Counter = 0
		return
	}
	c.last.Counter++
}

func isLowerHex(s string) bool {
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}
