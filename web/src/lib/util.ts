// IDs and dates. IDs are UUIDv7 like every other LekhaMint client (docs/04).

export function uuidv7(now = Date.now()): string {
  const b = new Uint8Array(16)
  crypto.getRandomValues(b)
  let ts = now
  for (let i = 5; i >= 0; i--) { b[i] = ts % 256; ts = Math.floor(ts / 256) }
  b[6] = (b[6] & 0x0f) | 0x70
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/** Local calendar date as YYYY-MM-DD (not UTC). */
export function isoDate(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function parseDate(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function addDays(s: string, n: number): string { const d = parseDate(s); d.setDate(d.getDate() + n); return isoDate(d) }

/** First and last day of the month containing [s], plus [offset] months. */
export function monthRange(s: string, offset = 0): [string, string] {
  const d = parseDate(s)
  const first = new Date(d.getFullYear(), d.getMonth() + offset, 1)
  const last = new Date(d.getFullYear(), d.getMonth() + offset + 1, 0)
  return [isoDate(first), isoDate(last)]
}

/** Adds months anchored on the start day (31st -> last day of shorter months). */
export function addMonths(s: string, n: number): string {
  const d = parseDate(s)
  const target = new Date(d.getFullYear(), d.getMonth() + n, 1)
  const last = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  target.setDate(Math.min(d.getDate(), last))
  return isoDate(target)
}

export function monthLabel(s: string): string {
  return parseDate(s).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
}

export function dayLabel(s: string): string {
  const today = isoDate()
  if (s === today) return 'Today'
  if (s === addDays(today, -1)) return 'Yesterday'
  const d = parseDate(s)
  return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', ...(d.getFullYear() !== new Date().getFullYear() ? { year: 'numeric' } : {}) })
}

export function shortDate(s: string): string {
  return parseDate(s).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

export function download(name: string, blob: Blob) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = name; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function csvEscape(s: string): string {
  const safe = /^[=+\-@\t\r]/.test(s) && Number.isNaN(Number(s)) ? "'" + s : s
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
}

/** Colours for new tags, picked by name so the same tag always gets the same one. */
export const TAG_COLORS = ['#0F766E', '#2563EB', '#7C3AED', '#DB2777', '#EA580C', '#CA8A04', '#16A34A', '#0891B2', '#475569']
export function tagColor(name: string): string {
  let h = 0
  for (const ch of name.toLowerCase()) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return TAG_COLORS[h % TAG_COLORS.length]
}

/** Namespace for deterministic LekhaMint IDs (same as server/internal/ids and Android Ids.kt). */
export const ID_NAMESPACE = '6f1a3c52-8d2e-4b7a-9c41-0e5d7b2a9f13'

/** RFC 4122 UUIDv5 (SHA-1 of namespace + name). */
export async function uuidv5(namespace: string, name: string): Promise<string> {
  const ns = namespace.replace(/-/g, '')
  const nsBytes = new Uint8Array(16)
  for (let i = 0; i < 16; i++) nsBytes[i] = parseInt(ns.slice(i * 2, i * 2 + 2), 16)
  const nameBytes = new TextEncoder().encode(name)
  const buf = new Uint8Array(16 + nameBytes.length)
  buf.set(nsBytes); buf.set(nameBytes, 16)
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-1', buf)).slice(0, 16)
  hash[6] = (hash[6] & 0x0f) | 0x50
  hash[8] = (hash[8] & 0x3f) | 0x80
  const h = Array.from(hash, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
