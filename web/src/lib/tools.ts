// Pure maths behind the Tools page. Money is integer minor units throughout;
// anything that divides money hands out the leftover minor units explicitly
// so parts always add up to the whole.

import { emi, monthlyInterest } from './loans'

/**
 * Splits [total] in proportion to [weights] (largest remainder method): every
 * part is a whole minor unit and the parts add up to exactly [total].
 * Ties for the leftover units go to the earlier participants.
 */
export function allocateByWeight(total: number, weights: number[]): number[] {
  const sum = weights.reduce((a, w) => a + Math.max(0, w), 0)
  if (sum <= 0 || weights.length === 0) return weights.map(() => 0)
  const exact = weights.map((w) => (total * Math.max(0, w)) / sum)
  const parts = exact.map(Math.floor)
  let left = total - parts.reduce((a, b) => a + b, 0)
  const order = exact.map((x, i) => ({ i, frac: x - Math.floor(x) })).sort((a, b) => b.frac - a.frac || a.i - b.i)
  for (let k = 0; left > 0; k = (k + 1) % order.length, left--) parts[order[k].i]++
  return parts
}

export interface SplitPerson { name: string; paid: number }
export type SplitMode = 'equal' | 'shares' | 'exact' | 'percent' | 'items'
export interface SplitItem { name: string; amount: number; people: number[] } // indexes into people

export interface SplitResult {
  owes: number[] // each person's share of the bill
  net: number[] // paid − share: positive gets money back
  transfers: { from: number; to: number; amount: number }[]
  error: string | null
}

/**
 * Splits a bill. [values] are shares, exact amounts or percentages depending
 * on [mode]; [extra] (tax, tip, service) is spread in proportion to each
 * person's share. Returns who owes what and the fewest transfers to settle.
 */
export function splitBill(total: number, people: SplitPerson[], mode: SplitMode, values: number[], extra = 0, items: SplitItem[] = []): SplitResult {
  const n = people.length
  const empty = { owes: people.map(() => 0), net: people.map(() => 0), transfers: [] }
  if (n === 0) return { ...empty, error: 'Add at least one person' }
  let base: number[]
  switch (mode) {
    case 'equal': base = allocateByWeight(total, people.map(() => 1)); break
    case 'shares':
      if (values.some((v) => v < 0) || values.reduce((a, b) => a + b, 0) <= 0) return { ...empty, error: 'Give at least one person a share above zero' }
      base = allocateByWeight(total, values); break
    case 'percent': {
      const pct = values.reduce((a, b) => a + b, 0)
      if (Math.abs(pct - 100) > 1e-9) return { ...empty, error: `Percentages add up to ${+pct.toFixed(4)}%, not 100%` }
      base = allocateByWeight(total, values); break
    }
    case 'exact': {
      const sum = values.reduce((a, b) => a + b, 0)
      if (sum !== total) return { ...empty, error: sum < total ? 'The amounts are less than the bill' : 'The amounts are more than the bill' }
      base = values.slice(); break
    }
    case 'items': {
      base = people.map(() => 0)
      const itemSum = items.reduce((a, it) => a + it.amount, 0)
      if (itemSum !== total) return { ...empty, error: `Items add up to a different amount than the bill (difference ${total - itemSum})` }
      for (const it of items) {
        if (!it.people.length) return { ...empty, error: `Choose who shared “${it.name || 'an item'}”` }
        const parts = allocateByWeight(it.amount, it.people.map(() => 1))
        it.people.forEach((p, k) => { base[p] += parts[k] })
      }
      break
    }
  }
  const tipParts = extra > 0 ? allocateByWeight(extra, base) : base.map(() => 0)
  const owes = base.map((b, i) => b + tipParts[i])
  const paid = people.map((p) => p.paid)
  const grand = total + Math.max(0, extra)
  const paidSum = paid.reduce((a, b) => a + b, 0)
  if (paidSum !== grand && paidSum !== 0) return { owes, net: owes.map(() => 0), transfers: [], error: `Payments add up to ${paidSum}, but the bill with extras is ${grand}` }
  // Nobody entered who paid: assume nothing settled yet and show only shares.
  const net = paidSum === 0 ? owes.map(() => 0) : paid.map((p, i) => p - owes[i])
  return { owes, net, transfers: settle(net), error: null }
}

/** Fewest transfers (greedy: biggest debtor pays biggest creditor) to bring every net to zero. */
export function settle(net: number[]): { from: number; to: number; amount: number }[] {
  const debt = net.map((v, i) => ({ i, v })).filter((x) => x.v < 0).map((x) => ({ i: x.i, v: -x.v }))
  const cred = net.map((v, i) => ({ i, v })).filter((x) => x.v > 0)
  const out: { from: number; to: number; amount: number }[] = []
  debt.sort((a, b) => b.v - a.v); cred.sort((a, b) => b.v - a.v)
  let d = 0, c = 0
  while (d < debt.length && c < cred.length) {
    const amt = Math.min(debt[d].v, cred[c].v)
    if (amt > 0) out.push({ from: debt[d].i, to: cred[c].i, amount: amt })
    debt[d].v -= amt; cred[c].v -= amt
    if (debt[d].v === 0) d++
    if (cred[c].v === 0) c++
  }
  return out
}

/** Largest principal whose EMI fits in [payment] at [rate] over [months] (inverse of the EMI formula). */
export function maxPrincipal(payment: number, rate: string | null, months: number): number {
  if (payment <= 0 || months <= 0) return 0
  const r = rate ? Number(rate) / 1200 : 0
  if (!r) return payment * months
  let p = Math.floor((payment * (1 - Math.pow(1 + r, -months))) / r)
  while (p > 0 && emi(p, rate, months) > payment) p-- // never suggest a loan whose EMI is above the limit
  return p
}

export interface Affordability {
  maxEmiByRatio: number // income × ratio − existing EMIs
  maxEmiBySurplus: number // income − expenses − existing EMIs − buffer
  maxEmi: number
  maxLoan: number
  target?: { emi: number; leftAfter: number; ratio: number; fits: boolean }
}

/** How large an EMI and loan look manageable. A planning aid, not a lender's decision. */
export function affordability(o: { income: number; expenses: number; existingEmi: number; buffer: number; ratio: number; rate: string | null; months: number; target?: number }): Affordability {
  const byRatio = Math.max(0, Math.floor(o.income * o.ratio) - o.existingEmi)
  const bySurplus = Math.max(0, o.income - o.expenses - o.existingEmi - o.buffer)
  const maxEmi = Math.min(byRatio, bySurplus)
  const out: Affordability = { maxEmiByRatio: byRatio, maxEmiBySurplus: bySurplus, maxEmi, maxLoan: maxPrincipal(maxEmi, o.rate, o.months) }
  if (o.target && o.target > 0) {
    const e = emi(o.target, o.rate, o.months)
    out.target = { emi: e, leftAfter: o.income - o.expenses - o.existingEmi - e, ratio: o.income > 0 ? (o.existingEmi + e) / o.income : 1, fits: e <= maxEmi }
  }
  return out
}

/** Monthly saving needed to reach [target] from [current] in [months] with [annualReturn]% compounded monthly. */
export function goalMonthly(target: number, current: number, months: number, annualReturn: number): number {
  if (months <= 0) return Math.max(0, target - current)
  const r = annualReturn / 1200
  const grown = current * Math.pow(1 + r, months)
  const need = target - grown
  if (need <= 0) return 0
  if (!r) return Math.ceil(need / months)
  return Math.ceil((need * r) / (Math.pow(1 + r, months) - 1))
}

/** Months until [target] is reached saving [monthly] on top of [current]; null if never within 100 years. */
export function goalMonths(target: number, current: number, monthly: number, annualReturn: number): number | null {
  const r = annualReturn / 1200
  let v = current
  for (let m = 0; m <= 1200; m++) {
    if (v >= target) return m
    v = v * (1 + r) + monthly
  }
  return null
}

export interface PayoffDebt { name: string; balance: number; rate: string | null; minimum: number }
export interface PayoffResult { months: number; totalInterest: number; order: string[]; never: boolean }

/**
 * Simulates paying several debts with a fixed monthly budget: every debt gets
 * its minimum, and the rest goes to the first debt in priority order —
 * highest rate first (avalanche) or smallest balance first (snowball).
 */
export function payoff(debts: PayoffDebt[], budget: number, strategy: 'avalanche' | 'snowball'): PayoffResult {
  const ds = debts.filter((d) => d.balance > 0).map((d) => ({ ...d }))
  const order: string[] = []
  let totalInterest = 0
  const minSum = ds.reduce((a, d) => a + d.minimum, 0)
  if (budget < minSum) return { months: 0, totalInterest: 0, order: [], never: true }
  for (let m = 1; m <= 1200; m++) {
    for (const d of ds) if (d.balance > 0) { const i = monthlyInterest(d.balance, d.rate); d.balance += i; totalInterest += i }
    let left = budget
    for (const d of ds) if (d.balance > 0) { const pay = Math.min(d.minimum, d.balance); d.balance -= pay; left -= pay }
    const prio = ds.filter((d) => d.balance > 0).sort(strategy === 'avalanche'
      ? (a, b) => Number(b.rate ?? 0) - Number(a.rate ?? 0) || a.balance - b.balance
      : (a, b) => a.balance - b.balance || Number(b.rate ?? 0) - Number(a.rate ?? 0))
    for (const d of prio) { if (left <= 0) break; const pay = Math.min(left, d.balance); d.balance -= pay; left -= pay }
    for (const d of ds) if (d.balance === 0 && !order.includes(d.name)) order.push(d.name)
    if (ds.every((d) => d.balance === 0)) return { months: m, totalInterest, order, never: false }
  }
  return { months: 1200, totalInterest, order, never: true }
}

export interface RecurringGuess { payee: string; currency: string; monthly: number; count: number; last: string; months: number }

/**
 * Payees charged in at least 3 different months of the last 6 with similar
 * amounts (within 15% of the median): likely subscriptions or regular bills.
 */
export function findRecurring(rows: { payee: string | null; amount: number; local_date: string; currency: string; type: string }[]): RecurringGuess[] {
  const by = new Map<string, { payee: string; currency: string; items: { amount: number; date: string }[] }>()
  for (const r of rows) {
    if (r.type !== 'expense' || !r.payee?.trim()) continue
    const k = r.payee.trim().toLowerCase() + '|' + r.currency
    const g = by.get(k) ?? { payee: r.payee.trim(), currency: r.currency, items: [] }
    g.items.push({ amount: r.amount, date: r.local_date })
    by.set(k, g)
  }
  const out: RecurringGuess[] = []
  for (const g of by.values()) {
    const months = new Set(g.items.map((i) => i.date.slice(0, 7)))
    if (months.size < 3) continue
    const sorted = g.items.map((i) => i.amount).sort((a, b) => a - b)
    const median = sorted[Math.floor(sorted.length / 2)]
    const similar = g.items.filter((i) => Math.abs(i.amount - median) <= median * 0.15)
    if (new Set(similar.map((i) => i.date.slice(0, 7))).size < 3) continue
    const total = g.items.reduce((a, i) => a + i.amount, 0)
    out.push({ payee: g.payee, currency: g.currency, monthly: Math.round(total / months.size), count: g.items.length, last: g.items.map((i) => i.date).sort().at(-1)!, months: months.size })
  }
  return out.sort((a, b) => b.monthly - a.monthly)
}
