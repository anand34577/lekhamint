// Command kosh is the LekhaMint sync and backup server.
//
//	kosh serve                         run the HTTP server
//	kosh migrate                       apply database migrations and exit
//	kosh backup  [--format db|json] --out PATH
//	kosh restore [--format db|json] --from PATH
//	kosh user add --email E [--name N]          (password from KOSH_PASSWORD or stdin)
//	kosh user passwd --email E
//	kosh version
//
// Every flag can also be set with an environment variable; see docs/08-deployment.md.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/kosh-app/kosh/server/internal/auth"
	"github.com/kosh-app/kosh/server/internal/backup"
	"github.com/kosh-app/kosh/server/internal/config"
	"github.com/kosh-app/kosh/server/internal/db"
	"github.com/kosh-app/kosh/server/internal/httpapi"
	"github.com/kosh-app/kosh/server/internal/ledger"
	"github.com/kosh-app/kosh/server/internal/report"
	"github.com/kosh-app/kosh/server/internal/store"
	"github.com/kosh-app/kosh/server/internal/syncer"
	"github.com/kosh-app/kosh/server/internal/webui"
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
		fmt.Printf("kosh %s (schema v%d)\n", version, db.LatestSchemaVersion())
		return nil
	case "help", "--help", "-h":
		fmt.Println(usage)
		return nil
	}
	return fmt.Errorf("unknown command %q\n\n%s", cmd, usage)
}

const usage = `Usage: kosh <command> [flags]

Commands:
  serve     run the server (default)
  migrate   apply database migrations
  backup    write a backup (--format db for a SQLite snapshot, json for a portable dump)
  restore   restore a backup (stop the server first)
  user      add | passwd  manage users
  healthcheck  exit 0 if the local server is healthy (for Docker)
  version   print the version

Run "kosh <command> -h" for the flags of a command.`

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
	log := newLogger(cfg)
	dialect, _ := cfg.Dialect()
	d, err := db.Open(context.Background(), dialect, cfg.DSN())
	if err != nil {
		return nil, nil, nil, err
	}
	n, err := d.Migrate(context.Background())
	if err != nil {
		d.Close()
		return nil, nil, nil, err
	}
	if n > 0 {
		log.Info("database migrated", "applied", n, "schema_version", db.LatestSchemaVersion())
	}
	return cfg, d, log, nil
}

func newLogger(cfg *config.Config) *slog.Logger {
	var level slog.Level
	if err := level.UnmarshalText([]byte(cfg.LogLevel)); err != nil {
		level = slog.LevelInfo
	}
	opts := &slog.HandlerOptions{Level: level}
	if cfg.LogFormat == "json" {
		return slog.New(slog.NewJSONHandler(os.Stderr, opts))
	}
	return slog.New(slog.NewTextHandler(os.Stderr, opts))
}

func cmdServe(args []string) error {
	cfg, d, log, err := setup("serve", args, nil)
	if err != nil {
		return err
	}
	defer d.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	sync := syncer.New(d)
	authSvc := auth.NewService(d, cfg.AllowSignup, cfg.SessionTTL)
	api := &httpapi.Server{
		DB: d, Auth: authSvc, Sync: sync,
		Ledgers: &ledger.Service{DB: d, Sync: sync}, Reports: &report.Service{DB: d},
		Log: log, Version: version, CORSOrigins: cfg.CORSOrigins, TrustProxy: cfg.TrustProxy,
		MaxImportSize: cfg.MaxImportSize,
	}
	if !cfg.NoWeb {
		web, err := webui.Handler(cfg.WebDir)
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

	errCh := make(chan error, 1)
	go func() {
		log.Info("kosh listening", "addr", cfg.Addr, "version", version, "database", d.Dialect().String(),
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
			out = filepath.Join(cfg.BackupDir, "kosh-"+stamp+".db")
		}
		err = backup.SnapshotSQLite(ctx, d, out)
	case "json":
		if out == "" {
			out = filepath.Join(cfg.BackupDir, "kosh-"+stamp+".ndjson.gz")
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
		return errors.New("usage: kosh user add --email E [--name N] | kosh user passwd --email E")
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

// readPassword takes the password from KOSH_PASSWORD or the first line of
// stdin, so it never appears in shell history or the process list.
func readPassword() (string, error) {
	if pw := os.Getenv("KOSH_PASSWORD"); pw != "" {
		return pw, nil
	}
	fmt.Fprint(os.Stderr, "password: ")
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return "", errors.New("no password given (set KOSH_PASSWORD or pipe it on stdin)")
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
