// Quick add: turns a line like "450 swiggy dinner yesterday #work @hdfc" into
// a draft transaction. Pure and synchronous; the caller resolves the account,
// tags, category and rules against the ledger.

import { evaluate } from './finance'
import { addDays, isoDate, parseDate } from './util'

export interface QuickDraft {
  /** Major units as typed (e.g. 450.5), or null when no amount was found. */
  amount: number | null
  type: 'expense' | 'income'
  date: string
  /** Tag names without "#". */
  tags: string[]
  /** Text after "@", matched against account names by the caller. */
  account: string | null
  /** What is left: the payee or description. */
  text: string
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']
const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat']
const INCOME_WORDS = new Set(['income', 'received', 'receive', 'got', 'earned', 'refund', 'refunded', 'cashback'])
const FILLER = new Set(['on', 'for', 'at', 'paid', 'spent', 'to', 'from', 'via', 'with', 'rs', 'rs.', 'inr'])

const month = (s: string) => MONTHS.indexOf(s.slice(0, 3).toLowerCase())

/** A calendar date in the past year closest to [today] for day/month. */
function pastDate(day: number, mon: number, today: string, year?: number): string | null {
  if (mon < 0 || mon > 11 || day < 1 || day > 31) return null
  const t = parseDate(today)
  let y = year ?? t.getFullYear()
  const make = (yy: number) => new Date(yy, mon, day)
  let d = make(y)
  if (d.getMonth() !== mon) return null
  if (year === undefined && isoDate(d) > today) d = make(--y)
  return isoDate(d)
}

export function parseQuick(input: string, today = isoDate()): QuickDraft {
  const out: QuickDraft = { amount: null, type: 'expense', date: today, tags: [], account: null, text: '' }
  const words = input.trim().split(/\s+/).filter(Boolean)
  const rest: string[] = []
  for (let i = 0; i < words.length; i++) {
    const w = words[i]
    const lw = w.toLowerCase()
    const next = words[i + 1]?.toLowerCase()
    if (w.startsWith('#') && w.length > 1) { out.tags.push(w.slice(1)); continue }
    if (w.startsWith('@') && w.length > 1) { out.account = w.slice(1); continue }
    if (lw === 'today') { out.date = today; continue }
    if (lw === 'yesterday' || lw === 'yday') { out.date = addDays(today, -1); continue }
    // "3 days ago"
    if (/^\d{1,3}$/.test(w) && (next === 'days' || next === 'day') && words[i + 2]?.toLowerCase() === 'ago') {
      out.date = addDays(today, -Number(w)); i += 2; continue
    }
    // Weekday names: the most recent one (today counts).
    const wd = DAYS.indexOf(lw.slice(0, 3))
    if (wd >= 0 && /^(sun|mon|tue|wed|thu|fri|sat)[a-z]*$/.test(lw) && (lw.length === 3 || lw.endsWith('day'))) {
      const back = (parseDate(today).getDay() - wd + 7) % 7
      out.date = addDays(today, -back); continue
    }
    // ISO date or 24/10 or 24/10/2026 (day first).
    if (/^\d{4}-\d{2}-\d{2}$/.test(w)) { out.date = w; continue }
    const dm = w.match(/^(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?$/)
    if (dm && out.amount !== null) {
      const y = dm[3] ? (dm[3].length === 2 ? 2000 + Number(dm[3]) : Number(dm[3])) : undefined
      const d = pastDate(Number(dm[1]), Number(dm[2]) - 1, today, y)
      if (d) { out.date = d; continue }
    }
    // "12 oct" / "oct 12"
    if (/^\d{1,2}(st|nd|rd|th)?$/.test(lw) && next && month(next) >= 0 && /^[a-z]+$/.test(next) && out.amount !== null) {
      const d = pastDate(parseInt(lw, 10), month(next), today)
      if (d) { out.date = d; i++; continue }
    }
    if (month(lw) >= 0 && /^[a-z]{3,9}$/.test(lw) && MONTHS.some((m) => lw.startsWith(m)) && next && /^\d{1,2}(st|nd|rd|th)?$/.test(next)) {
      const d = pastDate(parseInt(next, 10), month(lw), today)
      if (d) { out.date = d; i++; continue }
    }
    // The first number-like token is the amount: "450", "₹1,250.50", "+500", "230+40".
    if (out.amount === null) {
      const m = w.match(/^([+]?)[₹$€£¥]?([\d.,]+(?:[+\-*/×÷][\d.,]+)*)$/)
      if (m && /\d/.test(m[2])) {
        const v = evaluate(m[2].replace(/,/g, ''))
        if (v !== null && v > 0) {
          out.amount = v
          if (m[1] === '+') out.type = 'income'
          continue
        }
      }
    }
    if (INCOME_WORDS.has(lw)) { out.type = 'income'; if (lw === 'refund' || lw === 'cashback') rest.push(w); continue }
    rest.push(w)
  }
  // Drop filler words at the edges ("spent 200 on coffee" → "coffee").
  while (rest.length && FILLER.has(rest[0].toLowerCase())) rest.shift()
  while (rest.length && FILLER.has(rest[rest.length - 1].toLowerCase())) rest.pop()
  out.text = rest.join(' ')
  return out
}

/** Finds the best match for [query] among [items] by name: exact, then prefix, then contains. */
export function bestMatch<T extends { name: string }>(items: T[], query: string | null): T | undefined {
  const q = query?.trim().toLowerCase()
  if (!q) return undefined
  return items.find((x) => x.name.toLowerCase() === q)
    ?? items.find((x) => x.name.toLowerCase().startsWith(q))
    ?? items.find((x) => x.name.toLowerCase().includes(q))
}

/** [text] without the words of [name], ignoring case: "groceries big bazaar" − "Groceries" = "big bazaar". */
export function withoutWords(text: string, name: string): string {
  const drop = new Set(name.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean))
  return text.split(/\s+/).filter((w) => !drop.has(w.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ''))).join(' ').trim()
}

/** Everyday words for the default categories: "450 lunch" files under Dining out. Names the person changed fall through. */
const WORDS: Record<string, string> = Object.fromEntries(Object.entries({
  'Dining out': 'lunch dinner breakfast brunch restaurant meal biryani pizza burger',
  'Food delivery': 'swiggy zomato',
  'Coffee & snacks': 'coffee tea chai snack snacks starbucks',
  'Groceries': 'grocery vegetables veggies milk fruits bigbasket blinkit zepto dmart kirana',
  'Fuel': 'petrol diesel cng',
  'Taxi & ride-hailing': 'uber ola cab taxi auto rickshaw rapido',
  'Public transport': 'metro bus',
  'Parking & tolls': 'parking toll fastag',
  'Flights & trains': 'flight flights train irctc',
  'Hotels': 'hotel',
  'Movies & events': 'movie movies cinema concert',
  'Subscriptions': 'netflix spotify hotstar subscription',
  'Pharmacy': 'medicine medicines chemist',
  'Doctor': 'clinic consultation',
  'Fitness': 'gym',
  'Salon & grooming': 'haircut salon barber',
  'Internet': 'wifi broadband',
  'Mobile': 'recharge',
  'Clothing': 'clothes shirt shoes',
  'Gifts': 'gift',
  'Charity': 'donation',
  'Books': 'book',
}).flatMap(([cat, words]) => words.split(' ').map((w) => [w, cat])))

const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()

/** A category whose name appears as a word in [text] (longest name wins), else one an everyday word points to. */
export function categoryFromText<T extends { name: string }>(cats: T[], text: string): T | undefined {
  const words = ' ' + norm(text) + ' '
  const byName = [...cats].sort((a, b) => b.name.length - a.name.length)
    .find((c) => c.name.length > 2 && words.includes(' ' + norm(c.name) + ' '))
  if (byName) return byName
  for (const w of norm(text).split(' ')) {
    const cat = WORDS[w]
    const hit = cat ? cats.find((c) => norm(c.name) === norm(cat)) : undefined
    if (hit) return hit
  }
  return undefined
}
