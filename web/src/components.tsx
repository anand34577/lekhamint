import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { compactMoney, currencies, formatMoney } from './lib/money'
import { Icon } from './icons'

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
  useEffect(() => { ref.current?.showModal() }, [])
  // A press that starts and ends on the backdrop closes; a drag out of a text field does not.
  const downOnBackdrop = useRef(false)
  return (
    <dialog ref={ref} className={`modal ${wide ? 'wide' : ''}`} onClose={onClose} onCancel={(e) => { e.preventDefault(); onClose() }} aria-label={title}
      onMouseDown={(e) => { downOnBackdrop.current = e.target === ref.current }}
      onMouseUp={(e) => { if (downOnBackdrop.current && e.target === ref.current) onClose(); downOnBackdrop.current = false }}>
      <header className="modal-head"><h2>{title}</h2><button type="button" className="icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button></header>
      <div className="modal-body">{children}</div>
      {footer && <footer className="modal-foot">{footer}</footer>}
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
    <select value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled}>
      {currencies.map((c) => <option key={c.code} value={c.code}>{c.code} · {c.name}</option>)}
    </select>
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

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : 'Something went wrong'
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>
}
