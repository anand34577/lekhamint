// Package report computes balances and spending summaries straight from the
// transaction rows. Nothing here is stored, so reports can never drift from
// the data. Rules follow docs/02-expense-domain.md.
package report

import (
	"context"
	"sort"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// reportable is the shared filter for spending/income reports: live,
// non-planned income and expense rows that are not adjustments, user
// exclusions or debt movements.
const reportable = `t.ledger_id = ? AND t.deleted = 0 AND t.type IN ('expense', 'income')
	AND COALESCE(t.status, 'cleared') <> 'planned' AND COALESCE(t.is_adjustment, 0) = 0
	AND COALESCE(t.excluded_from_reports, 0) = 0 AND t.debt_id IS NULL
	AND t.local_date >= ? AND t.local_date <= ?`

// Service runs report queries.
type Service struct{ DB *db.DB }

// Balance is one account's computed balance.
type Balance struct {
	AccountID         string `json:"account_id"`
	Name              string `json:"name"`
	Type              string `json:"type"`
	Currency          string `json:"currency"`
	Archived          bool   `json:"archived"`
	IncludeInNetWorth bool   `json:"include_in_net_worth"`
	OpeningBalance    int64  `json:"opening_balance"`
	Balance           int64  `json:"balance"`
}

// Balances returns every live account's balance as of asOf (YYYY-MM-DD;
// empty means all time). Planned transactions are not counted.
func (s *Service) Balances(ctx context.Context, ledgerID, asOf string) ([]Balance, error) {
	if asOf == "" {
		asOf = "9999-12-31"
	}
	const live = `t.ledger_id = a.ledger_id AND t.deleted = 0 AND COALESCE(t.status, 'cleared') <> 'planned' AND t.local_date <= ?`
	q := `SELECT a.id, COALESCE(a.name, ''), COALESCE(a.type, 'other'), COALESCE(a.currency, 'XXX'),
		COALESCE(a.archived, 0), COALESCE(a.include_in_net_worth, 1), COALESCE(a.opening_balance, 0),
		CAST(COALESCE((SELECT SUM(CASE t.type WHEN 'income' THEN t.amount ELSE -t.amount END)
			FROM transactions t WHERE ` + live + ` AND t.account_id = a.id), 0) AS BIGINT),
		CAST(COALESCE((SELECT SUM(t.to_amount)
			FROM transactions t WHERE ` + live + ` AND t.type = 'transfer' AND t.to_account_id = a.id), 0) AS BIGINT)
		FROM accounts a WHERE a.ledger_id = ? AND a.deleted = 0
		ORDER BY COALESCE(a.sort_order, 0), a.name`
	rows, err := s.DB.QueryContext(ctx, q, asOf, asOf, ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Balance{}
	for rows.Next() {
		var (
			b              Balance
			archived, nw   int64
			own, transfers int64
		)
		if err := rows.Scan(&b.AccountID, &b.Name, &b.Type, &b.Currency, &archived, &nw, &b.OpeningBalance, &own, &transfers); err != nil {
			return nil, err
		}
		b.Archived, b.IncludeInNetWorth = archived != 0, nw != 0
		b.Balance = b.OpeningBalance + own + transfers
		out = append(out, b)
	}
	return out, rows.Err()
}

// CategoryTotal is net spending (or income) in one category.
type CategoryTotal struct {
	CategoryID *string `json:"category_id"` // nil = uncategorised
	ParentID   *string `json:"parent_id"`
	Name       string  `json:"name"`
	Amount     int64   `json:"amount"`
	Count      int64   `json:"count"`
}

// CurrencySummary holds totals in one currency. Currencies are never mixed
// without an explicit exchange rate.
type CurrencySummary struct {
	Currency     string          `json:"currency"`
	Income       int64           `json:"income"`
	Expense      int64           `json:"expense"`
	Net          int64           `json:"net"`
	ExpenseByCat []CategoryTotal `json:"expense_by_category"`
	IncomeByCat  []CategoryTotal `json:"income_by_category"`
}

// Summary returns income, expense and per-category totals between from and
// to (inclusive, YYYY-MM-DD). Splits replace their parent's category when
// they add up to the transaction amount. Income booked to an expense
// category is a refund and reduces that category's spending.
func (s *Service) Summary(ctx context.Context, ledgerID, from, to string) ([]CurrencySummary, error) {
	q := `WITH base AS (
			SELECT t.id, t.type, t.amount, t.category_id, COALESCE(a.currency, 'XXX') AS currency
			FROM transactions t
			LEFT JOIN accounts a ON a.ledger_id = t.ledger_id AND a.id = t.account_id
			WHERE ` + reportable + `
		), st AS (
			SELECT s.transaction_id, SUM(s.amount) AS total FROM transaction_splits s
			WHERE s.ledger_id = ? AND s.deleted = 0 GROUP BY s.transaction_id
		), lines AS (
			SELECT b.type, b.currency, s.category_id, s.amount
			FROM base b
			JOIN st ON st.transaction_id = b.id AND st.total = b.amount
			JOIN transaction_splits s ON s.ledger_id = ? AND s.transaction_id = b.id AND s.deleted = 0
			UNION ALL
			SELECT b.type, b.currency, b.category_id, b.amount
			FROM base b LEFT JOIN st ON st.transaction_id = b.id
			WHERE st.total IS NULL OR st.total <> b.amount
		)
		SELECT l.currency, l.category_id, l.type, c.kind, c.parent_id, COALESCE(c.name, ''),
			CAST(SUM(l.amount) AS BIGINT), COUNT(*)
		FROM lines l
		LEFT JOIN categories c ON c.ledger_id = ? AND c.id = l.category_id AND c.deleted = 0
		GROUP BY l.currency, l.category_id, l.type, c.kind, c.parent_id, c.name`
	rows, err := s.DB.QueryContext(ctx, q, ledgerID, from, to, ledgerID, ledgerID, ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	type key struct{ cur, cat string }
	byCur := map[string]*CurrencySummary{}
	expCats := map[key]*CategoryTotal{}
	incCats := map[key]*CategoryTotal{}
	for rows.Next() {
		var (
			cur, typ, name   string
			catID, kind, pid *string
			sum, count       int64
		)
		if err := rows.Scan(&cur, &catID, &typ, &kind, &pid, &name, &sum, &count); err != nil {
			return nil, err
		}
		cs := byCur[cur]
		if cs == nil {
			cs = &CurrencySummary{Currency: cur}
			byCur[cur] = cs
		}
		// The bucket is the category's kind; uncategorised rows use the type.
		bucket := typ
		if kind != nil {
			bucket = *kind
		}
		signed := sum
		if typ != bucket { // e.g. income in an expense category (refund)
			signed = -sum
		}
		k := key{cur, deref(catID)}
		cats := expCats
		if bucket == "income" {
			cs.Income += signed
			cats = incCats
		} else {
			cs.Expense += signed
		}
		ct := cats[k]
		if ct == nil {
			ct = &CategoryTotal{CategoryID: catID, ParentID: pid, Name: name}
			cats[k] = ct
		}
		ct.Amount += signed
		ct.Count += count
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	out := make([]CurrencySummary, 0, len(byCur))
	for cur, cs := range byCur {
		cs.Net = cs.Income - cs.Expense
		cs.ExpenseByCat = collect(expCats, func(k key) bool { return k.cur == cur })
		cs.IncomeByCat = collect(incCats, func(k key) bool { return k.cur == cur })
		out = append(out, *cs)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Currency < out[j].Currency })
	return out, nil
}

func collect[K comparable](m map[K]*CategoryTotal, keep func(K) bool) []CategoryTotal {
	out := []CategoryTotal{}
	for k, v := range m {
		if keep(k) {
			out = append(out, *v)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Amount != out[j].Amount {
			return out[i].Amount > out[j].Amount
		}
		return out[i].Name < out[j].Name
	})
	return out
}

// TrendPoint is income and expense for one period bucket.
type TrendPoint struct {
	Period   string `json:"period"` // YYYY-MM-DD or YYYY-MM
	Currency string `json:"currency"`
	Income   int64  `json:"income"`
	Expense  int64  `json:"expense"`
}

// Trend returns income and expense per day or month between from and to.
func (s *Service) Trend(ctx context.Context, ledgerID, from, to, interval string) ([]TrendPoint, error) {
	width := 10
	if interval == "month" {
		width = 7
	}
	// Bucket by category kind so refunds reduce spending, as in Summary.
	q := `SELECT SUBSTR(t.local_date, 1, ?) AS period, COALESCE(a.currency, 'XXX') AS currency,
		CAST(COALESCE(SUM(CASE WHEN COALESCE(c.kind, t.type) = 'income'
			THEN CASE WHEN t.type = 'income' THEN t.amount ELSE -t.amount END ELSE 0 END), 0) AS BIGINT),
		CAST(COALESCE(SUM(CASE WHEN COALESCE(c.kind, t.type) = 'expense'
			THEN CASE WHEN t.type = 'expense' THEN t.amount ELSE -t.amount END ELSE 0 END), 0) AS BIGINT)
		FROM transactions t
		LEFT JOIN accounts a ON a.ledger_id = t.ledger_id AND a.id = t.account_id
		LEFT JOIN categories c ON c.ledger_id = t.ledger_id AND c.id = t.category_id AND c.deleted = 0
		WHERE ` + reportable + `
		GROUP BY 1, 2
		ORDER BY period, currency`
	rows, err := s.DB.QueryContext(ctx, q, width, ledgerID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []TrendPoint{}
	for rows.Next() {
		var p TrendPoint
		if err := rows.Scan(&p.Period, &p.Currency, &p.Income, &p.Expense); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
