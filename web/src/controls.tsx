// Form controls drawn by LekhaMint instead of the browser: dropdown, date picker,
// number field, password field, switch and file drop zone. They keep the shape of
// the native elements they replace (value + onChange with e.target.value), so a
// page swaps <select> for <Select> and <input type="date"> for <DateField>.

import {
  Children, isValidElement, useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState,
  type ChangeEvent, type CSSProperties, type InputHTMLAttributes, type KeyboardEvent, type ReactNode, type RefObject,
} from 'react'
import { createPortal } from 'react-dom'
import { Icon } from './icons'

/** Builds the minimal change event the pages read: e.target.value. */
function synth<T extends HTMLElement>(value: string): ChangeEvent<T> {
  const target = { value } as unknown as EventTarget & T
  return { target, currentTarget: target } as unknown as ChangeEvent<T>
}

// ---------------------------------------------------------------------------
// Floating panel: anchored under (or over) a control, as a bottom sheet on phones.

const SHEET_QUERY = '(max-width: 560px)'

function Floating({ anchor, onClose, children, matchWidth, className = '', label }: {
  anchor: RefObject<HTMLElement | null>; onClose: () => void; children: ReactNode; matchWidth?: boolean; className?: string; label: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<CSSProperties | null>(null)
  const sheet = typeof matchMedia === 'function' && matchMedia(SHEET_QUERY).matches
  // Inside a modal dialog the page behind is inert, so the panel must live inside the dialog.
  const host = anchor.current?.closest('dialog[open]') ?? document.body

  const place = useCallback(() => {
    const a = anchor.current, f = ref.current
    if (!a || !f || sheet) return
    const r = a.getBoundingClientRect()
    const h = f.offsetHeight, w = matchWidth ? r.width : Math.max(f.offsetWidth, r.width)
    const below = innerHeight - r.bottom - 8, above = r.top - 8
    // Open upwards when the panel does not fit below and there is more room above (a calendar near
    // the bottom of a dialog would otherwise be cut short and need its own scrolling).
    const up = below < h && above > below
    const maxHeight = Math.max(160, up ? above : below)
    const left = Math.min(Math.max(8, r.left), Math.max(8, innerWidth - w - 8))
    setPos({ left, top: up ? Math.max(8, r.top - Math.min(h, maxHeight) - 6) : r.bottom + 6, width: matchWidth ? r.width : undefined, maxHeight })
  }, [anchor, matchWidth, sheet])

  useLayoutEffect(() => { place() }, [place, children])
  useEffect(() => {
    const move = () => place()
    addEventListener('resize', move); addEventListener('scroll', move, true)
    const down = (e: PointerEvent) => {
      const t = e.target as Node
      if (!ref.current?.contains(t) && !anchor.current?.contains(t)) onClose()
    }
    document.addEventListener('pointerdown', down, true)
    return () => { removeEventListener('resize', move); removeEventListener('scroll', move, true); document.removeEventListener('pointerdown', down, true) }
  }, [place, anchor, onClose])

  return createPortal(
    <>
      {sheet && <div className="float-scrim" onClick={onClose} />}
      <div ref={ref} className={`float ${sheet ? 'sheet' : ''} ${className}`} style={sheet ? undefined : pos ?? { visibility: 'hidden', left: 0, top: 0 }}
        role="dialog" aria-label={label}
        onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose(); (anchor.current?.querySelector('button, input') as HTMLElement | null)?.focus() } }}>
        {children}
      </div>
    </>,
    host,
  )
}

// ---------------------------------------------------------------------------
// Select

interface Opt { value: string; label: string; disabled?: boolean; group?: string }

const textOf = (n: ReactNode): string => {
  if (n === null || n === undefined || typeof n === 'boolean') return ''
  if (typeof n === 'string' || typeof n === 'number') return String(n)
  if (Array.isArray(n)) return n.map(textOf).join('')
  if (isValidElement<{ children?: ReactNode }>(n)) return textOf(n.props.children)
  return ''
}

function parseOptions(children: ReactNode, group?: string, out: Opt[] = []): Opt[] {
  Children.forEach(children, (c) => {
    if (!isValidElement<{ value?: string | number; children?: ReactNode; disabled?: boolean; label?: string }>(c)) return
    if (c.type === 'option') {
      const label = textOf(c.props.children)
      out.push({ value: String(c.props.value ?? label), label, disabled: c.props.disabled, group })
    } else if (c.type === 'optgroup') parseOptions(c.props.children, c.props.label, out)
    else if (c.props.children) parseOptions(c.props.children, group, out)
  })
  return out
}

interface SelectProps {
  value: string | number
  onChange?: (e: ChangeEvent<HTMLSelectElement>) => void
  children?: ReactNode
  disabled?: boolean
  className?: string
  style?: CSSProperties
  id?: string
  title?: string
  autoFocus?: boolean
  'aria-label'?: string
  /** Shown when no option matches the value. */
  placeholder?: string
}

export function Select({ value, onChange, children, disabled, className = '', style, id, title, autoFocus, placeholder, ...rest }: SelectProps) {
  const opts = useMemo(() => parseOptions(children), [children])
  const v = String(value)
  const current = opts.find((o) => o.value === v)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [q, setQ] = useState('')
  const anchor = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const uid = useId()
  const searchable = opts.length > 9
  const shown = useMemo(() => {
    const t = q.trim().toLowerCase()
    return t ? opts.filter((o) => o.label.toLowerCase().includes(t)) : opts
  }, [opts, q])

  const openList = () => {
    if (disabled) return
    setQ(''); setActive(Math.max(0, opts.findIndex((o) => o.value === v))); setOpen(true)
  }
  const close = useCallback(() => setOpen(false), [])
  const pick = (o: Opt) => {
    if (o.disabled) return
    setOpen(false)
    if (o.value !== v) onChange?.(synth<HTMLSelectElement>(o.value))
    anchor.current?.querySelector('button')?.focus()
  }
  useEffect(() => { if (open && !searchable) listRef.current?.focus({ preventScroll: true }) }, [open, searchable, shown.length])
  useEffect(() => { listRef.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' }) }, [active, open, shown])

  const move = (d: number) => {
    if (!shown.length) return
    let i = active
    for (let n = 0; n < shown.length; n++) { i = (i + d + shown.length) % shown.length; if (!shown[i].disabled) break }
    setActive(i)
  }
  const onListKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1) }
    else if (e.key === 'Home') { e.preventDefault(); setActive(0) }
    else if (e.key === 'End') { e.preventDefault(); setActive(shown.length - 1) }
    else if (e.key === 'Enter') { e.preventDefault(); if (shown[active]) pick(shown[active]) }
    else if (e.key === 'Tab') close()
    else if (!searchable && e.key.length === 1 && !e.ctrlKey && !e.metaKey) {
      const i = shown.findIndex((o) => o.label.toLowerCase().startsWith(e.key.toLowerCase()))
      if (i >= 0) setActive(i)
    }
  }

  let lastGroup: string | undefined
  return (
    <div className="select-wrap" ref={anchor} style={style}>
      <button type="button" id={id} className={`select ${open ? 'open' : ''} ${className}`} disabled={disabled} title={title} autoFocus={autoFocus}
        role="combobox" aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? uid : undefined} aria-label={rest['aria-label']}
        onClick={() => (open ? close() : openList())}
        onKeyDown={(e) => { if (['ArrowDown', 'ArrowUp'].includes(e.key) || (e.key === ' ' && !open)) { e.preventDefault(); openList() } }}>
        <span className={`select-value ${current ? '' : 'placeholder'}`}>{current?.label ?? (v === '' || !current ? placeholder ?? (v || 'Choose') : v)}</span>
        <Icon name="chevronDown" size={16} className="select-caret" />
      </button>
      {open && (
        <Floating anchor={anchor} onClose={close} matchWidth className="menu" label={rest['aria-label'] ?? 'Options'}>
          {searchable && (
            <div className="menu-search">
              <Icon name="search" size={15} />
              <input autoFocus value={q} onChange={(e) => { setQ(e.target.value); setActive(0) }} placeholder="Search" aria-label="Search options" onKeyDown={onListKey} />
            </div>
          )}
          <ul ref={listRef} id={uid} role="listbox" tabIndex={searchable ? -1 : 0} className="menu-list" onKeyDown={onListKey}>
            {shown.length === 0 && <li className="menu-empty">Nothing matches</li>}
            {shown.map((o, i) => {
              const head = o.group !== lastGroup && o.group ? <li key={`g-${o.group}`} className="menu-group" role="presentation">{o.group}</li> : null
              lastGroup = o.group
              return [head,
                <li key={o.value + i} role="option" aria-selected={o.value === v} data-active={i === active} aria-disabled={o.disabled}
                  className={`menu-item ${o.value === v ? 'sel' : ''} ${i === active ? 'on' : ''} ${o.disabled ? 'off' : ''}`}
                  onMouseEnter={() => setActive(i)} onClick={() => pick(o)}>
                  <span className="grow">{o.label}</span>{o.value === v && <Icon name="check" size={15} />}
                </li>]
            })}
          </ul>
        </Floating>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Dates

const pad = (n: number) => String(n).padStart(2, '0')
const toIso = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
const WEEK = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']

function parseIso(s: string): [number, number, number] | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
  if (!m) return null
  const y = +m[1], mo = +m[2] - 1, d = +m[3]
  const t = new Date(y, mo, d)
  return t.getFullYear() === y && t.getMonth() === mo && t.getDate() === d ? [y, mo, d] : null
}

const todayIso = () => { const n = new Date(); return toIso(n.getFullYear(), n.getMonth(), n.getDate()) }
const addDaysIso = (s: string, n: number) => { const [y, m, d] = parseIso(s)!; const t = new Date(y, m, d + n); return toIso(t.getFullYear(), t.getMonth(), t.getDate()) }

/** Whether the reader's region writes the month before the day (10/25/2026 in the US), from the browser's locale. */
export type DateOrder = 'dmy' | 'mdy'
let cachedOrder: DateOrder | null = null
export function dateOrder(): DateOrder {
  if (cachedOrder) return cachedOrder
  try {
    const parts = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'numeric', year: 'numeric' }).formatToParts(new Date(2026, 9, 25))
    const types = parts.map((x) => x.type)
    cachedOrder = types.indexOf('month') < types.indexOf('day') && types.indexOf('year') > types.indexOf('day') ? 'mdy' : 'dmy'
  } catch { cachedOrder = 'dmy' }
  return cachedOrder
}

/** The first day of the week in the reader's region: 0 Sunday, 1 Monday, 6 Saturday. Monday when the browser can't say. */
export function firstWeekday(): number {
  try {
    const loc = new Intl.Locale(navigator.language) as Intl.Locale & { weekInfo?: { firstDay: number }; getWeekInfo?: () => { firstDay: number } }
    const first = (loc.getWeekInfo?.() ?? loc.weekInfo)?.firstDay
    if (first) return first % 7
  } catch { /* older browsers */ }
  return 1
}

/** 25 Oct 2026, or Oct 25, 2026 where the month comes first. The month is a word, so neither reading is ambiguous. */
export function formatDate(s: string, order: DateOrder = dateOrder()): string {
  const p = parseIso(s)
  if (!p) return ''
  const mon = MONTHS[p[1]].slice(0, 3)
  return order === 'mdy' ? `${mon} ${p[2]}, ${p[0]}` : `${p[2]} ${mon} ${p[0]}`
}

/** An example in the reader's own order, for hints and error messages. */
export const dateExample = (order: DateOrder = dateOrder()) => (order === 'mdy' ? 'Oct 25, 2026 or 10/25/2026' : '25 Oct 2026 or 25/10/2026')

/**
 * Understands what people type: 25 Oct 2026, Oct 25 2026, 25/10/2026 (10/25/2026 where the month
 * comes first), 25-10-26, 2026-10-25, today, yesterday. A number pair that only works one way
 * round (25/10 is never month 25) is read that way, whatever the region.
 */
export function parseTypedDate(raw: string, order: DateOrder = dateOrder()): string | null {
  const t = raw.trim().toLowerCase()
  if (!t) return null
  if (t === 'today' || t === 't') return todayIso()
  if (t === 'yesterday' || t === 'y') return addDaysIso(todayIso(), -1)
  if (t === 'tomorrow') return addDaysIso(todayIso(), 1)
  const fix = (y: number) => (y < 100 ? 2000 + y : y)
  const make = (y: number, m: number, d: number) => (parseIso(toIso(y, m, d)) ? toIso(y, m, d) : null)
  let m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$/.exec(t)
  if (m) return make(+m[1], +m[2] - 1, +m[3])
  m = /^(\d{1,2})[-/. ](\d{1,2})[-/. ](\d{2,4})$/.exec(t)
  if (m) {
    const [a, b, y] = [+m[1], +m[2], fix(+m[3])]
    return order === 'mdy' ? make(y, a - 1, b) ?? make(y, b - 1, a) : make(y, b - 1, a) ?? make(y, a - 1, b)
  }
  const month = (w: string) => (w.length >= 3 ? MONTHS.findIndex((x) => x.toLowerCase().startsWith(w.slice(0, 3))) : -1)
  m = /^(\d{1,2})(?:st|nd|rd|th)?[-/. ]*([a-z]{3,9})\.?[-/. ,]*(\d{2,4})$/.exec(t)
  if (m) { const mi = month(m[2]); return mi < 0 ? null : make(fix(+m[3]), mi, +m[1]) }
  m = /^([a-z]{3,9})\.?[-/. ]*(\d{1,2})(?:st|nd|rd|th)?[-/. ,]+(\d{2,4})$/.exec(t)
  if (m) { const mi = month(m[1]); return mi < 0 ? null : make(fix(+m[3]), mi, +m[2]) }
  return null
}

interface DateProps {
  value: string
  onChange?: (e: ChangeEvent<HTMLInputElement>) => void
  min?: string
  max?: string
  required?: boolean
  disabled?: boolean
  id?: string
  className?: string
  style?: CSSProperties
  autoFocus?: boolean
  placeholder?: string
  'aria-label'?: string
}

export function DateField({ value, onChange, min, max, required, disabled, id, className = '', style, autoFocus, placeholder, ...rest }: DateProps) {
  const [text, setText] = useState(() => formatDate(value))
  const [editing, setEditing] = useState(false)
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<'days' | 'months' | 'years'>('days')
  const base = parseIso(value) ?? parseIso(todayIso())!
  const [view, setView] = useState({ y: base[0], m: base[1] })
  const [focusIso, setFocusIso] = useState(value || todayIso())
  const [bad, setBad] = useState('')
  const anchor = useRef<HTMLDivElement>(null)
  const proxy = useRef<HTMLInputElement>(null)
  const grid = useRef<HTMLDivElement>(null)
  const today = todayIso()

  useEffect(() => { if (!editing) { setText(formatDate(value)); setBad('') } }, [value, editing])

  const inRange = useCallback((s: string) => (!min || s >= min) && (!max || s <= max), [min, max])
  const rangeMsg = (s: string) => (min && s < min ? `Pick ${formatDate(min)} or later` : max && s > max ? `Pick ${formatDate(max)} or earlier` : '')
  // The proxy input carries `required` and range errors so the form's own validation reports them.
  useEffect(() => {
    const msg = value && !inRange(value) ? rangeMsg(value) : bad
    proxy.current?.setCustomValidity(msg)
  }, [value, bad, inRange]) // eslint-disable-line

  // A picked date satisfies the form's own validity check; let the page clear its message.
  useEffect(() => { if (value) proxy.current?.dispatchEvent(new Event('input', { bubbles: true })) }, [value])

  const emit = (s: string) => onChange?.(synth<HTMLInputElement>(s))
  const commit = (raw: string) => {
    if (!raw.trim()) { setBad(''); if (value) emit(''); return }
    const iso = parseTypedDate(raw)
    if (!iso) { setBad(`Enter a date like ${dateExample()}`); return }
    const msg = rangeMsg(iso)
    if (msg) { setBad(msg); return }
    setBad(''); setText(formatDate(iso))
    if (iso !== value) emit(iso)
  }
  const choose = (s: string) => {
    if (!inRange(s)) return
    setBad(''); setOpen(false); setText(formatDate(s)); emit(s)
    anchor.current?.querySelector('input')?.focus()
  }
  const openCal = () => {
    if (disabled) return
    const b = parseIso(value) ?? parseIso(inRange(today) ? today : min ?? max ?? today)!
    setView({ y: b[0], m: b[1] }); setMode('days'); setFocusIso(value && parseIso(value) ? value : toIso(...b)); setOpen(true)
  }
  const shiftMonth = (d: number) => setView((v) => { const t = new Date(v.y, v.m + d, 1); return { y: t.getFullYear(), m: t.getMonth() } })

  // Weeks start on the reader's first weekday (Sunday in the US, Monday in most places); six rows keep the height steady.
  const weekStart = useMemo(firstWeekday, [])
  const cells = useMemo(() => {
    const first = new Date(view.y, view.m, 1)
    const lead = (first.getDay() - weekStart + 7) % 7
    return Array.from({ length: 42 }, (_, i) => { const t = new Date(view.y, view.m, 1 - lead + i); return { iso: toIso(t.getFullYear(), t.getMonth(), t.getDate()), day: t.getDate(), other: t.getMonth() !== view.m } })
  }, [view, weekStart])
  const yearStart = Math.floor(view.y / 12) * 12

  useEffect(() => { if (open && mode === 'days') grid.current?.querySelector<HTMLElement>(`[data-iso="${focusIso}"]`)?.focus() }, [open, mode, focusIso, view])

  const onGridKey = (e: KeyboardEvent) => {
    const step = ({ ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 } as Record<string, number>)[e.key]
    if (step) { e.preventDefault(); const n = addDaysIso(focusIso, step); const p = parseIso(n)!; setFocusIso(n); setView({ y: p[0], m: p[1] }) }
    else if (e.key === 'PageUp' || e.key === 'PageDown') {
      e.preventDefault(); const d = e.key === 'PageUp' ? -1 : 1
      const p = parseIso(focusIso)!; const t = new Date(p[0], p[1] + d, Math.min(p[2], 28)); const n = toIso(t.getFullYear(), t.getMonth(), t.getDate())
      setFocusIso(n); setView({ y: t.getFullYear(), m: t.getMonth() })
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault(); const p = parseIso(focusIso)!; const n = addDaysIso(focusIso, e.key === 'Home' ? -((new Date(p[0], p[1], p[2]).getDay() - weekStart + 7) % 7) : 6 - ((new Date(p[0], p[1], p[2]).getDay() - weekStart + 7) % 7)); const q = parseIso(n)!; setFocusIso(n); setView({ y: q[0], m: q[1] })
    }
  }

  return (
    <div className={`dp ${bad ? 'bad' : ''} ${open ? 'open' : ''}`} ref={anchor} style={style}>
      <input id={id} className={`dp-input ${className}`} value={text} disabled={disabled} autoFocus={autoFocus} autoComplete="off"
        placeholder={placeholder ?? 'Select a date'} aria-label={rest['aria-label']} aria-invalid={bad ? true : undefined}
        // Phones get the calendar instead of the keyboard; a click anywhere on the field opens it too.
        inputMode={typeof matchMedia === 'function' && matchMedia(SHEET_QUERY).matches ? 'none' : 'text'}
        onClick={() => { if (!open) openCal() }}
        onFocus={(e) => { setEditing(true); e.currentTarget.select() }}
        onBlur={() => { setEditing(false); commit(text) }}
        onChange={(e) => { setText(e.target.value); setBad('') }}
        onKeyDown={(e) => { if (e.key === 'Enter') { commit(text); } else if (e.key === 'ArrowDown') { e.preventDefault(); openCal() } }} />
      <button type="button" className="dp-btn" tabIndex={-1} disabled={disabled} aria-label="Open calendar" onMouseDown={(e) => e.preventDefault()} onClick={() => (open ? setOpen(false) : openCal())}>
        <Icon name="calendar" size={17} />
      </button>
      <input ref={proxy} className="proxy" tabIndex={-1} aria-hidden required={required} value={value} onChange={() => {}} />
      {bad && <span className="dp-error" role="alert">{bad}</span>}
      {open && (
        <Floating anchor={anchor} onClose={() => setOpen(false)} className="cal" label="Choose a date">
          <div className="cal-head">
            <button type="button" className="icon-btn" aria-label={mode === 'days' ? 'Previous month' : 'Previous'} onClick={() => (mode === 'days' ? shiftMonth(-1) : mode === 'months' ? setView({ ...view, y: view.y - 1 }) : setView({ ...view, y: view.y - 12 }))}><Icon name="left" size={16} /></button>
            <button type="button" className="cal-title" onClick={() => setMode(mode === 'days' ? 'months' : mode === 'months' ? 'years' : 'days')} aria-live="polite">
              {mode === 'days' ? `${MONTHS[view.m]} ${view.y}` : mode === 'months' ? String(view.y) : `${yearStart} – ${yearStart + 11}`}
              <Icon name="chevronDown" size={14} />
            </button>
            <button type="button" className="icon-btn" aria-label={mode === 'days' ? 'Next month' : 'Next'} onClick={() => (mode === 'days' ? shiftMonth(1) : mode === 'months' ? setView({ ...view, y: view.y + 1 }) : setView({ ...view, y: view.y + 12 }))}><Icon name="right" size={16} /></button>
          </div>
          {mode === 'days' && (
            <div ref={grid} role="grid" aria-label={`${MONTHS[view.m]} ${view.y}`} onKeyDown={onGridKey}>
              <div className="cal-week" role="row">{[...WEEK.slice((weekStart + 6) % 7), ...WEEK.slice(0, (weekStart + 6) % 7)].map((w) => <span key={w} role="columnheader">{w}</span>)}</div>
              <div className="cal-days">
                {cells.map((c) => {
                  const off = !inRange(c.iso)
                  return (
                    <button type="button" key={c.iso} data-iso={c.iso} role="gridcell" disabled={off} tabIndex={c.iso === focusIso ? 0 : -1}
                      aria-selected={c.iso === value} aria-label={formatDate(c.iso)}
                      className={`dp-day ${c.other ? 'other' : ''} ${c.iso === value ? 'sel' : ''} ${c.iso === today ? 'today' : ''}`}
                      onClick={() => choose(c.iso)} onFocus={() => setFocusIso(c.iso)}>{c.day}</button>
                  )
                })}
              </div>
            </div>
          )}
          {mode === 'months' && (
            <div className="cal-grid">{MONTHS.map((m, i) => (
              <button type="button" key={m} className={`cal-cell ${i === view.m ? 'sel' : ''}`} onClick={() => { setView({ y: view.y, m: i }); setMode('days'); setFocusIso(toIso(view.y, i, 1)) }}>{m.slice(0, 3)}</button>
            ))}</div>
          )}
          {mode === 'years' && (
            <div className="cal-grid">{Array.from({ length: 12 }, (_, i) => yearStart + i).map((y) => (
              <button type="button" key={y} className={`cal-cell ${y === view.y ? 'sel' : ''}`} onClick={() => { setView({ ...view, y }); setMode('months') }}>{y}</button>
            ))}</div>
          )}
          <div className="cal-foot">
            <button type="button" className="link" disabled={!inRange(today)} onClick={() => choose(today)}>Today</button>
            {!required && value && <button type="button" className="link" onClick={() => { setOpen(false); setBad(''); emit('') }}>Clear</button>}
          </div>
        </Floating>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Numbers

type NumKind = 'decimal' | 'int' | 'expr'

const ALLOWED: Record<NumKind, RegExp> = {
  int: /[^0-9]/g,
  decimal: /[^0-9.,]/g,
  expr: /[^0-9.,+\-*/×÷−() ]/g,
}

/** Removes characters that can never be part of a number (and a second decimal point). */
export function cleanNumber(raw: string, kind: NumKind): string {
  if (kind === 'expr') return raw.replace(ALLOWED.expr, '')
  const neg = kind === 'decimal' && raw.trimStart().startsWith('-') ? '-' : ''
  let s = raw.replace(ALLOWED[kind], '')
  if (kind === 'decimal') { const i = s.indexOf('.'); if (i >= 0) s = s.slice(0, i + 1) + s.slice(i + 1).replace(/\./g, '') }
  return neg + s
}

type NumProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'type' | 'inputMode'> & {
  value: string | number
  onChange?: (e: ChangeEvent<HTMLInputElement>) => void
  kind?: NumKind
  /** Text shown inside the field on the right, e.g. % or months. */
  suffix?: string
  /** Text shown inside the field on the left, e.g. a currency symbol. */
  prefix?: string
}

export function NumInput({ value, onChange, kind = 'decimal', suffix, prefix, className = '', style, ...rest }: NumProps) {
  const input = (
    <input {...rest} className={`${className} ${prefix ? 'has-prefix' : ''} ${suffix ? 'has-suffix' : ''}`} value={value} autoComplete="off"
      inputMode={kind === 'int' ? 'numeric' : 'decimal'} spellCheck={false}
      style={prefix || suffix ? undefined : style}
      onChange={(e) => onChange?.(synth<HTMLInputElement>(cleanNumber(e.target.value, kind)))} />
  )
  if (!prefix && !suffix) return input
  return <span className="adorn" style={style}>{prefix && <i className="pre">{prefix}</i>}{input}{suffix && <i className="suf">{suffix}</i>}</span>
}

// ---------------------------------------------------------------------------
// Password

type PwProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>

export function PasswordInput({ className = '', ...rest }: PwProps) {
  const [show, setShow] = useState(false)
  return (
    <span className="pw">
      <input {...rest} type={show ? 'text' : 'password'} className={className} />
      <button type="button" className="icon-btn" tabIndex={-1} aria-label={show ? 'Hide password' : 'Show password'} aria-pressed={show} onClick={() => setShow(!show)}>
        <Icon name={show ? 'eyeOff' : 'eye'} size={17} />
      </button>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Switch and file picker

export function Switch({ checked, onChange, children, disabled, hint }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode; disabled?: boolean; hint?: ReactNode }) {
  return (
    <label className={`switch ${disabled ? 'off' : ''}`}>
      <span className="switch-text"><span>{children}</span>{hint && <span className="muted small">{hint}</span>}</span>
      <input type="checkbox" role="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <i className="switch-track" aria-hidden />
    </label>
  )
}

/** A drop zone that also opens the file dialog; shows the chosen file's name. */
export function FilePick({ accept, onPick, label = 'Choose a file or drop it here', hint, disabled }: { accept?: string; onPick: (f: File) => void; label?: string; hint?: string; disabled?: boolean }) {
  const ref = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  const [name, setName] = useState('')
  const take = (f?: File | null) => { if (f) { setName(f.name); onPick(f) } }
  return (
    <div className={`drop ${over ? 'over' : ''} ${disabled ? 'off' : ''}`} role="button" tabIndex={disabled ? -1 : 0}
      onClick={() => !disabled && ref.current?.click()}
      onKeyDown={(e) => { if ((e.key === 'Enter' || e.key === ' ') && !disabled) { e.preventDefault(); ref.current?.click() } }}
      onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); if (!disabled) take(e.dataTransfer.files[0]) }}>
      <span className="drop-icon"><Icon name="upload" size={22} /></span>
      <strong>{name || label}</strong>
      {hint && <span className="muted small">{hint}</span>}
      <input ref={ref} type="file" accept={accept} hidden onChange={(e) => { take(e.target.files?.[0]); e.target.value = '' }} />
    </div>
  )
}
