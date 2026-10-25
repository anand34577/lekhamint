package notify

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// EventDigest is the weekly summary, sent on Monday mornings.
const EventDigest = "digest"

// weeklyDigests sends each book's summary of the past week (Monday to Sunday),
// once per week per book.
func (d *Dispatcher) weeklyDigests(ctx context.Context, u store.User, pr store.Prefs, ledgers []store.Ledger, now time.Time) {
	if now.Weekday() != time.Monday || d.Reports == nil {
		return
	}
	for _, l := range ledgers {
		body, ok := d.Digest(ctx, l, now)
		if !ok {
			continue
		}
		d.once(ctx, u, pr, EventDigest, "digest:"+l.ID+":"+now.Format("2006-01-02"), "Your week in "+l.Name, body)
	}
}

// Digest writes the weekly summary of one book for the week before now:
// spending against the usual week, the biggest categories, income, what is
// due in the coming week and budgets that need attention. ok is false when
// nothing happened that week, so quiet books send nothing.
func (d *Dispatcher) Digest(ctx context.Context, l store.Ledger, now time.Time) (string, bool) {
	day := func(t time.Time) string { return t.Format("2006-01-02") }
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)
	from, to := today.AddDate(0, 0, -7), today.AddDate(0, 0, -1)
	week, err := d.Reports.Summary(ctx, l.ID, day(from), day(to))
	if err != nil || len(week) == 0 {
		return "", false
	}
	before, _ := d.Reports.Summary(ctx, l.ID, day(from.AddDate(0, 0, -28)), day(from.AddDate(0, 0, -1)))
	usual := map[string]int64{}
	for _, cs := range before {
		usual[cs.Currency] = cs.Expense / 4
	}
	var b strings.Builder
	fmt.Fprintf(&b, "Here is %s from %s to %s.\n", l.Name, from.Format("Mon 2 Jan"), to.Format("Mon 2 Jan"))
	active := false
	for _, cs := range week {
		if cs.Expense == 0 && cs.Income == 0 {
			continue
		}
		active = true
		fmt.Fprintf(&b, "\nSpent %s", Money(cs.Expense, cs.Currency))
		if avg := usual[cs.Currency]; avg > 0 {
			diff := (cs.Expense - avg) * 100 / avg
			switch {
			case diff >= 10:
				fmt.Fprintf(&b, ", %d%% more than your usual week (%s)", diff, Money(avg, cs.Currency))
			case diff <= -10:
				fmt.Fprintf(&b, ", %d%% less than your usual week (%s)", -diff, Money(avg, cs.Currency))
			default:
				fmt.Fprintf(&b, ", about your usual week (%s)", Money(avg, cs.Currency))
			}
		}
		b.WriteString(".\n")
		if cs.Income > 0 {
			fmt.Fprintf(&b, "Received %s.\n", Money(cs.Income, cs.Currency))
		}
		if cats := topCats(cs.ExpenseByCat, d.categoryNames(ctx, l.ID)); len(cats) > 0 {
			b.WriteString("Biggest categories:\n")
			for _, c := range cats {
				fmt.Fprintf(&b, "  - %s: %s\n", c.name, Money(c.amount, cs.Currency))
			}
		}
	}
	if !active {
		return "", false
	}
	if due := d.dueThisWeek(ctx, l, today); len(due) > 0 {
		b.WriteString("\nComing up this week:\n")
		for _, line := range due {
			b.WriteString("  - " + line + "\n")
		}
	}
	if progress, err := d.Reports.Budgets(ctx, l.ID, day(today)); err == nil {
		over, near := 0, 0
		for _, p := range progress {
			if !p.Active || p.Limit <= 0 {
				continue
			}
			switch pct := p.Spent * 100 / p.Limit; {
			case pct >= 100:
				over++
			case pct >= 80:
				near++
			}
		}
		if over+near > 0 {
			fmt.Fprintf(&b, "\nBudgets: %d over the limit, %d above 80%%.\n", over, near)
		}
	}
	b.WriteString("\nYou can turn this weekly email off in Settings, Notifications.")
	return b.String(), true
}

type reportCat struct {
	name   string
	amount int64
}

// topCats returns the three biggest spending categories, rolled up to their
// parent category so "Food · Groceries" and "Food · Dining" count as Food.
func topCats(list []report.CategoryTotal, names map[string]string) []reportCat {
	sums := map[string]int64{}
	for _, c := range list {
		key := c.Name
		if c.ParentID != nil {
			if p, ok := names[*c.ParentID]; ok {
				key = p
			}
		}
		if key == "" {
			key = "Uncategorised"
		}
		sums[key] += c.Amount
	}
	out := make([]reportCat, 0, len(sums))
	for n, a := range sums {
		if a > 0 {
			out = append(out, reportCat{n, a})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].amount != out[j].amount {
			return out[i].amount > out[j].amount
		}
		return out[i].name < out[j].name
	})
	if len(out) > 3 {
		out = out[:3]
	}
	return out
}

// categoryNames maps category IDs to names (for rolling sub-categories up).
func (d *Dispatcher) categoryNames(ctx context.Context, ledgerID string) map[string]string {
	out := map[string]string{}
	recs, err := store.ListRecords(ctx, d.DB, entity.Categories, ledgerID, store.ListOptions{Limit: 5000})
	if err != nil {
		return out
	}
	for _, r := range recs {
		if n, _ := r.Data["name"].(string); n != "" {
			out[r.ID] = n
		}
	}
	return out
}

// dueThisWeek lists recurring payments due in the next seven days.
func (d *Dispatcher) dueThisWeek(ctx context.Context, l store.Ledger, today time.Time) []string {
	recs, err := store.ListRecords(ctx, d.DB, entity.RecurringRules, l.ID, store.ListOptions{Limit: 1000})
	if err != nil {
		return nil
	}
	from, to := today.Format("2006-01-02"), today.AddDate(0, 0, 6).Format("2006-01-02")
	type item struct{ date, line string }
	var items []item
	for _, r := range recs {
		next, _ := r.Data["next_date"].(string)
		paused, _ := r.Data["paused"].(bool)
		typ, _ := r.Data["type"].(string)
		if next == "" || paused || next < from || next > to || typ == "income" {
			continue
		}
		name, _ := r.Data["name"].(string)
		if name == "" {
			name, _ = r.Data["payee"].(string)
		}
		if name == "" {
			name = "A recurring payment"
		}
		amount, _ := r.Data["amount"].(int64)
		t, _ := time.Parse("2006-01-02", next)
		items = append(items, item{next, fmt.Sprintf("%s: %s (%s)", t.Format("Mon 2 Jan"), name, Money(amount, l.BaseCurrency))})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].date < items[j].date })
	out := make([]string, 0, len(items))
	for i, it := range items {
		if i == 8 {
			out = append(out, fmt.Sprintf("and %d more", len(items)-8))
			break
		}
		out = append(out, it.line)
	}
	return out
}
