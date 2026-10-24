package demo_test

import (
	"context"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/demo"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/syncer"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

// The demo book must be believable: no account a household would never let go
// negative is overdrawn, and the budgets are in a realistic range.
func TestSeedIsRealistic(t *testing.T) {
	for _, today := range []string{"2026-10-24", "2027-03-01", "2026-12-31"} {
		t.Run(today, func(t *testing.T) {
			d := testutil.NewDB(t)
			ctx := context.Background()
			sync := syncer.New(d)
			deps := demo.Deps{Auth: auth.NewService(d, false, time.Hour), Ledgers: &ledger.Service{DB: d, Sync: sync}, Sync: sync}
			now, _ := time.Parse(time.DateOnly, today)
			l, err := demo.Seed(ctx, deps, now, 12)
			if err != nil {
				t.Fatal(err)
			}
			reports := &report.Service{DB: d}
			balances, err := reports.Balances(ctx, l.ID, today)
			if err != nil {
				t.Fatal(err)
			}
			for _, b := range balances {
				if (b.Type == "cash" || b.Type == "wallet" || b.Type == "bank" || b.Type == "savings") && b.Balance < 0 {
					t.Errorf("%s is overdrawn: %d", b.Name, b.Balance)
				}
			}
			budgets, err := reports.Budgets(ctx, l.ID, today)
			if err != nil {
				t.Fatal(err)
			}
			if len(budgets) != 5 {
				t.Fatalf("budgets = %d", len(budgets))
			}
			for _, b := range budgets {
				if b.Limit <= 0 {
					t.Errorf("budget %s has limit %d", b.BudgetID, b.Limit)
				}
			}
			// Signing in with the advertised credentials works.
			if _, _, err := deps.Auth.Login(ctx, demo.Email, demo.Password, "test"); err != nil {
				t.Fatal(err)
			}
		})
	}
}
