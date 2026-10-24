# 02 – Expense domain study

This is how personal money works in practice, and how each concept maps to LekhaMint. Most
expense apps fail on the edge cases (credit cards, refunds, transfers, foreign spends), so
those cases are covered explicitly.

## 1. The core idea: money moves between places

Every financial event is one of three kinds:

| Type | Meaning | Effect on net worth | Shows in spending reports |
|---|---|---|---|
| **Expense** | Money leaves you to the outside world | ↓ | Yes |
| **Income** | Money comes in from the outside world | ↑ | Yes (income side) |
| **Transfer** | Money moves between two of *your own* accounts | none | **No** |

The most common bug in home budgeting is recording a transfer as an expense. Examples of
transfers that are **not** spending:

- Paying a credit-card bill from a bank account. The spending already happened when the card was swiped.
- Withdrawing cash from an ATM.
- Moving money to savings or a fixed deposit.
- Topping up a wallet (Paytm, PayPal and similar).
- Paying the principal on a loan. Only the **interest** part is an expense. LekhaMint supports a split transfer for this; see §6.

## 2. Accounts

An account is any place money sits. It has exactly **one currency**.

| Account type | Normal balance | Examples |
|---|---|---|
| `cash` | positive | Wallet, petty cash |
| `bank` | positive | Savings and current accounts |
| `wallet` | positive | UPI wallets, PayPal, gift cards |
| `credit_card` | **negative** (money you owe) | Visa or Mastercard credit cards |
| `savings` | positive | Fixed and recurring deposits |
| `investment` | positive | Brokerage, mutual funds (tracked at cost or with manual revaluation) |
| `loan` | **negative** | Home loan, car loan, personal loan |
| `other` | either | Anything else |

**Balance rule.** `balance = opening_balance + Σincome − Σexpense − Σtransfers_out + Σtransfers_in`.
Balances are **always calculated, never stored**. Two devices therefore can never disagree
about a balance, and sync never has to merge numbers.

**Net worth** is the sum of balances of accounts with `include_in_net_worth = true`, converted to the ledger's base currency.

## 3. Credit cards (the classic trap)

1. Swipe ₹2,000 at a restaurant → **Expense** from the *credit card* account, category *Dining out*. The card balance becomes −₹2,000.
2. The bill arrives. Pay ₹2,000 from the bank → **Transfer** bank → credit card. The card returns to ₹0. Spending is unchanged.
3. Interest or late fee → **Expense** from the card, category *Financial › Interest & fees*.

The card's `credit_limit` lets the app show available credit and utilisation.

## 4. Refunds, cashback and reimbursements

**Reversal rule.** Income in an expense category is a refund and reduces that category's spending.
An expense in an income category (for example returning part of a salary advance) reduces income.

| Event | How to record | Why |
|---|---|---|
| Returned a product (refund) | **Income** with the *original expense category* (e.g. Shopping › Clothing) | Reports treat income in an expense category as **negative spending**, so the Clothing total is correct. |
| Cashback or rewards | Income, category *Cashback & rewards* | It is real income. Some users prefer the refund style. Both work. |
| Work reimbursement | Income, category *Reimbursements*, or mark the original expense `excluded_from_reports` | The user chooses. Both are supported. |

## 5. Foreign currency spends

A €50 purchase on an INR card that is billed at ₹4,650:

- `amount = 465000` (minor units of the **account** currency, INR)
- `original_amount = 5000`, `original_currency = "EUR"` (kept for reference)

The account currency is the source of truth because it is what actually left the account.
Cross-currency **transfers** store both sides: `amount` in the source currency and
`to_amount` in the destination currency. The implied rate is never stored, so it cannot
drift through rounding.

## 6. Splits

One receipt can cover several categories. A ₹3,000 supermarket bill might be ₹2,400 groceries
plus ₹600 household. A transaction can have **splits**. Each split has its own category,
amount and note, and the splits must add up to the transaction amount. Reports use the splits
when they exist, and the parent category otherwise.

A loan EMI is a split *transfer*. The principal goes to the loan account and the interest is
an expense. On the roadmap this becomes a one-tap "EMI" template.

## 7. Recurring transactions

Subscriptions, rent, salary and EMIs follow a schedule (daily, weekly, monthly or yearly, every
*n*, with an optional end). Each rule has a mode:

- **auto** – the transaction is created on the due date.
- **remind** – the app notifies the user, who confirms the actual amount (good for utility bills that vary).

Generated transactions get a **deterministic ID** made from `(rule_id, occurrence_date)`. If two
offline devices both generate the same occurrence, sync merges them into one record instead of
creating a duplicate. The web app uses the same IDs, so whichever client opens first records due
automatic items and the others skip them.

A new rule, or one whose schedule changes, starts at the **first occurrence on or after today**
(`next_date`), even when its start date is in the past. Nothing is back-filled by surprise; past
occurrences can still be entered by hand.

## 8. Pending vs cleared, and reconciliation

- `cleared` – the transaction appears on the bank statement (default).
- `pending` – authorised but not yet posted (hotel deposits, card holds).
- `planned` – a future expected transaction. It is excluded from balances until its date or until it is confirmed.

**Reconciliation.** When the app balance differs from the bank, the user enters the real
balance. LekhaMint then creates an **adjustment** transaction (`is_adjustment = true`). Adjustments
change the balance but never appear in spending reports.

## 9. Lending and borrowing (debts)

"I lent Rahul ₹5,000." A **Debt** records the person, the direction (`lent` or `borrowed`), the
amount and an optional due date. The money that leaves an account is a transaction linked to the
debt (`debt_id`) and is excluded from spending. Repayments are also linked, and the debt shows
what is still outstanding.

- Lending is money **out** (an `expense` row with `debt_id`); borrowing is money **in** (`income`).
- A repayment is the opposite direction: money in for a loan you gave, money out for one you took.
- Outstanding = amount − repayments. A repayment that clears it marks the debt `closed`.
- Debt movements change balances but never appear in spending, income, budgets or day totals.
- The logic lives in `android/.../domain/Debts.kt` and `web/src/pages/Debts.tsx`.

## 9a. Rules (auto-categorise)

A rule looks at the payee, the note or either (`contains`, `starts with` or `is exactly`, ignoring
case), optionally limited to a type, an account and an amount range. It can set the category,
rename the payee and add tags. Rules run in `sort_order`; the first rule that sets a category
wins (likewise for the payee) and tags from every matching rule are combined. They apply to new
entries and imports, never silently to edits; "Save and apply to past" updates history on request.
The algorithm is shared by `domain/Rules.kt` and `web/src/lib/rules.ts`, with identical tests.

## 9b. Importing statements

CSV rows are mapped once (date, description, amount or debit/credit columns). Each imported row
gets the ID `UUIDv5("import:<account>:<fingerprint>")`, written with `create_if_missing`, so
importing the same file twice adds nothing. Before importing, rows that match an existing
transaction in the same account (same amount and type, within ±2 days, one-to-one) are unticked
as likely duplicates.

## 10. Categories

### Design rules

- **Two levels only** (category › sub-category). Research on finance-app usability, and how
  Mint, YNAB and Ivy Wallet are used, shows deeper trees are rarely used and slow entry down.
- Each category is `expense` or `income`.
- Each expense category has an optional **nature**: `need`, `want` or `savings`. This drives the
  **50/30/20** insight (≈50% needs, 30% wants, 20% savings and debt pay-down), which is the most
  widely recommended budgeting rule of thumb.
- Categories are **archived, never hard-deleted**. History stays intact.
- Users can rename, re-colour, re-order, merge and add categories.

### Default set

The canonical list is [`server/internal/seed/default-categories.json`](../server/internal/seed/default-categories.json),
and every client uses the same file. Summary:

| Expense | Sub-categories | Nature |
|---|---|---|
| Housing | Rent, Mortgage interest, Maintenance & repairs, Property tax, Society/HOA fees | need |
| Utilities | Electricity, Water, Gas, Internet, Mobile, TV/DTH | need |
| Food | Groceries, Dining out, Food delivery, Coffee & snacks | need / want |
| Transport | Fuel, Public transport, Taxi & ride-hailing, Parking & tolls, Vehicle service, Vehicle insurance | need |
| Health | Doctor, Pharmacy, Health insurance, Fitness | need |
| Personal care | Salon & grooming, Toiletries | want |
| Shopping | Clothing, Electronics, Home & furniture, Household supplies | want |
| Entertainment | Movies & events, Subscriptions, Hobbies, Games | want |
| Travel | Flights & trains, Hotels, Activities, Visa & forex | want |
| Education | Tuition, Books, Courses | need |
| Family & kids | Childcare, School fees, Toys, Allowance | need |
| Pets | Food, Vet, Grooming | need |
| Gifts & donations | Gifts, Charity, Religious offerings | want |
| Financial | Bank fees, Interest & fees, Taxes, Life insurance | need |
| Business | Supplies, Software, Travel | — |
| Miscellaneous | — | — |

| Income | |
|---|---|
| Salary, Business & freelance, Interest, Dividends, Rental income, Gifts received, Cashback & rewards, Refunds & reimbursements, Other income | |

## 11. Budgets

A budget is a spending limit for a **period** (weekly, monthly, quarterly, yearly or a custom range), optionally narrowed to:

- one or more **categories** (an empty list means all expenses)
- one or more **accounts**

Options:

- **Rollover.** Unspent (or overspent) money carries into the next period. This is the YNAB-style envelope behaviour.
- **Alert threshold** (default 80%). Notify the user when spending crosses it.

Budgets are evaluated in the budget's currency. Spending in other currencies is converted at the
rate on the transaction date.

## 12. Money precision

- Amounts are `int64` minor units: ₹12.34 is `1234`, ¥500 is `500` (JPY has 0 decimals), and 1.234 KWD is `1234` (3 decimals).
- The number of decimals comes from ISO 4217.
- Exchange rates are stored as **decimal strings** (`"83.124500"`) and are only multiplied using decimal arithmetic. Rounding is half-even, applied once, at display or conversion time.
- An `int64` holds up to 9.2 × 10¹⁸ minor units. That is far beyond any personal ledger.
