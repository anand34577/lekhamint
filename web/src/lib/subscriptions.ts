// Finds repeating payments in past spending: the same payee, at a steady
// interval (weekly, monthly, quarterly or yearly), for roughly the same amount.
// It also flags price changes ("₹499 → ₹649"). Pure, so it is easy to test.

import { addDays, addMonths, daysBetween } from './util'

export interface SubTx { id: string; local_date: string; amount: number; payee: string | null; category_id: string | null; account_id: string; recurring_rule_id?: string | null }

export type Cadence = 'weekly' | 'monthly' | 'quarterly' | 'yearly'

export interface Subscription {
  key: string
  payee: string
  cadence: Cadence
  /** Typical amount (median of recent payments). */
  amount: number
  lastAmount: number
  lastDate: string
  nextDate: string
  /** Cost over a year at the typical amount. */
  yearly: number
  count: number
  categoryId: string | null
  accountId: string
  /** The latest payment differs from the earlier typical amount by more than 5%. */
  priceChange: { from: number; to: number } | null
  /** No payment seen for well past the expected date: perhaps cancelled. */
  stale: boolean
}

const CADENCES: [Cadence, number, number, number][] = [
  // name, min gap, max gap (days), payments per year
  ['weekly', 6, 8, 52], ['monthly', 26, 35, 12], ['quarterly', 85, 97, 4], ['yearly', 350, 380, 1],
]

/** A payee name reduced to its stable part: "AMAZON PRIME*2K4" and "Amazon Prime" match. */
export function payeeKey(p: string): string {
  return p.toLowerCase().replace(/[*#].*$/, '').replace(/\d+/g, '').replace(/[^a-zऀ-ॿ]+/g, ' ').trim()
}

const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b), m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2)
}

/**
 * Repeating payments among [txs] (expenses, oldest or newest first). Payees
 * already handled by a recurring rule are skipped when [ruled] has their key.
 */
export function detectSubscriptions(txs: SubTx[], today: string, ruled: Set<string> = new Set()): Subscription[] {
  const groups = new Map<string, SubTx[]>()
  for (const t of txs) {
    if (!t.payee || t.recurring_rule_id) continue
    const k = payeeKey(t.payee)
    if (k.length < 3 || ruled.has(k)) continue
    groups.set(k, [...(groups.get(k) ?? []), t])
  }
  const out: Subscription[] = []
  for (const [key, list] of groups) {
    if (list.length < 3) continue
    list.sort((a, b) => a.local_date.localeCompare(b.local_date))
    // One payment per day at most (a refund and re-charge on the same day count once).
    const days = list.filter((t, i) => i === 0 || t.local_date !== list[i - 1].local_date)
    const gaps = days.slice(1).map((t, i) => daysBetween(days[i].local_date, t.local_date))
    const gap = median(gaps)
    const c = CADENCES.find(([, lo, hi]) => gap >= lo && gap <= hi)
    if (!c) continue
    const [cadence, lo, hi, perYear] = c
    // Most gaps must fit the cadence (a missed month is allowed once).
    const fit = gaps.filter((g) => g >= lo && g <= hi * 2 + 2).length
    if (fit < gaps.length * 0.75) continue
    const amounts = days.map((t) => t.amount)
    const typical = median(amounts.slice(-6))
    // Amounts must be steady: most within 25% of the typical one (utility bills vary more and are left out).
    if (amounts.filter((a) => Math.abs(a - typical) <= typical * 0.25).length < amounts.length * 0.7) continue
    const last = days[days.length - 1]
    const before = amounts.slice(-7, -1)
    const prev = before.length >= 2 ? median(before) : null
    const priceChange = prev && Math.abs(last.amount - prev) > prev * 0.05 ? { from: prev, to: last.amount } : null
    const next = cadence === 'weekly' ? addDays(last.local_date, 7) : addMonths(last.local_date, cadence === 'monthly' ? 1 : cadence === 'quarterly' ? 3 : 12)
    out.push({
      key, payee: last.payee!, cadence, amount: priceChange ? last.amount : typical, lastAmount: last.amount, lastDate: last.local_date,
      nextDate: next, yearly: (priceChange ? last.amount : typical) * perYear, count: days.length, categoryId: last.category_id, accountId: last.account_id,
      priceChange, stale: daysBetween(next, today) > Math.max(10, (hi - lo) * 2),
    })
  }
  return out.sort((a, b) => Number(a.stale) - Number(b.stale) || b.yearly - a.yearly)
}

export const CADENCE_LABEL: Record<Cadence, string> = { weekly: 'Every week', monthly: 'Every month', quarterly: 'Every 3 months', yearly: 'Every year' }
/** Frequency and step for a recurring rule made from a subscription. */
export const CADENCE_RULE: Record<Cadence, [string, number]> = { weekly: ['weekly', 1], monthly: ['monthly', 1], quarterly: ['monthly', 3], yearly: ['yearly', 1] }
