package db

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"
)

//go:embed migrations
var migrationFS embed.FS

type migration struct {
	version int
	name    string
	sql     string
}

func loadMigrations(d Dialect) ([]migration, error) {
	dir := path.Join("migrations", d.String())
	entries, err := fs.ReadDir(migrationFS, dir)
	if err != nil {
		return nil, err
	}
	var out []migration
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".sql") {
			continue
		}
		prefix, _, ok := strings.Cut(e.Name(), "_")
		if !ok {
			return nil, fmt.Errorf("migration %s: name must be NNNN_description.sql", e.Name())
		}
		v, err := strconv.Atoi(prefix)
		if err != nil {
			return nil, fmt.Errorf("migration %s: bad version: %w", e.Name(), err)
		}
		body, err := fs.ReadFile(migrationFS, path.Join(dir, e.Name()))
		if err != nil {
			return nil, err
		}
		out = append(out, migration{version: v, name: e.Name(), sql: string(body)})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].version < out[j].version })
	for i := range out {
		if out[i].version != i+1 {
			return nil, fmt.Errorf("migrations must be numbered 1..n without gaps; found %s", out[i].name)
		}
	}
	return out, nil
}

// LatestSchemaVersion returns the newest migration version built into the binary.
func LatestSchemaVersion() int {
	m, err := loadMigrations(SQLite)
	if err != nil {
		return 0
	}
	return len(m)
}

// SchemaVersion returns the highest applied migration (0 for a fresh database).
func (d *DB) SchemaVersion(ctx context.Context) (int, error) {
	var exists int
	q := `SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_migrations'`
	if d.dialect == Postgres {
		q = `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'schema_migrations'`
	}
	if err := d.QueryRowContext(ctx, q).Scan(&exists); err != nil {
		return 0, err
	}
	if exists == 0 {
		return 0, nil
	}
	var v int
	err := d.QueryRowContext(ctx, `SELECT COALESCE(MAX(version), 0) FROM schema_migrations`).Scan(&v)
	return v, err
}

// Migrate applies all pending migrations, each in its own transaction.
// It refuses to run against a database newer than this binary.
func (d *DB) Migrate(ctx context.Context) (applied int, err error) {
	migs, err := loadMigrations(d.dialect)
	if err != nil {
		return 0, err
	}
	if _, err := d.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY,
		name TEXT NOT NULL,
		applied_at BIGINT NOT NULL)`); err != nil {
		return 0, fmt.Errorf("create schema_migrations: %w", err)
	}
	current, err := d.SchemaVersion(ctx)
	if err != nil {
		return 0, err
	}
	if current > len(migs) {
		return 0, fmt.Errorf("database schema version %d is newer than this binary supports (%d); upgrade kosh", current, len(migs))
	}
	for _, m := range migs[current:] {
		err := d.InTx(ctx, func(tx *Tx) error {
			if _, err := tx.ExecContext(ctx, m.sql); err != nil {
				return err
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)`,
				m.version, m.name, time.Now().UnixMilli())
			return err
		})
		if err != nil {
			return applied, fmt.Errorf("migration %s: %w", m.name, err)
		}
		applied++
	}
	return applied, nil
}
