import { describe, expect, it } from 'vitest'
import { dueDates, firstIndexOnOrAfter, initialNextDate, nextAfter, occurrence, occurrenceId } from './recurrence'
import { bestMatch, categoryFromText, parseQuick, withoutWords } from './quickadd'
import { insights } from './insights'

// Same cases as android/.../CoreTest.kt (Recurrence).
describe('recurrence', () => {
  const rule = (start: string, frequency: string, every = 1, extra = {}) =>
    ({ start_date: start, frequency, every, next_date: null, paused: false, ...extra })

  it('clamps month ends and returns to the 31st', () => {
    expect([1, 2, 3].map((i) => occurrence('2026-01-31', 'monthly', 1, i))).toEqual(['2026-02-28', '2026-03-31', '2026-04-30'])
  })
  it('respects every, limits and next_date', () => {
    expect(dueDates(rule('2026-01-01', 'weekly', 2), '2026-02-28').map((d) => Number(d.slice(8)))).toEqual([1, 15, 29, 12, 26])
    expect(dueDates(rule('2026-01-01', 'monthly', 1, { max_occurrences: 3 }), '2027-01-01')).toHaveLength(3)
    expect(dueDates(rule('2026-01-01', 'monthly', 1, { end_date: '2026-02-15' }), '2027-01-01')).toHaveLength(2)
    expect(dueDates(rule('2026-01-01', 'monthly', 1, { next_date: '2026-03-01' }), '2026-04-30')).toEqual(['2026-03-01', '2026-04-01'])
  })
  it('finds the next occurrence', () => {
    expect(nextAfter(rule('2026-01-10', 'monthly'), '2026-01-10')).toBe('2026-02-10')
    expect(nextAfter(rule('2026-01-10', 'monthly'), '2026-01-20')).toBe('2026-02-10')
    expect(nextAfter(rule('2026-01-10', 'monthly', 1, { max_occurrences: 1 }), '2026-01-10')).toBeNull()
    expect(nextAfter(rule('2024-02-29', 'yearly'), '2026-03-01')).toBe('2027-02-28')
  })
  it('starts a past-dated rule from today, not from the start (no backfill)', () => {
    expect(initialNextDate('2026-01-05', 'monthly', 1, '2026-10-24')).toBe('2026-11-05')
    expect(initialNextDate('2026-01-24', 'monthly', 1, '2026-10-24')).toBe('2026-10-24')
    expect(initialNextDate('2026-12-01', 'monthly', 1, '2026-10-24')).toBe('2026-12-01')
    expect(firstIndexOnOrAfter('2020-01-01', 'daily', 1, '2026-10-24')).toBe(2488)
  })
  it('derives occurrence IDs like Android (Ids.recurringOccurrence)', async () => {
    expect(await occurrenceId('0192f0c4-0000-7000-8000-000000000001', '2026-01-31')).toBe('1e0966bb-cefe-5d1f-ab05-ff19e702f91b')
  })
})

describe('quick add', () => {
  const today = '2026-10-24' // a Saturday
  it('reads amount, payee, date, tags and account', () => {
    expect(parseQuick('450 swiggy dinner yesterday #work @hdfc', today)).toEqual({
      amount: 450, type: 'expense', date: '2026-10-23', tags: ['work'], account: 'hdfc', text: 'swiggy dinner',
    })
  })
  it('handles sums, symbols, income and filler words', () => {
    expect(parseQuick('spent ₹1,250.50 on groceries', today)).toMatchObject({ amount: 1250.5, text: 'groceries' })
    expect(parseQuick('uber 230+40', today)).toMatchObject({ amount: 270, text: 'uber' })
    expect(parseQuick('salary 85000 received', today)).toMatchObject({ amount: 85000, type: 'income', text: 'salary' })
    expect(parseQuick('+500 from mom', today)).toMatchObject({ amount: 500, type: 'income', text: 'mom' })
  })
  it('understands dates', () => {
    expect(parseQuick('200 coffee 12 oct', today).date).toBe('2026-10-12')
    expect(parseQuick('200 coffee dec 30', today).date).toBe('2025-12-30')
    expect(parseQuick('200 coffee 3 days ago', today).date).toBe('2026-10-21')
    expect(parseQuick('200 coffee monday', today).date).toBe('2026-10-19')
    expect(parseQuick('200 coffee 5/10', today).date).toBe('2026-10-05')
    expect(parseQuick('200 coffee 2026-09-01', today).date).toBe('2026-09-01')
  })
  it('keeps numbers in the description after the amount', () => {
    expect(parseQuick('99 netflix 4k plan', today)).toMatchObject({ amount: 99, text: 'netflix 4k plan' })
    expect(parseQuick('coffee', today).amount).toBeNull()
  })
  it('matches names', () => {
    const accts = [{ name: 'HDFC Savings' }, { name: 'Cash' }, { name: 'Amex card' }]
    expect(bestMatch(accts, 'hdfc')?.name).toBe('HDFC Savings')
    expect(bestMatch(accts, 'card')?.name).toBe('Amex card')
    const cats = [{ name: 'Food' }, { name: 'Groceries' }, { name: 'Fuel' }]
    expect(categoryFromText(cats, 'weekly groceries big bazaar')?.name).toBe('Groceries')
    expect(categoryFromText(cats, 'seafood')).toBeUndefined()
    const defaults = [{ name: 'Dining out' }, { name: 'Taxi & ride-hailing' }, { name: 'Fuel' }]
    expect(categoryFromText(defaults, 'lunch with team')?.name).toBe('Dining out')
    expect(categoryFromText(defaults, 'Uber to airport')?.name).toBe('Taxi & ride-hailing')
    expect(categoryFromText([{ name: 'Eating out' }], 'lunch')).toBeUndefined()
    expect(withoutWords('groceries big bazaar', 'Groceries')).toBe('big bazaar')
    expect(withoutWords('rent', 'Rent')).toBe('')
  })
})

describe('insights', () => {
  const cats = new Map([['f', 'Food'], ['r', 'Rent'], ['t', 'Travel']])
  it('compares this month with last month by category', () => {
    const list = insights({
      now: [{ id: 'f', amount: 1500000 }, { id: 'r', amount: 2000000 }, { id: 't', amount: 50000 }],
      prev: [{ id: 'f', amount: 1000000 }, { id: 'r', amount: 2000000 }, { id: 't', amount: 600000 }],
      income: 9000000, expense: 3550000, prevExpense: 3600000, dayOfMonth: 24, daysInMonth: 31,
      name: (id) => cats.get(id ?? '') ?? 'Uncategorised',
    })
    expect(list[0]).toMatchObject({ tone: 'warn', title: 'Food is up 50%' })
    expect(list.some((i) => i.title === 'Travel is down 92%')).toBe(true)
    expect(list.some((i) => i.title.startsWith('You kept 61%'))).toBe(true)
  })
  it('ignores uncategorised and changes from a tiny base', () => {
    const list = insights({
      now: [{ id: null, amount: 400000 }, { id: 'f', amount: 300000 }], prev: [{ id: null, amount: 1000 }, { id: 'f', amount: 2000 }],
      income: 0, expense: 700000, prevExpense: 3000, dayOfMonth: 24, daysInMonth: 31, name: (id) => cats.get(id ?? '') ?? 'Uncategorised',
    })
    expect(list).toEqual([])
  })
})
