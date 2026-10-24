//go:build js

package db

import (
	"database/sql"
	"errors"

	_ "github.com/ncruces/go-sqlite3/driver"    // registers "sqlite3" (SQLite compiled to Wasm)
	_ "github.com/ncruces/go-sqlite3/vfs/memdb" // in-memory databases
)

// In the browser (the web demo) the database lives in memory; the path only
// names it.
func openSQLite(path string) (*sql.DB, error) {
	raw, err := sql.Open("sqlite3", "file:/"+path+"?vfs=memdb&_pragma=foreign_keys(1)&_pragma=busy_timeout(10000)&_txlock=immediate")
	if err == nil {
		raw.SetMaxOpenConns(4)
	}
	return raw, err
}

func openPostgres(string) (*sql.DB, error) {
	return nil, errors.New("PostgreSQL is not available in the browser build")
}
