package logfile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRotatesAndKeepsNewest(t *testing.T) {
	path := filepath.Join(t.TempDir(), "logs", "lekhamint.log")
	l, err := Open(path, 100, 2)
	if err != nil {
		t.Fatal(err)
	}
	line := strings.Repeat("x", 39) + "\n" // 40 bytes: two lines fit, the third rotates
	for range 9 {
		if _, err := l.Write([]byte(line)); err != nil {
			t.Fatal(err)
		}
	}
	l.Close()
	for _, name := range []string{path, path + ".1", path + ".2"} {
		st, err := os.Stat(name)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if st.Size() > 100 {
			t.Errorf("%s is %d bytes, over the limit", name, st.Size())
		}
	}
	if _, err := os.Stat(path + ".3"); !os.IsNotExist(err) {
		t.Errorf("kept more than 2 old files")
	}
}

func TestAppendsToExistingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a.log")
	os.WriteFile(path, []byte("old\n"), 0o600)
	l, err := Open(path, 0, -1)
	if err != nil {
		t.Fatal(err)
	}
	l.Write([]byte("new\n"))
	l.Close()
	if b, _ := os.ReadFile(path); string(b) != "old\nnew\n" {
		t.Fatalf("got %q", b)
	}
}
