import { Component, createContext, useContext, useEffect, useRef, useState, type ErrorInfo, type ReactNode } from 'react'
import { ApiError } from './lib/api'
import { compactMoney, currencies, formatMoney } from './lib/money'
import { Icon } from './icons'
import { Select } from './controls'

export const HideAmounts = createContext(false)

export function Money({ value, currency, signed, className = '' }: { value: number; currency: string; signed?: boolean; className?: string }) {
  const text = useContext(HideAmounts) ? '••••' : formatMoney(value, currency, { signed })
  const tone = signed ? (value > 0 ? 'pos' : value < 0 ? 'neg' : '') : ''
  return <span className={`money ${tone} ${className}`}>{text}</span>
}

export function Card({ title, action, children, className = '' }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || action) && <header className="card-head"><h2>{title}</h2>{action}</header>}
      {children}
    </section>
  )
}

export function Empty({ title, body, action, icon = 'inbox' }: { title: string; body: string; action?: ReactNode; icon?: string }) {
  return (
    <div className="empty">
      <div className="empty-icon"><Icon name={icon} size={26} /></div>
      <h3>{title}</h3><p>{body}</p>{action}
    </div>
  )
}

/** Placeholder rows while data loads, so the layout does not jump. */
export function Skeleton({ rows = 3, height = 18 }: { rows?: number; height?: number }) {
  return <div className="skeleton" aria-busy="true" aria-label="Loading">{Array.from({ length: rows }, (_, i) => <div key={i} style={{ height, width: `${92 - i * 13}%` }} />)}</div>
}

export function Modal({ title, onClose, children, footer, wide }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const d = ref.current
    if (!d) return
    // A field can't take focus while the dialog is still closed, so React's autoFocus is lost and
    // showModal() puts focus on the first button (Close). Put it where typing should start: the field
    // marked data-autofocus, or else (with a mouse or trackpad, where no keyboard pops up) the first field.
    d.showModal()
    const marked = d.querySelector<HTMLElement>('[data-autofocus]')
    const first = matchMedia('(pointer: fine)').matches
      ? d.querySelector<HTMLElement>('.modal-body :is(input:not([type=checkbox], [type=radio], [type=range], [type=file], .proxy), textarea):not(:disabled, [readonly])')
      : null
    ;(marked ?? first)?.focus()
  }, [])
  // A press that starts and ends on the backdrop closes; a drag out of a text field does not.
  const downOnBackdrop = useRef(false)
  // Something typed or ticked by the person (not set by the page) makes a stray click outside,
  // Escape or the close button ask first, so a half-filled form is not lost. Cancel stays direct.
  const dirty = useRef(false)
  const [asking, setAsking] = useState(false)
  const tryClose = () => { if (dirty.current) setAsking(true); else onClose() }
  const touched = (e: { nativeEvent: Event }) => { if (e.nativeEvent.isTrusted) dirty.current = true }
  return (
    <dialog ref={ref} className={`modal ${wide ? 'wide' : ''}`} onClose={onClose} onCancel={(e) => { e.preventDefault(); if (asking) setAsking(false); else tryClose() }} aria-label={title}
      onInput={touched} onChange={touched}
      onMouseDown={(e) => { downOnBackdrop.current = e.target === ref.current }}
      onMouseUp={(e) => { if (downOnBackdrop.current && e.target === ref.current) tryClose(); downOnBackdrop.current = false }}>
      <header className="modal-head"><h2>{title}</h2><button type="button" className="icon-btn" onClick={tryClose} aria-label="Close"><Icon name="x" /></button></header>
      <div className="modal-body">{children}</div>
      {asking ? (
        <footer className="modal-foot discard" role="alertdialog" aria-label="Discard changes?">
          <span className="grow"><Icon name="alert" size={16} /> Close without saving? What you entered will be lost.</span>
          <button type="button" autoFocus onClick={() => setAsking(false)}>Keep editing</button>
          <button type="button" className="danger-solid" onClick={onClose}>Discard</button>
        </footer>
      ) : footer && <footer className="modal-foot">{footer}</footer>}
    </dialog>
  )
}

/** A yes/no question for destructive actions that cannot be undone. */
export function Confirm({ title, body, confirm, danger, disabled, onConfirm, onClose }: { title: string; body: ReactNode; confirm: string; danger?: boolean; disabled?: boolean; onConfirm: () => void | Promise<void>; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  return (
    <Modal title={title} onClose={onClose} footer={<>
      <button type="button" onClick={onClose}>Cancel</button>
      <button type="button" className={danger ? 'danger-solid' : 'primary'} disabled={busy || disabled} onClick={async () => { setBusy(true); try { await onConfirm(); onClose() } catch (x) { toast(errorText(x)) } finally { setBusy(false) } }}>{confirm}</button>
    </>}>
      <div className="confirm-body">{body}</div>
    </Modal>
  )
}

export function Field({ label, hint, error, children }: { label: string; hint?: ReactNode; error?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {error ? <span className="field-error">{error}</span> : hint ? <span className="field-hint">{hint}</span> : null}
    </label>
  )
}

export function Check({ checked, onChange, children, disabled }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode; disabled?: boolean }) {
  return <label className="check"><input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} /><span>{children}</span></label>
}

export function Segmented<T extends string>({ value, onChange, options, label }: { value: T; onChange: (v: T) => void; options: [T, string][]; label: string }) {
  return (
    <div className="seg" role="radiogroup" aria-label={label}>
      {options.map(([k, v]) => <button type="button" key={k} role="radio" aria-checked={value === k} className={value === k ? 'on' : ''} onClick={() => onChange(k)}>{v}</button>)}
    </div>
  )
}

export function CurrencySelect({ value, onChange, disabled }: { value: string; onChange: (c: string) => void; disabled?: boolean }) {
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled}>
      {currencies.map((c) => <option key={c.code} value={c.code}>{c.code} · {c.name}</option>)}
    </Select>
  )
}

export function Dot({ color }: { color: string }) {
  return <span className="dot" style={{ background: color }} aria-hidden />
}

/** A round badge with the first letter of [name], tinted with [color]. */
export function Avatar({ name, color }: { name: string; color: string }) {
  return <span className="avatar" style={{ ['--c' as string]: color }} aria-hidden>{(name.trim()[0] ?? '?').toUpperCase()}</span>
}

export function Bar({ fraction, color, pace, danger }: { fraction: number; color?: string; pace?: number; danger?: boolean }) {
  return (
    <div className={`bar ${danger ? 'danger' : ''}`} role="progressbar" aria-valuenow={Math.round(fraction * 100)} aria-valuemin={0} aria-valuemax={100}>
      <div className="bar-fill" style={{ width: `${Math.min(100, Math.max(0, fraction * 100))}%`, ...(color ? { background: color } : {}) }} />
      {pace !== undefined && pace >= 0 && pace <= 1 && <div className="bar-pace" style={{ left: `${pace * 100}%` }} title="Even pace for today" />}
    </div>
  )
}

/** Donut chart as SVG arcs; [label] is the accessible description. */
export function Donut({ slices, label, center }: { slices: { value: number; color: string; name: string }[]; label: string; center?: ReactNode }) {
  const total = slices.reduce((s, x) => s + Math.max(0, x.value), 0)
  const r = 70, c = 2 * Math.PI * r
  let offset = 0
  return (
    <figure className="donut" role="img" aria-label={label}>
      <svg viewBox="0 0 180 180">
        <circle cx="90" cy="90" r={r} fill="none" stroke="var(--track)" strokeWidth="20" />
        {total > 0 && slices.filter((s) => s.value > 0).map((s) => {
          const len = (s.value / total) * c
          const el = <circle key={s.name} cx="90" cy="90" r={r} fill="none" stroke={s.color} strokeWidth="20" strokeLinecap="butt"
            strokeDasharray={`${Math.max(0, len - 2)} ${c}`} strokeDashoffset={-offset} transform="rotate(-90 90 90)"><title>{s.name}</title></circle>
          offset += len
          return el
        })}
      </svg>
      {center && <figcaption className="donut-center">{center}</figcaption>}
    </figure>
  )
}

export function Bars({ points, currency, label }: { points: { label: string; income: number; expense: number }[]; currency: string; label: string }) {
  const max = Math.max(1, ...points.flatMap((p) => [p.income, p.expense]))
  const hidden = useContext(HideAmounts)
  return (
    <figure className="bars" role="img" aria-label={label}>
      <div className="bars-plot">
        {points.map((p) => (
          <div className="bars-group" key={p.label} title={hidden ? p.label : `${p.label}: income ${formatMoney(p.income, currency)}, spending ${formatMoney(p.expense, currency)}`}>
            <div className="bars-pair">
              <div className="b inc" style={{ height: `${(p.income / max) * 100}%` }} />
              <div className="b exp" style={{ height: `${(p.expense / max) * 100}%` }} />
            </div>
            <span className="bars-label">{p.label}</span>
          </div>
        ))}
      </div>
      <figcaption className="legend"><span><i className="sw inc" />Income</span><span><i className="sw exp" />Spending</span>{!hidden && <span className="muted">max {compactMoney(max, currency)}</span>}</figcaption>
    </figure>
  )
}

/** Cumulative line for this period against the previous one (dashboard pace chart). */
export function PaceChart({ now, prev, days, today, label }: { now: number[]; prev: number[]; days: number; today: number; label: string }) {
  const max = Math.max(1, ...now, ...prev)
  const W = 300, H = 90
  const pt = (v: number, i: number) => `${(i / Math.max(1, days - 1)) * W},${H - (v / max) * (H - 6) - 3}`
  const line = (vals: number[]) => vals.map(pt).join(' ')
  return (
    <svg className="pace-chart" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={label}>
      <defs>
        <linearGradient id="paceFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="var(--brand)" stopOpacity="0.28" /><stop offset="1" stopColor="var(--brand)" stopOpacity="0" /></linearGradient>
      </defs>
      {prev.length > 1 && <polyline points={line(prev)} fill="none" stroke="var(--muted)" strokeOpacity="0.55" strokeWidth="1.5" strokeDasharray="4 4" vectorEffect="non-scaling-stroke" />}
      {now.length > 1 && <>
        <polygon points={`0,${H} ${line(now)} ${((now.length - 1) / Math.max(1, days - 1)) * W},${H}`} fill="url(#paceFill)" />
        <polyline points={line(now)} fill="none" stroke="var(--brand)" strokeWidth="2.2" vectorEffect="non-scaling-stroke" />
      </>}
      {today > 0 && <line x1={(today / Math.max(1, days - 1)) * W} x2={(today / Math.max(1, days - 1)) * W} y1="0" y2={H} stroke="var(--line)" vectorEffect="non-scaling-stroke" />}
    </svg>
  )
}

/**
 * Line chart with labelled axis ends, a point marker per value and a data
 * table for screen readers (and anyone who prefers numbers). [onPick] makes
 * points clickable, e.g. to open the records behind them.
 */
export function LineChart({ series, labels, currency: code, label, onPick, height = 160, area, fit }: {
  series: { name: string; color: string; values: number[]; dashed?: boolean }[]; labels: string[]; currency: string; label: string; onPick?: (i: number) => void; height?: number
  /** Washes the area under the first series. */
  area?: boolean
  /** Fits the axis to the data instead of starting at zero, for small changes on a large base (a deposit growing). */
  fit?: boolean
}) {
  const hidden = useContext(HideAmounts)
  const [hover, setHover] = useState<number | null>(null)
  const all = series.flatMap((s) => s.values)
  if (!labels.length || !all.length) return null
  const span = Math.max(...all) - Math.min(...all) || Math.abs(Math.max(...all)) || 1
  const lo = fit ? Math.min(...all) - span * 0.12 : Math.min(0, ...all) < 0 ? -niceStep(-Math.min(0, ...all)) : 0
  const hi = fit ? Math.max(...all) + span * 0.08 : niceStep(Math.max(1, ...all))
  const W = 600, H = height, P = 6
  const x = (i: number) => (labels.length === 1 ? W / 2 : (i / (labels.length - 1)) * (W - 2 * P) + P)
  const y = (v: number) => H - P - ((v - lo) / (hi - lo || 1)) * (H - 2 * P)
  const ticks = fit ? [hi, (hi + lo) / 2, lo] : lo < 0 ? [hi, 0, lo] : [hi, hi / 2, 0]
  const first = series[0]
  const dots = hover !== null ? series.map((s) => [s, hover] as const) : series.filter((s) => !s.dashed).map((s) => [s, s.values.length - 1] as const)
  return (
    <figure className="line-chart" aria-label={label}>
      <div className="line-body">
        {!hidden && <div className="line-axis" style={{ height: H }} aria-hidden>{ticks.map((t) => <span key={t} style={{ top: `${(y(t) / H) * 100}%` }}>{compactMoney(t, code)}</span>)}</div>}
        <div className="line-plot">
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={label} onMouseLeave={() => setHover(null)} style={{ height: H }}>
            {ticks.map((t) => <line key={t} x1="0" x2={W} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--line-strong)' : 'var(--line)'} vectorEffect="non-scaling-stroke" />)}
            {area && first && first.values.length > 1 && (
              <polygon points={`${x(0)},${y(Math.max(lo, 0))} ${first.values.map((v, i) => `${x(i)},${y(v)}`).join(' ')} ${x(first.values.length - 1)},${y(Math.max(lo, 0))}`} fill={first.color} fillOpacity="0.1" />
            )}
            {series.map((s) => (
              <polyline key={s.name} points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(' ')} fill="none" stroke={s.color} strokeWidth="2"
                strokeDasharray={s.dashed ? '5 5' : undefined} vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
            ))}
            {labels.map((_, i) => (
              <rect key={i} x={x(i) - W / labels.length / 2} y="0" width={W / labels.length} height={H} fill="transparent"
                onMouseEnter={() => setHover(i)} onClick={() => { setHover(i); onPick?.(i) }} style={{ cursor: onPick ? 'pointer' : 'default' }} />
            ))}
            {hover !== null && <line x1={x(hover)} x2={x(hover)} y1="0" y2={H} stroke="var(--muted)" strokeOpacity="0.4" vectorEffect="non-scaling-stroke" />}
          </svg>
          {dots.map(([s, i]) => i >= 0 && s.values[i] !== undefined && (
            <i key={s.name} className="line-dot" style={{ left: `${(x(i) / W) * 100}%`, top: y(s.values[i]), background: s.color }} />
          ))}
          {hover !== null && !hidden && (
            <div className="chart-tip" style={{ left: `${Math.min(85, Math.max(15, (x(hover) / W) * 100))}%` }}>
              <strong>{labels[hover]}</strong>
              {series.map((s) => <span key={s.name}><i className="sw" style={{ background: s.color }} />{s.name}: {formatMoney(s.values[hover] ?? 0, code)}</span>)}
            </div>
          )}
          <div className="line-labels" aria-hidden><span>{labels[0]}</span>{labels.length > 2 && <span>{labels[Math.floor((labels.length - 1) / 2)]}</span>}{labels.length > 1 && <span>{labels[labels.length - 1]}</span>}</div>
        </div>
      </div>
      {series.length > 1 && <figcaption className="legend">{series.map((s) => <span key={s.name}><i className="sw" style={{ background: s.color }} />{s.name}</span>)}</figcaption>}
      <details className="chart-data"><summary className="muted small">Show as a table</summary>
        <table className="table compact"><thead><tr><th>Period</th>{series.map((s) => <th key={s.name} className="num">{s.name}</th>)}</tr></thead>
          <tbody>{labels.map((l, i) => <tr key={l + i}><td>{l}</td>{series.map((s) => <td key={s.name} className="num"><Money value={s.values[i] ?? 0} currency={code} /></td>)}</tr>)}</tbody></table>
      </details>
    </figure>
  )
}

/** Rounds up to a clean axis value (1, 2, 2.5 or 5 × 10ⁿ). */
function niceStep(v: number): number {
  const p = 10 ** Math.floor(Math.log10(v))
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p
  return 10 * p
}

/** Small undo toast for reversible actions (docs/01: Undo instead of "Are you sure?"). */
type ToastT = { text: string; action?: { label: string; run: () => void }; tone?: 'error' }
let pushToast: (t: ToastT) => void = () => {}
export const toast = (text: string, action?: { label: string; run: () => void }) => pushToast({ text, action })
export const toastError = (e: unknown) => pushToast({ text: errorText(e), tone: 'error' })

export function Toaster() {
  const [t, setT] = useState<(ToastT & { key: number }) | null>(null)
  useEffect(() => { pushToast = (x) => setT({ ...x, key: Date.now() }) }, [])
  useEffect(() => { if (!t) return; const h = setTimeout(() => setT(null), t.action ? 7000 : 4500); return () => clearTimeout(h) }, [t])
  if (!t) return null
  return (
    <div className={`toast ${t.tone ?? ''}`} role={t.tone === 'error' ? 'alert' : 'status'} key={t.key}>
      <Icon name={t.tone === 'error' ? 'alert' : 'check'} size={16} />
      <span>{t.text}</span>
      {t.action && <button onClick={() => { t.action!.run(); setT(null) }}>{t.action.label}</button>}
      <button className="toast-x" aria-label="Dismiss" onClick={() => setT(null)}><Icon name="x" size={14} /></button>
    </div>
  )
}

/** A message a person can act on. Server faults carry the request ID that is also in the server log. */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429) return 'Too many attempts. Wait a minute and try again.'
    if (e.status >= 500) {
      const ref = e.requestId ? ` (reference ${e.requestId.slice(-8)})` : ''
      return `The server had a problem${ref}. Try again; if it keeps happening, check the server log for that reference.`
    }
  }
  return e instanceof Error && e.message ? e.message : 'Something went wrong'
}

/**
 * For a failed save. A dropped connection leaves the outcome unknown; since
 * every form keeps its record ID (useStableId + create_if_missing), saving
 * again is safe and cannot record the same thing twice, and we say so.
 */
export function saveErrorText(e: unknown): string {
  if (e instanceof ApiError && e.status === 0) return "The connection dropped, so we can't tell whether this was saved. Press Save again when you're back online; it is safe and will not record it twice."
  if (e instanceof ApiError && e.code === 'validation') return `Not saved: ${e.message}`
  return errorText(e)
}

/** Keeps a crash in one page from blanking the whole app; offers a retry and shows what broke. */
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { error: Error | null }> {
  state = { error: null as Error | null }
  static getDerivedStateFromError(error: Error) { return { error } }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('LekhaMint page error', error, info.componentStack) }
  componentDidUpdate(prev: { resetKey?: string }) { if (prev.resetKey !== this.props.resetKey && this.state.error) this.setState({ error: null }) }
  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="card pad" role="alert">
        <h2>This page ran into a problem</h2>
        <p className="muted">Your data is safe; nothing was lost. Try again, or open another page.</p>
        <details><summary className="muted small">Details</summary><pre className="small" style={{ whiteSpace: 'pre-wrap' }}>{String(this.state.error.stack || this.state.error)}</pre></details>
        <div className="row" style={{ marginTop: 12 }}>
          <button className="primary" onClick={() => this.setState({ error: null })}>Try again</button>
          <button onClick={() => location.reload()}>Reload</button>
        </div>
      </div>
    )
  }
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>
}
