package hlc

import (
	"testing"
	"time"
)

func TestParseRoundTrip(t *testing.T) {
	in := "1729771234567-00ff-9f2c4a1b7e3d5c60"
	ts, err := Parse(in)
	if err != nil {
		t.Fatal(err)
	}
	if ts.Wall != 1729771234567 || ts.Counter != 255 || ts.Node != "9f2c4a1b7e3d5c60" {
		t.Fatalf("parsed %+v", ts)
	}
	if ts.String() != in {
		t.Fatalf("round trip %q", ts.String())
	}
}

func TestParseRejectsMalformed(t *testing.T) {
	for _, s := range []string{
		"", "1729771234567-0000-9f2c4a1b7e3d5c6", // short node
		"1729771234567-0000-9F2C4A1B7E3D5C60", // uppercase
		"172977123456x-0000-9f2c4a1b7e3d5c60", // non-digit wall
		"1729771234567_0000-9f2c4a1b7e3d5c60", // bad separator
		"1729771234567-000g-9f2c4a1b7e3d5c60", // bad counter
	} {
		if _, err := Parse(s); err == nil {
			t.Errorf("Parse(%q) succeeded", s)
		}
	}
}

func TestClockIsMonotonicWhenTimeStalls(t *testing.T) {
	c := NewClock("0000000000000001")
	fixed := time.UnixMilli(1_700_000_000_000)
	c.now = func() time.Time { return fixed }
	prev := c.Now()
	for i := 0; i < 1000; i++ {
		next := c.Now()
		if next <= prev {
			t.Fatalf("not increasing: %s then %s", prev, next)
		}
		prev = next
	}
}

func TestClockObserveMovesPastRemote(t *testing.T) {
	c := NewClock("0000000000000001")
	c.now = func() time.Time { return time.UnixMilli(1_000) }
	remote := "0000000005000-0007-ffffffffffffffff"
	c.Observe(remote)
	if got := c.Now(); got <= remote {
		t.Fatalf("Now() = %s, want > %s", got, remote)
	}
}

func TestStringOrderMatchesCausalOrder(t *testing.T) {
	a := Timestamp{Wall: 999, Counter: 0xffff, Node: "ffffffffffffffff"}.String()
	b := Timestamp{Wall: 1000, Counter: 0, Node: "0000000000000000"}.String()
	if !(a < b) {
		t.Fatalf("%s should sort before %s", a, b)
	}
}
