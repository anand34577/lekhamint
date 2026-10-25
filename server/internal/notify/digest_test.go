package notify_test

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/notify"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

func TestWeeklyDigest(t *testing.T) {
	ctx := context.Background()
	d := testutil.NewDB(t)
	led := store.Ledger{ID: ids.NewV7(), Name: "Home", BaseCurrency: "INR", CreatedBy: "u", CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateLedger(ctx, d, led); err != nil {
		t.Fatal(err)
	}
	seq := int64(0)
	put := func(spec *entity.Spec, id string, data entity.Data) {
		seq++
		if err := store.PutRecord(ctx, d, spec, led.ID, store.Record{ID: id, HLC: "h", Seq: seq, Data: data}); err != nil {
			t.Fatal(err)
		}
	}
	acc, food, groceries, fuel := ids.NewV7(), ids.NewV7(), ids.NewV7(), ids.NewV7()
	put(entity.Accounts, acc, entity.Data{"name": "Bank", "type": "bank", "currency": "INR", "opening_balance": int64(0)})
	put(entity.Categories, food, entity.Data{"name": "Food", "kind": "expense"})
	put(entity.Categories, groceries, entity.Data{"name": "Groceries", "kind": "expense", "parent_id": food})
	put(entity.Categories, fuel, entity.Data{"name": "Fuel", "kind": "expense"})
	spend := func(day, cat string, amount int64) {
		put(entity.Transactions, ids.NewV7(), entity.Data{"type": "expense", "account_id": acc, "amount": amount, "category_id": cat,
			"occurred_at": int64(1), "local_date": day, "status": "cleared"})
	}
	// The usual week before: 4 weeks of 1,000 each.
	for _, day := range []string{"2026-09-22", "2026-09-29", "2026-10-06", "2026-10-13"} {
		spend(day, fuel, 100000)
	}
	// Last week (Mon 19 to Sun 25 Oct): 1,500, so 50% more than usual.
	spend("2026-10-20", groceries, 90000)
	spend("2026-10-24", fuel, 60000)
	put(entity.RecurringRules, ids.NewV7(), entity.Data{"name": "Rent", "type": "expense", "account_id": acc, "amount": int64(2000000),
		"frequency": "monthly", "every": int64(1), "start_date": "2026-01-01", "mode": "remind", "next_date": "2026-10-28", "paused": false})

	disp := &notify.Dispatcher{DB: d, Reports: &report.Service{DB: d}, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	monday := time.Date(2026, 10, 26, 9, 0, 0, 0, time.UTC)
	body, ok := disp.Digest(ctx, led, monday)
	if !ok {
		t.Fatal("no digest for an active week")
	}
	for _, want := range []string{"Spent INR 1,500.00, 50% more than your usual week (INR 1,000.00)", "Food: INR 900.00", "Fuel: INR 600.00", "Wed 28 Oct: Rent (INR 20,000.00)"} {
		if !strings.Contains(body, want) {
			t.Errorf("digest lacks %q:\n%s", want, body)
		}
	}
	// A quiet week sends nothing.
	if _, ok := disp.Digest(ctx, led, monday.AddDate(0, 0, 21)); ok {
		t.Fatal("digest for a week with no activity")
	}
}
