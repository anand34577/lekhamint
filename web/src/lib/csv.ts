// Bank-statement and spreadsheet import (docs/02, "Importing"). Everything runs
// in the browser: the file never leaves the device except as the transactions
// the user confirms.

import { parseMoney } from './money'
import { addDays } from './util'

// --- Parsing ------------------------------------------------------------------

/** Picks the delimiter that splits the first lines most consistently. */
export function detectDelimiter(text: string): string {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '').slice(0, 10)
  let best = ',', bestScore = -1
  for (const d of [',', ';', '\t', '|']) {
    const counts = lines.map((l) => splitLine(l, d).length)
    if (counts.length === 0 || counts[0] < 2) continue
    const same = counts.filter((c) => c === counts[0]).length
    const score = same * 100 + counts[0]
    if (score > bestScore) { best = d; bestScore = score }
  }
  return best
}

function splitLine(line: string, d: string): string[] {
  return parseCsv(line, d)[0] ?? []
}

/** RFC 4180 CSV: quoted fields, doubled quotes, newlines inside quotes, CRLF or LF. */
export function parseCsv(text: string, delimiter = ','): string[][] {
  const s = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
  const rows: string[][] = []
  let row: string[] = [], field = '', quoted = false, i = 0
  while (i < s.length) {
    const c = s[i]
    if (quoted) {
      if (c === '"') {
        if (s[i + 1] === '"') { field += '"'; i += 2; continue }
        quoted = false; i++; continue
      }
      field += c; i++; continue
    }
    if (c === '"' && field === '') { quoted = true; i++; continue }
    if (c === delimiter) { row.push(field); field = ''; i++; continue }
    if (c === '\r' || c === '\n') {
      row.push(field); field = ''
      if (row.some((f) => f.trim() !== '')) rows.push(row)
      row = []
      i += c === '\r' && s[i + 1] === '\n' ? 2 : 1
      continue
    }
    field += c; i++
  }
  row.push(field)
  if (row.some((f) => f.trim() !== '')) rows.push(row)
  return rows
}

// --- Columns --------------------------------------------------------------------

export type Role = 'date' | 'description' | 'note' | 'amount' | 'debit' | 'credit' | 'category' | 'type' | 'ignore'

export const ROLE_LABELS: Record<Role, string> = {
  date: 'Date', description: 'Description / payee', note: 'Note', amount: 'Amount (signed)', debit: 'Money out (debit)',
  credit: 'Money in (credit)', category: 'Category', type: 'Type (expense / income, Dr / Cr)', ignore: 'Ignore',
}

const GUESS: [Role, RegExp][] = [
  ['date', /^(txn |transaction |value |posting |posted |booking )?date|^date$|^when$/i],
  ['debit', /debit|withdrawal|money out|paid out|^dr\.?$|^out$/i],
  ['credit', /credit|deposit|money in|paid in|^cr\.?$|^in$/i],
  ['amount', /amount|^amt|^value$|^sum$/i],
  ['category', /categor/i],
  ['type', /^type$|^dr\s*\/\s*cr$|transaction type/i],
  ['note', /note|memo|comment|description 2|reference|ref\.?\s*no|cheque|chq/i],
  ['description', /description|narration|details|payee|merchant|title|particulars|remarks|name/i],
]

/** Guesses a role for each header; each role except note/ignore is used at most once. */
export function guessRoles(headers: string[]): Role[] {
  const used = new Set<Role>()
  return headers.map((h) => {
    for (const [role, re] of GUESS) {
      if (re.test(h.trim()) && !used.has(role)) { used.add(role); return role }
    }
    return 'ignore'
  })
}

// --- Dates -----------------------------------------------------------------------

export type DateFormat = 'YMD' | 'DMY' | 'MDY'
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']

/** Parses a date in the given order; month names ("24 Oct 2026", "Oct 24, 2026") work in any order. */
export function parseDateIn(raw: string, fmt: DateFormat): string | null {
  const s = raw.trim().replace(/[T ]\d{1,2}:\d{2}(:\d{2})?.*$/, '').trim()
  const named = s.match(/^(\d{1,2})[\s\-./]+([A-Za-z]{3,})[\s\-./,]+(\d{2,4})$/) ?? null
  const namedUS = s.match(/^([A-Za-z]{3,})[\s\-./]+(\d{1,2}),?[\s\-./]+(\d{2,4})$/) ?? null
  let y: number, m: number, d: number
  if (named || namedUS) {
    const [day, mon, yr] = named ? [named[1], named[2], named[3]] : [namedUS![2], namedUS![1], namedUS![3]]
    m = MONTHS.indexOf(mon.slice(0, 3).toLowerCase()) + 1
    d = Number(day); y = Number(yr)
  } else {
    const parts = s.split(/[\-./\s]+/).filter(Boolean)
    if (parts.length !== 3 || parts.some((p) => !/^\d+$/.test(p))) return null
    const n = parts.map(Number)
    if (parts[0].length === 4) [y, m, d] = n
    else if (fmt === 'MDY') [m, d, y] = n
    else [d, m, y] = n
  }
  if (y < 100) y += y >= 70 ? 1900 : 2000
  if (!(m >= 1 && m <= 12 && d >= 1 && d <= 31 && y >= 1900 && y <= 2200)) return null
  const dt = new Date(Date.UTC(y, m - 1, d))
  if (dt.getUTCMonth() !== m - 1) return null // 31 Feb
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

/** Picks the order that parses every sample; day-first wins ties (common outside the US). */
export function detectDateFormat(samples: string[], preferUS = false): DateFormat {
  const order: DateFormat[] = preferUS ? ['MDY', 'DMY', 'YMD'] : ['DMY', 'MDY', 'YMD']
  const vals = samples.filter((s) => s.trim() !== '')
  // 2026-10-24 reads the same in every order; say so, rather than "month first".
  if (vals.length > 0 && vals.every((v) => /^\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}/.test(v))) return 'YMD'
  for (const f of order) if (vals.every((v) => parseDateIn(v, f) !== null)) return f
  return order[0]
}

// --- Amounts ---------------------------------------------------------------------

/**
 * Parses "1,234.56", "-1 234,56", "(45.00)", "₹ 1,250", "120.00 DR" into signed
 * minor units. [decimalComma] reads "1.234,56" as 1234.56. Returns null if unreadable.
 */
export function parseAmount(raw: string, currency: string, decimalComma = false): number | null {
  let s = raw.trim()
  if (s === '') return null
  let sign = 1
  if (/^\(.*\)$/.test(s)) { sign = -1; s = s.slice(1, -1) }
  const suffix = s.match(/\s*(dr|cr|-)\.?$/i)
  if (suffix) {
    if (suffix[1].toLowerCase() === 'dr' || suffix[1] === '-') sign = -sign
    s = s.slice(0, suffix.index)
  }
  s = s.replace(/[^\d.,\-+]/g, '')
  if (s.startsWith('-')) { sign = -sign; s = s.slice(1) } else if (s.startsWith('+')) s = s.slice(1)
  if (decimalComma) s = s.replace(/\./g, '').replace(',', '.')
  else s = s.replace(/,/g, '')
  if (!/^\d*\.?\d+$/.test(s) && !/^\d+\.$/.test(s)) return null
  const minor = parseMoney(s, currency)
  return minor === null ? null : sign * minor
}

// --- Rows ------------------------------------------------------------------------

export interface ImportOptions {
  roles: Role[]
  hasHeader: boolean
  dateFormat: DateFormat
  currency: string
  decimalComma: boolean
  /** Card statements often list purchases as positive numbers. */
  positiveIsExpense: boolean
}

export interface ParsedRow {
  line: number // 1-based line in the file, for messages
  date: string
  type: 'expense' | 'income'
  amount: number // positive minor units
  description: string
  note: string
  category: string
  /** A bank's own ID for the transaction (OFX FITID), when the file has one: the most reliable fingerprint. */
  ref?: string
}

export interface RowError { line: number; message: string }

export function toRows(table: string[][], o: ImportOptions): { rows: ParsedRow[]; errors: RowError[] } {
  const col = (r: Role) => o.roles.indexOf(r)
  const [iDate, iDesc, iNote, iAmt, iDebit, iCredit, iCat, iType] =
    (['date', 'description', 'note', 'amount', 'debit', 'credit', 'category', 'type'] as Role[]).map(col)
  const rows: ParsedRow[] = [], errors: RowError[] = []
  const body = o.hasHeader ? table.slice(1) : table
  body.forEach((cells, k) => {
    const line = k + (o.hasHeader ? 2 : 1)
    const get = (i: number) => (i >= 0 ? (cells[i] ?? '').trim() : '')
    const date = parseDateIn(get(iDate), o.dateFormat)
    if (!date) { errors.push({ line, message: `Unreadable date “${get(iDate)}”` }); return }
    let signed: number | null = null
    if (iDebit >= 0 || iCredit >= 0) {
      const out = get(iDebit) ? parseAmount(get(iDebit), o.currency, o.decimalComma) : 0
      const inn = get(iCredit) ? parseAmount(get(iCredit), o.currency, o.decimalComma) : 0
      if (out === null || inn === null) { errors.push({ line, message: 'Unreadable amount' }); return }
      signed = Math.abs(inn) - Math.abs(out)
    } else if (iAmt >= 0) {
      signed = parseAmount(get(iAmt), o.currency, o.decimalComma)
      if (signed === null) { errors.push({ line, message: `Unreadable amount “${get(iAmt)}”` }); return }
      const t = get(iType).toLowerCase()
      if (/^(dr|debit|expense|withdrawal|out)/.test(t)) signed = -Math.abs(signed)
      else if (/^(cr|credit|income|deposit|in)/.test(t)) signed = Math.abs(signed)
      else if (o.positiveIsExpense) signed = -signed
    } else {
      errors.push({ line, message: 'No amount column chosen' }); return
    }
    if (signed === 0) { errors.push({ line, message: 'Amount is zero' }); return }
    rows.push({
      line, date, type: signed < 0 ? 'expense' : 'income', amount: Math.abs(signed),
      description: get(iDesc).replace(/\s+/g, ' '), note: get(iNote), category: get(iCat),
    })
  })
  return { rows, errors }
}

// --- Duplicates and IDs ----------------------------------------------------------

export interface Existing { id: string; type: string; amount: number; local_date: string; account_id: string }

/**
 * Pairs each imported row with at most one existing transaction on the same
 * account with the same type and amount, dated within ±2 days (closest first).
 * Returns the matched existing ID per row index.
 */
export function findDuplicates(rows: ParsedRow[], accountId: string, existing: Existing[]): Map<number, string> {
  const pool = existing.filter((e) => e.account_id === accountId)
  const used = new Set<string>()
  const out = new Map<number, string>()
  rows.forEach((r, i) => {
    let best: Existing | null = null, bestGap = 99
    for (const e of pool) {
      if (used.has(e.id) || e.type !== r.type || e.amount !== r.amount) continue
      const gap = Math.abs(dayDiff(e.local_date, r.date))
      if (gap <= 2 && gap < bestGap) { best = e; bestGap = gap }
    }
    if (best) { used.add(best.id); out.set(i, best.id) }
  })
  return out
}

function dayDiff(a: string, b: string): number {
  return Math.round((Date.UTC(+a.slice(0, 4), +a.slice(5, 7) - 1, +a.slice(8, 10)) - Date.UTC(+b.slice(0, 4), +b.slice(5, 7) - 1, +b.slice(8, 10))) / 864e5)
}

/** The date window to fetch existing transactions for duplicate checks. */
export function dateWindow(rows: ParsedRow[]): [string, string] | null {
  if (rows.length === 0) return null
  const dates = rows.map((r) => r.date).sort()
  return [addDays(dates[0], -2), addDays(dates[dates.length - 1], 2)]
}

/**
 * Stable fingerprints: identical rows in one file get #0, #1, … so a
 * statement imported twice (or two overlapping statements) maps to the same IDs.
 */
export function fingerprints(rows: ParsedRow[]): string[] {
  const seen = new Map<string, number>()
  return rows.map((r) => {
    if (r.ref) return r.ref
    const key = `${r.date}|${r.type}|${r.amount}|${r.description.toLowerCase()}`
    const n = seen.get(key) ?? 0
    seen.set(key, n + 1)
    return `${key}#${n}`
  })
}

/** Finds a category by name or "Parent · Child" / "Parent: Child" / "Parent > Child" path. */
export function matchCategory(name: string, cats: { id: string; name: string; parent_id: string | null; kind: string }[], kind: string): string | null {
  const n = name.trim().toLowerCase()
  if (!n) return null
  const byId = new Map(cats.map((c) => [c.id, c]))
  const path = (c: { name: string; parent_id: string | null }) => {
    const p = c.parent_id ? byId.get(c.parent_id) : undefined
    return p ? `${p.name} · ${c.name}`.toLowerCase() : c.name.toLowerCase()
  }
  const wanted = n.replace(/\s*(:|>|\/|·)\s*/g, ' · ')
  const pick = (pred: (c: (typeof cats)[number]) => boolean) =>
    cats.find((c) => c.kind === kind && pred(c)) ?? cats.find((c) => pred(c))
  return (pick((c) => path(c) === wanted) ?? pick((c) => c.name.toLowerCase() === n))?.id ?? null
}
