package entity

import (
	"errors"
)

// Shared value sets. Keep in step with docs/04-data-model.md.
var (
	AccountTypes     = []string{"cash", "bank", "wallet", "credit_card", "savings", "investment", "deposit", "loan", "other"}
	CategoryKinds    = []string{"expense", "income"}
	CategoryNatures  = []string{"need", "want", "savings"}
	TransactionTypes = []string{"expense", "income", "transfer"}
	TxStatuses       = []string{"cleared", "pending", "planned"}
	BudgetPeriods    = []string{"weekly", "monthly", "quarterly", "yearly", "custom"}
	Frequencies      = []string{"daily", "weekly", "monthly", "yearly"}
	RecurringModes   = []string{"auto", "remind"}
	DebtDirections   = []string{"lent", "borrowed"}
	RateSources      = []string{"manual", "imported"}
	RuleMatchFields  = []string{"payee", "note", "any"}
	RuleMatchTypes   = []string{"contains", "equals", "starts_with"}
	// TxParts says what a linked transaction is for (docs/02, "Loans and deposits"):
	// principal moves the balance owed or invested, interest and fee are costs or earnings,
	// prepayment is extra principal paid outside the EMI.
	TxParts         = []string{"principal", "interest", "fee", "prepayment"}
	LoanKinds       = []string{"home", "vehicle", "personal", "education", "gold", "business", "consumer", "other"}
	RateTypes       = []string{"fixed", "floating"}
	DepositKinds    = []string{"fd", "rd", "other"}
	Compoundings    = []string{"simple", "monthly", "quarterly", "half_yearly", "yearly"}
	Payouts         = []string{"cumulative", "monthly", "quarterly", "half_yearly", "yearly"}
	DepositStatuses = []string{"active", "matured", "closed"}
)

// Common bounds.
var (
	zero        = i64(0)
	one         = i64(1)
	maxMoney    = i64(1 << 53) // largest integer a JSON number carries exactly
	minMoney    = i64(-(1 << 53))
	maxTZOffset = i64(14 * 60)
	minTZOffset = i64(-14 * 60)
)

func name(max int) Field { return Field{Name: "name", Kind: KString, Required: true, MaxLen: max} }

func flag(n string, def bool) Field { return Field{Name: n, Kind: KBool, Default: def} }

func createdAt() Field { return Field{Name: "created_at", Kind: KInt, Min: zero} }

func sortOrder() Field { return Field{Name: "sort_order", Kind: KInt, Default: int64(0)} }

func note() Field { return Field{Name: "note", Kind: KText, MaxLen: 2000} }

// Specs, registered in dependency order.
var (
	Accounts = &Spec{
		Name: "accounts",
		Fields: []Field{
			name(100),
			{Name: "type", Kind: KEnum, Required: true, Enum: AccountTypes},
			{Name: "currency", Kind: KCurrency, Required: true},
			{Name: "opening_balance", Kind: KInt, Default: int64(0), Min: minMoney, Max: maxMoney},
			{Name: "credit_limit", Kind: KInt, Min: zero, Max: maxMoney},
			{Name: "statement_day", Kind: KInt, Min: i64(1), Max: i64(31)},
			{Name: "due_day", Kind: KInt, Min: i64(1), Max: i64(31)},
			{Name: "color", Kind: KColor},
			{Name: "icon", Kind: KString, MaxLen: 64},
			flag("include_in_net_worth", true),
			flag("archived", false),
			sortOrder(),
			note(),
			createdAt(),
		},
	}

	Categories = &Spec{
		Name: "categories",
		Fields: []Field{
			name(100),
			{Name: "kind", Kind: KEnum, Required: true, Enum: CategoryKinds},
			{Name: "parent_id", Kind: KRef},
			{Name: "nature", Kind: KEnum, Enum: CategoryNatures},
			{Name: "color", Kind: KColor},
			{Name: "icon", Kind: KString, MaxLen: 64},
			flag("archived", false),
			sortOrder(),
			createdAt(),
		},
	}

	Tags = &Spec{
		Name: "tags",
		Fields: []Field{
			name(50),
			{Name: "color", Kind: KColor},
			flag("archived", false),
			createdAt(),
		},
	}

	Debts = &Spec{
		Name: "debts",
		Fields: []Field{
			{Name: "person", Kind: KString, Required: true, MaxLen: 100},
			{Name: "direction", Kind: KEnum, Required: true, Enum: DebtDirections},
			{Name: "amount", Kind: KInt, Required: true, Min: one, Max: maxMoney},
			{Name: "currency", Kind: KCurrency, Required: true},
			{Name: "due_date", Kind: KDate},
			note(),
			flag("closed", false),
			createdAt(),
		},
	}

	// Loans are bank or institution loans with an EMI schedule. The balance
	// owed lives in a liability account (account_id, type "loan"); EMIs are a
	// principal transfer into that account plus an interest expense, both
	// carrying loan_id. Schedules are calculated, never stored.
	Loans = &Spec{
		Name: "loans",
		Fields: []Field{
			name(100),
			{Name: "lender", Kind: KString, MaxLen: 100},
			{Name: "kind", Kind: KEnum, Default: "personal", Enum: LoanKinds},
			{Name: "account_id", Kind: KRef, Required: true},
			{Name: "currency", Kind: KCurrency, Required: true},
			{Name: "principal", Kind: KInt, Required: true, Min: one, Max: maxMoney},
			{Name: "annual_rate", Kind: KDecimal}, // null = interest-free
			{Name: "rate_type", Kind: KEnum, Default: "fixed", Enum: RateTypes},
			{Name: "start_date", Kind: KDate, Required: true},
			{Name: "first_due_date", Kind: KDate, Required: true},
			{Name: "tenure_months", Kind: KInt, Required: true, Min: one, Max: i64(600)},
			{Name: "emi_amount", Kind: KInt, Min: one, Max: maxMoney},
			{Name: "paid_before", Kind: KInt, Default: int64(0), Min: zero, Max: i64(600)},
			{Name: "pay_from_account_id", Kind: KRef},
			{Name: "interest_category_id", Kind: KRef},
			{Name: "processing_fee", Kind: KInt, Min: zero, Max: maxMoney},
			{Name: "remind_days", Kind: KInt, Default: int64(3), Min: zero, Max: i64(30)},
			flag("closed", false),
			{Name: "closed_date", Kind: KDate},
			note(),
			createdAt(),
		},
		Check: func(r Data) error {
			if err := checkDateOrder(r, "start_date", "first_due_date"); err != nil {
				return err
			}
			paid, _ := r["paid_before"].(int64)
			if tenure, ok := r["tenure_months"].(int64); ok && paid > tenure {
				return fieldErr("paid_before", "cannot be more than the number of instalments")
			}
			return nil
		},
	}

	// Deposits are fixed and recurring deposits. The money sits in an asset
	// account (account_id, type "deposit"): funding is a transfer into it,
	// interest is income, and maturity is a transfer back out. Maturity values
	// are estimates unless maturity_amount is copied from the bank's receipt.
	Deposits = &Spec{
		Name: "deposits",
		Fields: []Field{
			name(100),
			{Name: "institution", Kind: KString, MaxLen: 100},
			{Name: "kind", Kind: KEnum, Default: "fd", Enum: DepositKinds},
			{Name: "account_id", Kind: KRef, Required: true},
			{Name: "currency", Kind: KCurrency, Required: true},
			{Name: "amount", Kind: KInt, Required: true, Min: one, Max: maxMoney}, // principal, or the monthly instalment of an RD
			{Name: "annual_rate", Kind: KDecimal, Required: true},
			{Name: "start_date", Kind: KDate, Required: true},
			{Name: "maturity_date", Kind: KDate, Required: true},
			{Name: "compounding", Kind: KEnum, Default: "quarterly", Enum: Compoundings},
			{Name: "payout", Kind: KEnum, Default: "cumulative", Enum: Payouts},
			{Name: "payout_account_id", Kind: KRef},
			{Name: "maturity_amount", Kind: KInt, Min: one, Max: maxMoney},
			{Name: "tax_percent", Kind: KDecimal},
			{Name: "status", Kind: KEnum, Default: "active", Enum: DepositStatuses},
			{Name: "closed_date", Kind: KDate},
			flag("auto_renew", false),
			{Name: "remind_days", Kind: KInt, Default: int64(7), Min: zero, Max: i64(60)},
			note(),
			createdAt(),
		},
		Check: func(r Data) error {
			a, _ := r["start_date"].(string)
			b, _ := r["maturity_date"].(string)
			if a != "" && b != "" && b <= a {
				return fieldErr("maturity_date", "must be after start_date")
			}
			return nil
		},
	}

	RecurringRules = &Spec{
		Name: "recurring_rules",
		Fields: []Field{
			{Name: "name", Kind: KString, MaxLen: 100},
			{Name: "type", Kind: KEnum, Required: true, Enum: TransactionTypes},
			{Name: "account_id", Kind: KRef, Required: true},
			{Name: "to_account_id", Kind: KRef},
			{Name: "amount", Kind: KInt, Required: true, Min: one, Max: maxMoney},
			{Name: "to_amount", Kind: KInt, Min: one, Max: maxMoney},
			{Name: "category_id", Kind: KRef},
			{Name: "payee", Kind: KString, MaxLen: 200},
			note(),
			{Name: "tag_ids", Kind: KIDList, MaxLen: 50, Default: []string{}},
			{Name: "frequency", Kind: KEnum, Required: true, Enum: Frequencies},
			{Name: "every", Kind: KInt, Default: int64(1), Min: one, Max: i64(366)},
			{Name: "start_date", Kind: KDate, Required: true},
			{Name: "end_date", Kind: KDate},
			{Name: "max_occurrences", Kind: KInt, Min: one},
			{Name: "mode", Kind: KEnum, Default: "remind", Enum: RecurringModes},
			{Name: "next_date", Kind: KDate},
			flag("paused", false),
			createdAt(),
		},
		Check: func(r Data) error {
			if err := checkTransferShape(r); err != nil {
				return err
			}
			return checkDateOrder(r, "start_date", "end_date")
		},
	}

	Transactions = &Spec{
		Name: "transactions",
		Fields: []Field{
			{Name: "type", Kind: KEnum, Required: true, Enum: TransactionTypes},
			{Name: "status", Kind: KEnum, Default: "cleared", Enum: TxStatuses},
			{Name: "account_id", Kind: KRef, Required: true},
			{Name: "amount", Kind: KInt, Required: true, Min: one, Max: maxMoney},
			{Name: "to_account_id", Kind: KRef},
			{Name: "to_amount", Kind: KInt, Min: one, Max: maxMoney},
			{Name: "category_id", Kind: KRef},
			{Name: "payee", Kind: KString, MaxLen: 200},
			note(),
			{Name: "occurred_at", Kind: KInt, Required: true, Min: zero},
			{Name: "local_date", Kind: KDate, Required: true},
			{Name: "tz_offset_min", Kind: KInt, Default: int64(0), Min: minTZOffset, Max: maxTZOffset},
			{Name: "tag_ids", Kind: KIDList, MaxLen: 50, Default: []string{}},
			{Name: "original_amount", Kind: KInt, Min: one, Max: maxMoney},
			{Name: "original_currency", Kind: KCurrency},
			{Name: "recurring_rule_id", Kind: KRef},
			{Name: "debt_id", Kind: KRef},
			{Name: "loan_id", Kind: KRef},
			{Name: "deposit_id", Kind: KRef},
			{Name: "part", Kind: KEnum, Enum: TxParts},
			flag("is_adjustment", false),
			flag("excluded_from_reports", false),
			createdAt(),
		},
		Check: func(r Data) error {
			if err := checkTransferShape(r); err != nil {
				return err
			}
			if (r["original_amount"] == nil) != (r["original_currency"] == nil) {
				return fieldErr("original_currency", "original_amount and original_currency must be set together")
			}
			return nil
		},
	}

	TransactionSplits = &Spec{
		Name: "transaction_splits",
		Fields: []Field{
			{Name: "transaction_id", Kind: KRef, Required: true},
			{Name: "category_id", Kind: KRef, Required: true},
			{Name: "amount", Kind: KInt, Required: true, Min: one, Max: maxMoney},
			note(),
			sortOrder(),
		},
	}

	Budgets = &Spec{
		Name: "budgets",
		Fields: []Field{
			name(100),
			{Name: "amount", Kind: KInt, Required: true, Min: one, Max: maxMoney},
			{Name: "currency", Kind: KCurrency, Required: true},
			{Name: "period", Kind: KEnum, Required: true, Enum: BudgetPeriods},
			{Name: "start_date", Kind: KDate, Required: true},
			{Name: "end_date", Kind: KDate},
			{Name: "category_ids", Kind: KIDList, MaxLen: 200, Default: []string{}},
			{Name: "account_ids", Kind: KIDList, MaxLen: 200, Default: []string{}},
			flag("rollover", false),
			{Name: "alert_percent", Kind: KInt, Default: int64(80), Min: one, Max: i64(100)},
			flag("archived", false),
			createdAt(),
		},
		Check: func(r Data) error {
			if r["period"] == "custom" && r["end_date"] == nil {
				return fieldErr("end_date", "is required for a custom period")
			}
			return checkDateOrder(r, "start_date", "end_date")
		},
	}

	ExchangeRates = &Spec{
		Name: "exchange_rates",
		Fields: []Field{
			{Name: "base", Kind: KCurrency, Required: true},
			{Name: "quote", Kind: KCurrency, Required: true},
			{Name: "rate", Kind: KDecimal, Required: true},
			{Name: "effective_date", Kind: KDate, Required: true},
			{Name: "source", Kind: KEnum, Default: "manual", Enum: RateSources},
		},
		Check: func(r Data) error {
			if r["base"] == r["quote"] {
				return fieldErr("quote", "must differ from base")
			}
			return nil
		},
	}

	// Rules suggest a category, payee and tags for new transactions
	// (docs/02, "Rules"). Matching is case-insensitive.
	Rules = &Spec{
		Name: "rules",
		Fields: []Field{
			{Name: "name", Kind: KString, MaxLen: 100},
			{Name: "match_field", Kind: KEnum, Default: "payee", Enum: RuleMatchFields},
			{Name: "match_type", Kind: KEnum, Default: "contains", Enum: RuleMatchTypes},
			{Name: "pattern", Kind: KString, Required: true, MaxLen: 200},
			{Name: "tx_type", Kind: KEnum, Enum: []string{"expense", "income"}},
			{Name: "account_id", Kind: KRef},
			{Name: "min_amount", Kind: KInt, Min: zero, Max: maxMoney},
			{Name: "max_amount", Kind: KInt, Min: zero, Max: maxMoney},
			{Name: "set_category_id", Kind: KRef},
			{Name: "set_payee", Kind: KString, MaxLen: 200},
			{Name: "add_tag_ids", Kind: KIDList, MaxLen: 50, Default: []string{}},
			sortOrder(),
			flag("enabled", true),
			createdAt(),
		},
		Check: func(r Data) error {
			tags, _ := r["add_tag_ids"].([]string)
			if r["set_category_id"] == nil && r["set_payee"] == nil && len(tags) == 0 {
				return fieldErr("set_category_id", "a rule must set a category, a payee or tags")
			}
			lo, okLo := r["min_amount"].(int64)
			hi, okHi := r["max_amount"].(int64)
			if okLo && okHi && hi < lo {
				return fieldErr("max_amount", "must not be less than min_amount")
			}
			return nil
		},
	}
)

func init() {
	for _, s := range []*Spec{Accounts, Categories, Tags, Debts, Loans, Deposits, RecurringRules, Transactions, TransactionSplits, Budgets, ExchangeRates, Rules} {
		register(s)
	}
}

// checkTransferShape enforces: transfers need a distinct destination account
// and amount; expenses and income must not have them.
func checkTransferShape(r Data) error {
	if r["type"] == "transfer" {
		switch {
		case r["to_account_id"] == nil:
			return fieldErr("to_account_id", "is required for a transfer")
		case r["to_amount"] == nil:
			return fieldErr("to_amount", "is required for a transfer")
		case r["to_account_id"] == r["account_id"]:
			return fieldErr("to_account_id", "must differ from account_id")
		}
		return nil
	}
	if r["to_account_id"] != nil || r["to_amount"] != nil {
		return fieldErr("to_account_id", "only transfers have a destination account")
	}
	return nil
}

func checkDateOrder(r Data, from, to string) error {
	a, _ := r[from].(string)
	b, _ := r[to].(string)
	if a != "" && b != "" && b < a { // YYYY-MM-DD sorts chronologically
		return fieldErr(to, "must not be before "+from)
	}
	return nil
}

func fieldErr(field, msg string) error {
	return &ValidationError{Fields: map[string]string{field: msg}}
}

// ErrUnknownEntity is returned for entity names that are not registered.
var ErrUnknownEntity = errors.New("unknown entity")
