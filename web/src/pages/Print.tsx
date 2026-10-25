import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useLedger } from '../lib/data'
import type { Column, Report, Value } from '../lib/reports'
import { formatMoney } from '../lib/money'
import { buildReport, downloadReportXlsx, useReportCtx } from './Statements'
import { Icon } from '../icons'
import type { PageProps } from '../App'

/**
 * A report as a printable document (docs/02, "Reports"). Rendered without the
 * app shell, always in light colours, with repeated table headers, totals kept
 * with their tables, and page numbers in the page margin. The browser's
 * "Save as PDF" turns it into a file.
 */
export function Print({ params, go }: PageProps) {
  const ctx = useReportCtx()
  const l = useLedger()
  const q = useQuery({ queryKey: [l.id, 'print', params], queryFn: () => buildReport(ctx!, params), enabled: !!ctx, retry: false })
  const r = q.data
  useEffect(() => {
    document.documentElement.classList.add('printing')
    return () => document.documentElement.classList.remove('printing')
  }, [])
  useEffect(() => { if (r) document.title = `${r.title} · ${l.name}` }, [r, l.name])
  const back = () => (history.length > 1 ? history.back() : go('statements'))
  return (
    <div className="print-page">
      <div className="print-bar no-print">
        <button onClick={back}><Icon name="left" size={16} /> Back</button>
        <span className="spacer" />
        {r && <button onClick={() => downloadReportXlsx(r, l.name)}><Icon name="table" size={16} /> Excel</button>}
        <button className="primary" onClick={() => window.print()} disabled={!r}><Icon name="printer" size={16} /> Print or save as PDF</button>
      </div>
      {q.isError ? <div className="doc"><h1>Could not build this report</h1><p>{(q.error as Error).message}</p><button onClick={back}>Back</button></div>
        : !r ? <div className="doc"><p className="muted">Preparing the report…</p></div> : <Doc r={r} book={l.name} />}
      {r && <style>{`@page { @bottom-left { content: "${(l.name + ' · ' + r.title).replace(/["\\]/g, '')}"; } }`}</style>}
    </div>
  )
}

const fmtDate = (s: string) => new Date(s + 'T00:00:00').toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })

function cell(v: Value, c: Column, cur: string) {
  if (v === null || v === '') return ''
  if (c.kind === 'money' && typeof v === 'number') return formatMoney(v, cur)
  if (c.kind === 'date' && typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)) return fmtDate(v)
  return String(v)
}

function Doc({ r, book }: { r: Report; book: string }) {
  return (
    <article className="doc">
      <header className="doc-head">
        <div className="doc-brand"><img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" /> <span>LekhaMint</span></div>
        <div className="doc-meta">
          <div>{book}</div>
          <div>Generated {new Date().toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</div>
        </div>
      </header>
      <h1 className="doc-title">{r.title}</h1>
      <p className="doc-sub">
        {r.subtitle && <span>{r.subtitle}</span>}
        {r.period && <span>{fmtDate(r.period.from)} – {fmtDate(r.period.to)}</span>}
        <span>Amounts in {r.currency}</span>
        {r.filters.map((f) => <span key={f}>{f}</span>)}
      </p>
      {r.stats.length > 0 && (
        <section className="doc-stats">
          {r.stats.map((s) => (
            <div key={s.label} className={`doc-stat ${s.tone ?? ''}`}>
              <span>{s.label}</span>
              <strong>{s.count ? s.value.toLocaleString() : formatMoney(s.value, r.currency)}</strong>
              {s.hint && <small>{s.hint}</small>}
            </div>
          ))}
        </section>
      )}
      {r.sections.map((s) => (
        <section key={s.title} className="doc-section">
          <h2>{s.title}</h2>
          {s.note && <p className="doc-note">{s.note}</p>}
          {s.table && (s.table.rows.length === 0 ? <p className="doc-empty">{s.table.empty ?? 'Nothing to show.'}</p> : (
            <table className="doc-table">
              <thead><tr>{s.table.columns.map((c) => <th key={c.label} className={c.kind === 'money' || c.kind === 'number' ? 'num' : ''}>{c.label}</th>)}</tr></thead>
              <tbody>
                {s.table.rows.map((row, i) => (
                  <tr key={i}>{row.map((v, j) => {
                    const c = s.table!.columns[j]
                    const neg = c.kind === 'money' && typeof v === 'number' && v < 0
                    // Share columns (% of spending, % used) carry a small bar so the biggest parts stand out on paper.
                    const share = c.kind === 'number' && c.label.startsWith('%') && typeof v === 'number'
                    return <td key={j} className={`${c.kind === 'money' || c.kind === 'number' ? 'num' : ''} ${c.kind === 'date' ? 'date' : ''} ${neg ? 'neg' : ''}`}>
                      {share ? <span className="doc-share"><i style={{ width: `${Math.min(100, Math.max(0, v as number))}%` }} className={(v as number) > 100 ? 'over' : ''} /></span> : null}{cell(v, c, r.currency)}</td>
                  })}</tr>
                ))}
                {s.table.total && <tr className="total">{s.table.total.map((v, j) => <td key={j} className={s.table!.columns[j].kind === 'money' || s.table!.columns[j].kind === 'number' ? 'num' : ''}>{cell(v, s.table!.columns[j], r.currency)}</td>)}</tr>}
              </tbody>
            </table>
          ))}
        </section>
      ))}
      {r.notes.length > 0 && (
        <footer className="doc-notes">
          <h2>Notes</h2>
          <ul>{r.notes.map((n) => <li key={n}>{n}</li>)}</ul>
          <p>Prepared with LekhaMint from your own records. It is not a bank or lender statement.</p>
        </footer>
      )}
    </article>
  )
}
