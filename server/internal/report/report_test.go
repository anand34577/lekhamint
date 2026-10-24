package report

import (
	"context"
	"math/big"
	"testing"
	"time"

	"github.com/kosh-app/kosh/server/internal/ids"
	"github.com/kosh-app/kosh/server/internal/store"
	"github.com/kosh-app/kosh/server/internal/syncer"
	"github.com/kosh-app/kosh/server/internal/testutil"
)

func date(s string) time.Time {
	t, _ := parseDate(s)
	return t
}

func TestMonthsClampLikeJavaTime(t *testing.T) {
	start := date("2026-01-31")
	want := []string{"2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2027-01-31"}
	for i, n := range []int{0, 1, 2, 3, 12} {
		if got := formatDate(periodStart(start, "monthly", n)); got != want[i] {
			t.Errorf("month %d: %s, want %s", n, got, want[i])
		}
	}
	if got := formatDate(addMonthsClamped(date("2024-02-29"), 12)); got != "2025-02-28" {
		t.Errorf("leap year: %s", got)
	}
}

func TestBudgetWindows(t *testing.T) {
	cases := []struct {
		period, start, end, on string
		want                   Window
		active                 bool
	}{
		{"monthly", "2026-01-01", "", "2026-10-24", Window{"2026-10-01", "2026-10-31"}, true},
		{"monthly", "2026-01-31", "", "2026-03-05", Window{"2026-02-28", "2026-03-30"}, true},
		{"weekly", "2026-10-19", "", "2026-10-26", Window{"2026-10-26", "2026-11-01"}, true},
		{"quarterly", "2026-01-01", "", "2026-05-15", Window{"2026-04-01", "2026-06-30"}, true},
		{"yearly", "2025-04-01", "", "2026-03-31", Window{"2025-04-01", "2026-03-31"}, true},
		{"monthly", "2026-01-01", "2026-06-15", "2026-06-10", Window{"2026-06-01", "2026-06-15"}, true},
		{"monthly", "2026-01-01", "2026-06-15", "2026-07-01", Window{}, false},
		{"monthly", "2026-11-01", "", "2026-10-24", Window{}, false},
		{"custom", "2026-12-20", "2027-01-05", "2026-10-24", Window{"2026-12-20", "2027-01-05"}, true},
	}
	for _, c := range cases {
		w, _, active := budgetWindow(c.period, c.start, c.end, date(c.on))
		if w != c.want || active != c.active {
			t.Errorf("%s from %s on %s: %v %v, want %v %v", c.period, c.start, c.on, w, active, c.want, c.active)
		}
	}
}

func TestConvertMinor(t *testing.T) {
	r := func(s string) *big.Rat { v, _ := new(big.Rat).SetString(s); return v }
	cases := []struct {
		amount   int64
		from, to string
		rate     string
		want     int64
	}{
		{1000, "USD", "INR", "83.1245", 83125},   // $10.00 → ₹831.245 → ₹831.25
		{-1000, "USD", "INR", "83.1245", -83125}, // symmetric rounding
		{100000, "INR", "JPY", "1.8", 1800},      // ₹1000.00 → ¥1800 (0 decimals)
		{1800, "JPY", "INR", "0.5555", 99990},    // ¥1800 → ₹999.90
		{1, "USD", "KWD", "0.305", 3},            // $0.01 → 0.00305 KWD → 0.003
		{5, "USD", "INR", "0.5", 3},              // 0.025 → half away from zero → 0.03
	}
	for _, c := range cases {
		if got := ConvertMinor(c.amount, c.from, c.to, r(c.rate)); got != c.want {
			t.Errorf("%d %s→%s @%s = %d, want %d", c.amount, c.from, c.to, c.rate, got, c.want)
		}
	}
}

// fixture builds a ledger with two accounts and a few categories.
type fixture struct {
	t                                    *testing.T
	svc                                  *Service
	sync                                 *syncer.Service
	lid                                  string
	bank, card, usd                      string
	food, groceries, dining, rent, wages string
}

func newFixture(t *testing.T) *fixture {
	d := testutil.NewDB(t)
	ctx := context.Background()
	lid := ids.NewV7()
	if err := store.CreateLedger(ctx, d, store.Ledger{ID: lid, Name: "T", BaseCurrency: "INR", CreatedBy: ids.NewV7()}); err != nil {
		t.Fatal(err)
	}
	f := &fixture{t: t, svc: &Service{DB: d}, sync: syncer.New(d), lid: lid}
	f.bank = f.put("accounts", map[string]any{"name": "Bank", "type": "bank", "currency": "INR"})
	f.card = f.put("accounts", map[string]any{"name": "Card", "type": "credit_card", "currency": "INR"})
	f.usd = f.put("accounts", map[string]any{"name": "Dollars", "type": "bank", "currency": "USD"})
	f.food = f.put("categories", map[string]any{"name": "Food", "kind": "expense"})
	f.groceries = f.put("categories", map[string]any{"name": "Groceries", "kind": "expense", "parent_id": f.food})
	f.dining = f.put("categories", map[string]any{"name": "Dining", "kind": "expense", "parent_id": f.food})
	f.rent = f.put("categories", map[string]any{"name": "Rent", "kind": "expense"})
	f.wages = f.put("categories", map[string]any{"name": "Salary", "kind": "income"})
	return f
}

func (f *fixture) put(entity string, data map[string]any) string {
	f.t.Helper()
	recs, err := f.sync.Write(context.Background(), f.lid, []syncer.WriteOp{{Entity: entity, Data: data}})
	if err != nil {
		f.t.Fatalf("%s: %v", entity, err)
	}
	return recs[0].ID
}

func (f *fixture) tx(typ, account string, amount int64, cat, day string, extra ...map[string]any) string {
	d := map[string]any{"type": typ, "account_id": account, "amount": amount, "local_date": day, "occurred_at": int64(1)}
	if cat != "" {
		d["category_id"] = cat
	}
	for _, e := range extra {
		for k, v := range e {
			d[k] = v
		}
	}
	return f.put("transactions", d)
}

func TestBudgetProgressRolloverFiltersRefundsSplits(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	// Food budget, ₹10,000 a month with rollover, starting September.
	food := f.put("budgets", map[string]any{"name": "Food", "amount": 1000000, "currency": "INR", "period": "monthly",
		"start_date": "2026-09-01", "category_ids": []any{f.food}, "rollover": true})
	// Card-only budget, no categories.
	cardOnly := f.put("budgets", map[string]any{"name": "Card", "amount": 500000, "currency": "INR", "period": "monthly",
		"start_date": "2026-10-01", "account_ids": []any{f.card}})
	f.put("budgets", map[string]any{"name": "Old", "amount": 1, "currency": "INR", "period": "monthly",
		"start_date": "2026-01-01", "end_date": "2026-03-31"})
	f.put("budgets", map[string]any{"name": "Hidden", "amount": 1, "currency": "INR", "period": "monthly",
		"start_date": "2026-01-01", "archived": true})

	// September: spent ₹7,000 on groceries → ₹3,000 rolls over.
	f.tx("expense", f.bank, 700000, f.groceries, "2026-09-10")
	// October: ₹4,000 dining on the card, ₹500 grocery refund, ₹2,000 rent (not food),
	// a split ₹3,000 bill: ₹1,000 groceries + ₹2,000 rent.
	f.tx("expense", f.card, 400000, f.dining, "2026-10-05")
	f.tx("income", f.bank, 50000, f.groceries, "2026-10-06")
	f.tx("expense", f.card, 200000, f.rent, "2026-10-07")
	split := f.tx("expense", f.bank, 300000, "", "2026-10-08")
	f.put("transaction_splits", map[string]any{"transaction_id": split, "category_id": f.groceries, "amount": 100000})
	f.put("transaction_splits", map[string]any{"transaction_id": split, "category_id": f.rent, "amount": 200000})
	// Ignored: planned, adjustment, transfer, other currency.
	f.tx("expense", f.bank, 999900, f.groceries, "2026-10-09", map[string]any{"status": "planned"})
	f.tx("expense", f.bank, 999900, f.groceries, "2026-10-09", map[string]any{"is_adjustment": true})
	f.tx("transfer", f.bank, 999900, "", "2026-10-09", map[string]any{"to_account_id": f.card, "to_amount": 999900})
	f.tx("expense", f.usd, 999900, f.groceries, "2026-10-09")

	list, err := f.svc.Budgets(ctx, f.lid, "2026-10-24")
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]BudgetProgress{}
	for _, b := range list {
		got[b.BudgetID] = b
	}
	if len(list) != 3 {
		t.Fatalf("want 3 budgets (archived hidden), got %d", len(list))
	}
	fb := got[food]
	// Spent = 4,000 dining − 500 refund + 1,000 split groceries = ₹4,500.
	if fb.Carry != 300000 || fb.Limit != 1300000 || fb.Spent != 450000 || !fb.Active {
		t.Fatalf("food budget = %+v", fb)
	}
	if fb.Window != (Window{"2026-10-01", "2026-10-31"}) || fb.DaysLeft != 8 || fb.PerDayLeft != (1300000-450000)/8 {
		t.Fatalf("food window = %+v", fb)
	}
	// Card budget: dining 4,000 + rent 2,000 on the card.
	if cb := got[cardOnly]; cb.Spent != 600000 || cb.Carry != 0 || cb.Limit != 500000 {
		t.Fatalf("card budget = %+v", cb)
	}
	for _, b := range list {
		if b.BudgetID != food && b.BudgetID != cardOnly && b.Active {
			t.Fatalf("ended budget reported active: %+v", b)
		}
	}
}

func TestTagTotals(t *testing.T) {
	f := newFixture(t)
	trip := f.put("tags", map[string]any{"name": "Goa trip"})
	work := f.put("tags", map[string]any{"name": "Work"})
	f.tx("expense", f.bank, 300000, f.dining, "2026-10-02", map[string]any{"tag_ids": []any{trip}})
	f.tx("expense", f.bank, 100000, f.dining, "2026-10-03", map[string]any{"tag_ids": []any{trip, work}})
	f.tx("income", f.bank, 20000, f.dining, "2026-10-04", map[string]any{"tag_ids": []any{trip}}) // refund
	f.tx("income", f.bank, 500000, f.wages, "2026-10-05", map[string]any{"tag_ids": []any{work}})
	f.tx("expense", f.bank, 700000, f.rent, "2026-10-06")

	list, err := f.svc.Tags(context.Background(), f.lid, "2026-10-01", "2026-10-31")
	if err != nil {
		t.Fatal(err)
	}
	by := map[string]TagTotal{}
	for _, x := range list {
		by[x.TagID] = x
	}
	if g := by[trip]; g.Expense != 380000 || g.Income != 0 || g.Count != 3 {
		t.Fatalf("trip = %+v", g)
	}
	if w := by[work]; w.Expense != 100000 || w.Income != 500000 || w.Count != 2 {
		t.Fatalf("work = %+v", w)
	}
}

func TestConvertUsesNewestRateAndInverse(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	f.put("exchange_rates", map[string]any{"base": "USD", "quote": "INR", "rate": "80", "effective_date": "2026-01-01"})
	f.put("exchange_rates", map[string]any{"base": "INR", "quote": "USD", "rate": "0.0125", "effective_date": "2026-06-01"}) // 1 USD = 80 INR
	f.put("exchange_rates", map[string]any{"base": "USD", "quote": "INR", "rate": "84", "effective_date": "2026-12-01"})     // future
	sums := []CurrencySummary{{Currency: "INR", Income: 100000, Expense: 50000}, {Currency: "USD", Income: 0, Expense: 1000}, {Currency: "EUR", Expense: 5}}
	c, err := f.svc.Convert(ctx, f.lid, "INR", "2026-10-31", sums)
	if err != nil {
		t.Fatal(err)
	}
	// $10.00 at the June inverse rate (1/0.0125 = 80) = ₹800.00.
	if c.Expense != 50000+80000 || c.Income != 100000 || c.Net != 100000-130000 {
		t.Fatalf("converted = %+v", c)
	}
	if len(c.Missing) != 1 || c.Missing[0] != "EUR" || len(c.Rates) != 1 || !c.Rates[0].Inverted || c.Rates[0].EffectiveDate != "2026-06-01" {
		t.Fatalf("rates = %+v missing = %v", c.Rates, c.Missing)
	}
}

func TestPayeeTotals(t *testing.T) {
	f := newFixture(t)
	f.tx("expense", f.bank, 30000, f.dining, "2026-10-02", map[string]any{"payee": "Swiggy"})
	f.tx("expense", f.card, 20000, f.dining, "2026-10-09", map[string]any{"payee": "swiggy "})
	f.tx("expense", f.bank, 10000, f.dining, "2026-10-05", map[string]any{"payee": "Swiggy"})
	f.tx("income", f.bank, 5000, f.dining, "2026-10-06", map[string]any{"payee": "Swiggy"}) // refund
	f.tx("income", f.bank, 900000, f.wages, "2026-10-01", map[string]any{"payee": "Acme"})
	f.tx("expense", f.bank, 700000, f.rent, "2026-10-03", map[string]any{"payee": "Landlord", "status": "planned"})
	f.tx("expense", f.usd, 1500, f.dining, "2026-10-04", map[string]any{"payee": "Swiggy"})
	f.tx("expense", f.bank, 100000, f.rent, "2026-10-04")

	list, err := f.svc.Payees(context.Background(), f.lid, "2026-10-01", "2026-10-31", 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 3 {
		t.Fatalf("want Swiggy INR, Swiggy USD and Acme, got %+v", list)
	}
	if s := list[0]; s.Payee != "Swiggy" || s.Currency != "INR" || s.Expense != 55000 || s.Count != 4 || s.LastDate != "2026-10-09" {
		t.Fatalf("swiggy = %+v", s)
	}
	if a := list[2]; a.Payee != "Acme" || a.Income != 900000 || a.Expense != 0 {
		t.Fatalf("acme = %+v", a)
	}
	if top, _ := f.svc.Payees(context.Background(), f.lid, "2026-10-01", "2026-10-31", 1); len(top) != 1 {
		t.Fatalf("limit ignored: %d", len(top))
	}
}
