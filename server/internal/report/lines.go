package report

import (
	"context"
	"encoding/json"
)

// Line is one reportable amount: a whole transaction, or one split of a
// transaction whose splits add up to its amount. Amount is always positive;
// Type and CategoryKind together decide its sign (see Signed).
type Line struct {
	TxID         string
	Date         string // local_date, YYYY-MM-DD
	Type         string // expense or income
	Currency     string
	AccountID    string
	CategoryID   string // empty = uncategorised
	CategoryKind string // kind of CategoryID, or empty
	Amount       int64
	TagIDs       []string
}

// Bucket is "expense" or "income": the category's kind, or the transaction
// type when the line is uncategorised.
func (l Line) Bucket() string {
	if l.CategoryKind != "" {
		return l.CategoryKind
	}
	return l.Type
}

// Signed is the line's effect on its bucket: income in an expense category
// is a refund and reduces spending, an expense in an income category
// reduces income.
func (l Line) Signed() int64 {
	if l.Type != l.Bucket() {
		return -l.Amount
	}
	return l.Amount
}

// Lines returns every reportable line between from and to (inclusive).
// Exclusions match Summary: transfers, planned rows, adjustments, user
// exclusions and debt movements.
func (s *Service) Lines(ctx context.Context, ledgerID, from, to string) ([]Line, error) {
	q := `WITH base AS (
			SELECT t.id, t.type, t.amount, t.category_id, t.account_id, t.local_date, t.tag_ids,
				COALESCE(a.currency, 'XXX') AS currency
			FROM transactions t
			LEFT JOIN accounts a ON a.ledger_id = t.ledger_id AND a.id = t.account_id
			WHERE ` + reportable + `
		), st AS (
			SELECT s.transaction_id, SUM(s.amount) AS total FROM transaction_splits s
			WHERE s.ledger_id = ? AND s.deleted = 0 GROUP BY s.transaction_id
		), lines AS (
			SELECT b.id, b.local_date, b.type, b.currency, b.account_id, s.category_id, s.amount, b.tag_ids
			FROM base b
			JOIN st ON st.transaction_id = b.id AND st.total = b.amount
			JOIN transaction_splits s ON s.ledger_id = ? AND s.transaction_id = b.id AND s.deleted = 0
			UNION ALL
			SELECT b.id, b.local_date, b.type, b.currency, b.account_id, b.category_id, b.amount, b.tag_ids
			FROM base b LEFT JOIN st ON st.transaction_id = b.id
			WHERE st.total IS NULL OR st.total <> b.amount
		)
		SELECT l.id, l.local_date, l.type, l.currency, COALESCE(l.account_id, ''), COALESCE(l.category_id, ''),
			COALESCE(c.kind, ''), l.amount, COALESCE(l.tag_ids, '')
		FROM lines l
		LEFT JOIN categories c ON c.ledger_id = ? AND c.id = l.category_id AND c.deleted = 0
		ORDER BY l.local_date, l.id`
	rows, err := s.DB.QueryContext(ctx, q, ledgerID, from, to, ledgerID, ledgerID, ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Line
	for rows.Next() {
		var (
			l    Line
			tags string
		)
		if err := rows.Scan(&l.TxID, &l.Date, &l.Type, &l.Currency, &l.AccountID, &l.CategoryID, &l.CategoryKind, &l.Amount, &tags); err != nil {
			return nil, err
		}
		if tags != "" && tags != "[]" {
			_ = json.Unmarshal([]byte(tags), &l.TagIDs)
		}
		out = append(out, l)
	}
	return out, rows.Err()
}
