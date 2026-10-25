import { describe, expect, it } from 'vitest'
import { detectSubscriptions, payeeKey, type SubTx } from './subscriptions'
import { dailySpend, forecast } from './forecast'
import { addMonths, addDays } from './util'

const tx = (date: string, amount: number, payee: string, extra: Partial<SubTx> = {}): SubTx =>
  ({ id: date + payee, local_date: date, amount, payee, category_id: 'c', account_id: 'a', ...extra })

describe('subscriptions', () => {
  it('normalises payee names', () => {
    expect(payeeKey('NETFLIX.COM*1234')).toBe('netflix com')
    expect(payeeKey('Amazon Prime 2025')).toBe(payeeKey('AMAZON PRIME'))
  })
  it('finds monthly payments and price changes, and ignores one-offs and bills that vary', () => {
    const list: SubTx[] = []
    for (let i = 0; i < 8; i++) list.push(tx(addMonths('2026-01-05', i), i < 6 ? 49900 : 64900, 'Netflix'))
    for (let i = 0; i < 6; i++) list.push(tx(addMonths('2026-02-10', i), 100000 + i * 60000, 'Electricity board'))
    list.push(tx('2026-03-01', 120000, 'Shoe shop'), tx('2026-06-01', 99000, 'Shoe shop'))
    for (let i = 0; i < 4; i++) list.push(tx(addDays('2026-07-06', i * 7), 30000, 'Gym'))
    const subs = detectSubscriptions(list, '2026-09-01')
    const netflix = subs.find((s) => s.payee === 'Netflix')!
    expect(netflix.cadence).toBe('monthly')
    expect(netflix.priceChange).toEqual({ from: 49900, to: 64900 })
    expect(netflix.yearly).toBe(64900 * 12)
    expect(netflix.nextDate).toBe('2026-09-05')
    expect(subs.find((s) => s.payee === 'Gym')?.cadence).toBe('weekly')
    expect(subs.find((s) => s.payee === 'Gym')?.stale).toBe(true)
    expect(subs.some((s) => s.payee.startsWith('Electricity'))).toBe(false)
    expect(subs.some((s) => s.payee === 'Shoe shop')).toBe(false)
    // Already tracked as a recurring rule: not suggested again.
    expect(detectSubscriptions(list, '2026-09-01', new Set(['netflix'])).some((s) => s.payee === 'Netflix')).toBe(false)
  })
})

describe('forecast', () => {
  it('moves with known items and everyday spending, with a widening range', () => {
    const spend = dailySpend(new Map([['2026-09-01', 100000], ['2026-09-02', 0]]), '2026-09-01', 2)
    expect(spend.mean).toBe(50000)
    const f = forecast(1000000, [
      { date: '2026-10-01', amount: 5000000, title: 'Salary' },
      { date: '2026-09-25', amount: -2000000, title: 'Rent (overdue)' },
      { date: '2026-12-31', amount: -1, title: 'beyond' },
    ], spend, '2026-09-30', 30)
    expect(f.points).toHaveLength(31)
    expect(f.points[0].expected).toBe(1000000 - 2000000)
    expect(f.points[1].expected).toBe(1000000 - 2000000 + 5000000 - 50000)
    expect(f.points[30].high - f.points[30].low).toBeGreaterThan(f.points[5].high - f.points[5].low)
    expect(f.lowest.date).toBe('2026-09-30')
    expect(f.shortBy).toBe('2026-09-30')
    expect(f.knownIn).toBe(5000000)
    expect(f.knownOut).toBe(2000000)
  })
})
