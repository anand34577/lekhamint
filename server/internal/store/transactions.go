package store

import (
	"context"
	"encoding/base64"
	"errors"
	"strconv"
	"strings"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
)

// TxFilter narrows a transaction listing. Empty fields are ignored.
type TxFilter struct {
	From, To   string // local_date range, inclusive
	AccountID  string // matches source or destination account
	CategoryID string // matches the category and its sub-categories; "none" = uncategorised
	TagID      string
	DebtID     string // a debt ID, or "any"
	LoanID     string // a loan ID, or "any"
	DepositID  string // a deposit ID, or "any"
	MinAmount  int64  // inclusive, 0 = no bound
	MaxAmount  int64  // inclusive, 0 = no bound
	Type       string
	Status     string
	Query      string // case-insensitive match on payee, note or category name
	// QueryAmount, when positive, also matches transactions of exactly this amount (minor
	// units): Query was a number such as "486" typed from a bank statement.
	QueryAmount int64
	Deleted     bool   // true lists the Trash (tombstones) instead
	Cursor      string // opaque cursor from a previous page
	Limit       int
}

// UncategorisedFilter as TxFilter.CategoryID lists transactions that still need a category.
const UncategorisedFilter = "none"

// AnyDebtFilter as TxFilter.DebtID lists every loan and repayment.
const AnyDebtFilter = "any"

// ErrBadCursor is returned for an unparseable cursor.
var ErrBadCursor = errors.New("invalid cursor")

// ListTransactions returns transactions newest first, keyset-paginated on
// (local_date, occurred_at, id) so deep pages stay fast on large ledgers.
func ListTransactions(ctx context.Context, q db.Querier, ledgerID string, f TxFilter) (out []Record, next string, err error) {
	s := entity.Transactions
	var (
		where = []string{"t.ledger_id = ?", "t.deleted = ?"}
		args  = []any{ledgerID, boolInt(f.Deleted)}
	)
	add := func(cond string, a ...any) { where = append(where, cond); args = append(args, a...) }
	if f.From != "" {
		add("t.local_date >= ?", f.From)
	}
	if f.To != "" {
		add("t.local_date <= ?", f.To)
	}
	if f.AccountID != "" {
		add("(t.account_id = ? OR t.to_account_id = ?)", f.AccountID, f.AccountID)
	}
	if f.CategoryID == UncategorisedFilter {
		// Income and expenses with no category that are not split, adjustments or debt movements.
		add("t.type <> 'transfer' AND t.category_id IS NULL AND COALESCE(t.is_adjustment, 0) = 0 AND (t.debt_id IS NULL OR t.part IN ('interest', 'fee'))" +
			" AND NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.ledger_id = t.ledger_id AND s.transaction_id = t.id AND s.deleted = 0)")
	} else if f.CategoryID != "" {
		add("(t.category_id = ? OR t.category_id IN (SELECT c.id FROM categories c WHERE c.ledger_id = ? AND c.parent_id = ?))",
			f.CategoryID, ledgerID, f.CategoryID)
	}
	if f.TagID != "" {
		// tag_ids is a JSON array of UUIDs; a quoted UUID cannot match by accident.
		add("t.tag_ids LIKE ?", `%"`+f.TagID+`"%`)
	}
	if f.DebtID == AnyDebtFilter {
		add("t.debt_id IS NOT NULL")
	} else if f.DebtID != "" {
		add("t.debt_id = ?", f.DebtID)
	}
	for _, l := range []struct{ col, v string }{{"t.loan_id", f.LoanID}, {"t.deposit_id", f.DepositID}} {
		if l.v == AnyDebtFilter {
			add(l.col + " IS NOT NULL")
		} else if l.v != "" {
			add(l.col+" = ?", l.v)
		}
	}
	if f.MinAmount > 0 {
		add("t.amount >= ?", f.MinAmount)
	}
	if f.MaxAmount > 0 {
		add("t.amount <= ?", f.MaxAmount)
	}
	if f.Type != "" {
		add("t.type = ?", f.Type)
	}
	if f.Status != "" {
		add("COALESCE(t.status, 'cleared') = ?", f.Status)
	}
	if f.Query != "" {
		pat := "%" + escapeLike(strings.ToLower(f.Query)) + "%"
		cond := `(LOWER(t.payee) LIKE ? ESCAPE '\' OR LOWER(t.note) LIKE ? ESCAPE '\'` +
			` OR t.category_id IN (SELECT c.id FROM categories c WHERE c.ledger_id = t.ledger_id AND c.deleted = 0 AND LOWER(c.name) LIKE ? ESCAPE '\')`
		qa := []any{pat, pat, pat}
		if f.QueryAmount > 0 {
			cond += " OR t.amount = ? OR t.to_amount = ?"
			qa = append(qa, f.QueryAmount, f.QueryAmount)
		}
		add(cond+")", qa...)
	}
	if f.Cursor != "" {
		date, at, id, err := decodeCursor(f.Cursor)
		if err != nil {
			return nil, "", err
		}
		add("(t.local_date < ? OR (t.local_date = ? AND (t.occurred_at < ? OR (t.occurred_at = ? AND t.id < ?))))",
			date, date, at, at, id)
	}
	cols := make([]string, 0, len(s.Fields)+4)
	for _, c := range append([]string{"id", "hlc", "seq", "deleted"}, s.Columns()...) {
		cols = append(cols, "t."+c)
	}
	query := "SELECT " + strings.Join(cols, ", ") + " FROM transactions t WHERE " + strings.Join(where, " AND ") +
		" ORDER BY t.local_date DESC, t.occurred_at DESC, t.id DESC LIMIT ?"
	args = append(args, f.Limit+1)

	rows, err := q.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, "", err
	}
	defer rows.Close()
	for rows.Next() {
		r, err := scanRecord(s, rows)
		if err != nil {
			return nil, "", err
		}
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, "", err
	}
	if len(out) > f.Limit {
		out = out[:f.Limit]
		last := out[len(out)-1]
		date, _ := last.Data["local_date"].(string)
		at, _ := last.Data["occurred_at"].(int64)
		next = encodeCursor(date, at, last.ID)
	}
	if out == nil {
		out = []Record{}
	}
	return out, next, nil
}

func encodeCursor(date string, at int64, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(date + "|" + strconv.FormatInt(at, 10) + "|" + id))
}

func decodeCursor(c string) (string, int64, string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return "", 0, "", ErrBadCursor
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 3 {
		return "", 0, "", ErrBadCursor
	}
	at, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return "", 0, "", ErrBadCursor
	}
	return parts[0], at, parts[2], nil
}

func escapeLike(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s)
}
