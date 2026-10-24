//go:build js

// Command lekhamint-wasm is the LekhaMint server compiled to WebAssembly for
// the online demo. It runs the real API on an in-memory SQLite database inside
// the visitor's browser, seeded with the sample household from package demo.
// Nothing leaves the browser and everything resets on reload.
//
// It exposes one global to the web app:
//
//	lekhamintDemo.fetch(method, path, headers, body?) → Promise<{status, headers, body}>
//
// and resolves the promise in globalThis.lekhamintDemoReady once seeded.
package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"syscall/js"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/demo"
	"github.com/anand34577/lekhamint/server/internal/httpapi"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

var version = "dev"

func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	handler, err := start(context.Background(), log)
	report := js.Global().Get("lekhamintDemoReport")
	if err != nil {
		log.Error("demo failed to start", "err", err)
		if report.Type() == js.TypeFunction {
			report.Invoke(err.Error())
		}
		return
	}
	js.Global().Set("lekhamintDemo", js.ValueOf(map[string]any{"fetch": js.FuncOf(func(_ js.Value, args []js.Value) any {
		return serve(handler, args)
	})}))
	if report.Type() == js.TypeFunction {
		report.Invoke(js.Null())
	}
	select {} // keep the handlers alive
}

func start(ctx context.Context, log *slog.Logger) (http.Handler, error) {
	d, err := db.Open(ctx, db.SQLite, "demo.db")
	if err != nil {
		return nil, err
	}
	if _, err := d.Migrate(ctx); err != nil {
		return nil, err
	}
	sync := syncer.New(d)
	authSvc := auth.NewService(d, true, 24*time.Hour)
	ledgers := &ledger.Service{DB: d, Sync: sync}
	if _, err := demo.Seed(ctx, demo.Deps{Auth: authSvc, Ledgers: ledgers, Sync: sync}, localToday(), 12); err != nil {
		return nil, err
	}
	api := &httpapi.Server{
		DB: d, Auth: authSvc, Sync: sync, Ledgers: ledgers, Reports: &report.Service{DB: d},
		Log: log, Version: version + " (demo)", MaxImportSize: 32 << 20,
	}
	return api.Handler(), nil
}

// localToday is the visitor's calendar date, so "today" in the sample data
// matches the date the web app shows.
func localToday() time.Time {
	now := js.Global().Get("Date").New()
	return time.Date(now.Call("getFullYear").Int(), time.Month(now.Call("getMonth").Int()+1), now.Call("getDate").Int(), 0, 0, 0, 0, time.UTC)
}

// serve runs one request through the API and resolves a promise with the
// response. Handlers block, so they run on their own goroutine.
func serve(h http.Handler, args []js.Value) any {
	method, path, headers := args[0].String(), args[1].String(), args[2]
	var body []byte
	if len(args) > 3 && args[3].Type() == js.TypeString {
		body = []byte(args[3].String())
	} else if len(args) > 3 && args[3].InstanceOf(js.Global().Get("Uint8Array")) {
		body = make([]byte, args[3].Get("length").Int())
		js.CopyBytesToGo(body, args[3])
	}
	return js.Global().Get("Promise").New(js.FuncOf(func(_ js.Value, p []js.Value) any {
		resolve := p[0]
		go func() {
			req := httptest.NewRequest(method, path, bytes.NewReader(body))
			req.RemoteAddr = "127.0.0.1:1"
			keys := js.Global().Get("Object").Call("keys", headers)
			for i := range keys.Length() {
				k := keys.Index(i).String()
				req.Header.Set(k, headers.Get(k).String())
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			res := rec.Result()
			out, _ := io.ReadAll(res.Body)
			hdr := map[string]any{}
			for k := range res.Header {
				hdr[k] = res.Header.Get(k)
			}
			resolve.Invoke(js.ValueOf(map[string]any{"status": res.StatusCode, "headers": hdr, "body": string(out)}))
		}()
		return nil
	}))
}
