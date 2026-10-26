package httpapi

import "testing"

func TestMinorFromText(t *testing.T) {
	for _, tc := range []struct {
		in     string
		digits int
		want   int64
		ok     bool
	}{
		{"486", 2, 48600, true},
		{"1,299", 2, 129900, true},
		{"₹ 486.5", 2, 48650, true},
		{"486.55", 2, 48655, true},
		{"486.555", 2, 0, false}, // more decimals than the currency has
		{"1200", 0, 1200, true},
		{"swiggy", 2, 0, false},
		{"12 swiggy", 2, 0, false},
		{"0", 2, 0, false},
		{".", 2, 0, false},
		{"1.2.3", 2, 0, false},
	} {
		got, ok := minorFromText(tc.in, tc.digits)
		if got != tc.want || ok != tc.ok {
			t.Errorf("minorFromText(%q, %d) = %d, %v; want %d, %v", tc.in, tc.digits, got, ok, tc.want, tc.ok)
		}
	}
}
