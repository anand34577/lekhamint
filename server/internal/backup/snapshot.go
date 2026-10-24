package backup

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/kosh-app/kosh/server/internal/db"
)

// SnapshotSQLite writes a consistent, compacted copy of a live SQLite
// database to outPath using VACUUM INTO. Safe while the server runs.
func SnapshotSQLite(ctx context.Context, d *db.DB, outPath string) error {
	if d.Dialect() != db.SQLite {
		return errors.New("file snapshots are only for SQLite; use pg_dump or --format json for PostgreSQL")
	}
	if _, err := os.Stat(outPath); err == nil {
		return fmt.Errorf("%s already exists", outPath)
	}
	if err := os.MkdirAll(filepath.Dir(outPath), 0o750); err != nil {
		return err
	}
	_, err := d.ExecContext(ctx, "VACUUM INTO ?", outPath)
	return err
}

// VerifySQLiteFile opens a SQLite file read-only and checks its integrity
// and schema version. It returns the schema version.
func VerifySQLiteFile(ctx context.Context, path string) (int, error) {
	if _, err := os.Stat(path); err != nil {
		return 0, err
	}
	d, err := db.Open(ctx, db.SQLite, path)
	if err != nil {
		return 0, err
	}
	defer d.Close()
	var res string
	if err := d.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&res); err != nil {
		return 0, err
	}
	if res != "ok" {
		return 0, fmt.Errorf("integrity check failed: %s", res)
	}
	v, err := d.SchemaVersion(ctx)
	if err != nil {
		return 0, err
	}
	if v == 0 {
		return 0, errors.New("file is not a LekhaMint database")
	}
	if v > db.LatestSchemaVersion() {
		return 0, fmt.Errorf("snapshot schema v%d is newer than this binary (v%d)", v, db.LatestSchemaVersion())
	}
	return v, nil
}

// RestoreSQLiteFile replaces the database at dst with the snapshot at src.
// The server must be stopped. The current database (and its WAL files) is
// kept as <dst>.pre-restore-<timestamp>. It returns that path.
func RestoreSQLiteFile(ctx context.Context, src, dst string) (string, error) {
	if _, err := VerifySQLiteFile(ctx, src); err != nil {
		return "", fmt.Errorf("snapshot rejected: %w", err)
	}
	keep := ""
	if _, err := os.Stat(dst); err == nil {
		keep = fmt.Sprintf("%s.pre-restore-%s", dst, time.Now().Format("20060102-150405"))
		for _, suffix := range []string{"", "-wal", "-shm"} {
			if _, err := os.Stat(dst + suffix); err == nil {
				if err := os.Rename(dst+suffix, keep+suffix); err != nil {
					return "", fmt.Errorf("move current database aside (is the server still running?): %w", err)
				}
			}
		}
	}
	if err := copyFile(src, dst); err != nil {
		return keep, err
	}
	return keep, nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}

// Scheduler takes periodic backups and prunes old ones.
type Scheduler struct {
	DB       *db.DB
	Dir      string
	Interval time.Duration
	Keep     int
	Log      *slog.Logger
}

const backupPrefix = "kosh-"

// Run blocks until ctx is cancelled, taking one backup per interval.
// SQLite gets a VACUUM INTO snapshot; PostgreSQL gets a JSON dump.
func (s *Scheduler) Run(ctx context.Context) {
	if s.Interval <= 0 {
		return
	}
	t := time.NewTicker(s.Interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			path, err := s.RunOnce(ctx)
			if err != nil {
				s.Log.Error("scheduled backup failed", "err", err)
				continue
			}
			s.Log.Info("scheduled backup written", "path", path)
		}
	}
}

// RunOnce takes one backup now and prunes old files.
func (s *Scheduler) RunOnce(ctx context.Context) (string, error) {
	if err := os.MkdirAll(s.Dir, 0o750); err != nil {
		return "", err
	}
	stamp := time.Now().UTC().Format("20060102-150405")
	var path string
	if s.DB.Dialect() == db.SQLite {
		path = filepath.Join(s.Dir, backupPrefix+stamp+".db")
		if err := SnapshotSQLite(ctx, s.DB, path); err != nil {
			return "", err
		}
	} else {
		path = filepath.Join(s.Dir, backupPrefix+stamp+".ndjson.gz")
		if err := writeFileAtomic(path, func(w io.Writer) error { return Dump(ctx, s.DB, w) }); err != nil {
			return "", err
		}
	}
	if err := Prune(s.Dir, s.Keep); err != nil {
		return path, fmt.Errorf("prune old backups: %w", err)
	}
	return path, nil
}

// Prune deletes all but the newest keep backups in dir.
func Prune(dir string, keep int) error {
	if keep <= 0 {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	var names []string
	for _, e := range entries {
		n := e.Name()
		if !e.IsDir() && strings.HasPrefix(n, backupPrefix) && (strings.HasSuffix(n, ".db") || strings.HasSuffix(n, ".ndjson.gz")) {
			names = append(names, n)
		}
	}
	sort.Strings(names) // timestamped names sort chronologically
	for len(names) > keep {
		if err := os.Remove(filepath.Join(dir, names[0])); err != nil {
			return err
		}
		names = names[1:]
	}
	return nil
}

// writeFileAtomic writes via a temp file and renames, so a crash never
// leaves a half-written backup under the final name.
func writeFileAtomic(path string, write func(io.Writer) error) error {
	tmp := path + ".partial"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	if err := write(f); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// WriteDumpFile writes a full dump to path atomically.
func WriteDumpFile(ctx context.Context, d *db.DB, path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	return writeFileAtomic(path, func(w io.Writer) error { return Dump(ctx, d, w) })
}
