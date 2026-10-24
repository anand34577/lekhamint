package report

import (
	"context"
	"sort"
)

// TagTotal is net spending and income for one tag in one currency.
type TagTotal struct {
	TagID    string `json:"tag_id"`
	Currency string `json:"currency"`
	Expense  int64  `json:"expense"` // net of refunds
	Income   int64  `json:"income"`
	Count    int    `json:"count"` // transactions
}

// Tags totals every tagged transaction between from and to. A transaction
// with several tags counts fully under each of them, so tag totals can add
// up to more than the period's spending.
func (s *Service) Tags(ctx context.Context, ledgerID, from, to string) ([]TagTotal, error) {
	lines, err := s.Lines(ctx, ledgerID, from, to)
	if err != nil {
		return nil, err
	}
	type key struct{ tag, cur string }
	acc := map[key]*TagTotal{}
	seen := map[key]map[string]bool{}
	for _, l := range lines {
		for _, tag := range l.TagIDs {
			k := key{tag, l.Currency}
			t := acc[k]
			if t == nil {
				t = &TagTotal{TagID: tag, Currency: l.Currency}
				acc[k] = t
				seen[k] = map[string]bool{}
			}
			if l.Bucket() == "income" {
				t.Income += l.Signed()
			} else {
				t.Expense += l.Signed()
			}
			if !seen[k][l.TxID] {
				seen[k][l.TxID] = true
				t.Count++
			}
		}
	}
	out := make([]TagTotal, 0, len(acc))
	for _, t := range acc {
		out = append(out, *t)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Expense != out[j].Expense {
			return out[i].Expense > out[j].Expense
		}
		if out[i].TagID != out[j].TagID {
			return out[i].TagID < out[j].TagID
		}
		return out[i].Currency < out[j].Currency
	})
	return out, nil
}
