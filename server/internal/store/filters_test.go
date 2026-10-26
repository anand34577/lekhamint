package store_test

import (
	"context"
	"testing"

	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

func TestTransactionFilters(t *testing.T) {
	d := testutil.NewDB(t)
	ctx := context.Background()
	lid := ids.NewV7()
	if err := store.CreateLedger(ctx, d, store.Ledger{ID: lid, Name: "T", BaseCurrency: "INR", CreatedBy: ids.NewV7()}); err != nil {
		t.Fatal(err)
	}
	sy := syncer.New(d)
	put := func(entity string, data map[string]any) string {
		t.Helper()
		recs, err := sy.Write(ctx, lid, []syncer.WriteOp{{Entity: entity, Data: data}})
		if err != nil {
			t.Fatalf("%s: %v", entity, err)
		}
		return recs[0].ID
	}
	bank := put("accounts", map[string]any{"name": "Bank", "type": "bank", "currency": "INR"})
	cash := put("accounts", map[string]any{"name": "Cash", "type": "cash", "currency": "INR"})
	food := put("categories", map[string]any{"name": "Food", "kind": "expense"})
	debt := put("debts", map[string]any{"person": "Ravi", "direction": "lent", "amount": 500000, "currency": "INR"})
	tx := func(typ string, amount int64, extra map[string]any) string {
		d := map[string]any{"type": typ, "account_id": bank, "amount": amount, "local_date": "2026-10-10", "occurred_at": int64(1)}
		for k, v := range extra {
			d[k] = v
		}
		return put("transactions", d)
	}
	plain := tx("expense", 10000, nil)
	tx("expense", 20000, map[string]any{"category_id": food})
	split := tx("expense", 30000, nil)
	put("transaction_splits", map[string]any{"transaction_id": split, "category_id": food, "amount": 30000})
	tx("transfer", 40000, map[string]any{"to_account_id": cash, "to_amount": 40000})
	tx("expense", 50000, map[string]any{"is_adjustment": true})
	lent := tx("expense", 500000, map[string]any{"debt_id": debt})

	list := func(f store.TxFilter) []string {
		t.Helper()
		f.Limit = 100
		recs, _, err := store.ListTransactions(ctx, d, lid, f)
		if err != nil {
			t.Fatal(err)
		}
		out := make([]string, len(recs))
		for i, r := range recs {
			out[i] = r.ID
		}
		return out
	}
	if got := list(store.TxFilter{CategoryID: store.UncategorisedFilter}); len(got) != 1 || got[0] != plain {
		t.Fatalf("uncategorised = %v, want only %s", got, plain)
	}
	if got := list(store.TxFilter{DebtID: debt}); len(got) != 1 || got[0] != lent {
		t.Fatalf("debt = %v", got)
	}
	if got := list(store.TxFilter{DebtID: store.AnyDebtFilter}); len(got) != 1 {
		t.Fatalf("any debt = %v", got)
	}
	if got := list(store.TxFilter{MinAmount: 20000, MaxAmount: 40000}); len(got) != 3 {
		t.Fatalf("amount range = %d rows, want 3", len(got))
	}
	// Search finds a category by name, and an amount typed as a number.
	if got := list(store.TxFilter{Query: "foo"}); len(got) != 1 {
		t.Fatalf(`search "foo" = %d rows, want the one in Food`, len(got))
	}
	if got := list(store.TxFilter{Query: "100", QueryAmount: 10000}); len(got) != 1 || got[0] != plain {
		t.Fatalf("search by amount = %v, want only %s", got, plain)
	}
	if got := list(store.TxFilter{Query: "400", QueryAmount: 40000}); len(got) != 1 {
		t.Fatalf("search by transfer amount = %v", got)
	}
}
