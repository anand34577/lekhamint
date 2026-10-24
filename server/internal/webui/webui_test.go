package webui

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func get(t *testing.T, h http.Handler, path string) (*http.Response, string) {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	res := rec.Result()
	b, _ := io.ReadAll(res.Body)
	return res, string(b)
}

func TestServesFolderWithSPAFallback(t *testing.T) {
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "assets"), 0o755)
	os.WriteFile(filepath.Join(dir, "index.html"), []byte("<html>app</html>"), 0o644)
	os.WriteFile(filepath.Join(dir, "assets", "app-123.js"), []byte("console.log(1)"), 0o644)
	h, err := Handler(dir)
	if err != nil {
		t.Fatal(err)
	}

	res, body := get(t, h, "/")
	if res.StatusCode != 200 || !strings.Contains(body, "app") || res.Header.Get("Cache-Control") != "no-cache" {
		t.Fatalf("index: %d %q %q", res.StatusCode, body, res.Header.Get("Cache-Control"))
	}
	if res.Header.Get("Content-Security-Policy") == "" {
		t.Fatal("missing CSP")
	}
	// Client-side routes get index.html.
	if res, body := get(t, h, "/transactions/deep/link"); res.StatusCode != 200 || !strings.Contains(body, "app") {
		t.Fatalf("fallback: %d %q", res.StatusCode, body)
	}
	// Hashed assets are cached for a long time.
	if res, body := get(t, h, "/assets/app-123.js"); res.StatusCode != 200 || body != "console.log(1)" ||
		!strings.Contains(res.Header.Get("Cache-Control"), "immutable") {
		t.Fatalf("asset: %d %q %q", res.StatusCode, body, res.Header.Get("Cache-Control"))
	}
	// Path traversal stays inside the folder.
	if _, body := get(t, h, "/../../etc/passwd"); !strings.Contains(body, "app") {
		t.Fatalf("traversal served %q", body)
	}
}

func TestEmbeddedBuildAndMissingFolder(t *testing.T) {
	h, err := Handler("")
	if err != nil {
		t.Fatal(err)
	}
	if res, body := get(t, h, "/"); res.StatusCode != 200 || !strings.Contains(body, "<html") {
		t.Fatalf("embedded index: %d", res.StatusCode)
	}
	if _, err := Handler(t.TempDir()); err == nil {
		t.Fatal("expected an error for a folder without index.html")
	}
}
