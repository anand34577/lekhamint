import { describe, expect, it, vi } from 'vitest'
import { cleanNumber, parseTypedDate } from '../controls'

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
