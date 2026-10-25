// Package webui serves the browser app. A build embedded in the binary is used
// by default (see `make web`); LEKHAMINT_WEB_DIR serves a folder instead.
package webui

import (
	"embed"
	"io/fs"
	"net/http"
	"os"
	"path"
	"strings"
)

//go:embed all:dist
var embedded embed.FS

// DefaultConnect lists the origins the web app may call besides its own
// server: AI providers and models on this computer, for the assistant's
// "connect from this browser" mode. LEKHAMINT_AI_CONNECT_ORIGINS adds more.
var DefaultConnect = []string{"https://api.openai.com", "https://openrouter.ai", "https://api.groq.com", "https://generativelanguage.googleapis.com", "http://localhost:*", "http://127.0.0.1:*"}

// Handler serves static files from dir (or the embedded build when dir is
// empty). Unknown paths get index.html so client-side routes work on reload.
// connect lists extra origins the page may fetch from (CSP connect-src).
func Handler(dir string, connect ...string) (http.Handler, error) {
	connectSrc := "'self'"
	for _, o := range connect {
		if o = strings.TrimSpace(o); o != "" && !strings.ContainsAny(o, " ;'\"") {
			connectSrc += " " + o
		}
	}
	var root fs.FS
	if dir != "" {
		if _, err := os.Stat(path.Join(dir, "index.html")); err != nil {
			return nil, err
		}
		root = os.DirFS(dir)
	} else {
		sub, err := fs.Sub(embedded, "dist")
		if err != nil {
			return nil, err
		}
		root = sub
	}
	files := http.FileServerFS(root)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		h := w.Header()
		if name == "" || name == "index.html" {
			h.Set("Cache-Control", "no-cache")
		} else if st, err := fs.Stat(root, name); err == nil && !st.IsDir() {
			if strings.HasPrefix(name, "assets/") {
				// Vite puts a content hash in asset file names.
				h.Set("Cache-Control", "public, max-age=31536000, immutable")
			} else {
				h.Set("Cache-Control", "no-cache")
			}
		} else {
			h.Set("Cache-Control", "no-cache")
			r = r.Clone(r.Context())
			r.URL.Path = "/"
		}
		h.Set("Content-Security-Policy",
			"default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; connect-src "+connectSrc+"; "+
				"frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
		files.ServeHTTP(w, r)
	}), nil
}
