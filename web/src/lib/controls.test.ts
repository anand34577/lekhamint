import { describe, expect, it, vi } from 'vitest'
import { cleanNumber, formatDate, parseTypedDate } from '../controls'

describe('cleanNumber', () => {
  it('keeps only digits for whole numbers', () => {
    expect(cleanNumber('12a3 .4', 'int')).toBe('1234')
  })
  it('allows one decimal point and a leading minus for decimals', () => {
    expect(cleanNumber('1,250.50.7', 'decimal')).toBe('1,250.507')
    expect(cleanNumber('-12abc', 'decimal')).toBe('-12')
    expect(cleanNumber('12-3', 'decimal')).toBe('123')
  })
  it('keeps arithmetic for amount expressions', () => {
    expect(cleanNumber('450+800*2 x', 'expr')).toBe('450+800*2 ')
    expect(cleanNumber('12÷4 − 1', 'expr')).toBe('12÷4 − 1')
  })
})

describe('parseTypedDate', () => {
  it('reads common formats', () => {
    expect(parseTypedDate('25/10/2026')).toBe('2026-10-25')
    expect(parseTypedDate('25-10-26')).toBe('2026-10-25')
    expect(parseTypedDate('2026-10-25')).toBe('2026-10-25')
    expect(parseTypedDate('25 Oct 2026')).toBe('2026-10-25')
    expect(parseTypedDate('5 october 2026')).toBe('2026-10-05')
  })
  it('reads numbers in the reader’s order, and the only possible order when one side is over 12', () => {
    expect(parseTypedDate('10/11/2026', 'dmy')).toBe('2026-11-10')
    expect(parseTypedDate('10/11/2026', 'mdy')).toBe('2026-10-11')
    expect(parseTypedDate('10/25/2026', 'dmy')).toBe('2026-10-25')
    expect(parseTypedDate('25/10/2026', 'mdy')).toBe('2026-10-25')
  })
  it('reads the month first or second when it is a word', () => {
    expect(parseTypedDate('Oct 25, 2026')).toBe('2026-10-25')
    expect(parseTypedDate('october 5 2026')).toBe('2026-10-05')
    expect(parseTypedDate('25th Oct 2026')).toBe('2026-10-25')
    expect(parseTypedDate('Sept. 3, 2026')).toBe('2026-09-03')
  })
  it('shows dates in the reader’s order and reads them back', () => {
    expect(formatDate('2026-10-25', 'dmy')).toBe('25 Oct 2026')
    expect(formatDate('2026-10-25', 'mdy')).toBe('Oct 25, 2026')
    for (const o of ['dmy', 'mdy'] as const) expect(parseTypedDate(formatDate('2026-03-04', o), o)).toBe('2026-03-04')
  })
  it('rejects impossible dates and nonsense', () => {
    expect(parseTypedDate('31/02/2026')).toBeNull()
    expect(parseTypedDate('32/1/2026')).toBeNull()
    expect(parseTypedDate('hello')).toBeNull()
    expect(parseTypedDate('')).toBeNull()
  })
  it('understands today, yesterday and tomorrow', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date(2026, 9, 25, 12))
    expect(parseTypedDate('today')).toBe('2026-10-25')
    expect(parseTypedDate('yesterday')).toBe('2026-10-24')
    expect(parseTypedDate('tomorrow')).toBe('2026-10-26')
    vi.useRealTimers()
  })
})
