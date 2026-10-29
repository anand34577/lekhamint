package syncer_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/hlc"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

var ctx = context.Background()

func setup(t *testing.T) (*syncer.Service, *db.DB, string) {
	t.Helper()
	d := testutil.NewDB(t)
	lid := ids.NewV7()
	now := time.Now().UnixMilli()
	if err := store.CreateLedger(ctx, d, store.Ledger{ID: lid, Name: "Test", BaseCurrency: "INR", CreatedBy: "u", CreatedAt: now, UpdatedAt: now}); err != nil {
		t.Fatal(err)
	}
	return syncer.New(d), d, lid
}

// device simulates a client with its own HLC clock.
type device struct{ clock *hlc.Clock }

func newDevice() *device { return &device{clock: hlc.NewClock("")} }

func (dv *device) change(entityName, id string, data map[string]any) syncer.Change {
	return syncer.Change{Entity: entityName, ID: id, HLC: dv.clock.Now(), Data: data}
}

func account(name string) map[string]any {
	return map[string]any{"name": name, "type": "bank", "currency": "INR", "opening_balance": json.Number("100000")}
}

func TestPushThenPull(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	a := ids.NewV7()
	res, err := svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", a, account("HDFC"))})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Accepted) != 1 || res.Cursor != 1 {
		t.Fatalf("push result %+v", res)
	}
	pull, err := svc.Pull(ctx, lid, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pull.Changes) != 1 || pull.Changes[0].Data["name"] != "HDFC" || pull.NextCursor != 1 || pull.HasMore {
		t.Fatalf("pull %+v", pull)
	}
	// Nothing new after the cursor.
	pull, _ = svc.Pull(ctx, lid, pull.NextCursor, 100)
	if len(pull.Changes) != 0 || pull.NextCursor != 1 {
		t.Fatalf("second pull %+v", pull)
	}
}

// A device whose cursor is past the ledger's seq (the server was restored from an older
// backup) must be told, not handed an empty page: it would skip every change numbered
// up to its cursor.
func TestPullCursorAhead(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	if _, err := svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", ids.NewV7(), account("HDFC"))}); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Pull(ctx, lid, 1, 100); err != nil {
		t.Fatalf("pull at the head: %v", err)
	}
	if _, err := svc.Pull(ctx, lid, 5, 100); !errors.Is(err, syncer.ErrCursorAhead) {
		t.Fatalf("pull past the head: got %v, want ErrCursorAhead", err)
	}
	if _, err := svc.PullWait(ctx, lid, 5, 100, time.Second); !errors.Is(err, syncer.ErrCursorAhead) {
		t.Fatalf("waiting pull past the head: got %v, want ErrCursorAhead", err)
	}
}

func TestLastWriterWins(t *testing.T) {
	svc, _, lid := setup(t)
	phone, laptop := newDevice(), newDevice()
	a := ids.NewV7()

	older := phone.change("accounts", a, account("Phone name"))
	time.Sleep(2 * time.Millisecond)
	newer := laptop.change("accounts", a, account("Laptop name"))

	if _, err := svc.Push(ctx, lid, []syncer.Change{newer}); err != nil {
		t.Fatal(err)
	}
	res, err := svc.Push(ctx, lid, []syncer.Change{older})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Conflicts) != 1 || res.Conflicts[0].Data["name"] != "Laptop name" {
		t.Fatalf("expected conflict returning newer version, got %+v", res)
	}
	rec, _ := store.GetRecord(ctx, svc.DB, entity.Accounts, lid, a)
	if rec.Data["name"] != "Laptop name" {
		t.Fatalf("stored %v", rec.Data["name"])
	}
}

func TestRepushIsIdempotent(t *testing.T) {
	svc, _, lid := setup(t)
	ch := newDevice().change("accounts", ids.NewV7(), account("Cash"))
	r1, _ := svc.Push(ctx, lid, []syncer.Change{ch})
	r2, err := svc.Push(ctx, lid, []syncer.Change{ch}) // response to r1 "lost"
	if err != nil {
		t.Fatal(err)
	}
	if len(r2.Accepted) != 1 || len(r2.Conflicts) != 0 || r2.Cursor != r1.Cursor {
		t.Fatalf("re-push should be a no-op accept: %+v", r2)
	}
}

func TestPartialUpdateKeepsOtherFields(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	a := ids.NewV7()
	data := account("HDFC")
	data["note"] = "salary account"
	svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", a, data)})
	// An older client that only knows "name".
	svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", a, map[string]any{"name": "HDFC Bank"})})
	rec, _ := store.GetRecord(ctx, svc.DB, entity.Accounts, lid, a)
	if rec.Data["name"] != "HDFC Bank" || rec.Data["note"] != "salary account" || rec.Data["opening_balance"] != int64(100000) {
		t.Fatalf("merge lost fields: %v", rec.Data)
	}
}

func TestDeleteAndRevive(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	a := ids.NewV7()
	svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", a, account("Wallet"))})
	del := dv.change("accounts", a, nil)
	del.Deleted = true
	svc.Push(ctx, lid, []syncer.Change{del})
	rec, _ := store.GetRecord(ctx, svc.DB, entity.Accounts, lid, a)
	if !rec.Deleted || rec.Data["name"] != "Wallet" {
		t.Fatalf("tombstone should keep fields for undo: %+v", rec)
	}
	pull, _ := svc.Pull(ctx, lid, 1, 10)
	if len(pull.Changes) != 1 || !pull.Changes[0].Deleted {
		t.Fatalf("delete not in feed: %+v", pull)
	}
	svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", a, nil)}) // undelete
	rec, _ = store.GetRecord(ctx, svc.DB, entity.Accounts, lid, a)
	if rec.Deleted {
		t.Fatal("revive failed")
	}
}

func TestTombstoneForUnknownRecord(t *testing.T) {
	svc, _, lid := setup(t)
	ch := newDevice().change("transactions", ids.NewV7(), nil)
	ch.Deleted = true
	res, err := svc.Push(ctx, lid, []syncer.Change{ch})
	if err != nil || len(res.Accepted) != 1 {
		t.Fatalf("res=%+v err=%v", res, err)
	}
}

func TestRejections(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	future := hlc.Timestamp{Wall: time.Now().Add(48 * time.Hour).UnixMilli(), Node: "0000000000000001"}.String()
	res, err := svc.Push(ctx, lid, []syncer.Change{
		{Entity: "unicorns", ID: ids.NewV7(), HLC: dv.clock.Now()},
		{Entity: "accounts", ID: "nope", HLC: dv.clock.Now()},
		{Entity: "accounts", ID: ids.NewV7(), HLC: "yesterday"},
		{Entity: "accounts", ID: ids.NewV7(), HLC: future, Data: account("x")},
		{Entity: "accounts", ID: ids.NewV7(), HLC: dv.clock.Now(), Data: map[string]any{"name": "x"}},
		dv.change("accounts", ids.NewV7(), account("Valid one")),
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{syncer.CodeUnknownEntity, syncer.CodeInvalidID, syncer.CodeInvalidHLC, syncer.CodeHLCInFuture, syncer.CodeValidation}
	if len(res.Rejected) != len(want) || len(res.Accepted) != 1 {
		t.Fatalf("got %+v", res)
	}
	for i, code := range want {
		if res.Rejected[i].Error != code {
			t.Errorf("rejection %d = %s, want %s", i, res.Rejected[i].Error, code)
		}
	}
	if res.Rejected[4].Fields["type"] == "" {
		t.Errorf("validation rejection should name the field: %+v", res.Rejected[4])
	}
}

func TestPullPagesAcrossTables(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	var changes []syncer.Change
	for i := 0; i < 7; i++ {
		changes = append(changes, dv.change("accounts", ids.NewV7(), account(fmt.Sprint("A", i))))
		changes = append(changes, dv.change("tags", ids.NewV7(), map[string]any{"name": fmt.Sprint("T", i)}))
	}
	if _, err := svc.Push(ctx, lid, changes); err != nil {
		t.Fatal(err)
	}
	var (
		cursor int64
		got    []store.Record
		pages  int
	)
	for {
		p, err := svc.Pull(ctx, lid, cursor, 4)
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, p.Changes...)
		cursor = p.NextCursor
		pages++
		if !p.HasMore {
			break
		}
	}
	if len(got) != 14 || pages != 4 {
		t.Fatalf("got %d records in %d pages", len(got), pages)
	}
	for i, r := range got {
		if r.Seq != int64(i+1) {
			t.Fatalf("record %d has seq %d: feed not in order", i, r.Seq)
		}
		if strings.HasPrefix(fmt.Sprint(r.Data["name"]), "T") != (r.Entity == "tags") {
			t.Fatalf("entity mix-up: %+v", r)
		}
	}
}

func TestServerWriteBeatsExistingHLC(t *testing.T) {
	svc, _, lid := setup(t)
	a := ids.NewV7()
	// A client with a clock 1 hour ahead (allowed skew) writes first.
	ahead := hlc.Timestamp{Wall: time.Now().Add(time.Hour).UnixMilli(), Node: "00000000000000aa"}.String()
	svc.Push(ctx, lid, []syncer.Change{{Entity: "accounts", ID: a, HLC: ahead, Data: account("Client")}})
	recs, err := svc.Write(ctx, lid, []syncer.WriteOp{{Entity: "accounts", ID: a, Data: map[string]any{"name": "Web edit"}, MustExist: true}})
	if err != nil {
		t.Fatal(err)
	}
	if recs[0].HLC <= ahead || recs[0].Data["name"] != "Web edit" {
		t.Fatalf("server write lost: %+v", recs[0])
	}
}

func TestImportMergesAndSkipsNewer(t *testing.T) {
	svc, _, lid := setup(t)
	dv := newDevice()
	a, b := ids.NewV7(), ids.NewV7()
	oldA := dv.change("accounts", a, account("Old A"))
	chB := dv.change("accounts", b, account("B"))
	svc.Push(ctx, lid, []syncer.Change{dv.change("accounts", a, account("Current A"))})

	stream := []syncer.Change{oldA, chB, {Entity: "accounts", ID: "bad"}}
	i := 0
	res, err := svc.Import(ctx, lid, func() (syncer.Change, bool, error) {
		if i >= len(stream) {
			return syncer.Change{}, false, nil
		}
		i++
		return stream[i-1], true, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	// oldA's HLC is older than "Current A" (pushed after it was stamped).
	if res.Accepted != 1 || res.Skipped != 1 || len(res.Rejected) != 1 {
		t.Fatalf("import %+v", res)
	}
}

// Two devices edit different fields of one record while apart: neither edit may be lost.
func TestStaleEditMergesByField(t *testing.T) {
	svc, _, lid := setup(t)
	phone, laptop := newDevice(), newDevice()
	a := ids.NewV7()
	base := account("HDFC")
	base["note"] = "joint"
	if _, err := svc.Push(ctx, lid, []syncer.Change{phone.change("accounts", a, base)}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)

	// The phone edits the note offline, based on the version above.
	phoneEdit := map[string]any{}
	for k, v := range base {
		phoneEdit[k] = v
	}
	phoneEdit["note"] = "salary account"
	stale := phone.change("accounts", a, phoneEdit)
	stale.Base = base
	time.Sleep(2 * time.Millisecond)

	// Meanwhile the laptop renames the account and pushes first.
	renamed := account("HDFC Savings")
	renamed["note"] = "joint"
	if _, err := svc.Push(ctx, lid, []syncer.Change{laptop.change("accounts", a, renamed)}); err != nil {
		t.Fatal(err)
	}

	res, err := svc.Push(ctx, lid, []syncer.Change{stale})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Conflicts) != 1 {
		t.Fatalf("want the merged record back as a conflict, got %+v", res)
	}
	got := res.Conflicts[0].Data
	if got["name"] != "HDFC Savings" || got["note"] != "salary account" {
		t.Fatalf("merged data %+v", got)
	}
	if res.Conflicts[0].HLC <= stale.HLC {
		t.Fatalf("merged record must be newer than both edits: %s vs %s", res.Conflicts[0].HLC, stale.HLC)
	}
	// Everyone pulling sees the merged version.
	pull, _ := svc.Pull(ctx, lid, 0, 100)
	last := pull.Changes[len(pull.Changes)-1]
	if last.Data["name"] != "HDFC Savings" || last.Data["note"] != "salary account" {
		t.Fatalf("pulled %+v", last.Data)
	}
}

// If both devices changed the same field, the newer write still wins.
func TestStaleEditSameFieldKeepsNewer(t *testing.T) {
	svc, _, lid := setup(t)
	phone, laptop := newDevice(), newDevice()
	a := ids.NewV7()
	base := account("HDFC")
	if _, err := svc.Push(ctx, lid, []syncer.Change{phone.change("accounts", a, base)}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)
	stale := phone.change("accounts", a, account("Phone name"))
	stale.Base = base
	time.Sleep(2 * time.Millisecond)
	if _, err := svc.Push(ctx, lid, []syncer.Change{laptop.change("accounts", a, account("Laptop name"))}); err != nil {
		t.Fatal(err)
	}
	res, _ := svc.Push(ctx, lid, []syncer.Change{stale})
	if len(res.Conflicts) != 1 || res.Conflicts[0].Data["name"] != "Laptop name" {
		t.Fatalf("%+v", res)
	}
}

// An update saved while offline and sent later keeps the fields nobody changed after
// it was made; a field someone else changed later keeps their newer value.
func TestLateEditKeepsNewerChanges(t *testing.T) {
	svc, _, lid := setup(t)
	actx := syncer.WithActor(ctx, syncer.Actor{UserID: "u", Device: "web"}) // history is kept for people's writes
	a := ids.NewV7()
	acct := account("HDFC")
	acct["note"] = "salary account"
	if _, err := svc.Write(actx, lid, []syncer.WriteOp{{Entity: "accounts", ID: a, Data: acct}}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(5 * time.Millisecond)
	editedAt := time.Now().UnixMilli() // the offline browser renames it and changes the note
	time.Sleep(5 * time.Millisecond)
	if _, err := svc.Write(actx, lid, []syncer.WriteOp{{Entity: "accounts", ID: a, Data: map[string]any{"name": "HDFC Savings"}, MustExist: true}}); err != nil {
		t.Fatal(err)
	}
	late := syncer.WriteOp{Entity: "accounts", ID: a, MustExist: true, EditedAt: editedAt,
		Data: map[string]any{"name": "My bank", "note": "joint with Ravi"}}
	recs, err := svc.Write(actx, lid, []syncer.WriteOp{late})
	if err != nil {
		t.Fatal(err)
	}
	if got := recs[0].Data; got["name"] != "HDFC Savings" || got["note"] != "joint with Ravi" {
		t.Fatalf("after the late edit: name=%v note=%v", got["name"], got["note"])
	}

	// Sent again with only the field changed later: nothing to take, nothing written.
	res, err := svc.WriteResults(actx, lid, []syncer.WriteOp{{Entity: "accounts", ID: a, MustExist: true, EditedAt: editedAt, Data: map[string]any{"name": "My bank"}}})
	if err != nil || res[0].Status != "skipped" {
		t.Fatalf("stale-only edit: %+v %v", res, err)
	}
	// An update with no EditedAt is an ordinary edit and wins whole.
	recs, err = svc.Write(actx, lid, []syncer.WriteOp{{Entity: "accounts", ID: a, MustExist: true, Data: map[string]any{"name": "My bank"}}})
	if err != nil || recs[0].Data["name"] != "My bank" {
		t.Fatalf("ordinary edit: %v %v", recs, err)
	}
}
