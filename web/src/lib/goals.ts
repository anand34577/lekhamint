import type { Balance, Goal } from './api'
import { daysBetween, isoDate } from './util'

export type GoalStatus = 'done' | 'ahead' | 'on_track' | 'behind' | 'overdue' | 'no_date'

export interface GoalProgress {
  /** Saved so far: saved_amount plus linked accounts' worth, in the goal's currency (minor units). */
  saved: number
  target: number
  /** 0…1 (capped at 1). */
  fraction: number
  /** Still to save (0 when reached). */
  left: number
  /** Whole and part months to the target date; null without a date. */
  monthsLeft: number | null
  /** Saving needed each month from now to reach the target on time; null without a date or when done. */
  perMonth: number | null
  /** Where a steady saver would be by today, from the start date to the target date. */
  expected: number | null
  status: GoalStatus
  /** Linked accounts left out because they are in another currency. */
  otherCurrency: number
}

/** Balance a goal counts for an account: its recorded value when it has one, else its balance. */
const worth = (b: Balance) => b.value ?? b.balance

/**
 * Progress of a goal. Linked accounts count with what they hold now (a
 * negative balance counts as 0); only accounts in the goal's currency count,
 * so a goal never mixes currencies without a rate.
 */
export function goalProgress(g: Goal & { created_at?: number }, balances: Balance[], today = isoDate()): GoalProgress {
  let saved = Math.max(0, g.saved_amount ?? 0)
  let otherCurrency = 0
  for (const id of g.account_ids ?? []) {
    const b = balances.find((x) => x.account_id === id)
    if (!b) continue
    if (b.currency !== g.currency) { otherCurrency++; continue }
    saved += Math.max(0, worth(b))
  }
  const target = g.target_amount
  const left = Math.max(0, target - saved)
  const fraction = target > 0 ? Math.min(1, saved / target) : 0
  if (left === 0) return { saved, target, fraction: 1, left: 0, monthsLeft: null, perMonth: null, expected: null, status: 'done', otherCurrency }
  if (!g.target_date) return { saved, target, fraction, left, monthsLeft: null, perMonth: null, expected: null, status: 'no_date', otherCurrency }
  const daysLeft = daysBetween(today, g.target_date)
  if (daysLeft < 0) return { saved, target, fraction, left, monthsLeft: 0, perMonth: null, expected: target, status: 'overdue', otherCurrency }
  const monthsLeft = daysLeft / 30.44
  const perMonth = Math.ceil(left / Math.max(1, monthsLeft))
  const start = g.start_date ?? (g.created_at ? isoDate(new Date(g.created_at)) : today)
  const total = daysBetween(start, g.target_date)
  const elapsed = Math.min(Math.max(0, daysBetween(start, today)), Math.max(0, total))
  const expected = total > 0 ? Math.round((target * elapsed) / total) : target
  // Within 5% of the target of the straight-line path counts as on track.
  const slack = target * 0.05
  const status: GoalStatus = saved >= expected + slack ? 'ahead' : saved >= expected - slack ? 'on_track' : 'behind'
  return { saved, target, fraction, left, monthsLeft, perMonth, expected, status, otherCurrency }
}

export const STATUS_TEXT: Record<GoalStatus, string> = {
  done: 'Reached', ahead: 'Ahead of plan', on_track: 'On track', behind: 'Behind plan', overdue: 'Past the date', no_date: 'No date set',
}
