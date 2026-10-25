import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useCategoryIndex, useLedger, useSummary, useTagIndex } from '../lib/data'
import { dayLabel, isoDate, monthLabel, monthRange, parseDate } from '../lib/util'
import { Bar, Bars, Card, Donut, Dot, Empty, Money, Segmented, Skeleton } from '../components'
import { CatIcon, Icon } from '../icons'
import type { PageProps } from '../App'
import { Select } from '../controls'

export function Reports({ go }: PageProps) {
  const l = useLedger()
  const cats = useCategoryIndex()
  const tagIndex = useTagIndex()
  const [span, setSpan] = useState<'month' | 'year'>('month')
  const [anchor, setAnchor] = useState(isoDate())
  const year = parseDate(anchor).getFullYear()
  const [from, to] = span === 'month' ? monthRange(anchor) : [`${year}-01-01`, `${year}-12-31`]
  const summary = useSummary(from, to)
  const currencies = summary.data?.map((s) => s.currency) ?? []
  const [curSel, setCurSel] = useState('')
  const cur = currencies.includes(curSel) ? curSel : l.base_currency
  const s = summary.data?.find((x) => x.currency === cur)

  const trendFrom = span === 'month' ? monthRange(anchor, -11)[0] : `${year}-01-01`
  const trend = useQuery({ queryKey: [l.id, 'trend', trendFrom, to], queryFn: () => api.trend(l.id, trendFrom, to, 'month') })
  const points = useMemo(() => {
    const months: string[] = []
    for (let i = span === 'month' ? -11 : 0, n = span === 'month' ? 0 : 11; i <= n; i++) {
      months.push((span === 'month' ? monthRange(anchor, i)[0] : `${year}-${String(i + 1).padStart(2, '0')}-01`).slice(0, 7))
    }
    return months.map((m) => {
      const p = trend.data?.find((x) => x.period === m && x.currency === cur)
      return { label: parseDate(m + '-01').toLocaleDateString(undefined, { month: 'short' }), income: p?.income ?? 0, expense: p?.expense ?? 0 }
    })
  }, [trend.data, anchor, span, year, cur])

  const byRoot = useMemo(() => {
    const m = new Map<string | null, { total: number; kids: Map<string | null, number> }>()
    for (const c of s?.expense_by_category ?? []) {
      const r = cats.rootOf(c.category_id)
      const e = m.get(r) ?? { total: 0, kids: new Map() }
      e.total += c.amount
      e.kids.set(c.category_id, (e.kids.get(c.category_id) ?? 0) + c.amount)
      m.set(r, e)
    }
    return [...m.entries()].filter(([, v]) => v.total > 0).sort((a, b) => b[1].total - a[1].total)
  }, [s, cats])
  const [open, setOpen] = useState<string | null>(null)

  const nature = useMemo(() => {
    const out = { need: 0, want: 0, savings: 0, other: 0 }
    for (const c of s?.expense_by_category ?? []) {
      const cat = c.category_id ? cats.byId.get(c.category_id) : undefined
      const n = cat?.nature ?? (cat?.parent_id ? cats.byId.get(cat.parent_id)?.nature : null)
      out[(n === 'need' || n === 'want' || n === 'savings') ? n : 'other'] += c.amount
    }
    return out
  }, [s, cats])
  const nTotal = nature.need + nature.want + nature.savings + nature.other
  const shift = (d: number) => setAnchor(span === 'month' ? monthRange(anchor, d)[0] : `${year + d}-01-01`)
  const tagTotals = useQuery({ queryKey: [l.id, 'tag-totals', from, to], queryFn: () => api.tagTotals(l.id, from, to) })
  const payees = useQuery({ queryKey: [l.id, 'payee-totals', from, to], queryFn: () => api.payeeTotals(l.id, from, to, 50) })
  const tagRows = (tagTotals.data ?? []).filter((t) => t.currency === cur && (t.expense !== 0 || t.income !== 0))
  const payeeRows = (payees.data ?? []).filter((p) => p.currency === cur && p.expense > 0).slice(0, 8)
  const isCurrent = span === 'month' ? monthRange(anchor)[0] === monthRange(isoDate())[0] : year === new Date().getFullYear()

  return (
    <>
      <div className="page-head">
        <h1>Reports</h1>
        <Segmented label="Period" value={span} onChange={setSpan} options={[['month', 'Month'], ['year', 'Year']]} />
        <div className="row month-nav"><button className="icon-btn" onClick={() => shift(-1)} aria-label="Previous"><Icon name="left" /></button><strong className="month-label">{span === 'month' ? monthLabel(anchor) : year}</strong><button className="icon-btn" onClick={() => shift(1)} aria-label="Next" disabled={isCurrent}><Icon name="right" /></button></div>
        {currencies.length > 1 && <Select style={{ width: 'auto' }} value={cur} onChange={(e) => setCurSel(e.target.value)}>{currencies.map((c) => <option key={c}>{c}</option>)}</Select>}
      </div>
      {summary.isPending ? <div className="grid"><Card><Skeleton /></Card><Card><Skeleton /></Card><Card><Skeleton /></Card></div> : !s || (s.income === 0 && s.expense === 0) ? <Card><Empty icon="chart" title="Nothing to report" body="No income or spending in this period." /></Card> : (
        <>
          <div className="grid">
            <Card><div className="stat-label">Income</div><div className="stat-value"><Money value={s.income} currency={cur} /></div></Card>
            <Card><div className="stat-label">Spent</div><div className="stat-value"><Money value={s.expense} currency={cur} /></div></Card>
            <Card><div className="stat-label">Saved</div><div className="stat-value"><Money value={s.net} currency={cur} signed /></div>
              {s.income > 0 && <div className="muted small">{Math.round((s.net / s.income) * 100)}% of income kept</div>}</Card>
          </div>
          <div className="grid-2" style={{ marginTop: 16 }}>
            <Card title="Spending by category">
              <Donut label={`Spending by category: ${byRoot.slice(0, 5).map(([id, v]) => `${cats.name(id)} ${Math.round((v.total / s.expense) * 100)}%`).join(', ')}`}
                slices={byRoot.map(([id, v]) => ({ value: v.total, color: cats.color(id), name: cats.name(id) }))}
                center={<div><div className="muted small">Spent</div><strong><Money value={s.expense} currency={cur} /></strong></div>} />
              <ul className="list">
                {byRoot.map(([id, v]) => (
                  <li key={id ?? 'none'} className="clickable" style={{ flexDirection: 'column', alignItems: 'stretch' }} onClick={() => setOpen(open === id ? null : id)}>
                    <div className="row" style={{ flexWrap: 'nowrap' }}><CatIcon name={cats.icon(id)} color={cats.color(id)} size={26} /><span className="grow">{cats.name(id)}</span>
                      <span className="muted small">{Math.round((v.total / s.expense) * 100)}%</span><Money value={v.total} currency={cur} /></div>
                    <Bar fraction={v.total / byRoot[0][1].total} color={cats.color(id)} />
                    {open === id && <>
                      {[...v.kids.entries()].sort((a, b) => b[1] - a[1]).map(([k, kv]) => (
                        <div key={k ?? 'g'} className="row small" style={{ paddingLeft: 22 }}><span className="grow">{k === id ? `${cats.name(id)} (general)` : cats.name(k)}</span><Money value={kv} currency={cur} /></div>
                      ))}
                      <button className="link small" style={{ alignSelf: 'flex-start', paddingLeft: 22 }} onClick={(e) => { e.stopPropagation(); go('transactions', id ? { category: id, ...(span === 'month' ? { month: from } : { all: '1' }) } : { category: 'none', all: '1' }) }}>See transactions →</button>
                    </>}
                  </li>
                ))}
              </ul>
            </Card>
            <div style={{ display: 'grid', gap: 16, alignContent: 'start' }}>
              <Card title={span === 'month' ? 'Last 12 months' : 'Month by month'}><Bars points={points} currency={cur} label="Income and spending per month" /></Card>
              {nTotal > 0 && (
                <Card title="Needs, wants and savings">
                  <div className="bar" style={{ display: 'flex', height: 14 }}>
                    {([['need', '#0EA5E9'], ['want', '#F59E0B'], ['savings', '#22C55E'], ['other', '#94A3B8']] as const).map(([k, c]) =>
                      <div key={k} style={{ width: `${(nature[k] / nTotal) * 100}%`, background: c }} />)}
                  </div>
                  <ul className="list">
                    {([['need', 'Needs', '50%', '#0EA5E9'], ['want', 'Wants', '30%', '#F59E0B'], ['savings', 'Savings & debt', '20%', '#22C55E'], ['other', 'Not set', '', '#94A3B8']] as const)
                      .filter(([k]) => k !== 'other' || nature.other > 0).map(([k, label, guide, c]) => (
                        <li key={k}><Dot color={c} /><span className="grow">{label} {guide && <span className="muted small">· guideline {guide}</span>}</span>
                          <strong>{Math.round((nature[k] / nTotal) * 100)}%</strong><Money value={nature[k]} currency={cur} /></li>
                      ))}
                  </ul>
                </Card>
              )}
              {s.income_by_category.length > 0 && (
                <Card title="Income by source">
                  <ul className="list">{[...s.income_by_category].sort((a, b) => b.amount - a.amount).map((c) => (
                    <li key={c.category_id ?? 'none'}><CatIcon name={cats.icon(c.category_id)} color={cats.color(c.category_id)} size={26} /><span className="grow">{c.name || 'Uncategorised'}</span><Money value={c.amount} currency={cur} /></li>
                  ))}</ul>
                </Card>
              )}
              {payeeRows.length > 0 && (
                <Card title="Top payees">
                  <ul className="list">{payeeRows.map((p) => (
                    <li key={p.payee} className="clickable" onClick={() => go('transactions', { q: p.payee })}>
                      <div className="grow"><div className="row between"><span className="ellipsis">{p.payee}</span><Money value={p.expense} currency={cur} /></div>
                        <div className="sub">{p.count} {p.count === 1 ? 'payment' : 'payments'} · latest {dayLabel(p.last_date)}</div>
                        <Bar fraction={p.expense / payeeRows[0].expense} /></div>
                    </li>
                  ))}</ul>
                </Card>
              )}
              {tagRows.length > 0 && (
                <Card title="By tag" action={<button className="link" onClick={() => go('tags')}>Tags</button>}>
                  <p className="muted small" style={{ marginTop: 0 }}>A transaction with two tags counts under both.</p>
                  <ul className="list">{tagRows.map((t) => (
                    <li key={t.tag_id} className="clickable" onClick={() => go('transactions', { tag: t.tag_id, all: '1' })}>
                      <Dot color={tagIndex.byId.get(t.tag_id)?.color ?? '#94A3B8'} /><div className="grow"><div>#{tagIndex.name(t.tag_id) ?? 'deleted tag'}</div><div className="sub">{t.count} {t.count === 1 ? 'transaction' : 'transactions'}{t.income ? <> · <Money value={t.income} currency={cur} /> in</> : null}</div></div>
                      <Money value={t.expense} currency={cur} />
                    </li>
                  ))}</ul>
                </Card>
              )}
            </div>
          </div>
        </>
      )}
    </>
  )
}
