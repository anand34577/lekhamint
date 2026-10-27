import { describe, expect, it } from 'vitest'
import { compactMoney, formatMoney, parseMoney, toPlain } from './money'
import { budgetSpent, budgetWindow, evaluate } from './finance'
import { addMonths, plural, uuidv7 } from './util'
import type { Budget } from './api'

describe('money', () => {
  it('formats like the Android app', () => {
    expect(formatMoney(12345678, 'INR')).toBe('₹1,23,456.78')
    expect(formatMoney(-125050, 'INR')).toBe('−₹1,250.50')
    expect(formatMoney(1000, 'INR', { signed: true })).toBe('+₹10.00')
    expect(formatMoney(12345678, 'USD')).toBe('$123,456.78')
    expect(formatMoney(1500, 'JPY')).toBe('¥1,500')
    expect(formatMoney(1500, 'KWD')).toBe('KD 1.500')
    expect(compactMoney(12000000, 'INR')).toBe('₹1.2L')
  })
  it('parses exactly, without float drift', () => {
    expect(parseMoney('1,250.50', 'INR')).toBe(125050)
    expect(parseMoney('12,50', 'EUR')).toBe(1250)
    expect(parseMoney('1.234,56', 'EUR')).toBe(123456)
    expect(parseMoney('1 234,56', 'EUR')).toBe(123456)
    expect(parseMoney("1'234.50", 'CHF')).toBe(123450)
    expect(parseMoney('1.234.567', 'EUR')).toBe(123456700)
    expect(parseMoney('1,234,567.8', 'USD')).toBe(123456780)
    expect(parseMoney('1,250', 'INR')).toBe(125000)
    expect(parseMoney('1,50,000', 'INR')).toBe(15000000)
    expect(parseMoney('0.1', 'USD')).toBe(10)
    expect(parseMoney('19.999', 'USD')).toBe(2000)
    expect(parseMoney('1500', 'JPY')).toBe(1500)
    expect(parseMoney('abc', 'USD')).toBeNull()
    expect(toPlain(125050, 'INR')).toBe('1250.5')
    expect(toPlain(-100, 'USD')).toBe('-1')
  })
})

describe('calculator', () => {
  it('honours precedence', () => {
    expect(evaluate('450+800×2')).toBe(2050)
    expect(evaluate('1000-750')).toBe(250)
    expect(evaluate('10÷4')).toBe(2.5)
    expect(evaluate('5/0')).toBeNull()
    expect(evaluate('5+')).toBeNull()
  })
})

describe('budgets', () => {
  const b: Budget = { name: 'Food', amount: 1_000_000, currency: 'INR', period: 'monthly', start_date: '2026-01-15', end_date: null, category_ids: ['food'], account_ids: [], rollover: false, alert_percent: 80, archived: false }
  it('finds the period containing a date (same as Android)', () => {
    expect(budgetWindow(b, '2026-10-20')).toEqual({ start: '2026-10-15', end: '2026-11-14' })
    expect(budgetWindow(b, '2026-01-01')).toBeNull()
    expect(budgetWindow({ ...b, period: 'weekly', start_date: '2026-10-05' }, '2026-10-20')).toEqual({ start: '2026-10-19', end: '2026-10-25' })
  })
  it('sums spending in the budget categories and their children', () => {
    const summary = [{ currency: 'INR', income: 0, expense: 0, net: 0, income_by_category: [], expense_by_category: [
      { category_id: 'dining', parent_id: 'food', name: 'Dining', amount: 200, count: 1 },
      { category_id: 'fun', parent_id: null, name: 'Fun', amount: 50, count: 1 },
    ] }]
    expect(budgetSpent(b, summary, () => new Set(['food', 'dining']))).toBe(200)
  })
  it('anchors month ends', () => {
    expect(addMonths('2026-01-31', 1)).toBe('2026-02-28')
    expect(addMonths('2026-01-31', 2)).toBe('2026-03-31')
  })
})

describe('ids', () => {
  it('makes time-ordered v7 UUIDs', () => {
    const a = uuidv7(1_700_000_000_000), c = uuidv7(1_700_000_000_001)
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(a < c).toBe(true)
  })
})

import { daysBetween, nextDayOfMonth } from './util'
describe('card due dates', () => {
  it('finds the next due date, clamping short months', () => {
    expect(nextDayOfMonth('2026-10-25', 28)).toBe('2026-10-28')
    expect(nextDayOfMonth('2026-10-25', 25)).toBe('2026-10-25')
    expect(nextDayOfMonth('2026-10-26', 25)).toBe('2026-11-25')
    expect(nextDayOfMonth('2026-02-10', 31)).toBe('2026-02-28')
    expect(nextDayOfMonth('2026-12-30', 5)).toBe('2027-01-05')
    expect(daysBetween('2026-10-25', '2026-10-28')).toBe(3)
  })
})

describe('plural', () => {
  it('uses the singular only for one', () => {
    expect(plural(1, 'day')).toBe('1 day')
    expect(plural(0, 'day')).toBe('0 days')
    expect(plural(3, 'entry', 'entries')).toBe('3 entries')
  })
})
