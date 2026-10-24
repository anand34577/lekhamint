package ids

import "testing"

func TestNewV7(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		id := NewV7()
		if !Valid(id) || id[14] != '7' {
			t.Fatalf("bad v7 %q", id)
		}
		if seen[id] {
			t.Fatalf("duplicate %q", id)
		}
		seen[id] = true
	}
}

func TestV5IsDeterministic(t *testing.T) {
	a := V5(RecurringNamespace, "rule-1:2026-10-01")
	b := V5(RecurringNamespace, "rule-1:2026-10-01")
	c := V5(RecurringNamespace, "rule-1:2026-11-01")
	if a != b || a == c || a[14] != '5' || !Valid(a) {
		t.Fatalf("a=%s b=%s c=%s", a, b, c)
	}
}

func TestNormalize(t *testing.T) {
	if n, ok := Normalize("0192A3B4-C5D6-7E8F-9A0B-1C2D3E4F5A6B"); !ok || n != "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b" {
		t.Fatalf("got %q %v", n, ok)
	}
	for _, s := range []string{"", "not-a-uuid", "0192a3b4c5d67e8f9a0b1c2d3e4f5a6b", "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6g"} {
		if _, ok := Normalize(s); ok {
			t.Errorf("Normalize(%q) accepted", s)
		}
	}
}
