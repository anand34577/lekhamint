// Calculator, budget periods and small finance helpers. Budget periods follow
// android/.../domain/Budgets.kt so both apps show the same window.

import type { Budget, CurrencySummary } from './api'
import { addDays, addMonths, isoDate, parseDate } from './util'

/** Evaluates "450+800*2" with + − × ÷ and normal precedence, exactly in minor units of 10^-8. */
export function evaluate(expr: string): number | null {
  const s = expr.replace(/[,\s]/g, '').replace(/×/g, '*').replace(/÷/g, '/').replace(/−/g, '-')
  if (!/^[\d.+\-*/]+$/.test(s)) return null
  const tokens = s.match(/\d*\.?\d+|[+\-*/]/g)
  if (!tokens) return null
  const nums: number[] = []
  const ops: string[] = []
  let expectNum = true
  for (const t of tokens) {
    if (/[+\-*/]/.test(t)) { if (expectNum) return null; ops.push(t); expectNum = true }
    else { nums.push(Number(t)); expectNum = false }
  }
  if (expectNum) return null
  // × and ÷ first.
  const n2 = [nums[0]], o2: string[] = []
  ops.forEach((op, i) => {
    const v = nums[i + 1]
    if (op === '*') n2[n2.length - 1] *= v
    else if (op === '/') n2[n2.length - 1] = v === 0 ? NaN : n2[n2.length - 1] / v
    else { o2.push(op); n2.push(v) }
  })
  let acc = n2[0]
  o2.forEach((op, i) => { acc = op === '+' ? acc + n2[i + 1] : acc - n2[i + 1] })
  return Number.isFinite(acc) ? Math.round(acc * 1e8) / 1e8 : null
}

export interface Window { start: string; end: string }

function step(start: string, period: string, i: number): string {
  switch (period) {
    case 'weekly': return addDays(start, 7 * i)
    case 'quarterly': return addMonths(start, 3 * i)
    case 'yearly': return addMonths(start, 12 * i)
    default: return addMonths(start, i)
  }
}

/** The budget period that contains [date], or null if not started / ended. */
export function budgetWindow(b: Budget, date = isoDate()): Window | null {
  if (b.period === 'custom') return { start: b.start_date, end: b.end_date ?? b.start_date }
  if (date < b.start_date) return null
  let i = 0
  // Jump close, then walk; periods are at most a year so this is cheap.
  const days = (parseDate(date).getTime() - parseDate(b.start_date).getTime()) / 864e5
  const approx = { weekly: 7, monthly: 30.44, quarterly: 91.3, yearly: 365.25 }[b.period] ?? 30.44
  i = Math.max(0, Math.floor(days / approx) - 1)
  while (step(b.start_date, b.period, i + 1) <= date) i++
  while (i > 0 && step(b.start_date, b.period, i) > date) i--
  const start = step(b.start_date, b.period, i)
  let end = addDays(step(b.start_date, b.period, i + 1), -1)
  if (b.end_date && start > b.end_date) return null
  if (b.end_date && end > b.end_date) end = b.end_date
  return { start, end }
}

/** Spending against a budget from the server summary (refunds already net out). */
export function budgetSpent(b: Budget, summary: CurrencySummary[] | undefined, expand: (ids: string[]) => Set<string>): number {
  const s = summary?.find((c) => c.currency === b.currency)
  if (!s) return 0
  const cats = expand(b.category_ids)
  return s.expense_by_category.filter((c) => cats.size === 0 || (c.category_id !== null && cats.has(c.category_id))).reduce((a, c) => a + c.amount, 0)
}

export function daysBetween(a: string, b: string): number {
  return Math.round((parseDate(b).getTime() - parseDate(a).getTime()) / 864e5)
}
