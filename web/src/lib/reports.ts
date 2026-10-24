// One report model behind every statement, PDF and spreadsheet export
// (docs/02, "Reports"). Builders fetch exactly the records a report needs and
// return a Report; Print.tsx renders it as a document, toSheets() as Excel.
// Because both read the same object, a PDF and its spreadsheet always agree.

import { api, type Account, type BudgetProgress, type Category, type Debt, type Deposit, type Loan, type Tag, type Transaction } from './api'
import type { WithId } from './data'
import { estimate, depositKindLabel, tenureLabel } from './deposits'
import { people, statement, type Move } from './debts'
import { loanKindLabel, loanStatus } from './loans'
import { currency, formatMoney } from './money'
import { addDays, monthRange } from './util'
import type { Cell, Sheet } from './xlsx'

export type ColKind = 'text' | 'money' | 'date' | 'number'
export interface Column { label: string; kind: ColKind; width?: number }
export type Value = string | number | null
export interface Table { columns: Column[]; rows: Value[][]; total?: Value[]; empty?: string }
export interface Section { title: string; note?: string; table?: Table; stats?: Stat[] }
export interface Stat { label: string; value: number; tone?: 'pos' | 'neg' | 'muted'; hint?: string; /** A plain number, not money. */ count?: boolean }

export interface Report {
  kind: string
  title: string
  subtitle?: string
  period?: { from: string; to: string }
  currency: string
  filters: string[]
  stats: Stat[]
  sections: Section[]
  notes: string[]
}

export interface Ctx {
  ledgerId: string
  bookName: string
  base: string
  accounts: WithId<Account>[]
  categories: WithId<Category>[]
  tags: WithId<Tag>[]
}

const catName = (ctx: Ctx, id: string | null) => {
  if (!id) return 'Uncategorised'
  const c = ctx.categories.find((x) => x.id === id)
  if (!c) return 'Unknown category'
  const p = c.parent_id ? ctx.categories.find((x) => x.id === c.parent_id) : undefined
  return p ? `${p.name} › ${c.name}` : c.name
}
/** Category column: linked movements say what they are instead of "Uncategorised". */
const catLabel = (ctx: Ctx, t: Transaction) => t.type === 'transfer' ? (t.loan_id ? 'Loan repayment' : t.deposit_id ? 'Deposit' : 'Transfer')
  : t.debt_id && t.part !== 'interest' && t.part !== 'fee' ? 'Lent & borrowed' : t.is_adjustment ? 'Balance adjustment' : catName(ctx, t.category_id)
const accName = (ctx: Ctx, id: string | null | undefined) => ctx.accounts.find((a) => a.id === id)?.name ?? (id ? 'Deleted account' : '')

/** Every transaction matching [params], following pages. */
export async function allTransactions(ledgerId: string, params: Record<string, string>): Promise<(Transaction & { id: string })[]> {
  const out: (Transaction & { id: string })[] = []
  for (let cursor = '', more = true; more;) {
    const p = await api.transactions(ledgerId, { ...params, limit: '500', ...(cursor ? { cursor } : {}) })
    out.push(...p.transactions.map((r) => ({ ...r.data, id: r.id })))
    cursor = p.next_cursor ?? ''; more = !!cursor
  }
  return out.sort((a, b) => a.local_date.localeCompare(b.local_date) || a.occurred_at - b.occurred_at)
}

function describe(ctx: Ctx, t: Transaction): string {
  if (t.type === 'transfer') return `${accName(ctx, t.account_id)} → ${accName(ctx, t.to_account_id)}${t.note ? ` · ${t.note}` : ''}`
  return [t.payee, t.note].filter(Boolean).join(' · ') || catName(ctx, t.category_id)
}

const kindLabel = (t: Transaction) => t.type === 'transfer' ? 'Transfer' : t.part === 'interest' ? 'Interest' : t.part === 'fee' ? 'Fee' : t.debt_id ? (t.type === 'income' ? 'Money in (debt)' : 'Money out (debt)') : t.is_adjustment ? 'Adjustment' : t.type === 'income' ? 'Income' : 'Expense'

// --- Account statement -------------------------------------------------------------

export async function accountStatement(ctx: Ctx, accountId: string, from: string, to: string): Promise<Report> {
  const acc = ctx.accounts.find((a) => a.id === accountId)
  if (!acc) throw new Error('That account no longer exists')
  // Balance at the end of the day before [from]: the server sums every non-planned transaction up to then.
  const [balances, before] = await Promise.all([api.balances(ctx.ledgerId), api.balancesAsOf(ctx.ledgerId, addDays(from, -1))])
  const opening = before.find((b) => b.account_id === accountId)?.balance ?? acc.opening_balance
  const txs = (await allTransactions(ctx.ledgerId, { account_id: accountId, from, to }))
  let bal = opening, inSum = 0, outSum = 0
  const rows: Value[][] = []
  for (const t of txs) {
    const planned = t.status === 'planned'
    let delta = 0
    if (t.type === 'transfer') delta = t.to_account_id === accountId && t.account_id !== accountId ? (t.to_amount ?? t.amount) : -t.amount
    else delta = t.type === 'income' ? t.amount : -t.amount
    if (!planned) { bal += delta; if (delta > 0) inSum += delta; else outSum -= delta }
    rows.push([t.local_date, describe(ctx, t) + (planned ? ' (planned, not counted)' : '') + (t.status === 'pending' ? ' (pending)' : ''), catLabel(ctx, t), delta > 0 ? delta : null, delta < 0 ? -delta : null, planned ? null : bal])
  }
  const now = balances.find((b) => b.account_id === accountId)?.balance ?? bal
  return {
    kind: 'account', title: `Account statement · ${acc.name}`, subtitle: acc.note ?? undefined, period: { from, to }, currency: acc.currency,
    filters: [`Account: ${acc.name} (${acc.type.replace('_', ' ')})`],
    stats: [{ label: 'Opening balance', value: opening }, { label: 'Money in', value: inSum, tone: 'pos' }, { label: 'Money out', value: outSum, tone: 'neg' }, { label: 'Closing balance', value: bal }],
    sections: [{ title: 'Transactions', table: { columns: [{ label: 'Date', kind: 'date' }, { label: 'Description', kind: 'text', width: 40 }, { label: 'Category', kind: 'text', width: 24 }, { label: 'In', kind: 'money' }, { label: 'Out', kind: 'money' }, { label: 'Balance', kind: 'money' }],
      rows, total: ['', 'Totals', '', inSum, outSum, bal], empty: 'No transactions in this period.' } }],
    notes: [
      `Opening + in − out = closing: ${formatMoney(opening, acc.currency)} + ${formatMoney(inSum, acc.currency)} − ${formatMoney(outSum, acc.currency)} = ${formatMoney(bal, acc.currency)}.`,
      ...(to < new Date().toISOString().slice(0, 10) ? [`Balance today: ${formatMoney(now, acc.currency)}.`] : []),
      'Planned transactions are listed but do not change the balance. Balance adjustments from reconciling are included.',
    ],
  }
}

// --- Income & spending ---------------------------------------------------------------

export async function incomeExpense(ctx: Ctx, from: string, to: string): Promise<Report> {
  const [cur, prev, trend, payees] = await Promise.all([
    api.summary(ctx.ledgerId, from, to),
    (() => { const days = Math.round((Date.parse(to) - Date.parse(from)) / 864e5); return api.summary(ctx.ledgerId, addDays(from, -days - 1), addDays(from, -1)) })(),
    api.trend(ctx.ledgerId, from, to, 'month'),
    api.payeeTotals(ctx.ledgerId, from, to, 15),
  ])
  const c = cur.find((x) => x.currency === ctx.base) ?? { currency: ctx.base, income: 0, expense: 0, net: 0, expense_by_category: [], income_by_category: [] }
  const p = prev.find((x) => x.currency === ctx.base)
  const rollup = (list: typeof c.expense_by_category) => {
    const m = new Map<string, number>()
    for (const x of list) {
      const cat = ctx.categories.find((k) => k.id === x.category_id)
      const root = cat?.parent_id && ctx.categories.some((k) => k.id === cat.parent_id) ? cat.parent_id : x.category_id ?? 'none'
      m.set(root, (m.get(root) ?? 0) + x.amount)
    }
    return [...m].filter(([, v]) => v !== 0).sort((a, b) => b[1] - a[1])
  }
  const prevRoots = new Map(rollup(p?.expense_by_category ?? []))
  const expRows = rollup(c.expense_by_category).map(([id, v]) => [catName(ctx, id === 'none' ? null : id), v, c.expense ? +(v / c.expense * 100).toFixed(1) : 0, prevRoots.get(id) ?? 0, v - (prevRoots.get(id) ?? 0)] as Value[])
  const incRows = rollup(c.income_by_category).map(([id, v]) => [catName(ctx, id === 'none' ? null : id), v, c.income ? +(v / c.income * 100).toFixed(1) : 0] as Value[])
  const months = trend.filter((t) => t.currency === ctx.base).map((t) => [t.period, t.income, t.expense, t.income - t.expense] as Value[])
  const rate = c.income > 0 ? ((c.income - c.expense) / c.income) * 100 : null
  const others = cur.filter((x) => x.currency !== ctx.base)
  return {
    kind: 'summary', title: 'Income & spending', period: { from, to }, currency: ctx.base, filters: [],
    stats: [{ label: 'Income', value: c.income, tone: 'pos' }, { label: 'Spending', value: c.expense, tone: 'neg' }, { label: c.net >= 0 ? 'Saved' : 'Overspent', value: Math.abs(c.net), tone: c.net >= 0 ? 'pos' : 'neg', hint: rate !== null ? `${rate.toFixed(1)}% of income` : undefined },
      ...(p ? [{ label: 'Spending, previous period', value: p.expense, tone: 'muted' as const, hint: p.expense ? `${c.expense >= p.expense ? '+' : ''}${(((c.expense - p.expense) / p.expense) * 100).toFixed(1)}% now` : undefined }] : [])],
    sections: [
      { title: 'Spending by category', table: { columns: [{ label: 'Category', kind: 'text', width: 32 }, { label: 'Amount', kind: 'money' }, { label: '% of spending', kind: 'number' }, { label: 'Previous period', kind: 'money' }, { label: 'Change', kind: 'money' }], rows: expRows, total: ['Total', c.expense, 100, p?.expense ?? 0, c.expense - (p?.expense ?? 0)], empty: 'No spending in this period.' } },
      { title: 'Income by source', table: { columns: [{ label: 'Source', kind: 'text', width: 32 }, { label: 'Amount', kind: 'money' }, { label: '% of income', kind: 'number' }], rows: incRows, total: ['Total', c.income, 100], empty: 'No income in this period.' } },
      ...(months.length > 1 ? [{ title: 'Month by month', table: { columns: [{ label: 'Month', kind: 'text' as const }, { label: 'Income', kind: 'money' as const }, { label: 'Spending', kind: 'money' as const }, { label: 'Net', kind: 'money' as const }], rows: months, total: ['Total', c.income, c.expense, c.net] } }] : []),
      { title: 'Top payees', table: { columns: [{ label: 'Payee', kind: 'text', width: 30 }, { label: 'Spent', kind: 'money' }, { label: 'Received', kind: 'money' }, { label: 'Payments', kind: 'number' }, { label: 'Last', kind: 'date' }],
        rows: payees.filter((x) => x.currency === ctx.base).map((x) => [x.payee, x.expense, x.income, x.count, x.last_date]), empty: 'No payees recorded.' } },
    ],
    notes: [
      'Transfers between your own accounts, loan principal, money lent or borrowed, deposit movements and balance adjustments are not income or spending. Interest and fees are.',
      'Refunds recorded as income in a spending category reduce that category. Split transactions count under each split category.',
      ...(others.length ? [`Also recorded in other currencies, not converted here: ${others.map((o) => `${o.currency} income ${formatMoney(o.income, o.currency)}, spending ${formatMoney(o.expense, o.currency)}`).join('; ')}.`] : []),
    ],
  }
}

// --- Transaction ledger -------------------------------------------------------------------

export async function ledger(ctx: Ctx, from: string, to: string, f: { account?: string; category?: string; type?: string; q?: string }): Promise<Report> {
  const txs = await allTransactions(ctx.ledgerId, { from, to, ...(f.account ? { account_id: f.account } : {}), ...(f.category ? { category_id: f.category } : {}), ...(f.type ? { type: f.type } : {}), ...(f.q ? { q: f.q } : {}) })
  const tagName = (id: string) => ctx.tags.find((t) => t.id === id)?.name ?? ''
  const cur = (t: Transaction) => ctx.accounts.find((a) => a.id === t.account_id)?.currency ?? ctx.base
  const sameCur = new Set(txs.map(cur)).size <= 1
  const code = sameCur && txs.length ? cur(txs[0]) : ctx.base
  let inSum = 0, outSum = 0
  const rows = txs.map((t) => {
    const signed = t.type === 'income' ? t.amount : t.type === 'expense' ? -t.amount : 0
    if (cur(t) === code && t.status !== 'planned') { if (signed > 0) inSum += signed; else outSum -= signed }
    return [t.local_date, kindLabel(t), describe(ctx, t), catLabel(ctx, t), accName(ctx, t.account_id), cur(t), t.type === 'transfer' ? -t.amount : signed, t.tag_ids.map(tagName).filter(Boolean).join(', '), t.status] as Value[]
  })
  const filters = [f.account && `Account: ${accName(ctx, f.account)}`, f.category && `Category: ${catName(ctx, f.category)}`, f.type && `Type: ${f.type}`, f.q && `Search: “${f.q}”`].filter(Boolean) as string[]
  return {
    kind: 'ledger', title: 'Transactions', period: { from, to }, currency: code, filters,
    stats: [{ label: 'Transactions', value: txs.length, tone: 'muted', count: true }, { label: 'Money in', value: inSum, tone: 'pos' }, { label: 'Money out', value: outSum, tone: 'neg' }],
    sections: [{ title: 'All transactions', table: { columns: [{ label: 'Date', kind: 'date' }, { label: 'Type', kind: 'text' }, { label: 'Description', kind: 'text', width: 36 }, { label: 'Category', kind: 'text', width: 22 }, { label: 'Account', kind: 'text' }, { label: 'Currency', kind: 'text' }, { label: 'Amount', kind: 'money' }, { label: 'Tags', kind: 'text' }, { label: 'Status', kind: 'text' }], rows, empty: 'No transactions match.' } }],
    notes: [sameCur ? 'Money in and out exclude transfers and planned items.' : `Amounts are in each account's own currency; the totals above count only ${code}.`],
  }
}

// --- Loans ------------------------------------------------------------------------------------

export async function loanReport(ctx: Ctx, loanId?: string): Promise<Report> {
  const [loans, balances, moves] = await Promise.all([api.records<Loan>(ctx.ledgerId, 'loans'), api.balances(ctx.ledgerId), allTransactions(ctx.ledgerId, { loan_id: 'any' })])
  const today = new Date().toISOString().slice(0, 10)
  const live = loans.filter((r) => !r.deleted && (!loanId || r.id === loanId))
  if (loanId && !live.length) throw new Error('That loan no longer exists')
  const entries = live.map((r) => {
    const l = r.data
    const ms = moves.filter((m) => m.loan_id === r.id)
    return { id: r.id, l, ms, s: loanStatus(l, ms, balances.find((b) => b.account_id === l.account_id)?.balance ?? 0, today, l.account_id) }
  })
  const code = entries[0]?.l.currency ?? ctx.base
  if (loanId) {
    const { l, ms, s } = entries[0]
    const paid = ms.filter((m) => m.part).reduce((acc, m) => { const g = acc.get(m.local_date) ?? { p: 0, i: 0, f: 0, pre: 0 }; const a = m.type === 'transfer' ? (m.to_amount ?? m.amount) : m.amount; if (m.part === 'principal') g.p += a; else if (m.part === 'prepayment') g.pre += a; else if (m.part === 'interest') g.i += a; else g.f += a; acc.set(m.local_date, g); return acc }, new Map<string, { p: number; i: number; f: number; pre: number }>())
    return {
      kind: 'loan', title: `Loan statement · ${l.name}`, subtitle: [loanKindLabel(l.kind), l.lender].filter(Boolean).join(' · '), currency: l.currency, filters: [],
      stats: [{ label: 'Borrowed', value: l.principal }, { label: 'Outstanding', value: s.outstanding, tone: 'neg' }, { label: 'EMI', value: s.emi }, { label: 'Interest paid', value: s.interestPaid, tone: 'muted' }, { label: 'Interest still to pay', value: s.projectedInterest, tone: 'muted' }],
      sections: [
        { title: 'Terms', table: { columns: [{ label: 'Term', kind: 'text', width: 28 }, { label: 'Value', kind: 'text', width: 40 }], rows: [
          ['Interest', l.annual_rate ? `${l.annual_rate}% a year (${l.rate_type})` : 'Interest-free'], ['Started', l.start_date], ['First EMI', l.first_due_date], ['Tenure', `${l.tenure_months} months`],
          ['Instalments', `${s.paidInstalments} paid, ${s.remainingInstalments} left`], ['Next due', s.nextDue ?? '—'], ['Expected end', s.endDate ?? '—']] } },
        { title: 'Payments made', table: { columns: [{ label: 'Date', kind: 'date' }, { label: 'Principal', kind: 'money' }, { label: 'Prepaid', kind: 'money' }, { label: 'Interest', kind: 'money' }, { label: 'Fees', kind: 'money' }, { label: 'Total', kind: 'money' }],
          rows: [...paid].map(([d, g]) => [d, g.p, g.pre, g.i, g.f, g.p + g.pre + g.i + g.f]), total: ['Total', s.principalPaid, s.prepaid, s.interestPaid, s.feesPaid, s.principalPaid + s.prepaid + s.interestPaid + s.feesPaid], empty: 'No payments recorded.' } },
        { title: 'Remaining schedule (projected)', table: { columns: [{ label: '#', kind: 'number' }, { label: 'Due', kind: 'date' }, { label: 'Payment', kind: 'money' }, { label: 'Principal', kind: 'money' }, { label: 'Interest', kind: 'money' }, { label: 'Balance after', kind: 'money' }],
          rows: s.projection.rows.map((r) => [r.n, r.date, r.payment, r.principal, r.interest, r.closing]), total: ['', 'Total', s.projection.totalPayment, s.outstanding, s.projection.totalInterest, 0], empty: 'Nothing left to pay.' } },
      ],
      notes: ['The remaining schedule assumes interest is charged monthly on the reducing balance at the current rate. Your lender may round or count days differently, so treat it as an estimate, not a lender statement.', ...(l.paid_before ? [`${l.paid_before} instalments were paid before tracking started and are not itemised.`] : [])],
    }
  }
  const open = entries.filter((e) => !e.l.closed)
  return {
    kind: 'loans', title: 'Loans & EMIs', currency: code, filters: [],
    stats: [{ label: 'Outstanding', value: open.filter((e) => e.l.currency === code).reduce((a, e) => a + e.s.outstanding, 0), tone: 'neg' }, { label: 'Monthly EMIs', value: open.filter((e) => e.l.currency === code).reduce((a, e) => a + e.s.emi, 0) }, { label: 'Interest still to pay', value: open.filter((e) => e.l.currency === code).reduce((a, e) => a + e.s.projectedInterest, 0), tone: 'muted' }],
    sections: [{ title: 'All loans', table: { columns: [{ label: 'Loan', kind: 'text', width: 24 }, { label: 'Lender', kind: 'text' }, { label: 'Rate %', kind: 'text' }, { label: 'Borrowed', kind: 'money' }, { label: 'Outstanding', kind: 'money' }, { label: 'EMI', kind: 'money' }, { label: 'Next due', kind: 'date' }, { label: 'Left', kind: 'number' }, { label: 'Status', kind: 'text' }],
      rows: entries.map((e) => [e.l.name, e.l.lender ?? '', e.l.annual_rate ?? '0', e.l.principal, e.s.outstanding, e.s.emi, e.s.nextDue, e.s.remainingInstalments, e.l.closed ? 'Closed' : e.s.overdue ? 'Overdue' : 'Active']), empty: 'No loans recorded.' } }],
    notes: ['Outstanding is the balance of each loan account. Projections assume monthly interest on the reducing balance.'],
  }
}

// --- Deposits -------------------------------------------------------------------------------

export async function depositReport(ctx: Ctx): Promise<Report> {
  const [deps, balances] = await Promise.all([api.records<Deposit>(ctx.ledgerId, 'deposits'), api.balances(ctx.ledgerId)])
  const today = new Date().toISOString().slice(0, 10)
  const live = deps.filter((r) => !r.deleted).map((r) => ({ d: r.data, e: estimate(r.data, today), bal: balances.find((b) => b.account_id === r.data.account_id)?.balance ?? 0 })).sort((a, b) => a.d.maturity_date.localeCompare(b.d.maturity_date))
  const active = live.filter((x) => x.d.status === 'active' && x.d.currency === ctx.base)
  return {
    kind: 'deposits', title: 'Deposits & maturity schedule', currency: ctx.base, filters: [],
    stats: [{ label: 'In active deposits', value: active.reduce((a, x) => a + x.bal, 0) }, { label: 'Expected at maturity', value: active.reduce((a, x) => a + x.e.maturity, 0), tone: 'pos' }, { label: 'Interest over the terms', value: active.reduce((a, x) => a + x.e.interest, 0), tone: 'muted' }],
    sections: [{ title: 'Deposits', table: { columns: [{ label: 'Deposit', kind: 'text', width: 24 }, { label: 'Type', kind: 'text' }, { label: 'Rate %', kind: 'text' }, { label: 'Start', kind: 'date' }, { label: 'Matures', kind: 'date' }, { label: 'Term', kind: 'text' }, { label: 'Now', kind: 'money' }, { label: 'At maturity', kind: 'money' }, { label: 'Basis', kind: 'text' }, { label: 'Status', kind: 'text' }],
      rows: live.map((x) => [x.d.name + (x.d.institution ? ` (${x.d.institution})` : ''), depositKindLabel(x.d.kind), x.d.annual_rate, x.d.start_date, x.d.maturity_date, tenureLabel(x.d.start_date, x.d.maturity_date), x.bal, x.e.maturity, x.e.fromBank ? 'Bank' : 'Estimate', x.d.status]), empty: 'No deposits recorded.' } }],
    notes: ['“Now” is the money recorded in the deposit (principal plus interest credited). Maturity values are estimates from the terms unless the bank\'s figure was entered; tax and early closure can change them.'],
  }
}

// --- Person statement -------------------------------------------------------------------------

export async function personReport(ctx: Ctx, person: string, cur: string): Promise<Report> {
  const [debts, moves] = await Promise.all([api.records<Debt>(ctx.ledgerId, 'debts'), allTransactions(ctx.ledgerId, { debt_id: 'any' })])
  const live = debts.filter((r) => !r.deleted).map((r) => ({ ...r.data, id: r.id }))
  const mv = new Map<string, Move[]>()
  for (const m of moves) if (m.debt_id) mv.set(m.debt_id, [...(mv.get(m.debt_id) ?? []), m])
  const p = people(live, mv, new Date().toISOString().slice(0, 10)).find((x) => x.name.toLowerCase() === person.trim().toLowerCase() && x.currency === cur)
  if (!p) throw new Error(`No records with ${person} in ${cur}`)
  const rows = statement(p.debts, mv)
  return {
    kind: 'person', title: `Statement with ${p.name}`, currency: cur, filters: [],
    stats: [{ label: p.net > 0 ? `${p.name} owes you` : p.net < 0 ? `You owe ${p.name}` : 'Settled', value: Math.abs(p.net), tone: p.net > 0 ? 'pos' : p.net < 0 ? 'neg' : 'muted' }, { label: 'Open debts', value: p.open, tone: 'muted', count: true }],
    sections: [{ title: 'History', table: { columns: [{ label: 'Date', kind: 'date' }, { label: 'What', kind: 'text', width: 40 }, { label: 'Amount', kind: 'money' }, { label: 'Interest', kind: 'money' }, { label: 'Balance', kind: 'money' }],
      rows: rows.map((r) => [r.date, r.text, r.change || null, r.interest || null, r.balance]), empty: 'Nothing recorded.' } }],
    notes: ['Balance: positive means they owe you, negative means you owe them. Interest is shown separately and does not change the balance.'],
  }
}

// --- Net worth -------------------------------------------------------------------------------

export async function netWorthReport(ctx: Ctx, months: number): Promise<Report> {
  const pts = await api.netWorth(ctx.ledgerId, months)
  const at = (i: number) => pts[i]?.positions.find((p) => p.currency === ctx.base)
  const last = at(pts.length - 1), first = at(0)
  const balances = await api.balances(ctx.ledgerId)
  const accRows = balances.filter((b) => !b.archived && b.include_in_net_worth && b.currency === ctx.base).map((b) => [b.name, b.type.replace('_', ' '), b.balance] as Value[])
  return {
    kind: 'networth', title: 'Net worth', period: pts.length ? { from: pts[0].date, to: pts[pts.length - 1].date } : undefined, currency: ctx.base, filters: [],
    stats: [{ label: 'Net worth', value: last?.net_worth ?? 0 }, { label: 'Assets', value: (last?.cash ?? 0) + (last?.investments ?? 0) + (last?.receivable ?? 0), tone: 'pos' }, { label: 'Debts', value: (last?.liabilities ?? 0) + (last?.payable ?? 0), tone: 'neg' }, { label: 'Change in period', value: (last?.net_worth ?? 0) - (first?.net_worth ?? 0), tone: 'muted' }],
    sections: [
      { title: 'Today', table: { columns: [{ label: 'Item', kind: 'text', width: 30 }, { label: 'Amount', kind: 'money' }], rows: [['Cash & bank', last?.cash ?? 0], ['Investments & deposits', last?.investments ?? 0], ['Owed to you', last?.receivable ?? 0], ['Cards & loans', -(last?.liabilities ?? 0)], ['You owe people', -(last?.payable ?? 0)]], total: ['Net worth', last?.net_worth ?? 0] } },
      { title: 'Accounts', table: { columns: [{ label: 'Account', kind: 'text', width: 30 }, { label: 'Type', kind: 'text' }, { label: 'Balance', kind: 'money' }], rows: accRows } },
      { title: 'Month by month', table: { columns: [{ label: 'Month end', kind: 'date' }, { label: 'Cash & bank', kind: 'money' }, { label: 'Investments', kind: 'money' }, { label: 'Owed to you', kind: 'money' }, { label: 'Cards & loans', kind: 'money' }, { label: 'You owe', kind: 'money' }, { label: 'Net worth', kind: 'money' }],
        rows: pts.map((x) => { const p = x.positions.find((q) => q.currency === ctx.base); return [x.date, p?.cash ?? 0, p?.investments ?? 0, p?.receivable ?? 0, -(p?.liabilities ?? 0), -(p?.payable ?? 0), p?.net_worth ?? 0] }) } },
    ],
    notes: ['Credit limits are not assets. Deposits and investments are shown at the money recorded in them, not market value. Archived accounts and accounts excluded from net worth are left out.'],
  }
}

// --- Budgets ---------------------------------------------------------------------------------

export async function budgetReport(ctx: Ctx, date: string): Promise<Report> {
  const [prog, budgets] = await Promise.all([api.budgetProgress(ctx.ledgerId, date), api.records<{ name: string; currency: string }>(ctx.ledgerId, 'budgets')])
  const name = (id: string) => budgets.find((b) => b.id === id)?.data.name ?? 'Budget'
  const rows = prog.filter((p: BudgetProgress) => p.active).map((p) => [name(p.budget_id), `${p.window.start} – ${p.window.end}`, p.limit, p.spent, p.limit - p.spent, p.limit ? +((p.spent / p.limit) * 100).toFixed(1) : 0] as Value[])
  return {
    kind: 'budgets', title: 'Budgets', subtitle: `As of ${date}`, currency: ctx.base, filters: [],
    stats: [{ label: 'Budgeted', value: prog.filter((p) => p.active && p.currency === ctx.base).reduce((a, p) => a + p.limit, 0) }, { label: 'Spent', value: prog.filter((p) => p.active && p.currency === ctx.base).reduce((a, p) => a + p.spent, 0), tone: 'neg' }],
    sections: [{ title: 'Progress', table: { columns: [{ label: 'Budget', kind: 'text', width: 24 }, { label: 'Period', kind: 'text', width: 24 }, { label: 'Limit', kind: 'money' }, { label: 'Spent', kind: 'money' }, { label: 'Left', kind: 'money' }, { label: '% used', kind: 'number' }], rows, empty: 'No active budgets.' } }],
    notes: ['Limits include anything rolled over from earlier periods. Spending is net of refunds.'],
  }
}

// --- Spreadsheet --------------------------------------------------------------------------

export function toSheets(r: Report, bookName: string): Sheet[] {
  const digits = currency(r.currency).digits
  const intro = [`${r.title} · ${bookName}`, [r.period ? `${r.period.from} to ${r.period.to}` : null, `Amounts in ${r.currency}`, `Generated ${new Date().toLocaleString()}`, ...r.filters].filter(Boolean).join(' · ')]
  const conv = (v: Value, c: Column): Cell => v === null || v === '' ? null : c.kind === 'money' && typeof v === 'number' ? { money: v, digits } : c.kind === 'date' && typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) ? { date: v } : v
  const sheets: Sheet[] = [{ name: 'Summary', intro, header: ['Measure', 'Amount'], rows: r.stats.map((s) => [s.label, s.count ? s.value : { money: s.value, digits }]), widths: [34, 18] }]
  for (const sec of r.sections) {
    if (!sec.table) continue
    const t = sec.table
    sheets.push({ name: sec.title, intro: [sec.title, ...(sec.note ? [sec.note] : [])], header: t.columns.map((c) => c.label), rows: t.rows.map((row) => row.map((v, i) => conv(v, t.columns[i]))), total: t.total?.map((v, i) => conv(v, t.columns[i])), widths: t.columns.map((c) => c.width ?? (c.kind === 'money' ? 16 : c.kind === 'date' ? 12 : 14)) })
  }
  sheets[0].rows.push([null, null], ...r.notes.map((n) => [n, null] as Cell[]))
  return sheets
}

/** Period presets used by the statements page and the print view. */
export function presets(today: string, fyStartMonth = 4): [string, string, [string, string]][] {
  const y = Number(today.slice(0, 4)), mo = Number(today.slice(5, 7))
  const q0 = Math.floor((mo - 1) / 3) * 3 + 1
  const fy = mo >= fyStartMonth ? y : y - 1
  const pad = (n: number) => String(n).padStart(2, '0')
  const lastDay = (yy: number, mm: number) => new Date(yy, mm, 0).getDate()
  return [
    ['month', 'This month', monthRange(today)],
    ['last-month', 'Last month', monthRange(today, -1)],
    ['quarter', 'This quarter', [`${y}-${pad(q0)}-01`, `${y}-${pad(q0 + 2)}-${lastDay(y, q0 + 2)}`]],
    ['fy', `Financial year ${fy}–${String(fy + 1).slice(2)}`, [`${fy}-${pad(fyStartMonth)}-01`, `${fy + 1}-${pad(fyStartMonth - 1 || 12)}-${lastDay(fyStartMonth === 1 ? fy : fy + 1, fyStartMonth - 1 || 12)}`]],
    ['last-fy', `Financial year ${fy - 1}–${String(fy).slice(2)}`, [`${fy - 1}-${pad(fyStartMonth)}-01`, `${fy}-${pad(fyStartMonth - 1 || 12)}-${lastDay(fyStartMonth === 1 ? fy - 1 : fy, fyStartMonth - 1 || 12)}`]],
    ['year', `Year ${y}`, [`${y}-01-01`, `${y}-12-31`]],
    ['last-year', `Year ${y - 1}`, [`${y - 1}-01-01`, `${y - 1}-12-31`]],
    ['12m', 'Last 12 months', [monthRange(today, -11)[0], monthRange(today)[1]]],
  ]
}
