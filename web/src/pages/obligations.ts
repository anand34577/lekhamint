// Everything coming up, from every part of the app: recurring bills and
// income, EMIs, RD instalments, deposit maturities and personal debts. Used by
// the dashboard and the bills page. Expected items are never shown as paid.

import { useMemo } from 'react'
import { useAccounts, useBalances, useDebts, useRules } from '../lib/data'
import { dueDates } from '../lib/recurrence'
import { addDays, addMonths, isoDate } from '../lib/util'
import { useLoanStatuses } from './Loans'
import { useDepositEntries } from './Deposits'
import { outstanding } from '../lib/debts'
import { useDebtMoves } from './Debts'
import type { Route } from '../App'

export interface Obligation {
  key: string
  date: string
  title: string
  detail: string
  amount: number | null
  currency: string
  direction: 'out' | 'in'
  kind: 'bill' | 'income' | 'emi' | 'rd' | 'maturity' | 'payout' | 'debt'
  accountId: string | null
  overdue: boolean
  link: { route: Route; params: Record<string, string> }
}

export function useObligations(days = 30) {
  const rules = useRules()
  const accounts = useAccounts()
  const balances = useBalances()
  const loans = useLoanStatuses()
  const deposits = useDepositEntries()
  const debts = useDebts()
  const debtMoves = useDebtMoves()
  const today = isoDate()
  const until = addDays(today, days)
  const items = useMemo(() => {
    const out: Obligation[] = []
    const cur = (id: string | null) => accounts.data?.find((a) => a.id === id)?.currency ?? ''
    for (const r of rules.data ?? []) {
      if (r.deleted || r.paused || r.type === 'transfer') continue
      for (const d of dueDates(r, until, 4)) out.push({ key: `r${r.id}${d}`, date: d, title: r.name || r.payee || 'Bill', detail: r.mode === 'auto' ? 'records automatically' : 'recurring', amount: r.amount, currency: cur(r.account_id),
        direction: r.type === 'income' ? 'in' : 'out', kind: r.type === 'income' ? 'income' : 'bill', accountId: r.account_id, overdue: d < today, link: { route: 'recurring', params: {} } })
    }
    for (const { loan, status } of loans.list) {
      if (loan.closed) continue
      for (const row of status.projection.rows) {
        if (row.date > until) break
        out.push({ key: `l${loan.id}${row.n}`, date: row.date, title: `${loan.name} EMI`, detail: `instalment ${row.n}`, amount: row.payment, currency: loan.currency, direction: 'out', kind: 'emi', accountId: loan.pay_from_account_id, overdue: row.date < today, link: { route: 'loans', params: { id: loan.id } } })
      }
    }
    for (const x of deposits.list) {
      const d = x.dep
      if (d.status !== 'active') continue
      if (d.maturity_date <= until) out.push({ key: `d${d.id}`, date: d.maturity_date, title: `${d.name} matures`, detail: x.est.fromBank ? "bank's figure" : 'estimate', amount: x.est.maturity, currency: d.currency, direction: 'in', kind: 'maturity', accountId: d.payout_account_id, overdue: d.maturity_date < today, link: { route: 'deposits', params: { id: d.id } } })
      if (d.kind === 'rd') {
        for (let i = x.rdPaid; ; i++) {
          const due = addMonths(d.start_date, i)
          if (due > until || due >= d.maturity_date) break
          out.push({ key: `rd${d.id}${i}`, date: due, title: `${d.name} instalment`, detail: `RD instalment ${i + 1}`, amount: d.amount, currency: d.currency, direction: 'out', kind: 'rd', accountId: null, overdue: due < today, link: { route: 'deposits', params: { id: d.id } } })
          if (i - x.rdPaid > 12) break
        }
      }
      for (const p of x.est.payouts) if (p.date >= today && p.date <= until && p.date < d.maturity_date) out.push({ key: `p${d.id}${p.date}`, date: p.date, title: `${d.name} interest`, detail: 'expected payout', amount: p.amount, currency: d.currency, direction: 'in', kind: 'payout', accountId: d.payout_account_id, overdue: false, link: { route: 'deposits', params: { id: d.id } } })
    }
    for (const d of debts.data ?? []) {
      if (d.deleted || d.closed || !d.due_date || d.due_date > until) continue
      const left = outstanding(d, debtMoves.data?.get(d.id) ?? [])
      if (left <= 0) continue
      out.push({ key: `x${d.id}`, date: d.due_date, title: d.direction === 'lent' ? `${d.person} to repay you` : `Repay ${d.person}`, detail: 'lent & borrowed', amount: left, currency: d.currency, direction: d.direction === 'lent' ? 'in' : 'out', kind: 'debt', accountId: null, overdue: d.due_date < today, link: { route: 'debts', params: {} } })
    }
    return out.sort((a, b) => a.date.localeCompare(b.date) || (a.direction === 'out' ? -1 : 1))
  }, [rules.data, accounts.data, loans.list, deposits.list, debts.data, debtMoves.data, today, until])

  /** Outgoing items whose paying account does not have enough for everything due from it. */
  const shortfalls = useMemo(() => {
    const need = new Map<string, number>()
    const res: { accountId: string; name: string; need: number; have: number; currency: string; first: string }[] = []
    for (const o of items) if (o.direction === 'out' && o.accountId && o.amount) need.set(o.accountId, (need.get(o.accountId) ?? 0) + o.amount)
    for (const [id, n] of need) {
      const a = accounts.data?.find((x) => x.id === id)
      const have = balances.data?.find((b) => b.account_id === id)?.balance
      if (!a || have === undefined || a.type === 'credit_card') continue
      if (have < n) res.push({ accountId: id, name: a.name, need: n, have, currency: a.currency, first: items.find((o) => o.accountId === id && o.direction === 'out')!.date })
    }
    return res
  }, [items, accounts.data, balances.data])

  return { items, shortfalls, pending: rules.isPending || loans.pending || deposits.pending }
}
