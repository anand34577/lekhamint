// Package config loads server settings from environment variables, with
// command-line flags taking precedence. Every setting has a safe default, so
// `lekhamint serve` works with no configuration at all.
package config

import (
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// Config is the complete server configuration.
type Config struct {
	Addr          string
	DataDir       string
	DBDriver      string
	DBDSN         string
	AllowSignup   bool
	CORSOrigins   []string
	SessionTTL    time.Duration
	TrustProxy    bool
	LogLevel      string
	LogFormat     string
	LogFile       string
	BackupDir     string
	BackupEvery   time.Duration
	BackupKeep    int
	MaxImportSize int64
	WebDir        string
	NoWeb         bool

	corsRaw *string
	envErrs []string
}

// Dialect returns the parsed database dialect.
func (c *Config) Dialect() (db.Dialect, error) { return db.ParseDialect(c.DBDriver) }

// DSN returns the DSN, defaulting to <data dir>/lekhamint.db for SQLite.
func (c *Config) DSN() string {
	if c.DBDSN != "" {
		return c.DBDSN
	}
	return filepath.Join(c.DataDir, "lekhamint.db")
}

// Register defines flags on fs, with defaults taken from the environment.
func Register(fs *flag.FlagSet) *Config {
	c := &Config{}
	env, envBool, envInt, envDuration := c.env, c.envBool, c.envInt, c.envDuration
	fs.StringVar(&c.Addr, "addr", env("LEKHAMINT_ADDR", ":8080"), "listen address")
	fs.StringVar(&c.DataDir, "data-dir", env("LEKHAMINT_DATA_DIR", "./data"), "directory for the SQLite database and backups")
	fs.StringVar(&c.DBDriver, "db-driver", env("LEKHAMINT_DB_DRIVER", "sqlite"), "database driver: sqlite or postgres")
	fs.StringVar(&c.DBDSN, "db-dsn", env("LEKHAMINT_DB_DSN", ""), "SQLite file path or PostgreSQL URL (default <data-dir>/lekhamint.db)")
	fs.BoolVar(&c.AllowSignup, "allow-signup", envBool("LEKHAMINT_ALLOW_SIGNUP", false), "allow anyone to register (the first user can always register)")
	origins := fs.String("cors-origins", env("LEKHAMINT_CORS_ORIGINS", ""), "comma-separated origins allowed to call the API from a browser")
	fs.DurationVar(&c.SessionTTL, "session-ttl", envDuration("LEKHAMINT_SESSION_TTL", 90*24*time.Hour), "idle time before a session expires")
	fs.BoolVar(&c.TrustProxy, "trust-proxy", envBool("LEKHAMINT_TRUST_PROXY", false), "use X-Forwarded-For for client IPs (only behind a reverse proxy)")
	fs.StringVar(&c.LogLevel, "log-level", env("LEKHAMINT_LOG_LEVEL", "info"), "debug, info, warn or error")
	fs.StringVar(&c.LogFormat, "log-format", env("LEKHAMINT_LOG_FORMAT", "text"), "text or json")
	fs.StringVar(&c.LogFile, "log-file", env("LEKHAMINT_LOG_FILE", ""), "also write logs to this file (rotated at 10 MB, 3 kept)")
	fs.StringVar(&c.BackupDir, "backup-dir", env("LEKHAMINT_BACKUP_DIR", ""), "directory for scheduled backups (default <data-dir>/backups)")
	fs.DurationVar(&c.BackupEvery, "backup-interval", envDuration("LEKHAMINT_BACKUP_INTERVAL", 24*time.Hour), "time between scheduled backups (0 disables)")
	fs.IntVar(&c.BackupKeep, "backup-keep", envInt("LEKHAMINT_BACKUP_KEEP", 14), "number of scheduled backups to keep")
	fs.Int64Var(&c.MaxImportSize, "max-import-mb", int64(envInt("LEKHAMINT_MAX_IMPORT_MB", 256)), "largest ledger import accepted, in MB")
	fs.StringVar(&c.WebDir, "web-dir", env("LEKHAMINT_WEB_DIR", ""), "serve the web app from this folder instead of the built-in copy")
	fs.BoolVar(&c.NoWeb, "no-web", envBool("LEKHAMINT_NO_WEB", false), "do not serve the web app (API only)")
	c.corsRaw = origins
	return c
}

// Finish validates the config after flags are parsed. Every problem is
// reported at once, so a bad setting stops the server with a clear message
// instead of being silently replaced by a default.
func (c *Config) Finish() error {
	problems := append([]string(nil), c.envErrs...)
	bad := func(format string, a ...any) { problems = append(problems, fmt.Sprintf(format, a...)) }
	if c.corsRaw != nil {
		for _, o := range strings.Split(*c.corsRaw, ",") {
			if o = strings.TrimSpace(o); o != "" {
				c.CORSOrigins = append(c.CORSOrigins, strings.TrimRight(o, "/"))
			}
		}
	}
	if c.BackupDir == "" {
		c.BackupDir = filepath.Join(c.DataDir, "backups")
	}
	if _, err := c.Dialect(); err != nil {
		bad("%v", err)
	}
	if d, _ := c.Dialect(); d == db.Postgres && c.DBDSN == "" {
		bad("LEKHAMINT_DB_DSN (--db-dsn) is required for PostgreSQL, e.g. postgres://user:pass@host:5432/lekhamint")
	}
	if _, port, err := net.SplitHostPort(c.Addr); err != nil {
		bad("LEKHAMINT_ADDR (--addr) %q must look like :8080 or 127.0.0.1:8080", c.Addr)
	} else if n, err := strconv.Atoi(port); err != nil || n < 0 || n > 65535 {
		bad("LEKHAMINT_ADDR (--addr) has an invalid port %q", port)
	}
	var level slog.Level
	if err := level.UnmarshalText([]byte(c.LogLevel)); err != nil {
		bad("LEKHAMINT_LOG_LEVEL (--log-level) must be debug, info, warn or error, not %q", c.LogLevel)
	}
	if c.LogFormat != "text" && c.LogFormat != "json" {
		bad("LEKHAMINT_LOG_FORMAT (--log-format) must be text or json, not %q", c.LogFormat)
	}
	if c.BackupKeep < 0 {
		bad("LEKHAMINT_BACKUP_KEEP (--backup-keep) must be 0 or more")
	}
	if c.BackupEvery < 0 {
		bad("LEKHAMINT_BACKUP_INTERVAL (--backup-interval) must be 0 (off) or a duration like 24h")
	}
	if c.SessionTTL < time.Hour {
		bad("LEKHAMINT_SESSION_TTL (--session-ttl) must be at least 1h")
	}
	if c.MaxImportSize < 1 {
		bad("LEKHAMINT_MAX_IMPORT_MB (--max-import-mb) must be at least 1")
	}
	if len(problems) > 0 {
		return fmt.Errorf("invalid configuration:\n  - %s", strings.Join(problems, "\n  - "))
	}
	c.MaxImportSize <<= 20
	return nil
}

// The env helpers read LEKHAMINT_* variables. A value that is set but cannot
// be parsed is recorded and reported by Finish rather than ignored.

func (c *Config) env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v)
	}
	return def
}

func (c *Config) envBool(key string, def bool) bool {
	v := c.env(key, "")
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		c.envErrs = append(c.envErrs, fmt.Sprintf("%s must be true or false, not %q", key, v))
		return def
	}
	return b
}

func (c *Config) envInt(key string, def int) int {
	v := c.env(key, "")
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		c.envErrs = append(c.envErrs, fmt.Sprintf("%s must be a whole number, not %q", key, v))
		return def
	}
	return n
}

func (c *Config) envDuration(key string, def time.Duration) time.Duration {
	v := c.env(key, "")
	if v == "" {
		return def
	}
	if v == "0" {
		return 0
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		c.envErrs = append(c.envErrs, fmt.Sprintf("%s must be a duration like 30m, 24h or 2160h, not %q", key, v))
		return def
	}
	return d
}
