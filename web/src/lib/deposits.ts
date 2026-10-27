// Fixed and recurring deposit estimates (docs/02, "Loans and deposits").
// Mirrors android/.../domain/Deposits.kt; shared cases live in
// deposits.test.ts and DepositsTest.kt.
//
// Method (shown to the user next to every estimate):
//  - Cumulative FD: interest compounds every full period (month, quarter,
//    half-year or year) counted from the start date; any days left after the
//    last full period earn simple interest on an actual/365 basis.
//  - "Simple" compounding: principal × rate × days ⁄ 365.
//  - Payout FD: each full payout period pays principal × rate × months ⁄ 12;
//    the principal comes back at maturity.
//  - RD: each monthly instalment compounds (quarterly by default) for the
//    whole months it stays invested.
// Banks differ in day counts, rounding and tax deduction, so the bank's own
// maturity value (maturity_amount) always wins when it is entered.

import { addDays, addMonths, parseDate } from './util'

export interface DepositTerms {
  kind: string // fd | rd | other
  amount: number // principal, or the monthly instalment for an RD
  annual_rate: string
  start_date: string
  maturity_date: string
  compounding: string // simple | monthly | quarterly | half_yearly | yearly
  payout: string // cumulative | monthly | quarterly | half_yearly | yearly
  maturity_amount?: number | null
  tax_percent?: string | null
}

const PERIOD_MONTHS: Record<string, number> = { monthly: 1, quarterly: 3, half_yearly: 6, yearly: 12 }

export function daysBetween(a: string, b: string): number {
  return Math.round((parseDate(b).getTime() - parseDate(a).getTime()) / 864e5)
}

/** Whole months from [a] to [b] (anchored on a's day) and the days left over. */
export function monthsAndDays(a: string, b: string): { months: number; days: number } {
  let m = 0
  while (addMonths(a, m + 1) <= b) m++
  return { months: m, days: daysBetween(addMonths(a, m), b) }
}

/** The end of a term of [years], [months] and [days] from [start]: months first, then days, as banks count it. */
export function termEnd(start: string, years: number, months: number, days: number): string {
  return addDays(addMonths(start, years * 12 + months), days)
}

/** A renewal keeps the same term ("7 days", "1 year 4 months 15 days"), now starting on [from]. */
export function renewedEnd(oldStart: string, oldEnd: string, from: string): string {
  const { months, days } = monthsAndDays(oldStart, oldEnd)
  const end = termEnd(from, 0, months, days)
  return end > from ? end : addDays(from, 1)
}

const rateOf = (s: string) => Number(s) / 100

/** Estimated value of a cumulative FD of [p] from [start] to [end]. */
export function fdValue(p: number, rate: string, start: string, end: string, compounding: string): number {
  if (end <= start) return p
  const r = rateOf(rate)
  const step = PERIOD_MONTHS[compounding]
  if (!step) return Math.round(p * (1 + (r * daysBetween(start, end)) / 365))
  const { months } = monthsAndDays(start, end)
  const periods = Math.floor(months / step)
  const afterPeriods = addMonths(start, periods * step)
  const leftover = daysBetween(afterPeriods, end)
  const grown = p * Math.pow(1 + (r * step) / 12, periods)
  return Math.round(grown * (1 + (r * leftover) / 365))
}

/** Estimated value of an RD paying [inst] on the start day of each month until [end]. */
export function rdValue(inst: number, rate: string, start: string, end: string, compounding: string, asOf = end): { deposited: number; value: number; instalments: number } {
  const r = rateOf(rate)
  const step = PERIOD_MONTHS[compounding] ?? 3
  const { months: total } = monthsAndDays(start, end)
  let deposited = 0, value = 0, instalments = 0
  for (let k = 0; k < Math.max(1, total); k++) {
    const paidOn = addMonths(start, k)
    // An instalment paid on the maturity date itself is the last one: it
    // counts toward the deposited sum (paidOn == end passes; paidOn > end
    // means the schedule ran past maturity).
    if (paidOn > asOf || paidOn > end) break
    instalments++
    deposited += inst
    const until = asOf < end ? asOf : end
    const { months, days } = monthsAndDays(paidOn, until)
    const years = months / 12 + days / 365
    value += inst * Math.pow(1 + (r * step) / 12, (years * 12) / step)
  }
  return { deposited, value: Math.round(value), instalments }
}

export interface PayoutRow { date: string; amount: number }

/** Interest payouts of a payout FD (non-cumulative). The last row is any partial period at maturity. */
export function payoutSchedule(d: DepositTerms): PayoutRow[] {
  const step = PERIOD_MONTHS[d.payout]
  if (!step) return []
  const r = rateOf(d.annual_rate)
  const rows: PayoutRow[] = []
  for (let i = 1; ; i++) {
    const date = addMonths(d.start_date, i * step)
    if (date > d.maturity_date) {
      const prev = addMonths(d.start_date, (i - 1) * step)
      const days = daysBetween(prev, d.maturity_date)
      if (days > 0) rows.push({ date: d.maturity_date, amount: Math.round((d.amount * r * days) / 365) })
      break
    }
    rows.push({ date, amount: Math.round((d.amount * r * step) / 12) })
    if (rows.length > 1200) break
  }
  return rows
}

export interface DepositEstimate {
  invested: number // principal, or total instalments for an RD
  interest: number // over the whole term
  maturity: number // what comes back at maturity (principal only for payout FDs)
  payouts: PayoutRow[] // interest paid out during the term
  tax: number // estimated tax on the interest, if a rate was given
  valueToday: number // estimated value if it were valued today (not what a premature closure pays)
  fromBank: boolean // maturity came from the bank's figure, not our estimate
  days: number
}

export function estimate(d: DepositTerms, today: string): DepositEstimate {
  const days = daysBetween(d.start_date, d.maturity_date)
  const asOf = today < d.start_date ? d.start_date : today > d.maturity_date ? d.maturity_date : today
  let invested: number, maturity: number, payouts: PayoutRow[] = [], valueToday: number
  if (d.kind === 'rd') {
    const full = rdValue(d.amount, d.annual_rate, d.start_date, d.maturity_date, d.compounding)
    invested = full.deposited
    maturity = full.value
    valueToday = rdValue(d.amount, d.annual_rate, d.start_date, d.maturity_date, d.compounding, asOf).value
  } else if (d.payout !== 'cumulative') {
    invested = d.amount
    payouts = payoutSchedule(d)
    maturity = d.amount
    valueToday = d.amount
  } else {
    invested = d.amount
    maturity = fdValue(d.amount, d.annual_rate, d.start_date, d.maturity_date, d.compounding)
    valueToday = fdValue(d.amount, d.annual_rate, d.start_date, asOf, d.compounding)
  }
  const fromBank = !!d.maturity_amount
  if (d.maturity_amount) maturity = d.maturity_amount
  const interest = maturity - invested + payouts.reduce((a, p) => a + p.amount, 0)
  const tax = d.tax_percent ? Math.round((Math.max(0, interest) * Number(d.tax_percent)) / 100) : 0
  return { invested, interest, maturity, payouts, tax, valueToday, fromBank, days }
}

export const DEPOSIT_KINDS: [string, string][] = [['fd', 'Fixed deposit'], ['rd', 'Recurring deposit'], ['other', 'Other deposit / bond']]
export const COMPOUNDINGS: [string, string][] = [['quarterly', 'Quarterly'], ['monthly', 'Monthly'], ['half_yearly', 'Half-yearly'], ['yearly', 'Yearly'], ['simple', 'Simple interest (no compounding)']]
export const PAYOUTS: [string, string][] = [['cumulative', 'At maturity (cumulative)'], ['monthly', 'Monthly payout'], ['quarterly', 'Quarterly payout'], ['half_yearly', 'Half-yearly payout'], ['yearly', 'Yearly payout']]
export const depositKindLabel = (k: string) => DEPOSIT_KINDS.find(([x]) => x === k)?.[1] ?? 'Deposit'

/** Human tenure: "1 year 3 months 10 days". */
export function tenureLabel(start: string, end: string): string {
  const { months, days } = monthsAndDays(start, end)
  const y = Math.floor(months / 12), m = months % 12
  const parts = [y && `${y} year${y > 1 ? 's' : ''}`, m && `${m} month${m > 1 ? 's' : ''}`, days && `${days} day${days > 1 ? 's' : ''}`].filter(Boolean)
  return parts.join(' ') || '0 days'
}
