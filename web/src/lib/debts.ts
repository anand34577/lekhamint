// Personal lending and borrowing (docs/02 §9). Mirrors the server's
// report.Debts and android/.../domain/Debts.kt: a repayment is money moving
// back (income on a loan you gave, an expense on one you took) that is not
// interest or a fee. Outstanding can go negative when someone overpays.

import type { Debt, Transaction } from './api'

export type Move = Transaction & { id: string }
export type DebtRec = Debt & { id: string }

export const isRepayment = (d: Debt, t: Pick<Transaction, 'type' | 'part'>) =>
  t.type === (d.direction === 'lent' ? 'income' : 'expense') && (t.part == null || t.part === 'principal' || t.part === 'prepayment')

export const isInterest = (t: Pick<Transaction, 'part'>) => t.part === 'interest' || t.part === 'fee'

/** What has been repaid, not counting interest. */
export const repaid = (d: Debt, moves: Move[]) => moves.filter((m) => isRepayment(d, m)).reduce((a, m) => a + m.amount, 0)

/** Amount − repayments; negative means it was overpaid. */
export const outstanding = (d: Debt, moves: Move[]) => d.amount - repaid(d, moves)

export const interestTotal = (moves: Move[]) => moves.filter(isInterest).reduce((a, m) => a + m.amount, 0)

/** Same person regardless of case or stray spaces. */
export const personKey = (name: string) => name.trim().toLowerCase().replace(/\s+/g, ' ')

export interface StatementRow {
  date: string | null
  text: string
  debtId: string
  /** Positive: they owe you more (you lent / they were paid back by you). Negative: they paid you, or you borrowed. */
  change: number
  interest: number
  balance: number // running: positive = they owe you
}

export interface PersonSummary {
  key: string
  name: string
  currency: string
  /** Positive: they owe you. Negative: you owe them. Settled debts count as zero. */
  net: number
  open: number
  debts: DebtRec[]
  overdue: number
  nextDue: string | null
  lastActivity: string | null
}

/**
 * One row per person and currency. Settled debts are left out of the net
 * (whatever was left was written off when they were marked settled).
 */
export function people(debts: DebtRec[], moves: Map<string, Move[]>, today: string): PersonSummary[] {
  const by = new Map<string, PersonSummary>()
  for (const d of debts) {
    const k = personKey(d.person) + '|' + d.currency
    let p = by.get(k)
    if (!p) { p = { key: k, name: d.person.trim(), currency: d.currency, net: 0, open: 0, debts: [], overdue: 0, nextDue: null, lastActivity: null }; by.set(k, p) }
    p.debts.push(d)
    const ms = moves.get(d.id) ?? []
    const last = ms.length ? ms[ms.length - 1].local_date : null
    if (last && (!p.lastActivity || last > p.lastActivity)) p.lastActivity = last
    if (d.closed) continue
    const left = outstanding(d, ms)
    if (left === 0) continue
    p.open++
    p.net += d.direction === 'lent' ? left : -left
    if (d.due_date) {
      if (d.due_date < today) p.overdue++
      if (!p.nextDue || d.due_date < p.nextDue) p.nextDue = d.due_date
    }
  }
  return [...by.values()].sort((a, b) => Number(b.open > 0) - Number(a.open > 0) || Math.abs(b.net) - Math.abs(a.net) || a.name.localeCompare(b.name))
}

/** The start of a debt: its first linked movement, else when it was recorded. */
export function debtStart(d: DebtRec, ms: Move[]): string {
  const first = ms.find((m) => !isRepayment(d, m) && !isInterest(m))
  return first?.local_date ?? ms[0]?.local_date ?? new Date(d.created_at || Date.now()).toISOString().slice(0, 10)
}

/** A chronological statement for one person in one currency. */
export function statement(debts: DebtRec[], moves: Map<string, Move[]>): StatementRow[] {
  const events: Omit<StatementRow, 'balance'>[] = []
  for (const d of debts) {
    const ms = moves.get(d.id) ?? []
    const sign = d.direction === 'lent' ? 1 : -1
    const principalMove = ms.find((m) => !isRepayment(d, m) && !isInterest(m))
    const label = d.note ? ` · ${d.note}` : ''
    events.push({ date: debtStart(d, ms), text: (d.direction === 'lent' ? 'You lent' : 'You borrowed') + label + (principalMove ? '' : ' (no account linked)'), debtId: d.id, change: sign * d.amount, interest: 0 })
    for (const m of ms) {
      if (m === principalMove) continue
      if (isInterest(m)) events.push({ date: m.local_date, text: m.part === 'fee' ? 'Fee' : d.direction === 'lent' ? 'Interest received' : 'Interest paid', debtId: d.id, change: 0, interest: m.amount })
      else if (isRepayment(d, m)) events.push({ date: m.local_date, text: d.direction === 'lent' ? 'They paid you' : 'You paid them', debtId: d.id, change: -sign * m.amount, interest: 0 })
      // A second "loan" movement on the same debt: the debt amount is what counts, so this one is listed for reference only.
      else events.push({ date: m.local_date, text: 'Linked movement (the debt amount already covers it)', debtId: d.id, change: 0, interest: 0 })
    }
    if (d.closed) {
      const left = outstanding(d, ms)
      if (left !== 0) events.push({ date: ms.length ? ms[ms.length - 1].local_date : null, text: left > 0 ? 'Marked settled; the rest was written off' : 'Marked settled; overpayment kept', debtId: d.id, change: -sign * left, interest: 0 })
    }
  }
  events.sort((a, b) => (a.date ?? '9999').localeCompare(b.date ?? '9999'))
  let bal = 0
  return events.map((e) => ({ ...e, balance: (bal += e.change) }))
}

/**
 * Spreads a repayment over open debts, oldest first. Returns the allocation
 * and anything left over (more than is owed in total).
 */
export function allocate(amount: number, open: { d: DebtRec; left: number; start: string }[]): { parts: { debt: DebtRec; amount: number; settles: boolean }[]; rest: number } {
  let rest = amount
  const parts: { debt: DebtRec; amount: number; settles: boolean }[] = []
  for (const o of [...open].sort((a, b) => a.start.localeCompare(b.start) || (a.d.due_date ?? '9').localeCompare(b.d.due_date ?? '9'))) {
    if (rest <= 0) break
    if (o.left <= 0) continue
    const take = Math.min(rest, o.left)
    parts.push({ debt: o.d, amount: take, settles: take === o.left })
    rest -= take
  }
  return { parts, rest }
}
