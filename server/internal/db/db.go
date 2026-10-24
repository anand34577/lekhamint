// Package db opens the SQLite or PostgreSQL database, hides placeholder
// differences between them, and runs the embedded schema migrations.
package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Dialect identifies the SQL flavour.
type Dialect int

const (
	SQLite Dialect = iota
	Postgres
)

func (d Dialect) String() string {
	if d == Postgres {
		return "postgres"
	}
	return "sqlite"
}

// ParseDialect maps a config value to a Dialect.
func ParseDialect(s string) (Dialect, error) {
	switch strings.ToLower(s) {
	case "", "sqlite", "sqlite3":
		return SQLite, nil
	case "postgres", "postgresql", "pg":
		return Postgres, nil
	}
	return 0, fmt.Errorf("unknown database driver %q (use sqlite or postgres)", s)
}

// Querier is satisfied by both *DB and *Tx. Queries use '?' placeholders; they
// are rewritten for PostgreSQL automatically.
type Querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
	Dialect() Dialect
}

// DB wraps *sql.DB with dialect awareness.
type DB struct {
	raw     *sql.DB
	dialect Dialect
	path    string // SQLite file path (empty for PostgreSQL)
}

// Open connects to the database. For SQLite, dsn is a file path; parent
// directories are created and recommended pragmas are applied.
func Open(ctx context.Context, dialect Dialect, dsn string) (*DB, error) {
	var (
		raw  *sql.DB
		err  error
		path string
	)
	switch dialect {
	case SQLite:
		path = dsn
		if dir := filepath.Dir(path); dir != "" && dir != "." {
			if err := os.MkdirAll(dir, 0o750); err != nil {
				return nil, fmt.Errorf("create database directory: %w", err)
			}
		}
		raw, err = openSQLite(path)
	case Postgres:
		raw, err = openPostgres(dsn)
		if err == nil {
			raw.SetMaxOpenConns(25)
			raw.SetMaxIdleConns(10)
			raw.SetConnMaxIdleTime(5 * time.Minute)
		}
	}
	if err != nil {
		return nil, err
	}
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := raw.PingContext(pingCtx); err != nil {
		raw.Close()
		return nil, fmt.Errorf("connect to %s: %w", dialect, err)
	}
	return &DB{raw: raw, dialect: dialect, path: path}, nil
}

// Close closes the pool.
func (d *DB) Close() error { return d.raw.Close() }

// Dialect returns the SQL dialect.
func (d *DB) Dialect() Dialect { return d.dialect }

// Path returns the SQLite file path, or "" for PostgreSQL.
func (d *DB) Path() string { return d.path }

// Ping checks connectivity.
func (d *DB) Ping(ctx context.Context) error { return d.raw.PingContext(ctx) }

func (d *DB) ExecContext(ctx context.Context, q string, args ...any) (sql.Result, error) {
	return d.raw.ExecContext(ctx, rebind(d.dialect, q), args...)
}

func (d *DB) QueryContext(ctx context.Context, q string, args ...any) (*sql.Rows, error) {
	return d.raw.QueryContext(ctx, rebind(d.dialect, q), args...)
}

func (d *DB) QueryRowContext(ctx context.Context, q string, args ...any) *sql.Row {
	return d.raw.QueryRowContext(ctx, rebind(d.dialect, q), args...)
}

// Tx is a dialect-aware transaction.
type Tx struct {
	raw     *sql.Tx
	dialect Dialect
}

func (t *Tx) Dialect() Dialect { return t.dialect }

func (t *Tx) ExecContext(ctx context.Context, q string, args ...any) (sql.Result, error) {
	return t.raw.ExecContext(ctx, rebind(t.dialect, q), args...)
}

func (t *Tx) QueryContext(ctx context.Context, q string, args ...any) (*sql.Rows, error) {
	return t.raw.QueryContext(ctx, rebind(t.dialect, q), args...)
}

func (t *Tx) QueryRowContext(ctx context.Context, q string, args ...any) *sql.Row {
	return t.raw.QueryRowContext(ctx, rebind(t.dialect, q), args...)
}

// InTx runs fn inside a transaction. It commits if fn returns nil and rolls
// back otherwise (including on panic).
func (d *DB) InTx(ctx context.Context, fn func(tx *Tx) error) (err error) {
	raw, err := d.raw.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	tx := &Tx{raw: raw, dialect: d.dialect}
	defer func() {
		if p := recover(); p != nil {
			_ = raw.Rollback()
			panic(p)
		}
		if err != nil {
			_ = raw.Rollback()
		}
	}()
	if err = fn(tx); err != nil {
		return err
	}
	if err = raw.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}
	return nil
}

// IsUniqueViolation reports whether err is a unique-constraint failure.
func IsUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "UNIQUE constraint failed") || // SQLite
		strings.Contains(msg, "SQLSTATE 23505") // PostgreSQL
}

// ErrNoRows aliases sql.ErrNoRows so callers need not import database/sql.
var ErrNoRows = sql.ErrNoRows

// IsNoRows reports whether err is sql.ErrNoRows.
func IsNoRows(err error) bool { return errors.Is(err, sql.ErrNoRows) }

// rebind rewrites '?' placeholders to '$1, $2, …' for PostgreSQL. Queries in
// this codebase never contain '?' inside string literals.
func rebind(d Dialect, q string) string {
	if d != Postgres || !strings.Contains(q, "?") {
		return q
	}
	var b strings.Builder
	b.Grow(len(q) + 16)
	n := 0
	for i := 0; i < len(q); i++ {
		if q[i] == '?' {
			n++
			b.WriteByte('$')
			b.WriteString(strconv.Itoa(n))
			continue
		}
		b.WriteByte(q[i])
	}
	return b.String()
}
