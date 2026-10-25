import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type CategoryTotal } from '../lib/api'
import { allTransactions } from '../lib/reports'
import { useCanWrite, useCategoryIndex, useLedger, useLoans, useWrite } from '../lib/data'
import { TAX_SECTIONS, financialYear, fyMonths, fyStartMonth, setFyStartMonth, taxSection } from '../lib/tax'
import { toPlain } from '../lib/money'
import { csvEscape, download, isoDate, monthLabel } from '../lib/util'
import { Card, Empty, Money, Skeleton, errorText, toast } from '../components'
import { Columns, Explain, Loading, Meter, RankBars, SERIES } from '../charts'
import { Select } from '../controls'
import { Icon } from '../icons'
import type { PageProps } from '../App'

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

interface TaxLine { section: string; source: string; amount: number; auto?: boolean }

/** The financial year in one place: income, spending, saving, and what counts for tax. */
export function YearTax({ go }: PageProps) {
  const l = useLedger()
  const cats = useCategoryIndex()
  const loans = useLoans()
  const [start, setStart] = useState(() => fyStartMonth(l.id, l.base_currency))
  const [offset, setOffset] = useState(0)
  const [from, to, label] = financialYear(isoDate(), start, offset)
  const cur = l.base_currency
  const summary = useQuery({ queryKey: [l.id, 'summary', from, to], queryFn: () => api.summary(l.id, from, to) })
  const trend = useQuery({ queryKey: [l.id, 'trend', from, to], queryFn: () => api.trend(l.id, from, to) })
  const linked = useQuery({
    queryKey: [l.id, 'transactions', 'year-linked', from, to],
    queryFn: async () => [...await allTransactions(l.id, { from, to, loan_id: 'any' }), ...await allTransactions(l.id, { from, to, deposit_id: 'any' })],
  })
  const s = summary.data?.find((x) => x.currency === cur)
  const income = s?.income ?? 0, spent = s?.expense ?? 0, saved = income - spent

  // Spending rolled up to top-level categories.
  const topCats = useMemo(() => {
    const m = new Map<string, number>()
    for (const c of s?.expense_by_category ?? []) { const k = cats.rootOf(c.category_id) ?? ''; m.set(k, (m.get(k) ?? 0) + c.amount) }
    return [...m].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).slice(0, 8)
  }, [s, cats])

  // Tax lines: tagged categories (a sub-category without its own tag uses its parent's), plus loans and deposits.
  const lines = useMemo(() => {
    const out: TaxLine[] = []
    const sectionOf = (id: string | null) => {
      const c = id ? cats.byId.get(id) : undefined
      return c?.tax_section ?? (c?.parent_id ? cats.byId.get(c.parent_id)?.tax_section : null) ?? null
    }
    for (const c of s?.expense_by_category ?? []) {
      const sec = sectionOf(c.category_id)
      if (sec && c.amount > 0) out.push({ section: sec, source: cats.path(c.category_id), amount: c.amount })
    }
    for (const c of s?.income_by_category ?? ([] as CategoryTotal[])) {
      const sec = sectionOf(c.category_id)
      if (sec === '80TTA' && c.amount > 0) out.push({ section: sec, source: cats.path(c.category_id), amount: c.amount })
    }
    const loanKind = new Map((loans.data ?? []).filter((x) => !x.deleted).map((x) => [x.id, x]))
    const add = (section: string, source: string, amount: number) => { if (amount > 0) out.push({ section, source, amount, auto: true }) }
    for (const x of loanKind.values()) {
      if (x.currency !== cur) continue
      const moves = (linked.data ?? []).filter((t) => t.loan_id === x.id && !t.is_adjustment)
      const principal = moves.filter((t) => t.part === 'principal' || t.part === 'prepayment').reduce((a, t) => a + (t.to_amount ?? t.amount), 0)
      const interest = moves.filter((t) => t.part === 'interest').reduce((a, t) => a + t.amount, 0)
      if (x.kind === 'home') { add('80C', `${x.name}: principal repaid`, principal); add('24(b)', `${x.name}: interest paid`, interest) }
      if (x.kind === 'education') add('80E', `${x.name}: interest paid`, interest)
    }
    return out
  }, [s, cats, loans.data, linked.data, cur])
  const depositInterest = (linked.data ?? []).filter((t) => t.deposit_id && t.part === 'interest' && t.type === 'income').reduce((a, t) => a + t.amount, 0)
  const bySection = TAX_SECTIONS.map((t) => ({ t, lines: lines.filter((x) => x.section === t.code) })).filter((x) => x.lines.length)
  const months = fyMonths(from)
  const monthly = months.map((m) => trend.data?.find((p) => p.period === m && p.currency === cur))

  const exportCsv = () => {
    const rows = [['Section', 'Source', 'Amount', 'Currency'], ...lines.map((x) => [taxSection(x.section)?.label ?? x.section, x.source, toPlain(x.amount, cur), cur])]
    if (depositInterest) rows.push(['Interest earned', 'Deposits (taxable income)', toPlain(depositInterest, cur), cur])
    download(`lekhamint-tax-summary-${label.replace(/\s+/g, '')}.csv`, new Blob(['﻿' + rows.map((r) => r.map(csvEscape).join(',')).join('\r\n')], { type: 'text/csv' }))
  }

  return (
    <>
      <div className="page-head">
        <h1>Year & tax</h1>
        <div className="row nowrap">
          <button className="icon-btn" onClick={() => setOffset(offset - 1)} aria-label="Previous year"><Icon name="left" /></button>
          <strong className="month-label">{label}</strong>
          <button className="icon-btn" onClick={() => setOffset(offset + 1)} disabled={offset >= 0} aria-label="Next year"><Icon name="right" /></button>
        </div>
        <Select value={String(start)} onChange={(e) => { const m = Number(e.target.value); setStart(m); setFyStartMonth(l.id, m); setOffset(0) }} aria-label="Year starts in" style={{ width: 'auto' }}>
          {MONTHS.map((m, i) => <option key={m} value={i + 1}>Year starts in {m}</option>)}
        </Select>
      </div>
      <p className="muted page-lead">{monthLabel(from)} to {monthLabel(to)}, in {cur}. Transfers, loan principal and money lent are not counted as spending or income.</p>

      {summary.isPending ? <Card><Loading text="Adding up the year…" /></Card> : summary.isError ? <Card><p className="error">{errorText(summary.error)}</p></Card> : <>
        <div className="grid stats mb">
          <Card className="stat-card"><div className="stat-label">Income</div><div className="stat-big"><Money value={income} currency={cur} /></div></Card>
          <Card className="stat-card"><div className="stat-label">Spending</div><div className="stat-big"><Money value={spent} currency={cur} /></div></Card>
          <Card className="stat-card"><div className="stat-label">{saved >= 0 ? 'Saved' : 'Spent more than earned'}</div><div className={`stat-big ${saved >= 0 ? 'pos' : 'neg'}`}><Money value={Math.abs(saved)} currency={cur} /></div>
            {income > 0 && <div className="muted small">{saved >= 0 ? `${Math.round((saved / income) * 100)}% of income kept` : 'more than came in'}</div>}</Card>
        </div>
        <Card title="Month by month" className="mb">
          {trend.isPending ? <Skeleton rows={3} /> : <Columns currency={cur} height={170} label={`Income and spending each month of ${label}`} every={1}
            labels={months.map((m) => monthLabel(m + '-01').slice(0, 3))}
            series={[{ name: 'Income', color: 'var(--pos)', values: monthly.map((p) => p?.income ?? 0) }, { name: 'Spending', color: 'var(--neg)', values: monthly.map((p) => p?.expense ?? 0) }]} />}
        </Card>
        {topCats.length > 0 && <Card title="Where the money went" className="mb">
          <RankBars currency={cur} label={`Spending by category in ${label}`} onPick={(id) => go('transactions', { category: id, all: '1' })}
            rows={topCats.map(([id, v], i) => ({ key: id, name: cats.name(id || null), value: v, color: cats.color(id || null) ?? SERIES[i % 8], sub: spent ? `${Math.round((v / spent) * 100)}%` : undefined }))} />
        </Card>}
      </>}

      <Card title={<><Icon name="percent" size={16} /> Tax summary</>} className="mb" action={lines.length ? <button className="link" onClick={exportCsv}><Icon name="download" size={14} /> CSV</button> : undefined}>
        <p className="muted small">Spending in categories marked “counts for tax”, plus home and education loan payments recorded in Loans. Use it to gather proofs and fill your return; it is a summary of your records, not tax advice. Limits shown are the usual old-regime figures.</p>
        {linked.isPending ? <Skeleton rows={3} /> : bySection.length === 0 ? <TagHelp /> : (
          <div className="tax-sections">{bySection.map(({ t, lines: ls }) => {
            const total = ls.reduce((a, x) => a + x.amount, 0)
            return (
              <section key={t.code} className="tax-section">
                <header className="row"><strong className="grow">{t.label}</strong><Money value={total} currency={cur} /></header>
                {t.limit && cur === 'INR' && <>
                  <Meter value={total} max={Math.max(t.limit, total)} marks={total > t.limit ? [{ at: t.limit, label: 'limit' }] : []} tone={total >= t.limit ? 'good' : 'brand'} label={`${t.label}: used of the limit`} />
                  <p className="muted small">{total >= t.limit ? <>Limit of <Money value={t.limit} currency={cur} /> reached{total > t.limit ? <>; <Money value={total - t.limit} currency={cur} /> above it does not count</> : ''}.</> : <><Money value={t.limit - total} currency={cur} /> of room left under the <Money value={t.limit} currency={cur} /> limit.</>}</p>
                </>}
                <ul className="list compact">{ls.map((x, i) => <li key={i}><span className="grow">{x.source}{x.auto && <span className="chip">from Loans</span>}</span><Money value={x.amount} currency={cur} /></li>)}</ul>
              </section>
            )
          })}</div>
        )}
        {depositInterest > 0 && <p className="small"><Icon name="piggy" size={14} /> Interest earned on deposits this year: <strong><Money value={depositInterest} currency={cur} /></strong>. It is usually taxable income; check the TDS your bank deducted.</p>}
        <Explain title="How to use this" steps={[
          { title: 'Mark the categories that count', body: 'In Categories, edit a category such as Insurance, PPF, School fees or Rent and choose its tax section.' },
          { title: 'Record payments in those categories', body: 'Attach the receipt to the transaction, so the proof is with the amount.' },
          { title: 'Check the totals before you file', body: 'Download the CSV, or print it from Statements & export.' },
        ]} />
      </Card>
    </>
  )
}

/** Shown when no category is marked for tax: suggests likely ones and lets you mark them here. */
function TagHelp() {
  const cats = useCategoryIndex()
  const write = useWrite()
  const can = useCanWrite()
  const guess: [RegExp, string][] = [[/insur|premium|lic/i, '80D'], [/ppf|elss|provident|tuition|school|college/i, '80C'], [/nps|pension/i, '80CCD(1B)'],
    [/donat|charity/i, '80G'], [/rent/i, 'HRA'], [/medic|doctor|hospital|pharma/i, 'medical']]
  const likely = cats.all.filter((c) => c.kind === 'expense' && !c.archived).map((c) => ({ c, sec: guess.find(([re]) => re.test(c.name))?.[1] })).filter((x) => x.sec).slice(0, 8)
  if (!likely.length) return <Empty icon="percent" title="No categories marked for tax yet" body="Edit a category under Categories and choose what it counts for, for example 80C for PPF or 80D for health insurance." />
  return (
    <div>
      <p><strong>No categories are marked for tax yet.</strong> These look likely; mark them with one click:</p>
      <ul className="list compact">{likely.map(({ c, sec }) => (
        <li key={c.id}><span className="grow">{cats.path(c.id)}</span>
          {can && <button className="link" onClick={async () => { await write.save.mutateAsync({ entity: 'categories', id: c.id, data: { tax_section: sec } }); toast(`${c.name} now counts for ${sec}`) }}>Mark as {sec}</button>}</li>
      ))}</ul>
    </div>
  )
}
