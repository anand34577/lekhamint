// Command lekhamint is the LekhaMint sync and backup server.
//
//	lekhamint serve                         run the HTTP server
//	lekhamint demo                          try it with sample data; nothing is saved
//	lekhamint migrate                       apply database migrations and exit
//	lekhamint backup  [--format db|json] --out PATH
//	lekhamint restore [--format db|json] --from PATH
//	lekhamint user add --email E [--name N]          (password from LEKHAMINT_PASSWORD or stdin)
//	lekhamint user passwd --email E
//	lekhamint version
//
// Every flag can also be set with an environment variable; see docs/08-deployment.md.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/backup"
	"github.com/anand34577/lekhamint/server/internal/config"
	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/demo"
	"github.com/anand34577/lekhamint/server/internal/httpapi"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/logfile"
	"github.com/anand34577/lekhamint/server/internal/notify"
	"github.com/anand34577/lekhamint/server/internal/oidc"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/webui"

	"golang.org/x/term"
)

// version is set at build time: -ldflags "-X main.version=1.2.3".
var version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		args = []string{"serve"}
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "serve":
		return cmdServe(rest)
	case "demo":
		return cmdDemo(rest)
	case "migrate":
		return cmdMigrate(rest)
	case "backup":
		return cmdBackup(rest)
	case "restore":
		return cmdRestore(rest)
	case "user":
		return cmdUser(rest)
	case "healthcheck":
		return cmdHealthcheck(rest)
	case "version", "--version", "-v":
		fmt.Printf("lekhamint %s (schema v%d)\n", version, db.LatestSchemaVersion())
		return nil
	case "help", "--help", "-h":
		fmt.Println(usage)
		return nil
	}
	return fmt.Errorf("unknown command %q\n\n%s", cmd, usage)
}

const usage = `Usage: lekhamint <command> [flags]

Commands:
  serve     run the server (default)
  demo      run a throwaway server with sample data (nothing is saved)
  migrate   apply database migrations
  backup    write a backup (--format db for a SQLite snapshot, json for a portable dump)
  restore   restore a backup (stop the server first)
  user      add | passwd  manage users
  healthcheck  exit 0 if the local server is healthy (for Docker)
  version   print the version

Run "lekhamint <command> -h" for the flags of a command.`

// setup parses common flags, opens the database and applies migrations.
func setup(name string, args []string, extra func(fs *flag.FlagSet)) (*config.Config, *db.DB, *slog.Logger, error) {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	cfg := config.Register(fs)
	if extra != nil {
		extra(fs)
	}
	if err := fs.Parse(args); err != nil {
		return nil, nil, nil, err
	}
	if err := cfg.Finish(); err != nil {
		return nil, nil, nil, err
	}
	log, err := newLogger(cfg)
	if err != nil {
		return nil, nil, nil, err
	}
	dialect, _ := cfg.Dialect()
	d, err := db.Open(context.Background(), dialect, cfg.DSN())
	if err != nil {
		log.Error("cannot open the database", "driver", dialect.String(), "err", err)
		return nil, nil, nil, err
	}
	n, err := d.Migrate(context.Background())
	if err != nil {
		log.Error("database migration failed", "err", err)
		d.Close()
		return nil, nil, nil, err
	}
	if n > 0 {
		log.Info("database migrated", "applied", n, "schema_version", db.LatestSchemaVersion())
	}
	return cfg, d, log, nil
}

// newLogger logs to stderr and, with --log-file, also to a rotated file.
func newLogger(cfg *config.Config) (*slog.Logger, error) {
	var level slog.Level
	_ = level.UnmarshalText([]byte(cfg.LogLevel)) // validated by cfg.Finish
	var out io.Writer = os.Stderr
	if cfg.LogFile != "" {
		f, err := logfile.Open(cfg.LogFile, logfile.DefaultMaxSize, logfile.DefaultKeep)
		if err != nil {
			return nil, err
		}
		out = io.MultiWriter(os.Stderr, f)
	}
	opts := &slog.HandlerOptions{Level: level}
	if cfg.LogFormat == "json" {
		return slog.New(slog.NewJSONHandler(out, opts)), nil
	}
	return slog.New(slog.NewTextHandler(out, opts)), nil
}

func cmdServe(args []string) error {
	cfg, d, log, err := setup("serve", args, nil)
	if err != nil {
		return err
	}
	defer d.Close()
	return serve(cfg, d, log, nil)
}

// cmdDemo runs a throwaway server filled with a sample household, so the
// apps can be tried without entering any data. Nothing is kept on exit.
func cmdDemo(args []string) error {
	dir, err := os.MkdirTemp("", "lekhamint-demo-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	args = append([]string{"--data-dir", dir, "--backup-interval", "0"}, args...)
	cfg, d, log, err := setup("demo", args, nil)
	if err != nil {
		return err
	}
	defer d.Close()
	return serve(cfg, d, log, func(ctx context.Context, svc demo.Deps) error {
		start := time.Now()
		if _, err := demo.Seed(ctx, svc, time.Now(), 12); err != nil {
			return err
		}
		log.Info("demo data ready", "took", time.Since(start).Round(time.Millisecond))
		host := cfg.Addr
		if strings.HasPrefix(host, ":") {
			host = "localhost" + host
		}
		fmt.Fprintf(os.Stderr, "\n  LekhaMint demo is running at http://%s\n  Sign in with %s / %s\n"+
			"  Everything is deleted when you stop it (Ctrl+C).\n\n", host, demo.Email, demo.Password)
		return nil
	})
}

// serve runs the HTTP server until SIGINT/SIGTERM. before, if set, runs once
// the services exist and before the server accepts requests.
func serve(cfg *config.Config, d *db.DB, log *slog.Logger, before func(context.Context, demo.Deps) error) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	sync := syncer.New(d)
	authSvc := auth.NewService(d, cfg.AllowSignup, cfg.SessionTTL)
	ledgers := &ledger.Service{DB: d, Sync: sync}
	api := &httpapi.Server{
		DB: d, Auth: authSvc, Sync: sync,
		Ledgers: ledgers, Reports: &report.Service{DB: d},
		Log: log, Version: version, CORSOrigins: cfg.CORSOrigins, TrustProxy: cfg.TrustProxy,
		MaxImportSize: cfg.MaxImportSize,
	}
	if cfg.AIBaseURL != "" {
		api.AI = &httpapi.AIProxy{BaseURL: cfg.AIBaseURL, APIKey: cfg.AIAPIKey, Model: cfg.AIModel, Timeout: cfg.AITimeout}
		log.Info("assistant proxy enabled", "endpoint", httpapi.RedactURL(cfg.AIBaseURL), "model", cfg.AIModel)
	}
	notifier := buildNotifier(cfg)
	api.Notice = &notify.Dispatcher{DB: d, Notifier: notifier, Reports: api.Reports, Log: log, ReminderHour: cfg.ReminderHour}
	if notifier.HasEmail() || notifier.HasGotify() {
		log.Info("notifications enabled", "email", notifier.HasEmail(), "gotify", notifier.HasGotify(), "reminder_hour", cfg.ReminderHour)
	}
	if cfg.OIDCIssuer != "" {
		api.OIDC = &oidc.Client{Config: oidc.Config{Issuer: cfg.OIDCIssuer, ClientID: cfg.OIDCClientID, ClientSecret: cfg.OIDCClientSecret,
			RedirectURL: cfg.PublicURL + "/api/v1/auth/oidc/callback", Scopes: cfg.OIDCScopes, DisplayName: cfg.OIDCName}}
		api.OIDCAutoCreate, api.OIDCOnly = cfg.OIDCAutoCreate, cfg.OIDCOnly
		log.Info("single sign-on enabled", "issuer", cfg.OIDCIssuer, "redirect_url", api.OIDC.RedirectURL, "password_login", !cfg.OIDCOnly)
		checkCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		if err := api.OIDC.Check(checkCtx); err != nil {
			log.Warn("identity provider not reachable yet; sign-in will retry on use", "err", err)
		}
		cancel()
	}
	if before != nil {
		if err := before(ctx, demo.Deps{Auth: authSvc, Ledgers: ledgers, Sync: sync}); err != nil {
			return err
		}
	}
	if !cfg.NoWeb {
		web, err := webui.Handler(cfg.WebDir, append(webui.DefaultConnect, cfg.AIConnectOrigins...)...)
		if err != nil {
			return fmt.Errorf("web app: %w", err)
		}
		api.Web = web
	}
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       10 * time.Minute, // large imports on slow links
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    64 << 10,
	}

	go (&backup.Scheduler{DB: d, Dir: cfg.BackupDir, Interval: cfg.BackupEvery, Keep: cfg.BackupKeep, Log: log}).Run(ctx)
	go housekeeping(ctx, d, log)
	go api.Notice.Run(ctx)

	errCh := make(chan error, 1)
	go func() {
		log.Info("lekhamint listening", "addr", cfg.Addr, "version", version, "database", d.Dialect().String(),
			"signup", cfg.AllowSignup, "backups", backupDesc(cfg))
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}

// buildNotifier returns the configured channels; a channel with no settings is left out.
func buildNotifier(cfg *config.Config) *notify.Notifier {
	n := &notify.Notifier{}
	if cfg.SMTPHost != "" {
		n.SMTP = &notify.SMTPConfig{Host: cfg.SMTPHost, Port: cfg.SMTPPort, Username: cfg.SMTPUser, Password: cfg.SMTPPassword, From: cfg.SMTPFrom, Security: cfg.SMTPSecurity}
	}
	if cfg.GotifyURL != "" {
		n.Gotify = &notify.GotifyConfig{URL: cfg.GotifyURL}
	}
	return n
}

func backupDesc(cfg *config.Config) string {
	if cfg.BackupEvery <= 0 {
		return "disabled"
	}
	return fmt.Sprintf("every %s to %s (keep %d)", cfg.BackupEvery, cfg.BackupDir, cfg.BackupKeep)
}

// housekeeping removes expired sessions hourly.
func housekeeping(ctx context.Context, d *db.DB, log *slog.Logger) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if n, err := store.DeleteExpiredSessions(ctx, d, time.Now().UnixMilli()); err != nil {
				log.Warn("session cleanup failed", "err", err)
			} else if n > 0 {
				log.Info("expired sessions removed", "count", n)
			}
		}
	}
}

func cmdMigrate(args []string) error {
	_, d, _, err := setup("migrate", args, nil)
	if err != nil {
		return err
	}
	defer d.Close()
	v, err := d.SchemaVersion(context.Background())
	fmt.Printf("database is at schema v%d\n", v)
	return err
}

func cmdBackup(args []string) error {
	var format, out string
	cfg, d, _, err := setup("backup", args, func(fs *flag.FlagSet) {
		fs.StringVar(&format, "format", "", "db (SQLite snapshot) or json (portable dump); default db for SQLite, json for PostgreSQL")
		fs.StringVar(&out, "out", "", "output file (default: timestamped file in the backup dir)")
	})
	if err != nil {
		return err
	}
	defer d.Close()
	ctx := context.Background()
	if format == "" {
		format = "db"
		if d.Dialect() == db.Postgres {
			format = "json"
		}
	}
	stamp := time.Now().UTC().Format("20060102-150405")
	switch format {
	case "db":
		if out == "" {
			out = filepath.Join(cfg.BackupDir, "lekhamint-"+stamp+".db")
		}
		err = backup.SnapshotSQLite(ctx, d, out)
	case "json":
		if out == "" {
			out = filepath.Join(cfg.BackupDir, "lekhamint-"+stamp+".ndjson.gz")
		}
		err = backup.WriteDumpFile(ctx, d, out)
	default:
		return fmt.Errorf("--format must be db or json")
	}
	if err != nil {
		return err
	}
	fmt.Println("backup written:", out)
	return nil
}

func cmdRestore(args []string) error {
	fs := flag.NewFlagSet("restore", flag.ContinueOnError)
	cfg := config.Register(fs)
	format := fs.String("format", "", "db or json (default: guessed from the file name)")
	from := fs.String("from", "", "backup file to restore")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if err := cfg.Finish(); err != nil {
		return err
	}
	if *from == "" {
		return errors.New("--from is required")
	}
	if *format == "" {
		*format = "db"
		if strings.HasSuffix(*from, ".gz") || strings.HasSuffix(*from, ".ndjson") {
			*format = "json"
		}
	}
	ctx := context.Background()
	switch *format {
	case "db":
		dialect, _ := cfg.Dialect()
		if dialect != db.SQLite {
			return errors.New("a .db snapshot can only be restored into SQLite; use --format json for PostgreSQL")
		}
		kept, err := backup.RestoreSQLiteFile(ctx, *from, cfg.DSN())
		if err != nil {
			return err
		}
		if kept != "" {
			fmt.Println("previous database kept at:", kept)
		}
		fmt.Println("restored", *from, "->", cfg.DSN())
		return nil
	case "json":
		dialect, _ := cfg.Dialect()
		d, err := db.Open(ctx, dialect, cfg.DSN())
		if err != nil {
			return err
		}
		defer d.Close()
		if _, err := d.Migrate(ctx); err != nil {
			return err
		}
		f, err := os.Open(*from)
		if err != nil {
			return err
		}
		defer f.Close()
		st, err := backup.RestoreDump(ctx, d, f)
		if err != nil {
			return err
		}
		fmt.Printf("restored %d users, %d ledgers, %d members, %d records\n", st.Users, st.Ledgers, st.Members, st.Records)
		return nil
	}
	return errors.New("--format must be db or json")
}

func cmdUser(args []string) error {
	if len(args) == 0 || (args[0] != "add" && args[0] != "passwd") {
		return errors.New("usage: lekhamint user add --email E [--name N] | lekhamint user passwd --email E")
	}
	sub := args[0]
	var email, name string
	_, d, _, err := setup("user "+sub, args[1:], func(fs *flag.FlagSet) {
		fs.StringVar(&email, "email", "", "user email")
		if sub == "add" {
			fs.StringVar(&name, "name", "", "display name")
		}
	})
	if err != nil {
		return err
	}
	defer d.Close()
	if email == "" {
		return errors.New("--email is required")
	}
	password, err := readPassword()
	if err != nil {
		return err
	}
	ctx := context.Background()
	svc := auth.NewService(d, true, 0)
	if sub == "add" {
		u, err := svc.CreateUser(ctx, email, name, password)
		if err != nil {
			return err
		}
		fmt.Printf("user created: %s (admin: %v)\n", u.Email, u.IsAdmin)
		return nil
	}
	u, err := store.UserByEmail(ctx, d, email)
	if err != nil {
		return fmt.Errorf("find user: %w", err)
	}
	if err := svc.SetPassword(ctx, u.ID, "", password); err != nil {
		return err
	}
	fmt.Println("password changed; all sessions signed out:", u.Email)
	return nil
}

// readPassword takes the password from LEKHAMINT_PASSWORD, a hidden prompt on
// a terminal, or the first line of stdin, so it never appears in shell history,
// on screen or in the process list.
func readPassword() (string, error) {
	if pw := os.Getenv("LEKHAMINT_PASSWORD"); pw != "" {
		return pw, nil
	}
	if fd := int(os.Stdin.Fd()); term.IsTerminal(fd) {
		fmt.Fprint(os.Stderr, "password: ")
		b, err := term.ReadPassword(fd)
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", fmt.Errorf("read password: %w", err)
		}
		return string(b), nil
	}
	fmt.Fprint(os.Stderr, "password: ")
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return "", errors.New("no password given (set LEKHAMINT_PASSWORD or pipe it on stdin)")
	}
	return strings.TrimRight(line, "\r\n"), nil
}

// cmdHealthcheck probes /healthz on the local server. Distroless images have
// no curl, so Docker's HEALTHCHECK calls this instead.
func cmdHealthcheck(args []string) error {
	fs := flag.NewFlagSet("healthcheck", flag.ContinueOnError)
	cfg := config.Register(fs)
	if err := fs.Parse(args); err != nil {
		return err
	}
	host, port, ok := strings.Cut(cfg.Addr, ":")
	if !ok {
		port = cfg.Addr
	}
	if host == "" || host == "0.0.0.0" || host == "[::]" {
		host = "127.0.0.1"
	}
	c := &http.Client{Timeout: 3 * time.Second}
	resp, err := c.Get("http://" + host + ":" + port + "/healthz")
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unhealthy: HTTP %d", resp.StatusCode)
	}
	return nil
}
