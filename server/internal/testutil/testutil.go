// Package testutil provides migrated throwaway databases for tests.
//
// Tests run on SQLite by default. Set LEKHAMINT_TEST_PG_DSN to a PostgreSQL URL to
// also run them on PostgreSQL; each test gets its own schema, dropped after.
package testutil

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/hlc"
)

// NewDB returns a migrated database that is cleaned up when the test ends.
func NewDB(t testing.TB) *db.DB {
	t.Helper()
	ctx := context.Background()
	if dsn := os.Getenv("LEKHAMINT_TEST_PG_DSN"); dsn != "" {
		return newPG(t, dsn)
	}
	d, err := db.Open(ctx, db.SQLite, filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	if _, err := d.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	return d
}

func newPG(t testing.TB, dsn string) *db.DB {
	ctx := context.Background()
	admin, err := db.Open(ctx, db.Postgres, dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := "kosh_test_" + strings.ReplaceAll(hlc.RandomNode(), "-", "")
	if _, err := admin.ExecContext(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	d, err := db.Open(ctx, db.Postgres, u.String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		d.Close()
		_, _ = admin.ExecContext(ctx, fmt.Sprintf("DROP SCHEMA %s CASCADE", schema))
		admin.Close()
	})
	if _, err := d.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	return d
}
