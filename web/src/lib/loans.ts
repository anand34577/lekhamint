// Loan and EMI arithmetic (docs/02, "Loans and deposits"). Mirrors
// android/.../domain/Loans.kt; the shared cases in loans.test.ts and
// LoansTest.kt must give identical results.
//
// Method (stated to the user wherever a schedule is shown):
//  - Interest is charged monthly on the reducing balance: outstanding ×
//    annual rate ÷ 12, rounded half-up to the minor unit. That step is exact
//    integer arithmetic on the rate's decimal digits.
//  - The EMI uses the standard formula P·r·(1+r)^n ⁄ ((1+r)^n − 1), rounded
//    half-up. The last instalment pays whatever is left, so the principal
//    column always adds up to the amount borrowed.
//  - Lenders may use other day counts or rounding. The user can enter the EMI
//    and each payment's interest exactly as on the bank statement.

import { addMonths } from './util'

/** "8.65" -> [865n, 100n]. Null or empty means interest-free. */
export function rateParts(rate: string | null | undefined): [bigint, bigint] | null {
  if (!rate) return null
  const m = /^(\d+)(?:\.(\d+))?$/.exec(rate.trim())
  if (!m) return null
  const frac = m[2] ?? ''
  const num = BigInt(m[1] + frac)
  if (num === 0n) return null
  return [num, 10n ** BigInt(frac.length)]
}

/** Validates a user-typed annual rate; returns an error message or null. */
export function rateError(rate: string, opts: { allowZero?: boolean } = {}): string | null {
  const t = rate.trim()
  if (!t) return opts.allowZero ? null : 'Enter the interest rate'
  if (!/^\d+(\.\d{1,4})?$/.test(t)) return 'Use a number like 8.5 (up to 4 decimal places), without the % sign'
  const v = Number(t)
  if (v === 0 && !opts.allowZero) return 'The rate must be more than 0'
  if (v > 60) return 'That rate is above 60% a year; check the number'
  return null
}

/** One month's interest on [balance] at [rate] % a year, rounded half-up. Exact. */
export function monthlyInterest(balance: number, rate: string | null | undefined): number {
  const p = rateParts(rate)
  if (!p || balance <= 0) return 0
  const [num, den] = p
  const d = den * 1200n
  return Number((BigInt(balance) * num * 2n + d) / (2n * d))
}

/** The EMI for [principal] over [months] at [rate] % a year. */
export function emi(principal: number, rate: string | null | undefined, months: number): number {
  if (principal <= 0 || months <= 0) return 0
  const p = rateParts(rate)
  if (!p) return Math.ceil(principal / months)
  const r = Number(p[0]) / Number(p[1]) / 1200
  const f = Math.pow(1 + r, months)
  return Math.round((principal * r * f) / (f - 1))
}

export interface ScheduleRow {
  n: number // instalment number, 1-based over the whole loan
  date: string
  opening: number
  interest: number
  principal: number
  payment: number
  closing: number
}

export interface Schedule {
  rows: ScheduleRow[]
  totalInterest: number
  totalPayment: number
  /** The EMI does not even cover the first month's interest, so the loan would never be repaid. */
  neverRepaid: boolean
  /** Rows were cut at [maxRows]; the loan runs longer than shown. */
  truncated: boolean
}

/**
 * Projects instalments from [balance] with a fixed [payment], starting at
 * instalment [firstN] whose due date is occurrence firstN−1 of [anchor]
 * (the first due date). Stops when the balance reaches zero.
 */
export function schedule(balance: number, rate: string | null | undefined, payment: number, anchor: string, firstN = 1, maxRows = 600): Schedule {
  const rows: ScheduleRow[] = []
  let b = balance, totalInterest = 0, totalPayment = 0
  const neverRepaid = balance > 0 && payment <= monthlyInterest(balance, rate)
  if (neverRepaid || payment <= 0) return { rows, totalInterest, totalPayment, neverRepaid: balance > 0, truncated: false }
  for (let n = firstN; b > 0 && rows.length < maxRows; n++) {
    const interest = monthlyInterest(b, rate)
    let principal = Math.min(payment - interest, b)
    // Rounding the EMI can leave a few paise; the last instalment absorbs them
    // (as lenders do) instead of adding a tiny extra instalment.
    if (b - principal > 0 && b - principal < Math.max(1, Math.round(payment / 100))) principal = b
    const pay = principal + interest
    rows.push({ n, date: addMonths(anchor, n - 1), opening: b, interest, principal, payment: pay, closing: b - principal })
    b -= principal
    totalInterest += interest
    totalPayment += pay
  }
  return { rows, totalInterest, totalPayment, neverRepaid: false, truncated: b > 0 }
}

/** The EMI that clears [balance] in exactly [months] more instalments. */
export function emiFor(balance: number, rate: string | null | undefined, months: number): number {
  return emi(balance, rate, months)
}

// --- Status of a recorded loan --------------------------------------------------

export interface LoanTerms {
  principal: number
  annual_rate: string | null
  first_due_date: string
  tenure_months: number
  emi_amount: number | null
  paid_before: number
  closed: boolean
}

export interface LoanMove { type: string; amount: number; to_amount?: number | null; part?: string | null; local_date: string; to_account_id?: string | null; account_id: string }

export interface LoanStatus {
  emi: number
  outstanding: number
  /** The loan account shows more paid than owed. */
  overpaid: number
  paidInstalments: number
  remainingInstalments: number
  /** Due date of the next unpaid instalment (null once repaid or closed). */
  nextDue: string | null
  nextInterest: number
  nextPrincipal: number
  overdue: number // number of instalments whose due date has passed without a payment
  principalPaid: number
  interestPaid: number
  feesPaid: number
  prepaid: number
  endDate: string | null
  projectedInterest: number
  progress: number // share of the principal repaid, 0..1
  projection: Schedule
}

/** EMI payments are the dates with a principal or interest payment, ignoring prepayments and fees. */
export function emiDates(moves: LoanMove[]): string[] {
  return [...new Set(moves.filter((m) => m.part === 'principal' || m.part === 'interest').map((m) => m.local_date))].sort()
}

/**
 * Status from the terms, the money actually recorded and the loan account's
 * balance ([accountBalance], negative while money is owed). Nothing is
 * assumed paid unless a payment was recorded or counted in paid_before.
 */
export function loanStatus(l: LoanTerms, moves: LoanMove[], accountBalance: number, today: string, loanAccountId: string): LoanStatus {
  const outstanding = Math.max(0, -accountBalance)
  const overpaid = Math.max(0, accountBalance)
  const sum = (f: (m: LoanMove) => boolean) => moves.filter(f).reduce((a, m) => a + (m.type === 'transfer' ? (m.to_amount ?? m.amount) : m.amount), 0)
  const intoLoan = (m: LoanMove) => m.type === 'transfer' && m.to_account_id === loanAccountId
  const principalPaid = sum((m) => intoLoan(m) && m.part === 'principal')
  const prepaid = sum((m) => intoLoan(m) && m.part === 'prepayment')
  const interestPaid = sum((m) => m.type === 'expense' && m.part === 'interest')
  const feesPaid = sum((m) => m.type === 'expense' && m.part === 'fee')
  const paidInstalments = Math.min(l.tenure_months + 600, l.paid_before + emiDates(moves.filter((m) => intoLoan(m) || m.type === 'expense')).length)
  const pay = l.emi_amount ?? emi(l.principal, l.annual_rate, l.tenure_months)
  const done = l.closed || outstanding === 0
  const projection = done ? schedule(0, l.annual_rate, pay, l.first_due_date) : schedule(outstanding, l.annual_rate, pay, l.first_due_date, paidInstalments + 1)
  const first = projection.rows[0]
  let overdue = 0
  if (!done) for (const r of projection.rows) { if (r.date < today) overdue++; else break }
  const last = projection.rows[projection.rows.length - 1]
  return {
    emi: pay, outstanding, overpaid, paidInstalments,
    remainingInstalments: done ? 0 : projection.rows.length,
    nextDue: done ? null : first?.date ?? null,
    nextInterest: first?.interest ?? 0, nextPrincipal: first?.principal ?? 0,
    overdue, principalPaid, interestPaid, feesPaid, prepaid,
    endDate: done ? null : last?.date ?? null,
    projectedInterest: projection.totalInterest,
    progress: l.principal > 0 ? Math.min(1, Math.max(0, 1 - outstanding / l.principal)) : 0,
    projection,
  }
}

/** Splits a payment of [amount] into interest and principal for a balance of [outstanding]. */
export function splitPayment(amount: number, outstanding: number, rate: string | null | undefined): { interest: number; principal: number } {
  const interest = Math.min(amount, monthlyInterest(outstanding, rate))
  return { interest, principal: Math.min(outstanding, amount - interest) }
}

/**
 * Compares keeping the EMI (shorter loan) with keeping the tenure (smaller
 * EMI) after a prepayment of [extra] from [outstanding] with [remaining]
 * instalments left.
 */
export function prepaymentOptions(outstanding: number, rate: string | null | undefined, currentEmi: number, remaining: number, extra: number, anchor: string, nextN: number) {
  const before = schedule(outstanding, rate, currentEmi, anchor, nextN)
  const left = Math.max(0, outstanding - extra)
  const keepEmi = schedule(left, rate, currentEmi, anchor, nextN)
  const newEmi = emi(left, rate, Math.max(1, remaining))
  const keepTenure = schedule(left, rate, newEmi, anchor, nextN)
  return {
    before,
    keepEmi: { schedule: keepEmi, months: keepEmi.rows.length, interestSaved: before.totalInterest - keepEmi.totalInterest },
    keepTenure: { emi: newEmi, schedule: keepTenure, interestSaved: before.totalInterest - keepTenure.totalInterest },
  }
}

export const LOAN_KINDS: [string, string][] = [
  ['home', 'Home loan'], ['vehicle', 'Vehicle loan'], ['personal', 'Personal loan'], ['education', 'Education loan'],
  ['gold', 'Gold loan'], ['business', 'Business loan'], ['consumer', 'Consumer / no-cost EMI'], ['other', 'Other loan'],
]
export const loanKindLabel = (k: string) => LOAN_KINDS.find(([x]) => x === k)?.[1] ?? 'Loan'
