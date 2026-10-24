package report

import (
	"context"
	"math/big"
	"sort"

	"github.com/kosh-app/kosh/server/internal/db"
)

// Digits is the number of minor-unit decimals the apps use for a currency
// (web/src/lib/money.ts, android/.../core/Money.kt). Unknown codes use 2.
func Digits(currency string) int {
	switch currency {
	case "JPY", "VND", "KRW":
		return 0
	case "KWD", "BHD", "OMR":
		return 3
	case "BTC":
		return 8
	}
	return 2
}

// Rate is the newest exchange rate for converting one currency into another.
type Rate struct {
	From          string `json:"from"`
	To            string `json:"to"`
	Rate          string `json:"rate"` // decimal: 1 From = Rate To
	EffectiveDate string `json:"effective_date"`
	Inverted      bool   `json:"inverted"` // derived from a To→From rate
}

// Converted holds income and expense across currencies in one currency.
// Currencies without a rate are listed in Missing and left out of the totals,
// so a missing rate never silently counts as 1:1.
type Converted struct {
	Currency string   `json:"currency"`
	Income   int64    `json:"income"`
	Expense  int64    `json:"expense"`
	Net      int64    `json:"net"`
	Rates    []Rate   `json:"rates"`
	Missing  []string `json:"missing_rates"`
}

// Convert totals sums in target using the newest rates effective on or
// before asOf. Results are rounded half away from zero to target's precision.
func (s *Service) Convert(ctx context.Context, ledgerID, target, asOf string, sums []CurrencySummary) (Converted, error) {
	out := Converted{Currency: target, Rates: []Rate{}, Missing: []string{}}
	for _, cs := range sums {
		if cs.Currency == target {
			out.Income += cs.Income
			out.Expense += cs.Expense
			continue
		}
		rate, ok, err := s.FindRate(ctx, ledgerID, cs.Currency, target, asOf)
		if err != nil {
			return out, err
		}
		if !ok {
			out.Missing = append(out.Missing, cs.Currency)
			continue
		}
		r, _ := new(big.Rat).SetString(rate.Rate)
		if rate.Inverted {
			r.Inv(r)
		}
		out.Income += ConvertMinor(cs.Income, cs.Currency, target, r)
		out.Expense += ConvertMinor(cs.Expense, cs.Currency, target, r)
		out.Rates = append(out.Rates, rate)
	}
	out.Net = out.Income - out.Expense
	sort.Strings(out.Missing)
	return out, nil
}

// ConvertMinor converts minor units of from into minor units of to at rate
// (1 from = rate to), exactly, rounding half away from zero.
func ConvertMinor(amount int64, from, to string, rate *big.Rat) int64 {
	v := new(big.Rat).SetInt64(amount)
	v.Mul(v, rate)
	shift := Digits(to) - Digits(from)
	pow := new(big.Rat).SetInt(new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(abs(shift))), nil))
	if shift >= 0 {
		v.Mul(v, pow)
	} else {
		v.Quo(v, pow)
	}
	// Round half away from zero: trunc((2v + sign) / 2).
	num, den := new(big.Int).Set(v.Num()), v.Denom()
	q, m := new(big.Int).QuoRem(num, den, new(big.Int))
	if twice := new(big.Int).Mul(new(big.Int).Abs(m), big.NewInt(2)); twice.Cmp(den) >= 0 {
		if num.Sign() < 0 {
			q.Sub(q, big.NewInt(1))
		} else {
			q.Add(q, big.NewInt(1))
		}
	}
	return q.Int64()
}

// FindRate returns the newest rate from→to effective on or before asOf,
// using the inverse of a to→from rate when that one is newer.
func (s *Service) FindRate(ctx context.Context, ledgerID, from, to, asOf string) (Rate, bool, error) {
	if asOf == "" {
		asOf = "9999-12-31"
	}
	row := s.DB.QueryRowContext(ctx, `SELECT base, quote, rate, effective_date FROM exchange_rates
		WHERE ledger_id = ? AND deleted = 0 AND effective_date <= ?
		  AND ((base = ? AND quote = ?) OR (base = ? AND quote = ?))
		ORDER BY effective_date DESC, CASE WHEN base = ? THEN 0 ELSE 1 END, id DESC LIMIT 1`,
		ledgerID, asOf, from, to, to, from, from)
	var base, quote, rate, date string
	if err := row.Scan(&base, &quote, &rate, &date); err != nil {
		if db.IsNoRows(err) {
			return Rate{}, false, nil
		}
		return Rate{}, false, err
	}
	return Rate{From: from, To: to, Rate: rate, EffectiveDate: date, Inverted: base != from}, true, nil
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}
