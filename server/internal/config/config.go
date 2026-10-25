// Package config loads server settings from environment variables, with
// command-line flags taking precedence. Every setting has a safe default, so
// `lekhamint serve` works with no configuration at all.
package config

import (
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/mail"
	"net/url"
	"os"
	"path/filepath"
	"slices"
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
	// Assistant proxy (docs/11): an OpenAI-compatible endpoint the server forwards chat requests to.
	AIBaseURL        string
	AIAPIKey         string
	AIModel          string
	AITimeout        time.Duration
	AIConnectOrigins []string

	// PublicURL is the address people reach this server at (https://money.example.com). Single sign-on
	// redirects and links in messages are built from it.
	PublicURL string
	// Single sign-on through an OpenID Connect provider (Keycloak, Authentik, ...).
	OIDCIssuer              string
	OIDCClientID            string
	OIDCClientSecret        string
	OIDCName                string
	OIDCScopes              []string
	OIDCAutoCreate          bool
	OIDCOnly                bool // hide and refuse password sign-in
	OIDCAllowUnverifiedMail bool // accept providers that never send email_verified
	// Outgoing email and Gotify for notifications.
	SMTPHost       string
	SMTPPort       int
	SMTPUser       string
	SMTPPassword   string
	SMTPFrom       string
	SMTPSecurity   string
	SMTPSkipVerify bool
	GotifyURL      string
	// ReminderHour is the local hour (0-23) the daily reminders go out; -1 turns reminders off.
	ReminderHour int

	oidcScopesRaw *string
	aiOriginsRaw  *string

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
	fs.StringVar(&c.AIBaseURL, "ai-base-url", env("LEKHAMINT_AI_BASE_URL", ""), "OpenAI-compatible API for the assistant, e.g. http://localhost:11434/v1 (empty disables the proxy)")
	fs.StringVar(&c.AIAPIKey, "ai-api-key", env("LEKHAMINT_AI_API_KEY", ""), "API key sent to the assistant endpoint (prefer the environment variable)")
	fs.StringVar(&c.AIModel, "ai-model", env("LEKHAMINT_AI_MODEL", ""), "default model for the assistant")
	fs.DurationVar(&c.AITimeout, "ai-timeout", envDuration("LEKHAMINT_AI_TIMEOUT", 2*time.Minute), "time limit for one assistant request")
	c.aiOriginsRaw = fs.String("ai-connect-origins", env("LEKHAMINT_AI_CONNECT_ORIGINS", ""), "extra origins the web app may call directly for the assistant (comma-separated)")
	fs.StringVar(&c.PublicURL, "public-url", env("LEKHAMINT_PUBLIC_URL", ""), "address people use to reach this server, e.g. https://money.example.com (needed for single sign-on)")
	fs.StringVar(&c.OIDCIssuer, "oidc-issuer", env("LEKHAMINT_OIDC_ISSUER", ""), "OpenID Connect issuer URL, e.g. https://auth.example.com/realms/home (empty disables single sign-on)")
	fs.StringVar(&c.OIDCClientID, "oidc-client-id", env("LEKHAMINT_OIDC_CLIENT_ID", ""), "OpenID Connect client ID")
	fs.StringVar(&c.OIDCClientSecret, "oidc-client-secret", env("LEKHAMINT_OIDC_CLIENT_SECRET", ""), "OpenID Connect client secret (prefer the environment variable; empty for a public client)")
	fs.StringVar(&c.OIDCName, "oidc-name", env("LEKHAMINT_OIDC_NAME", "Single sign-on"), "name shown on the sign-in button, e.g. Keycloak")
	c.oidcScopesRaw = fs.String("oidc-scopes", env("LEKHAMINT_OIDC_SCOPES", "openid email profile"), "scopes to request")
	fs.BoolVar(&c.OIDCAutoCreate, "oidc-auto-create", envBool("LEKHAMINT_OIDC_AUTO_CREATE", true), "create an account the first time someone signs in through the provider")
	fs.BoolVar(&c.OIDCOnly, "oidc-only", envBool("LEKHAMINT_OIDC_ONLY", false), "turn off password sign-in and registration; use the provider only")
	fs.BoolVar(&c.OIDCAllowUnverifiedMail, "oidc-allow-unverified-email", envBool("LEKHAMINT_OIDC_ALLOW_UNVERIFIED_EMAIL", false), "accept sign-ins from providers that never send the email_verified claim")
	fs.StringVar(&c.SMTPHost, "smtp-host", env("LEKHAMINT_SMTP_HOST", ""), "mail server for email notifications (empty disables email)")
	fs.IntVar(&c.SMTPPort, "smtp-port", envInt("LEKHAMINT_SMTP_PORT", 0), "mail server port (default 587, or 465 with --smtp-security tls)")
	fs.StringVar(&c.SMTPUser, "smtp-user", env("LEKHAMINT_SMTP_USER", ""), "mail server user name")
	fs.StringVar(&c.SMTPPassword, "smtp-password", env("LEKHAMINT_SMTP_PASSWORD", ""), "mail server password (prefer the environment variable)")
	fs.StringVar(&c.SMTPFrom, "smtp-from", env("LEKHAMINT_SMTP_FROM", ""), "sender, e.g. \"LekhaMint <no-reply@example.com>\"")
	fs.StringVar(&c.SMTPSecurity, "smtp-security", env("LEKHAMINT_SMTP_SECURITY", "starttls"), "starttls, tls (implicit TLS) or none")
	fs.BoolVar(&c.SMTPSkipVerify, "smtp-skip-verify", envBool("LEKHAMINT_SMTP_SKIP_VERIFY", false), "accept a self-signed or mismatched mail server certificate (internal relays)")
	fs.StringVar(&c.GotifyURL, "gotify-url", env("LEKHAMINT_GOTIFY_URL", ""), "Gotify server, e.g. https://gotify.example.com (empty disables Gotify)")
	fs.IntVar(&c.ReminderHour, "reminder-hour", envInt("LEKHAMINT_REMINDER_HOUR", 8), "hour of day (server time, 0-23) for bill and budget reminders; -1 turns them off")
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
	if c.aiOriginsRaw != nil {
		for _, o := range strings.Split(*c.aiOriginsRaw, ",") {
			if o = strings.TrimSpace(o); o != "" {
				if strings.ContainsAny(o, " ;'\"") || !(strings.HasPrefix(o, "http://") || strings.HasPrefix(o, "https://")) {
					bad("LEKHAMINT_AI_CONNECT_ORIGINS entry %q must be an origin like https://ai.example.com or http://192.168.1.10:11434", o)
					continue
				}
				c.AIConnectOrigins = append(c.AIConnectOrigins, strings.TrimRight(o, "/"))
			}
		}
	}
	if c.AIBaseURL != "" {
		if u, err := url.Parse(c.AIBaseURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			bad("LEKHAMINT_AI_BASE_URL (--ai-base-url) must be an http(s) URL such as http://localhost:11434/v1")
		}
		c.AIBaseURL = strings.TrimRight(c.AIBaseURL, "/")
	}
	c.PublicURL = strings.TrimRight(c.PublicURL, "/")
	if c.PublicURL != "" {
		if u, err := url.Parse(c.PublicURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			bad("LEKHAMINT_PUBLIC_URL (--public-url) must be an http(s) address such as https://money.example.com")
		}
	}
	if c.oidcScopesRaw != nil {
		c.OIDCScopes = strings.Fields(strings.ReplaceAll(*c.oidcScopesRaw, ",", " "))
	}
	if c.OIDCIssuer != "" {
		if u, err := url.Parse(c.OIDCIssuer); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			bad("LEKHAMINT_OIDC_ISSUER (--oidc-issuer) must be an http(s) URL such as https://auth.example.com/realms/home")
		}
		if c.OIDCClientID == "" {
			bad("LEKHAMINT_OIDC_CLIENT_ID (--oidc-client-id) is required with an OIDC issuer")
		}
		if c.PublicURL == "" {
			bad("LEKHAMINT_PUBLIC_URL (--public-url) is required for single sign-on: the provider redirects people back to it")
		}
		if !slices.Contains(c.OIDCScopes, "openid") {
			c.OIDCScopes = append([]string{"openid"}, c.OIDCScopes...)
		}
	} else if c.OIDCOnly {
		bad("LEKHAMINT_OIDC_ONLY needs LEKHAMINT_OIDC_ISSUER; otherwise nobody could sign in")
	}
	c.SMTPSecurity = strings.ToLower(c.SMTPSecurity)
	if c.SMTPHost != "" {
		switch c.SMTPSecurity {
		case "starttls", "tls", "none":
		default:
			bad("LEKHAMINT_SMTP_SECURITY (--smtp-security) must be starttls, tls or none, not %q", c.SMTPSecurity)
		}
		if c.SMTPPort == 0 {
			c.SMTPPort = map[string]int{"tls": 465, "none": 25}[c.SMTPSecurity]
			if c.SMTPPort == 0 {
				c.SMTPPort = 587
			}
		}
		if c.SMTPPort < 1 || c.SMTPPort > 65535 {
			bad("LEKHAMINT_SMTP_PORT (--smtp-port) must be between 1 and 65535")
		}
		if _, err := mail.ParseAddress(c.SMTPFrom); err != nil {
			bad("LEKHAMINT_SMTP_FROM (--smtp-from) must be an email address such as no-reply@example.com when email is on")
		}
	}
	if c.GotifyURL != "" {
		c.GotifyURL = strings.TrimRight(c.GotifyURL, "/")
		if u, err := url.Parse(c.GotifyURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			bad("LEKHAMINT_GOTIFY_URL (--gotify-url) must be an http(s) URL such as https://gotify.example.com")
		}
	}
	if c.ReminderHour < -1 || c.ReminderHour > 23 {
		bad("LEKHAMINT_REMINDER_HOUR (--reminder-hour) must be 0 to 23, or -1 to turn reminders off")
	}
	if c.AITimeout < time.Second {
		bad("LEKHAMINT_AI_TIMEOUT (--ai-timeout) must be at least 1s")
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
