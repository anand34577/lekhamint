// Schedule arithmetic for recurring rules. Mirrors android/.../core/Recurrence.kt
// and domain/RecurringEngine.kt: occurrence n is computed from the start date
// (not chained), so a rule on the 31st lands on the last day of shorter months.
// Occurrence IDs are UUIDv5 of "<rule id>:<date>", so the web and every phone
// that record the same occurrence create one record, never two.

import type { RecurringRule, Transaction } from './api'
import { ID_NAMESPACE, addDays, addMonths, parseDate, uuidv5 } from './util'

export function occurrence(start: string, frequency: string, every: number, index: number): string {
  const step = Math.max(1, every) * index
  switch (frequency) {
    case 'daily': return addDays(start, step)
    case 'weekly': return addDays(start, 7 * step)
    case 'yearly': return addMonths(start, 12 * step)
    default: return addMonths(start, step)
  }
}

/** Index of the first occurrence on or after [date]. */
export function firstIndexOnOrAfter(start: string, frequency: string, every: number, date: string): number {
  if (date <= start) return 0
  const days = (parseDate(date).getTime() - parseDate(start).getTime()) / 864e5
  const unit = { daily: 1, weekly: 7, yearly: 365.25 }[frequency] ?? 30.44
  let i = Math.max(0, Math.floor(days / unit / Math.max(1, every)) - 1)
  while (i > 0 && occurrence(start, frequency, every, i - 1) >= date) i--
  while (occurrence(start, frequency, every, i) < date) i++
  return i
}

type Schedule = Pick<RecurringRule, 'start_date' | 'frequency' | 'every' | 'next_date' | 'paused'> & { end_date?: string | null; max_occurrences?: number | null }

/** Occurrences from next_date (or the start) up to [until], inclusive. */
export function dueDates(r: Schedule, until: string, limit = 400): string[] {
  if (r.paused) return []
  const out: string[] = []
  for (let i = firstIndexOnOrAfter(r.start_date, r.frequency, r.every, r.next_date ?? r.start_date); out.length < limit; i++) {
    if (r.max_occurrences != null && i >= r.max_occurrences) break
    const d = occurrence(r.start_date, r.frequency, r.every, i)
    if (d > until || (r.end_date && d > r.end_date)) break
    out.push(d)
  }
  return out
}

/** The next date the rule fires, or null when it has ended. */
export function nextDate(r: Schedule): string | null {
  return dueDates({ ...r, paused: false }, '9999-12-31', 1)[0] ?? null
}

/** First occurrence strictly after [date], or null when the rule has ended. */
export function nextAfter(r: Schedule, date: string): string | null {
  const i = firstIndexOnOrAfter(r.start_date, r.frequency, r.every, addDays(date, 1))
  if (r.max_occurrences != null && i >= r.max_occurrences) return null
  const d = occurrence(r.start_date, r.frequency, r.every, i)
  return r.end_date && d > r.end_date ? null : d
}

/** next_date for a new or rescheduled rule: the first occurrence on or after today. */
export function initialNextDate(start: string, frequency: string, every: number, today: string): string {
  return occurrence(start, frequency, every, firstIndexOnOrAfter(start, frequency, every, start > today ? start : today))
}

export const occurrenceId = (ruleId: string, date: string) => uuidv5(ID_NAMESPACE, `${ruleId}:${date}`)

export type RuleWithId = RecurringRule & { id: string; note?: string | null; to_account_id?: string | null; to_amount?: number | null; tag_ids?: string[] }

/** The transaction an occurrence records (same fields as RecurringEngine.occurrenceIfMissing). */
export function occurrenceData(r: RuleWithId, date: string, amount = r.amount): Partial<Transaction> {
  const at = new Date(date + 'T09:00:00').getTime()
  const transfer = r.type === 'transfer'
  const sameCurrency = transfer && (r.to_amount == null || r.to_amount === r.amount)
  return {
    type: r.type as Transaction['type'], account_id: r.account_id, amount,
    to_account_id: transfer ? r.to_account_id ?? null : null,
    to_amount: transfer ? (sameCurrency ? amount : r.to_amount ?? amount) : null,
    category_id: transfer ? null : r.category_id, payee: r.payee ?? r.name, note: r.note ?? null,
    occurred_at: at, local_date: date, tz_offset_min: -new Date(at).getTimezoneOffset(),
    tag_ids: r.tag_ids ?? [], recurring_rule_id: r.id, status: 'cleared',
  }
}
