// Cash-flow forecast: where the money in cash, bank and wallet accounts is
// heading over the next weeks. Known items (bills, income, EMIs) move it on
// their dates; everyday spending is spread out at the recent daily average,
// with a range that widens with time, so it is never a single false-precision line.

import { addDays, daysBetween } from './util'

export interface FlowEvent { date: string; amount: number; title: string }

export interface DailySpend {
  /** Average everyday spending per day (minor units). */
  mean: number
  /** Day-to-day standard deviation. */
  sd: number
  /** Days of history the average comes from. */
  days: number
}

export interface ForecastPoint { date: string; expected: number; low: number; high: number }

export interface Forecast {
  points: ForecastPoint[]
  /** Lowest expected balance and its date. */
  lowest: ForecastPoint
  /** First date the cautious (low) line drops below zero, if it does. */
  shortBy: string | null
  /** Total of known items in the period, in and out. */
  knownIn: number
  knownOut: number
  everyday: number
}

/** Average and spread of everyday spending from daily totals over [days] days (days with nothing count as 0). */
export function dailySpend(totals: Map<string, number>, from: string, days: number): DailySpend {
  const vals: number[] = []
  for (let i = 0; i < days; i++) vals.push(totals.get(addDays(from, i)) ?? 0)
  const mean = vals.reduce((a, b) => a + b, 0) / Math.max(1, days)
  const sd = Math.sqrt(vals.reduce((a, v) => a + (v - mean) ** 2, 0) / Math.max(1, days - 1))
  return { mean, sd, days }
}

/**
 * Day-by-day forecast from [today] for [horizon] days. [events] in the past
 * (overdue bills) are counted on day 0. The range is an 80% band
 * (±1.28 standard deviations of the summed everyday spending).
 */
export function forecast(start: number, events: FlowEvent[], spend: DailySpend, today: string, horizon: number): Forecast {
  const byDay = new Map<number, number>()
  let knownIn = 0, knownOut = 0
  for (const e of events) {
    const d = Math.max(0, daysBetween(today, e.date))
    if (d > horizon) continue
    byDay.set(d, (byDay.get(d) ?? 0) + e.amount)
    if (e.amount > 0) knownIn += e.amount; else knownOut -= e.amount
  }
  const points: ForecastPoint[] = []
  let known = 0
  for (let t = 0; t <= horizon; t++) {
    known += byDay.get(t) ?? 0
    const expected = Math.round(start + known - spend.mean * t)
    const band = 1.28 * spend.sd * Math.sqrt(t)
    points.push({ date: addDays(today, t), expected, low: Math.round(expected - band), high: Math.round(expected + band) })
  }
  const lowest = points.reduce((m, p) => (p.expected < m.expected ? p : m), points[0])
  const short = points.find((p) => p.low < 0)
  return { points, lowest, shortBy: short ? short.date : null, knownIn, knownOut, everyday: Math.round(spend.mean * horizon) }
}
