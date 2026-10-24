// Package config loads server settings from environment variables, with
// command-line flags taking precedence. Every setting has a safe default, so
// `kosh serve` works with no configuration at all.
package config

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/kosh-app/kosh/server/internal/db"
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
	BackupDir     string
	BackupEvery   time.Duration
	BackupKeep    int
	MaxImportSize int64
	WebDir        string
	NoWeb         bool

	corsRaw *string
}

// Dialect returns the parsed database dialect.
func (c *Config) Dialect() (db.Dialect, error) { return db.ParseDialect(c.DBDriver) }

// DSN returns the DSN, defaulting to <data dir>/kosh.db for SQLite.
func (c *Config) DSN() string {
	if c.DBDSN != "" {
		return c.DBDSN
	}
	return filepath.Join(c.DataDir, "kosh.db")
}

// Register defines flags on fs, with defaults taken from the environment.
func Register(fs *flag.FlagSet) *Config {
	c := &Config{}
	fs.StringVar(&c.Addr, "addr", env("KOSH_ADDR", ":8080"), "listen address")
	fs.StringVar(&c.DataDir, "data-dir", env("KOSH_DATA_DIR", "./data"), "directory for the SQLite database and backups")
	fs.StringVar(&c.DBDriver, "db-driver", env("KOSH_DB_DRIVER", "sqlite"), "database driver: sqlite or postgres")
	fs.StringVar(&c.DBDSN, "db-dsn", env("KOSH_DB_DSN", ""), "SQLite file path or PostgreSQL URL (default <data-dir>/kosh.db)")
	fs.BoolVar(&c.AllowSignup, "allow-signup", envBool("KOSH_ALLOW_SIGNUP", false), "allow anyone to register (the first user can always register)")
	origins := fs.String("cors-origins", env("KOSH_CORS_ORIGINS", ""), "comma-separated origins allowed to call the API from a browser")
	fs.DurationVar(&c.SessionTTL, "session-ttl", envDuration("KOSH_SESSION_TTL", 90*24*time.Hour), "idle time before a session expires")
	fs.BoolVar(&c.TrustProxy, "trust-proxy", envBool("KOSH_TRUST_PROXY", false), "use X-Forwarded-For for client IPs (only behind a reverse proxy)")
	fs.StringVar(&c.LogLevel, "log-level", env("KOSH_LOG_LEVEL", "info"), "debug, info, warn or error")
	fs.StringVar(&c.LogFormat, "log-format", env("KOSH_LOG_FORMAT", "text"), "text or json")
	fs.StringVar(&c.BackupDir, "backup-dir", env("KOSH_BACKUP_DIR", ""), "directory for scheduled backups (default <data-dir>/backups)")
	fs.DurationVar(&c.BackupEvery, "backup-interval", envDuration("KOSH_BACKUP_INTERVAL", 24*time.Hour), "time between scheduled backups (0 disables)")
	fs.IntVar(&c.BackupKeep, "backup-keep", envInt("KOSH_BACKUP_KEEP", 14), "number of scheduled backups to keep")
	fs.Int64Var(&c.MaxImportSize, "max-import-mb", int64(envInt("KOSH_MAX_IMPORT_MB", 256)), "largest ledger import accepted, in MB")
	fs.StringVar(&c.WebDir, "web-dir", env("KOSH_WEB_DIR", ""), "serve the web app from this folder instead of the built-in copy")
	fs.BoolVar(&c.NoWeb, "no-web", envBool("KOSH_NO_WEB", false), "do not serve the web app (API only)")
	c.corsRaw = origins
	return c
}

// Finish validates the config after flags are parsed.
func (c *Config) Finish() error {
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
		return err
	}
	if c.BackupKeep < 0 {
		return fmt.Errorf("backup-keep must be >= 0")
	}
	c.MaxImportSize <<= 20
	return nil
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	if b, err := strconv.ParseBool(env(key, "")); err == nil {
		return b
	}
	return def
}

func envInt(key string, def int) int {
	if n, err := strconv.Atoi(env(key, "")); err == nil {
		return n
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if d, err := time.ParseDuration(env(key, "")); err == nil {
		return d
	}
	return def
}
