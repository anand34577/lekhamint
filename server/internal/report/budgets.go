package report

import (
	"context"
	"encoding/json"
	"time"
)

// MaxRolloverPeriods bounds how far back a rollover budget carries
// unspent (or overspent) amounts. Same as the Android app.
const MaxRolloverPeriods = 24

// BudgetProgress is one budget's state on a given day.
type BudgetProgress struct {
	BudgetID string `json:"budget_id"`
	Active   bool   `json:"active"` // false: not started yet, or ended
	Window   Window `json:"window"`
	Amount   int64  `json:"amount"` // the budget's own amount per period
	Carry    int64  `json:"carry"`  // rolled over from earlier periods (may be negative)
	Limit    int64  `json:"limit"`  // amount + carry
	Spent    int64  `json:"spent"`  // net of refunds
	Currency string `json:"currency"`
	// DaysLeft counts today; PerDayLeft is what can be spent per remaining day.
	DaysLeft     int     `json:"days_left"`
	PerDayLeft   int64   `json:"per_day_left"`
	PaceFraction float64 `json:"pace_fraction"` // share of the period elapsed, 0..1
}

type budgetRow struct {
	id, period, start, end, currency string
	amount                           int64
	rollover                         bool
	categories, accounts             []string
}

// Budgets returns the progress of every live, non-archived budget on date.
// The rules match the apps: spending counts in the budget's currency only,
// category filters include sub-categories, refunds reduce spending, and
// rollover carries up to MaxRolloverPeriods earlier periods.
func (s *Service) Budgets(ctx context.Context, ledgerID, date string) ([]BudgetProgress, error) {
	today, ok := parseDate(date)
	if !ok {
		today, _ = parseDate(time.Now().Format(dateLayout))
	}
	budgets, err := s.loadBudgets(ctx, ledgerID)
	if err != nil {
		return nil, err
	}
	children, err := s.childCategories(ctx, ledgerID)
	if err != nil {
		return nil, err
	}

	// One query covers every budget's history.
	type plan struct {
		b       budgetRow
		w       Window
		index   int
		active  bool
		history []Window
	}
	plans := make([]plan, 0, len(budgets))
	from, to := "", ""
	widen := func(w Window) {
		if from == "" || w.Start < from {
			from = w.Start
		}
		if w.End > to {
			to = w.End
		}
	}
	for _, b := range budgets {
		w, i, active := budgetWindow(b.period, b.start, b.end, today)
		p := plan{b: b, w: w, index: i, active: active}
		if active {
			widen(w)
			if b.rollover && b.period != "custom" {
				start, _ := parseDate(b.start)
				for k := max(0, i-MaxRolloverPeriods); k < i; k++ {
					hs := periodStart(start, b.period, k)
					he := periodStart(start, b.period, k+1).AddDate(0, 0, -1)
					hw := Window{formatDate(hs), formatDate(he)}
					p.history = append(p.history, hw)
					widen(hw)
				}
			}
		}
		plans = append(plans, p)
	}
	var lines []Line
	if from != "" {
		if lines, err = s.Lines(ctx, ledgerID, from, to); err != nil {
			return nil, err
		}
	}

	out := make([]BudgetProgress, 0, len(plans))
	for _, p := range plans {
		bp := BudgetProgress{BudgetID: p.b.id, Active: p.active, Amount: p.b.amount, Currency: p.b.currency}
		if !p.active {
			out = append(out, bp)
			continue
		}
		cats := expandCategories(p.b.categories, children)
		accs := toSet(p.b.accounts)
		for _, hw := range p.history {
			bp.Carry += p.b.amount - spentIn(lines, hw, p.b.currency, cats, accs)
		}
		bp.Window = p.w
		bp.Limit = p.b.amount + bp.Carry
		bp.Spent = spentIn(lines, p.w, p.b.currency, cats, accs)
		ws, _ := parseDate(p.w.Start)
		we, _ := parseDate(p.w.End)
		total := int(we.Sub(ws).Hours()/24) + 1
		switch {
		case today.After(we):
			bp.DaysLeft, bp.PaceFraction = 0, 1
		case today.Before(ws):
			bp.DaysLeft, bp.PaceFraction = total, 0
		default:
			bp.DaysLeft = int(we.Sub(today).Hours()/24) + 1
			bp.PaceFraction = float64(int(today.Sub(ws).Hours()/24)+1) / float64(total)
		}
		if remaining := bp.Limit - bp.Spent; bp.DaysLeft > 0 && remaining > 0 {
			bp.PerDayLeft = remaining / int64(bp.DaysLeft)
		}
		out = append(out, bp)
	}
	return out, nil
}

// spentIn is net spending in w: expense lines add, refunds subtract.
// Lines in income categories never count against a budget.
func spentIn(lines []Line, w Window, currency string, cats, accs map[string]bool) int64 {
	var total int64
	for _, l := range lines {
		if l.Date < w.Start || l.Date > w.End || l.Currency != currency || l.Bucket() != "expense" {
			continue
		}
		if len(accs) > 0 && !accs[l.AccountID] {
			continue
		}
		if len(cats) > 0 && !cats[l.CategoryID] {
			continue
		}
		total += l.Signed()
	}
	return total
}

func (s *Service) loadBudgets(ctx context.Context, ledgerID string) ([]budgetRow, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id, COALESCE(period, 'monthly'), COALESCE(start_date, ''), COALESCE(end_date, ''),
		COALESCE(currency, 'XXX'), COALESCE(amount, 0), COALESCE(rollover, 0), COALESCE(category_ids, ''), COALESCE(account_ids, '')
		FROM budgets WHERE ledger_id = ? AND deleted = 0 AND COALESCE(archived, 0) = 0 ORDER BY name, id`, ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []budgetRow
	for rows.Next() {
		var (
			b          budgetRow
			rollover   int64
			cats, accs string
		)
		if err := rows.Scan(&b.id, &b.period, &b.start, &b.end, &b.currency, &b.amount, &rollover, &cats, &accs); err != nil {
			return nil, err
		}
		b.rollover = rollover != 0
		b.categories, b.accounts = idList(cats), idList(accs)
		out = append(out, b)
	}
	return out, rows.Err()
}

// childCategories maps each category to its live sub-categories.
func (s *Service) childCategories(ctx context.Context, ledgerID string) (map[string][]string, error) {
	rows, err := s.DB.QueryContext(ctx,
		`SELECT id, parent_id FROM categories WHERE ledger_id = ? AND deleted = 0 AND parent_id IS NOT NULL`, ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]string{}
	for rows.Next() {
		var id, parent string
		if err := rows.Scan(&id, &parent); err != nil {
			return nil, err
		}
		out[parent] = append(out[parent], id)
	}
	return out, rows.Err()
}

func expandCategories(ids []string, children map[string][]string) map[string]bool {
	if len(ids) == 0 {
		return nil
	}
	set := make(map[string]bool, len(ids))
	for _, id := range ids {
		set[id] = true
		for _, c := range children[id] {
			set[c] = true
		}
	}
	return set
}

func toSet(ids []string) map[string]bool {
	if len(ids) == 0 {
		return nil
	}
	set := make(map[string]bool, len(ids))
	for _, id := range ids {
		set[id] = true
	}
	return set
}

func idList(s string) []string {
	var out []string
	if s != "" {
		_ = json.Unmarshal([]byte(s), &out)
	}
	return out
}
