import { useState } from 'react'
import { api, type Account, type Budget, type Category, type Debt, type Deposit, type Loan, type RecurringRule, type Tag } from '../lib/api'
import { useAccounts, useCategories, useCategoryIndex, useDebts, useLedger, useLoans, useTags } from '../lib/data'
import { allTransactions, accountStatement, budgetReport, depositReport, incomeExpense, ledger, loanReport, netWorthReport, personReport, presets, toSheets, type Ctx, type Report } from '../lib/reports'
import { currency } from '../lib/money'
import { csvEscape, download, isoDate, plural } from '../lib/util'
import { workbook, XLSX_TYPE, type Cell } from '../lib/xlsx'
import { Card, Field, Segmented, errorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, DateField } from '../controls'

/**
 * Statements & export (docs/02, "Reports"): choose a report and its filters,
 * then preview it as a printable document (Save as PDF) or download it as
 * Excel or CSV. Everything is built from the same report model, so the PDF and
 * the spreadsheet always show the same numbers.
 */

const KINDS = [
  ['summary', 'Income & spending', 'chart', 'Totals, categories, month by month and top payees for a period, compared with the period before.'],
  ['account', 'Account statement', 'bank', 'Opening balance, every transaction with a running balance, and the closing balance.'],
  ['ledger', 'Transaction list', 'list', 'Every transaction in a period with category, account, tags and status. Filter by account, category or type.'],
  ['loans', 'Loans & EMI schedule', 'hand', 'All loans, or one loan with payments made and the remaining schedule.'],
  ['deposits', 'Deposits & maturities', 'piggy', 'Every deposit with rate, term, current value and expected maturity.'],
  ['person', 'Statement with a person', 'users', 'Money lent and borrowed with one person, repayments and the running balance.'],
  ['networth', 'Net worth', 'scale', 'Assets, debts and money owed today, and month-end history.'],
  ['budgets', 'Budgets', 'target', 'Each active budget: limit, spent, left and % used.'],
] as const
export type ReportKind = (typeof KINDS)[number][0]

/** Builds a report from URL-style parameters; shared by this page and the print view. */
export async function buildReport(ctx: Ctx, p: Record<string, string>): Promise<Report> {
  const from = p.from ?? presets(isoDate())[0][2][0], to = p.to ?? presets(isoDate())[0][2][1]
  switch (p.kind) {
    case 'account': if (!p.account) throw new Error('Choose an account'); return accountStatement(ctx, p.account, from, to)
    case 'ledger': return ledger(ctx, from, to, { account: p.account, category: p.category, type: p.type, q: p.q })
    case 'loans': return loanReport(ctx, p.loan || undefined)
    case 'loan': return loanReport(ctx, p.id)
    case 'deposits': return depositReport(ctx)
    case 'person': if (!p.person) throw new Error('Choose a person'); return personReport(ctx, p.person, p.currency ?? ctx.base)
    case 'networth': return netWorthReport(ctx, Number(p.months ?? 12))
    case 'budgets': return budgetReport(ctx, p.date ?? isoDate())
    default: return incomeExpense(ctx, from, to)
  }
}

export function useReportCtx(): Ctx | null {
  const l = useLedger()
  const accounts = useAccounts()
  const categories = useCategories()
  const tags = useTags()
  if (!accounts.data || !categories.data || !tags.data) return null
  return { ledgerId: l.id, bookName: l.name, base: l.base_currency, accounts: accounts.data.filter((a) => !a.deleted), categories: categories.data.filter((c) => !c.deleted), tags: tags.data.filter((t) => !t.deleted) }
}

export function downloadReportXlsx(r: Report, bookName: string) {
  const bytes = workbook(toSheets(r, bookName))
  const slug = r.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
  download(`${slug}${r.period ? `-${r.period.from}-to-${r.period.to}` : ''}.xlsx`, new Blob([bytes], { type: XLSX_TYPE }))
}

export function Statements({ go }: PageProps) {
  const ctx = useReportCtx()
  const l = useLedger()
  const cats = useCategoryIndex()
  const debts = useDebts()
  const loans = useLoans()
  const today = isoDate()
  const ps = presets(today)
  const [kind, setKind] = useState<ReportKind>('summary')
  const [preset, setPreset] = useState('month')
  const [from, setFrom] = useState(ps[0][2][0])
  const [to, setTo] = useState(ps[0][2][1])
  const [account, setAccount] = useState('')
  const [category, setCategory] = useState('')
  const [type, setType] = useState('')
  const [person, setPerson] = useState('')
  const [loan, setLoan] = useState('')
  const [months, setMonths] = useState('12')
  const [busy, setBusy] = useState<string | null>(null)
  const accounts = (ctx?.accounts ?? []).filter((a) => !a.archived)
  const peopleList = [...new Map((debts.data ?? []).filter((d) => !d.deleted).map((d) => [d.person.trim().toLowerCase() + '|' + d.currency, { name: d.person.trim(), currency: d.currency }])).values()]
  const needsPeriod = kind === 'summary' || kind === 'account' || kind === 'ledger'
  const pick = (k: string) => { setPreset(k); const hit = ps.find(([id]) => id === k); if (hit) { setFrom(hit[2][0]); setTo(hit[2][1]) } }
  const personSel = peopleList.find((p) => `${p.name}|${p.currency}` === person) ?? peopleList[0]
  const params = (): Record<string, string> => {
    const p: Record<string, string> = { kind }
    if (needsPeriod) { p.from = from; p.to = to }
    if (kind === 'account') p.account = account || accounts[0]?.id || ''
    if (kind === 'ledger') { if (account) p.account = account; if (category) p.category = category; if (type) p.type = type }
    if (kind === 'loans' && loan) p.loan = loan
    if (kind === 'person' && personSel) { p.person = personSel.name; p.currency = personSel.currency }
    if (kind === 'networth') p.months = months
    return p
  }
  const invalid = needsPeriod && (!from || !to || to < from) ? 'The end date is before the start date' : kind === 'account' && !accounts.length ? 'Add an account first' : kind === 'person' && !personSel ? 'You have not recorded money lent or borrowed yet' : null
  const run = async (what: 'xlsx' | 'csv') => {
    if (!ctx || invalid) return
    setBusy(what)
    try {
      const r = await buildReport(ctx, params())
      if (what === 'xlsx') downloadReportXlsx(r, l.name)
      else {
        const t = r.sections.find((s) => s.table)?.table
        if (!t) throw new Error('This report has no table to export')
        const lines = [t.columns.map((c) => csvEscape(c.label)).join(','), ...t.rows.map((row) => row.map((v, i) => v === null ? '' : t.columns[i].kind === 'money' && typeof v === 'number' ? (v / 10 ** currency(r.currency).digits).toFixed(currency(r.currency).digits) : csvEscape(String(v))).join(','))]
        download(`${r.title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}.csv`, new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }))
      }
      toast('Downloaded')
    } catch (x) { toast(errorText(x)) } finally { setBusy(null) }
  }
  const preview = () => { if (!invalid) go('print', params()) }

  return (
    <>
      <div className="page-head"><h1>Statements & export</h1></div>
      <div className="tool-grid compact">
        {KINDS.map(([id, title, icon, body]) => (
          <button key={id} className={`tool-card ${kind === id ? 'on' : ''}`} onClick={() => setKind(id)} aria-pressed={kind === id}>
            <span className="acc-icon" style={{ ['--c' as string]: 'var(--brand)' }}><Icon name={icon} size={18} /></span>
            <strong>{title}</strong><span className="muted small">{body}</span>
          </button>
        ))}
      </div>
      <Card title={KINDS.find(([k]) => k === kind)?.[1]} className="mt">
        {needsPeriod && <>
          <div className="row date-chips">{ps.map(([id, label]) => <button key={id} type="button" className={`chip-btn ${preset === id ? 'on' : ''}`} onClick={() => pick(id)}>{label}</button>)}</div>
          <div className="two">
            <Field label="From"><DateField value={from} onChange={(e) => { setFrom(e.target.value); setPreset('') }} /></Field>
            <Field label="To"><DateField value={to} onChange={(e) => { setTo(e.target.value); setPreset('') }} /></Field>
          </div>
        </>}
        {(kind === 'account' || kind === 'ledger') && (
          <div className="two">
            <Field label="Account"><Select value={account} onChange={(e) => setAccount(e.target.value)}>{kind === 'ledger' && <option value="">All accounts</option>}{accounts.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}</Select></Field>
            {kind === 'ledger' && <Field label="Category"><Select value={category} onChange={(e) => setCategory(e.target.value)}><option value="">All categories</option><option value="none">Needs a category</option>{['expense', 'income'].flatMap((k) => cats.roots(k)).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></Field>}
          </div>
        )}
        {kind === 'ledger' && <Segmented label="Type" value={type} onChange={setType} options={[['', 'All'], ['expense', 'Spending'], ['income', 'Income'], ['transfer', 'Transfers']]} />}
        {kind === 'loans' && <Field label="Loan"><Select value={loan} onChange={(e) => setLoan(e.target.value)}><option value="">All loans (summary)</option>{(loans.data ?? []).filter((x) => !x.deleted).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select></Field>}
        {kind === 'person' && <Field label="Person"><Select value={personSel ? `${personSel.name}|${personSel.currency}` : ''} onChange={(e) => setPerson(e.target.value)}>{peopleList.map((p) => <option key={`${p.name}|${p.currency}`} value={`${p.name}|${p.currency}`}>{p.name} ({p.currency})</option>)}</Select></Field>}
        {kind === 'networth' && <Segmented label="History" value={months} onChange={setMonths} options={[['6', '6 months'], ['12', '12 months'], ['24', '2 years'], ['60', '5 years']]} />}
        {invalid && <p className="error">{invalid}</p>}
        <div className="row mt">
          <button className="primary" onClick={preview} disabled={!!invalid || !ctx}><Icon name="printer" size={16} /> Preview & save as PDF</button>
          <button onClick={() => run('xlsx')} disabled={!!invalid || !ctx || !!busy}><Icon name="table" size={16} /> {busy === 'xlsx' ? 'Preparing…' : 'Excel'}</button>
          <button onClick={() => run('csv')} disabled={!!invalid || !ctx || !!busy}><Icon name="download" size={16} /> {busy === 'csv' ? 'Preparing…' : 'CSV'}</button>
        </div>
        <p className="muted small">The preview opens a clean, paginated document. Use your browser's Print and choose “Save as PDF”. Every report shows its period, currency and filters.</p>
      </Card>
      <ExportAll />
    </>
  )
}

/** Everything in the book as one workbook with readable columns and IDs that keep the links between records. */
function ExportAll() {
  const l = useLedger()
  const [busy, setBusy] = useState(false)
  const run = async () => {
    setBusy(true)
    try {
      const [accounts, categories, tags, loans, deposits, debts, budgets, rules, txs] = await Promise.all([
        api.records<Account>(l.id, 'accounts'), api.records<Category>(l.id, 'categories'), api.records<Tag>(l.id, 'tags'), api.records<Loan>(l.id, 'loans'),
        api.records<Deposit>(l.id, 'deposits'), api.records<Debt>(l.id, 'debts'), api.records<Budget>(l.id, 'budgets'), api.records<RecurringRule>(l.id, 'recurring_rules'),
        allTransactions(l.id, {}),
      ])
      const live = <T,>(rs: { id: string; deleted: boolean; data: T }[]) => rs.filter((r) => !r.deleted)
      const acc = new Map(accounts.map((a) => [a.id, a.data]))
      const cat = new Map(categories.map((c) => [c.id, c.data]))
      const tag = new Map(tags.map((t) => [t.id, t.data.name]))
      const money = (v: number | null | undefined, code: string): Cell => v == null ? null : { money: v, digits: currency(code).digits }
      const d = (s: string | null | undefined): Cell => (s ? { date: s } : null)
      const catPath = (id: string | null) => { const c = id ? cat.get(id) : undefined; if (!c) return ''; const p = c.parent_id ? cat.get(c.parent_id) : undefined; return p ? `${p.name} › ${c.name}` : c.name }
      const bytes = workbook([
        { name: 'Transactions', intro: [`${l.name} · all transactions`, `Exported ${new Date().toLocaleString()} · amounts in each account's currency`], header: ['Date', 'Type', 'Status', 'Account', 'To account', 'Amount', 'Amount received', 'Currency', 'Category', 'Payee', 'Note', 'Tags', 'Part', 'Loan ID', 'Deposit ID', 'Debt ID', 'Adjustment', 'Excluded', 'ID'],
          rows: txs.map((t) => { const c = acc.get(t.account_id)?.currency ?? l.base_currency; return [d(t.local_date), t.type, t.status, acc.get(t.account_id)?.name ?? '', t.to_account_id ? acc.get(t.to_account_id)?.name ?? '' : '', money(t.amount, c), t.to_amount ? money(t.to_amount, acc.get(t.to_account_id ?? '')?.currency ?? c) : null, c, catPath(t.category_id), t.payee ?? '', t.note ?? '', t.tag_ids.map((x) => tag.get(x) ?? '').join(', '), t.part ?? '', t.loan_id ?? '', t.deposit_id ?? '', t.debt_id ?? '', t.is_adjustment ? 'yes' : '', t.excluded_from_reports ? 'yes' : '', t.id] }) },
        { name: 'Accounts', header: ['Name', 'Type', 'Currency', 'Opening balance', 'Credit limit', 'In net worth', 'Archived', 'Note', 'ID'], rows: live(accounts).map((a) => [a.data.name, a.data.type, a.data.currency, money(a.data.opening_balance, a.data.currency), money(a.data.credit_limit, a.data.currency), a.data.include_in_net_worth ? 'yes' : 'no', a.data.archived ? 'yes' : '', a.data.note ?? '', a.id]) },
        { name: 'Categories', header: ['Name', 'Kind', 'Parent', 'Nature', 'Archived', 'ID'], rows: live(categories).map((c) => [c.data.name, c.data.kind, c.data.parent_id ? cat.get(c.data.parent_id)?.name ?? '' : '', c.data.nature ?? '', c.data.archived ? 'yes' : '', c.id]) },
        { name: 'Loans', header: ['Name', 'Lender', 'Kind', 'Currency', 'Principal', 'Rate %', 'Start', 'First EMI', 'Tenure (months)', 'EMI (fixed)', 'Paid before', 'Closed', 'Account ID', 'ID'], rows: live(loans).map((x) => [x.data.name, x.data.lender ?? '', x.data.kind, x.data.currency, money(x.data.principal, x.data.currency), x.data.annual_rate ?? '0', d(x.data.start_date), d(x.data.first_due_date), x.data.tenure_months, money(x.data.emi_amount, x.data.currency), x.data.paid_before, x.data.closed ? 'yes' : '', x.data.account_id, x.id]) },
        { name: 'Deposits', header: ['Name', 'Institution', 'Kind', 'Currency', 'Amount', 'Rate %', 'Start', 'Maturity', 'Compounding', 'Payout', "Bank's maturity amount", 'Status', 'Account ID', 'ID'], rows: live(deposits).map((x) => [x.data.name, x.data.institution ?? '', x.data.kind, x.data.currency, money(x.data.amount, x.data.currency), x.data.annual_rate, d(x.data.start_date), d(x.data.maturity_date), x.data.compounding, x.data.payout, money(x.data.maturity_amount, x.data.currency), x.data.status, x.data.account_id, x.id]) },
        { name: 'Lent & borrowed', header: ['Person', 'Direction', 'Amount', 'Currency', 'Due', 'Settled', 'Note', 'ID'], rows: live(debts).map((x) => [x.data.person, x.data.direction, money(x.data.amount, x.data.currency), x.data.currency, d(x.data.due_date), x.data.closed ? 'yes' : '', x.data.note ?? '', x.id]) },
        { name: 'Budgets', header: ['Name', 'Amount', 'Currency', 'Period', 'Start', 'End', 'Categories', 'Alert %', 'Rollover', 'ID'], rows: live(budgets).map((x) => [x.data.name, money(x.data.amount, x.data.currency), x.data.currency, x.data.period, d(x.data.start_date), d(x.data.end_date), x.data.category_ids.map((c) => cat.get(c)?.name ?? '').join(', '), x.data.alert_percent, x.data.rollover ? 'yes' : '', x.id]) },
        { name: 'Recurring', header: ['Name', 'Type', 'Account', 'Amount', 'Category', 'Frequency', 'Every', 'Start', 'End', 'Mode', 'Paused', 'ID'], rows: live(rules).map((x) => [x.data.name ?? x.data.payee ?? '', x.data.type, acc.get(x.data.account_id)?.name ?? '', money(x.data.amount, acc.get(x.data.account_id)?.currency ?? l.base_currency), catPath(x.data.category_id), x.data.frequency, x.data.every, d(x.data.start_date), d(x.data.end_date ?? null), x.data.mode, x.data.paused ? 'yes' : '', x.id]) },
      ])
      download(`${l.name.replace(/[^\w-]+/g, '-')}-all-data-${isoDate()}.xlsx`, new Blob([bytes], { type: XLSX_TYPE }))
      toast(`Exported ${plural(txs.length, 'transaction')} and every list`)
    } catch (x) { toast(errorText(x)) } finally { setBusy(false) }
  }
  return (
    <Card title="All your data" className="mt">
      <div className="row">
        <button onClick={run} disabled={busy}><Icon name="table" size={16} /> {busy ? 'Preparing…' : 'Everything as Excel'}</button>
        <a className="button" href="#/settings?section=data"><Icon name="download" size={16} /> Full backup (JSON, restorable)</a>
      </div>
      <p className="muted small">The Excel file is for reading and analysis: one sheet per list, with IDs so you can match transactions to their loans, deposits and debts. To move or restore a book, use the JSON backup in Settings; it is the format LekhaMint can import back without duplicates.</p>
    </Card>
  )
}

