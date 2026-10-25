import { describe, expect, it } from 'vitest'
import { goalProgress } from './goals'
import type { Balance, Goal } from './api'

const goal = (over: Partial<Goal> = {}): Goal => ({
  name: 'Goa trip', target_amount: 6000000, currency: 'INR', target_date: '2027-03-31', start_date: '2026-03-31',
  account_ids: ['acc'], saved_amount: 0, color: null, icon: null, archived: false, achieved_date: null, note: null, created_at: 0, ...over,
})
const bal = (id: string, balance: number, over: Partial<Balance> = {}): Balance => ({
  account_id: id, name: id, type: 'savings', currency: 'INR', archived: false, include_in_net_worth: true, opening_balance: 0, balance, ...over,
})

describe('goalProgress', () => {
  it('adds linked accounts (their value when recorded) and money set aside elsewhere', () => {
    const p = goalProgress(goal({ saved_amount: 500000, account_ids: ['acc', 'fund', 'usd'] }),
      [bal('acc', 1000000), bal('fund', 800000, { value: 900000 }), bal('usd', 50000, { currency: 'USD' })], '2026-09-30')
    expect(p.saved).toBe(2400000)
    expect(p.otherCurrency).toBe(1)
    expect(p.left).toBe(3600000)
    expect(p.fraction).toBeCloseTo(0.4)
  })
  it('compares with a steady path and says what to save each month', () => {
    // Half way through the year, half the target is "on track".
    const half = goalProgress(goal(), [bal('acc', 3000000)], '2026-09-30')
    expect(half.status).toBe('on_track')
    expect(half.expected).toBeGreaterThan(2950000)
    expect(half.perMonth).toBe(Math.ceil(3000000 / (182 / 30.44)))
    expect(goalProgress(goal(), [bal('acc', 1000000)], '2026-09-30').status).toBe('behind')
    expect(goalProgress(goal(), [bal('acc', 5000000)], '2026-09-30').status).toBe('ahead')
  })
  it('handles reached, undated and late goals, and ignores overdrawn accounts', () => {
    expect(goalProgress(goal(), [bal('acc', 7000000)]).status).toBe('done')
    expect(goalProgress(goal({ target_date: null }), [bal('acc', 100)]).status).toBe('no_date')
    expect(goalProgress(goal(), [bal('acc', 100)], '2027-05-01').status).toBe('overdue')
    expect(goalProgress(goal(), [bal('acc', -500000)], '2026-09-30').saved).toBe(0)
  })
})
