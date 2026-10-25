import { describe, expect, test } from 'vitest'
import { affordability, allocateByWeight, findRecurring, goalMonthly, goalMonths, maxPrincipal, payoff, settle, splitBill } from './tools'
import { emi } from './loans'

describe('splitting money', () => {
  test('parts always add up', () => {
    expect(allocateByWeight(1000, [1, 1, 1])).toEqual([334, 333, 333])
    expect(allocateByWeight(100, [1, 2])).toEqual([33, 67])
    expect(allocateByWeight(5, [0, 0])).toEqual([0, 0])
    for (const t of [1, 7, 99999, 123457]) expect(allocateByWeight(t, [3, 5, 7, 11]).reduce((a, b) => a + b, 0)).toBe(t)
  })
  test('equal split with one payer', () => {
    const r = splitBill(100000, [{ name: 'Me', paid: 100000 }, { name: 'A', paid: 0 }, { name: 'B', paid: 0 }], 'equal', [])
    expect(r.owes).toEqual([33334, 33333, 33333])
    expect(r.transfers).toEqual([{ from: 1, to: 0, amount: 33333 }, { from: 2, to: 0, amount: 33333 }])
  })
  test('tip spread by share; validation explains', () => {
    const r = splitBill(90000, [{ name: 'A', paid: 99000 }, { name: 'B', paid: 0 }], 'shares', [2, 1], 9000)
    expect(r.owes).toEqual([66000, 33000])
    expect(splitBill(1000, [{ name: 'A', paid: 0 }, { name: 'B', paid: 0 }], 'percent', [50, 40]).error).toMatch(/90%/)
    expect(splitBill(1000, [{ name: 'A', paid: 0 }], 'exact', [900]).error).toMatch(/less than the bill/)
    expect(splitBill(1000, [{ name: 'A', paid: 500 }, { name: 'B', paid: 0 }], 'equal', []).error).toMatch(/Payments add up/)
  })
  test('items', () => {
    const r = splitBill(1000, [{ name: 'A', paid: 1000 }, { name: 'B', paid: 0 }], 'items', [], 0, [{ name: 'pizza', amount: 600, people: [0, 1] }, { name: 'beer', amount: 400, people: [1] }])
    expect(r.owes).toEqual([300, 700])
    expect(r.transfers).toEqual([{ from: 1, to: 0, amount: 700 }])
  })
  test('settle uses few transfers', () => {
    expect(settle([500, -200, -300])).toEqual([{ from: 2, to: 0, amount: 300 }, { from: 1, to: 0, amount: 200 }])
    expect(settle([0, 0])).toEqual([])
  })
})

describe('planning', () => {
  test('max principal is the inverse of the EMI', () => {
    const p = maxPrincipal(867823, '8.5', 240)
    expect(emi(p, '8.5', 240)).toBeLessThanOrEqual(867823)
    expect(emi(p + 100, '8.5', 240)).toBeGreaterThan(867823)
    expect(maxPrincipal(1000, null, 12)).toBe(12000)
  })
  test('affordability takes the stricter limit', () => {
    const a = affordability({ income: 10000000, expenses: 5000000, existingEmi: 1000000, buffer: 500000, ratio: 0.4, rate: '9', months: 240, target: 3000000000 })
    expect(a.maxEmiByRatio).toBe(3000000)
    expect(a.maxEmiBySurplus).toBe(3500000)
    expect(a.maxEmi).toBe(3000000)
    expect(a.target!.fits).toBe(false)
  })
  test('goals', () => {
    expect(goalMonthly(120000, 0, 12, 0)).toBe(10000)
    expect(goalMonthly(100, 200, 12, 5)).toBe(0)
    expect(goalMonths(120000, 0, 10000, 0)).toBe(12)
    expect(goalMonths(1, 0, 0, 0)).toBeNull()
  })
  test('avalanche never costs more interest than snowball', () => {
    const debts = [{ name: 'Card', balance: 5000000, rate: '36', minimum: 250000 }, { name: 'Car', balance: 30000000, rate: '9', minimum: 700000 }, { name: 'Small', balance: 1000000, rate: '12', minimum: 100000 }]
    const a = payoff(debts, 2000000, 'avalanche'), s = payoff(debts, 2000000, 'snowball')
    expect(a.never || s.never).toBe(false)
    expect(a.totalInterest).toBeLessThanOrEqual(s.totalInterest)
    expect(s.order[0]).toBe('Small')
    expect(payoff(debts, 100, 'avalanche').never).toBe(true)
    // The timeline starts at today's total, falls to zero, and has one entry per month.
    expect(a.balances[0]).toBe(36000000)
    expect(a.balances).toHaveLength(a.months + 1)
    expect(a.balances[a.balances.length - 1]).toBe(0)
  })
  test('recurring payees', () => {
    const rows = ['2026-05-03', '2026-06-03', '2026-07-04', '2026-08-03'].map((d) => ({ payee: 'Netflix', amount: 64900, local_date: d, currency: 'INR', type: 'expense' }))
    rows.push({ payee: 'Swiggy', amount: 45000, local_date: '2026-05-01', currency: 'INR', type: 'expense' }, { payee: 'Swiggy', amount: 90000, local_date: '2026-06-01', currency: 'INR', type: 'expense' })
    expect(findRecurring(rows)).toEqual([{ payee: 'Netflix', currency: 'INR', monthly: 64900, count: 4, last: '2026-08-03', months: 4 }])
  })
})
