package backup_test

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/backup"
	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/hlc"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

var ctx = context.Background()

// populate creates a user with a seeded ledger and some records.
func populate(t *testing.T, d *db.DB) (store.User, store.Ledger) {
	t.Helper()
	u, err := auth.NewService(d, true, 0).CreateUser(ctx, "a@example.com", "A", "password123")
	if err != nil {
		t.Fatal(err)
	}
	sync := syncer.New(d)
	l, _, err := (&ledger.Service{DB: d, Sync: sync}).Create(ctx, u.ID, ledger.CreateInput{Name: "Home", BaseCurrency: "INR", SeedDefaultCategories: true})
	if err != nil {
		t.Fatal(err)
	}
	clock := hlc.NewClock("")
	var changes []syncer.Change
	acct := ids.NewV7()
	changes = append(changes, syncer.Change{Entity: "accounts", ID: acct, HLC: clock.Now(),
		Data: map[string]any{"name": "Cash", "type": "cash", "currency": "INR"}})
	for i := 0; i < 50; i++ {
		changes = append(changes, syncer.Change{Entity: "transactions", ID: ids.NewV7(), HLC: clock.Now(), Data: map[string]any{
			"type": "expense", "account_id": acct, "amount": int64(100 + i), "local_date": "2026-10-01", "occurred_at": int64(1)}})
	}
	del := syncer.Change{Entity: "transactions", ID: changes[1].ID, HLC: clock.Now(), Deleted: true}
	changes = append(changes, del)
	if res, err := sync.Push(ctx, l.ID, changes); err != nil || len(res.Rejected) > 0 {
		t.Fatalf("push: %v %+v", err, res.Rejected)
	}
	l, _ = store.GetLedger(ctx, d, l.ID)
	return u, l
}

func TestDumpRestoreRoundTrip(t *testing.T) {
	src := testutil.NewDB(t)
	u, l := populate(t, src)

	var buf bytes.Buffer
	if err := backup.Dump(ctx, src, &buf); err != nil {
		t.Fatal(err)
	}
	dst := testutil.NewDB(t)
	st, err := backup.RestoreDump(ctx, dst, bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	if st.Users != 1 || st.Ledgers != 1 || st.Members != 1 || st.Records < 100 {
		t.Fatalf("stats %+v", st)
	}
	// Same seq, so synced clients keep their cursors.
	l2, _ := store.GetLedger(ctx, dst, l.ID)
	if l2.Seq != l.Seq {
		t.Fatalf("seq %d != %d", l2.Seq, l.Seq)
	}
	// Exports of both are identical apart from the timestamp.
	if a, b := exportString(t, src, l), exportString(t, dst, l2); a != b {
		t.Fatal("restored ledger differs from source")
	}
	// The password still works (hash was carried over).
	if _, _, err := auth.NewService(dst, false, 0).Login(ctx, u.Email, "password123", "test"); err != nil {
		t.Fatalf("login after restore: %v", err)
	}
	// Restoring into a non-empty database is refused.
	if _, err := backup.RestoreDump(ctx, dst, bytes.NewReader(buf.Bytes())); err == nil {
		t.Fatal("restore into non-empty database succeeded")
	}
}

func exportString(t *testing.T, d *db.DB, l store.Ledger) string {
	var b bytes.Buffer
	if err := backup.ExportLedger(ctx, d, l, &b); err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(b.Bytes(), &doc); err != nil {
		t.Fatalf("export is not valid JSON: %v", err)
	}
	delete(doc, "exported_at")
	out, _ := json.Marshal(doc)
	return string(out)
}

func TestTruncatedDumpChangesNothing(t *testing.T) {
	src := testutil.NewDB(t)
	populate(t, src)
	var buf bytes.Buffer
	backup.Dump(ctx, src, &buf)

	// Decompress, drop the last lines (including the footer), recompress.
	gz, _ := gzip.NewReader(&buf)
	var plain bytes.Buffer
	plain.ReadFrom(gz)
	lines := strings.Split(strings.TrimSpace(plain.String()), "\n")
	var cut bytes.Buffer
	zw := gzip.NewWriter(&cut)
	zw.Write([]byte(strings.Join(lines[:len(lines)-5], "\n") + "\n"))
	zw.Close()

	dst := testutil.NewDB(t)
	if _, err := backup.RestoreDump(ctx, dst, &cut); err == nil || !strings.Contains(err.Error(), "truncated") {
		t.Fatalf("want truncation error, got %v", err)
	}
	if n, _ := store.CountUsers(ctx, dst); n != 0 {
		t.Fatal("partial restore left data behind")
	}
}

func TestLedgerExportImport(t *testing.T) {
	d := testutil.NewDB(t)
	u, l := populate(t, d)
	var b bytes.Buffer
	if err := backup.ExportLedger(ctx, d, l, &b); err != nil {
		t.Fatal(err)
	}
	sync := syncer.New(d)
	l2, _, _ := (&ledger.Service{DB: d, Sync: sync}).Create(ctx, u.ID, ledger.CreateInput{Name: "Copy", BaseCurrency: "INR"})
	r, err := backup.NewLedgerReader(&b)
	if err != nil {
		t.Fatal(err)
	}
	res, err := sync.Import(ctx, l2.ID, r.Next)
	if err != nil {
		t.Fatal(err)
	}
	// One record per row: the seeded categories, 1 account and 50 transactions
	// (one of them deleted twice but exported once as a tombstone).
	var rows int
	store.EachRecord(ctx, d, l.ID, l.Seq, func(store.Record) error { rows++; return nil })
	if res.Accepted != rows || len(res.Rejected) != 0 {
		t.Fatalf("import %+v", res)
	}
	// Importing the same export again changes nothing (idempotent merge).
	b.Reset()
	backup.ExportLedger(ctx, d, l, &b)
	r, _ = backup.NewLedgerReader(&b)
	before, _ := store.GetLedger(ctx, d, l2.ID)
	res2, _ := sync.Import(ctx, l2.ID, r.Next)
	after, _ := store.GetLedger(ctx, d, l2.ID)
	if before.Seq != after.Seq || res2.Accepted != res.Accepted {
		t.Fatalf("second import wrote data: seq %d -> %d, %+v", before.Seq, after.Seq, res2)
	}
}

func TestSnapshotVerifyRestoreAndPrune(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "live.db")
	d, err := db.Open(ctx, db.SQLite, path)
	if err != nil {
		t.Fatal(err)
	}
	d.Migrate(ctx)
	populate(t, d)

	snap := filepath.Join(dir, "snap.db")
	if err := backup.SnapshotSQLite(ctx, d, snap); err != nil {
		t.Fatal(err)
	}
	if v, err := backup.VerifySQLiteFile(ctx, snap); err != nil || v != db.LatestSchemaVersion() {
		t.Fatalf("verify: v%d %v", v, err)
	}
	d.Close()

	kept, err := backup.RestoreSQLiteFile(ctx, snap, path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(kept); err != nil {
		t.Fatalf("previous database not kept: %v", err)
	}
	d2, _ := db.Open(ctx, db.SQLite, path)
	defer d2.Close()
	if n, _ := store.CountUsers(ctx, d2); n != 1 {
		t.Fatal("restored database has no users")
	}

	// Garbage is rejected.
	junk := filepath.Join(dir, "junk.db")
	os.WriteFile(junk, []byte("not sqlite"), 0o600)
	if _, err := backup.VerifySQLiteFile(ctx, junk); err == nil {
		t.Fatal("junk file verified")
	}

	// Prune keeps the newest N.
	bdir := filepath.Join(dir, "backups")
	os.MkdirAll(bdir, 0o750)
	for i := 0; i < 5; i++ {
		os.WriteFile(filepath.Join(bdir, fmt.Sprintf("lekhamint-2026010%d-000000.db", i)), nil, 0o600)
	}
	os.WriteFile(filepath.Join(bdir, "keep-me.txt"), nil, 0o600)
	if err := backup.Prune(bdir, 2); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(bdir)
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	if strings.Join(names, ",") != "keep-me.txt,lekhamint-20260103-000000.db,lekhamint-20260104-000000.db" {
		t.Fatalf("after prune: %v", names)
	}
}

func TestSchedulerRunOnce(t *testing.T) {
	d := testutil.NewDB(t)
	populate(t, d)
	s := &backup.Scheduler{DB: d, Dir: t.TempDir(), Keep: 3, Interval: time.Hour}
	path, err := s.RunOnce(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if fi, err := os.Stat(path); err != nil || fi.Size() == 0 {
		t.Fatalf("backup file missing: %v", err)
	}
}
