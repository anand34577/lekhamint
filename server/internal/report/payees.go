package report

import (
	"context"
	"sort"
	"strings"
)

// PayeeTotal is net spending and income with one payee in one currency.
type PayeeTotal struct {
	Payee    string `json:"payee"` // the most used spelling
	Currency string `json:"currency"`
	Expense  int64  `json:"expense"` // net of refunds
	Income   int64  `json:"income"`
	Count    int    `json:"count"`
	LastDate string `json:"last_date"`
}

// Payees totals reportable transactions between from and to by payee.
// Payees are matched case-insensitively with surrounding spaces ignored, so
// "Swiggy" and "swiggy " are one payee. A split transaction counts under its
// payee once, with its type deciding the bucket. At most limit rows are
// returned, biggest spending first (limit <= 0 means all).
func (s *Service) Payees(ctx context.Context, ledgerID, from, to string, limit int) ([]PayeeTotal, error) {
	q := `SELECT t.payee, t.type, t.amount, COALESCE(a.currency, 'XXX'), COALESCE(c.kind, ''), t.local_date
		FROM transactions t
		LEFT JOIN accounts a ON a.ledger_id = t.ledger_id AND a.id = t.account_id
		LEFT JOIN categories c ON c.ledger_id = t.ledger_id AND c.id = t.category_id AND c.deleted = 0
		WHERE ` + reportable + ` AND t.payee IS NOT NULL AND t.payee <> ''`
	rows, err := s.DB.QueryContext(ctx, q, ledgerID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type key struct{ payee, cur string }
	acc := map[key]*PayeeTotal{}
	spellings := map[key]map[string]int{}
	for rows.Next() {
		var payee, cur, date string
		var l Line
		if err := rows.Scan(&payee, &l.Type, &l.Amount, &cur, &l.CategoryKind, &date); err != nil {
			return nil, err
		}
		name := strings.TrimSpace(payee)
		if name == "" {
			continue
		}
		k := key{strings.ToLower(name), cur}
		p := acc[k]
		if p == nil {
			p = &PayeeTotal{Currency: cur}
			acc[k] = p
			spellings[k] = map[string]int{}
		}
		spellings[k][name]++
		if l.Bucket() == "income" {
			p.Income += l.Signed()
		} else {
			p.Expense += l.Signed()
		}
		p.Count++
		if date > p.LastDate {
			p.LastDate = date
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]PayeeTotal, 0, len(acc))
	for k, p := range acc {
		best, n := "", -1
		for name, c := range spellings[k] {
			if c > n || (c == n && name < best) {
				best, n = name, c
			}
		}
		p.Payee = best
		out = append(out, *p)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Expense != out[j].Expense {
			return out[i].Expense > out[j].Expense
		}
		if out[i].Income != out[j].Income {
			return out[i].Income > out[j].Income
		}
		if out[i].Payee != out[j].Payee {
			return out[i].Payee < out[j].Payee
		}
		return out[i].Currency < out[j].Currency
	})
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
