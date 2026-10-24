//go:build !js

package db

import (
	"database/sql"
	"path/filepath"

	_ "github.com/jackc/pgx/v5/stdlib" // registers "pgx"
	_ "modernc.org/sqlite"             // registers "sqlite" (pure Go, no CGO)
)

func openSQLite(path string) (*sql.DB, error) {
	// WAL: readers never block the writer. busy_timeout: wait instead of
	// failing under contention. _txlock=immediate: take the write lock at
	// BEGIN, which avoids SQLITE_BUSY when a read transaction upgrades.
	q := "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(10000)&_pragma=foreign_keys(ON)" +
		"&_pragma=synchronous(NORMAL)&_txlock=immediate"
	raw, err := sql.Open("sqlite", "file:"+filepath.ToSlash(path)+q)
	if err == nil {
		// One writer at a time is SQLite's model; a small pool serves reads.
		raw.SetMaxOpenConns(8)
	}
	return raw, err
}

func openPostgres(dsn string) (*sql.DB, error) { return sql.Open("pgx", dsn) }
