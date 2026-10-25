package report

import (
	"context"
	"sort"
	"time"
)

// Position is the money picture in one currency, split the way people think
// about it (docs/02, "Net worth"). Every figure is in minor units.
//
//	NetWorth = Cash + Investments + Receivable − Liabilities − Payable
//
// Credit limits are never assets, borrowed money is a liability and not
// income, and money lent to someone is still yours until it is written off.
type Position struct {
	Currency    string `json:"currency"`
	Cash        int64  `json:"cash"`        // cash, bank, wallet, savings and other accounts (can be negative when overdrawn)
	Investments int64  `json:"investments"` // investment and deposit accounts
	Liabilities int64  `json:"liabilities"` // owed on credit cards and loan accounts, shown positive
	Receivable  int64  `json:"receivable"`  // open debts others owe you
	Payable     int64  `json:"payable"`     // open debts you owe others
	NetWorth    int64  `json:"net_worth"`
	// Accounts left out (archived or excluded from net worth) that still hold money.
	ExcludedAccounts int   `json:"excluded_accounts"`
	ExcludedBalance  int64 `json:"excluded_balance"`
}

// DebtOutstanding is what is still owed on one personal debt.
type DebtOutstanding struct {
	DebtID    string `json:"debt_id"`
	Person    string `json:"person"`
	Direction string `json:"direction"`
	Currency  string `json:"currency"`
	Amount    int64  `json:"amount"`
	Repaid    int64  `json:"repaid"`
	// Outstanding is amount − repaid; negative means it was overpaid.
	Outstanding int64  `json:"outstanding"`
	Closed      bool   `json:"closed"`
	Start       string `json:"start"` // date of the first linked movement, else the record's creation date
}

// IsLiabilityType reports whether an account type holds money owed.
func IsLiabilityType(t string) bool { return t == "credit_card" || t == "loan" }

// IsInvestmentType reports whether an account type holds invested money.
func IsInvestmentType(t string) bool { return t == "investment" || t == "deposit" }

// Debts returns every live debt with its repayments as of asOf (YYYY-MM-DD,
// empty = all time). A repayment is money moving back: income on a loan you
// gave, an expense on one you took. Interest and fees are not repayments.
func (s *Service) Debts(ctx context.Context, ledgerID, asOf string) ([]DebtOutstanding, error) {
	if asOf == "" {
		asOf = "9999-12-31"
	}
	q := `SELECT d.id, COALESCE(d.person, ''), COALESCE(d.direction, 'lent'), COALESCE(d.currency, 'XXX'),
			COALESCE(d.amount, 0), COALESCE(d.closed, 0), COALESCE(d.created_at, 0),
			CAST(COALESCE((SELECT SUM(t.amount) FROM transactions t
				WHERE t.ledger_id = d.ledger_id AND t.debt_id = d.id AND t.deleted = 0 AND t.local_date <= ?
				AND COALESCE(t.part, 'principal') IN ('principal', 'prepayment')
				AND t.type = CASE WHEN d.direction = 'lent' THEN 'income' ELSE 'expense' END), 0) AS BIGINT),
			(SELECT MIN(t.local_date) FROM transactions t
				WHERE t.ledger_id = d.ledger_id AND t.debt_id = d.id AND t.deleted = 0)
		FROM debts d WHERE d.ledger_id = ? AND d.deleted = 0`
	rows, err := s.DB.QueryContext(ctx, q, asOf, ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []DebtOutstanding{}
	for rows.Next() {
		var (
			d       DebtOutstanding
			closed  int64
			created int64
			first   *string
		)
		if err := rows.Scan(&d.DebtID, &d.Person, &d.Direction, &d.Currency, &d.Amount, &closed, &created, &d.Repaid, &first); err != nil {
			return nil, err
		}
		d.Closed = closed != 0
		d.Outstanding = d.Amount - d.Repaid
		if first != nil && *first != "" {
			d.Start = *first
		} else if created > 0 {
			d.Start = time.UnixMilli(created).UTC().Format("2006-01-02")
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// Position returns the money picture per currency as of asOf (empty = now).
func (s *Service) Position(ctx context.Context, ledgerID, asOf string) ([]Position, error) {
	balances, err := s.Balances(ctx, ledgerID, asOf)
	if err != nil {
		return nil, err
	}
	debts, err := s.Debts(ctx, ledgerID, asOf)
	if err != nil {
		return nil, err
	}
	return combine(balances, debts, asOf), nil
}

func combine(balances []Balance, debts []DebtOutstanding, asOf string) []Position {
	by := map[string]*Position{}
	get := func(cur string) *Position {
		p := by[cur]
		if p == nil {
			p = &Position{Currency: cur}
			by[cur] = p
		}
		return p
	}
	for _, b := range balances {
		if b.Archived || !b.IncludeInNetWorth {
			if b.Balance != 0 {
				p := get(b.Currency)
				p.ExcludedAccounts++
				p.ExcludedBalance += b.Balance
			}
			continue
		}
		p := get(b.Currency)
		switch {
		case IsLiabilityType(b.Type):
			p.Liabilities -= b.Balance // a card at −₹5,000 means ₹5,000 owed
		case IsInvestmentType(b.Type):
			p.Investments += b.Balance
		default:
			p.Cash += b.Balance
		}
	}
	for _, d := range debts {
		// Settled debts are done, and a debt that had not started by asOf did not exist yet.
		if d.Closed || d.Outstanding == 0 || (asOf != "" && d.Start != "" && d.Start > asOf) {
			continue
		}
		p := get(d.Currency)
		owedToMe := (d.Direction == "lent") == (d.Outstanding > 0) // an overpaid loan flips sides
		amt := d.Outstanding
		if amt < 0 {
			amt = -amt
		}
		if owedToMe {
			p.Receivable += amt
		} else {
			p.Payable += amt
		}
	}
	out := make([]Position, 0, len(by))
	for _, p := range by {
		p.NetWorth = p.Cash + p.Investments + p.Receivable - p.Liabilities - p.Payable
		out = append(out, *p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Currency < out[j].Currency })
	return out
}

// NetWorthPoint is the position at the end of one month.
type NetWorthPoint struct {
	Date      string     `json:"date"` // last day of the month, or today for the current month
	Positions []Position `json:"positions"`
}

// NetWorthHistory returns month-end positions for the last n months,
// ending with today.
func (s *Service) NetWorthHistory(ctx context.Context, ledgerID string, today time.Time, months int) ([]NetWorthPoint, error) {
	if months < 1 {
		months = 1
	}
	first := time.Date(today.Year(), today.Month(), 1, 0, 0, 0, 0, time.UTC)
	out := make([]NetWorthPoint, 0, months)
	for i := months - 1; i >= 0; i-- {
		end := first.AddDate(0, -i+1, -1) // last day of that month
		if i == 0 {
			end = today
		}
		day := formatDate(end)
		pos, err := s.Position(ctx, ledgerID, day)
		if err != nil {
			return nil, err
		}
		out = append(out, NetWorthPoint{Date: day, Positions: pos})
	}
	return out, nil
}
