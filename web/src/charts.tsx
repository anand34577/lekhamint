import { useContext, useState, type ReactNode } from 'react'
import { HideAmounts } from './components'
import { compactMoney, formatMoney } from './lib/money'
import { Icon } from './icons'

/**
 * Small, dependency-free charts and explainers shared by the pages.
 *
 * Colours: identity uses the categorical slots (--series-1…8, styles.css),
 * assigned in fixed order; income/spending keep their semantic colours. Text
 * never wears a series colour: a swatch beside it carries identity. Every chart
 * has a hover read-out and an accessible label, and respects "hide amounts".
 */

export const SERIES = [1, 2, 3, 4, 5, 6, 7, 8].map((i) => `var(--series-${i})`)

const pct = (v: number, total: number) => (total > 0 ? (v / total) * 100 : 0)
const pctLabel = (v: number, total: number) => { const p = pct(v, total); return p > 0 && p < 1 ? '<1%' : `${Math.round(p)}%` }

/** A whole split into parts as one bar, with a legend that gives each part's amount and share. */
export function StackBar({ parts, currency, label, legend = true, inline }: {
  parts: { name: string; value: number; color: string; hint?: string }[]; currency: string; label: string; legend?: boolean
  /** A one-line legend (name and amount) for tight spaces such as list cards. */
  inline?: boolean
}) {
  const hidden = useContext(HideAmounts)
  const [on, setOn] = useState<number | null>(null)
  const shown = parts.filter((p) => p.value > 0)
  const total = shown.reduce((a, p) => a + p.value, 0)
  return (
    <figure className="stackbar" aria-label={label}>
      <div className="stackbar-track" role="img" aria-label={`${label}: ${shown.map((p) => `${p.name} ${pctLabel(p.value, total)}`).join(', ')}`} onMouseLeave={() => setOn(null)}>
        {total > 0 && shown.map((p, i) => (
          <div key={p.name} className={`stackbar-seg ${on !== null && on !== i ? 'dim' : ''}`} style={{ width: `${pct(p.value, total)}%`, background: p.color }}
            onMouseEnter={() => setOn(i)} title={hidden ? p.name : `${p.name}: ${formatMoney(p.value, currency)} (${pctLabel(p.value, total)})`} />
        ))}
      </div>
      {legend && (
        <figcaption className={`stackbar-legend ${inline ? 'inline' : ''}`}>
          {shown.map((p, i) => (
            <div key={p.name} className={`stackbar-key ${on === i ? 'on' : ''}`} onMouseEnter={() => setOn(i)} onMouseLeave={() => setOn(null)}>
              <i className="sw" style={{ background: p.color }} />
              <span className="grow">{p.name}{p.hint && <span className="muted small"> · {p.hint}</span>}</span>
              {!hidden && <strong className="money">{formatMoney(p.value, currency)}</strong>}
              {!inline && <span className="muted small stackbar-pct">{pctLabel(p.value, total)}</span>}
            </div>
          ))}
        </figcaption>
      )}
    </figure>
  )
}

/** Ranked horizontal bars (largest first), value at the tip. */
export function RankBars({ rows, currency, label, onPick, max: maxIn }: {
  rows: { key: string; name: ReactNode; value: number; color?: string; sub?: ReactNode }[]; currency: string; label: string; onPick?: (key: string) => void; max?: number
}) {
  const hidden = useContext(HideAmounts)
  const max = maxIn ?? Math.max(1, ...rows.map((r) => Math.abs(r.value)))
  return (
    <ul className="rankbars" aria-label={label}>
      {rows.map((r) => (
        <li key={r.key} className={onPick ? 'clickable' : ''} onClick={onPick ? () => onPick(r.key) : undefined}>
          <div className="rankbars-line">
            <span className="rankbars-name">{r.name}{r.sub && <span className="muted small"> · {r.sub}</span>}</span>
            <span className="money rankbars-val">{hidden ? '••••' : formatMoney(r.value, currency)}</span>
          </div>
          <div className="rankbars-track"><div className="rankbars-fill" style={{ width: `${Math.max(0.6, pct(Math.abs(r.value), max))}%`, background: r.color ?? 'var(--series-1)' }} /></div>
        </li>
      ))}
    </ul>
  )
}

/** Stacked columns (one per period) with a hover read-out of every part. */
export function Columns({ labels, series, currency, label, height = 190, every }: {
  labels: string[]; series: { name: string; color: string; values: number[] }[]; currency: string; label: string; height?: number; every?: number
}) {
  const hidden = useContext(HideAmounts)
  const [on, setOn] = useState<number | null>(null)
  const totals = labels.map((_, i) => series.reduce((a, s) => a + Math.max(0, s.values[i] ?? 0), 0))
  const max = niceMax(Math.max(1, ...totals))
  const step = every ?? Math.max(1, Math.ceil(labels.length / 12))
  return (
    <figure className="columns" aria-label={label}>
      <div className="columns-body">
        {!hidden && <div className="columns-axis" style={{ height }} aria-hidden>{[1, 0.5, 0].map((f) => <span key={f}>{compactMoney(max * f, currency)}</span>)}</div>}
        <div className="columns-plot" style={{ height }} role="img" aria-label={label} onMouseLeave={() => setOn(null)}>
          {labels.map((l, i) => (
            <div key={l + i} className={`columns-col ${on === i ? 'on' : ''}`} onMouseEnter={() => setOn(i)} onClick={() => setOn(i)}>
              <div className="columns-area">
                <div className="columns-stack" style={{ height: `${pct(totals[i], max)}%` }}>
                  {series.map((s) => (s.values[i] ?? 0) > 0 && <div key={s.name} style={{ flexGrow: s.values[i], background: s.color }} />)}
                </div>
              </div>
              <span className="columns-label">{i % step === 0 ? l : ''}</span>
            </div>
          ))}
          {on !== null && !hidden && (
            <div className="chart-tip" style={{ left: `${((on + 0.5) / labels.length) * 100}%` }}>
              <strong>{labels[on]}</strong>
              {series.map((s) => <span key={s.name}><i className="sw" style={{ background: s.color }} />{s.name}: {formatMoney(s.values[on] ?? 0, currency)}</span>)}
              {series.length > 1 && <span className="muted">Total: {formatMoney(totals[on], currency)}</span>}
            </div>
          )}
        </div>
      </div>
      {series.length > 1 && <figcaption className="legend">{series.map((s) => <span key={s.name}><i className="sw" style={{ background: s.color }} />{s.name}</span>)}</figcaption>}
    </figure>
  )
}

/** Rounds an axis maximum up to a clean number (1, 2, 2.5 or 5 × 10ⁿ). */
export function niceMax(v: number): number {
  const p = 10 ** Math.floor(Math.log10(v))
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p
  return 10 * p
}

/** A progress meter with reference marks (e.g. 3 and 6 months). [tone] picks the fill colour. */
export function Meter({ value, max, marks = [], tone = 'brand', label, valueLabel }: {
  value: number; max: number; marks?: { at: number; label: string }[]; tone?: 'brand' | 'good' | 'warn' | 'danger'; label: string; valueLabel?: string
}) {
  const f = Math.min(1, Math.max(0, max > 0 ? value / max : 0))
  return (
    <div className={`meter tone-${tone}`} role="meter" aria-label={label} aria-valuenow={value} aria-valuemin={0} aria-valuemax={max} aria-valuetext={valueLabel}>
      <div className="meter-track">
        <div className="meter-fill" style={{ width: `${f * 100}%` }} />
        {marks.map((m) => m.at > 0 && m.at < max && <i key={m.label} className="meter-mark" style={{ left: `${(m.at / max) * 100}%` }} />)}
      </div>
      {marks.length > 0 && <div className="meter-marks">{marks.map((m) => <span key={m.label} style={{ left: `${Math.min(100, (m.at / max) * 100)}%` }}>{m.label}</span>)}</div>}
    </div>
  )
}

/**
 * "In simple words": the calculation as numbered steps with the person's own
 * numbers, so the result can be followed without knowing the formula.
 */
export function Explain({ title = 'In simple words', steps, children }: { title?: string; steps: { title: ReactNode; body?: ReactNode; value?: ReactNode }[]; children?: ReactNode }) {
  return (
    <section className="explain">
      <h3 className="explain-title"><span className="explain-icon"><Icon name="bulb" size={16} /></span>{title}</h3>
      <ol className="explain-steps">
        {steps.map((s, i) => (
          <li key={i}>
            <span className="explain-n" aria-hidden>{i + 1}</span>
            <div className="grow"><div className="explain-step-title">{s.title}</div>{s.body && <div className="muted small">{s.body}</div>}</div>
            {s.value !== undefined && <div className="explain-value">{s.value}</div>}
          </li>
        ))}
      </ol>
      {children}
    </section>
  )
}

/** An indeterminate spinner for work in progress. */
export function Spinner({ size = 16, label }: { size?: number; label?: string }) {
  return <span className="spinner" style={{ width: size, height: size }} role={label ? 'status' : undefined} aria-label={label} aria-hidden={label ? undefined : true} />
}

/** A labelled loading state for a card or page section. */
export function Loading({ text = 'Loading…' }: { text?: string }) {
  return <div className="loading-state" role="status"><Spinner size={22} /><span>{text}</span></div>
}
