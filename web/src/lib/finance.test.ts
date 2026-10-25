// Shared loan and deposit cases. android/.../LoansTest.kt asserts the same numbers.
import { describe, expect, test } from 'vitest'
import { emi, loanStatus, monthlyInterest, prepaymentOptions, rateError, schedule, splitPayment } from './loans'
import { estimate, fdValue, rdValue, tenureLabel } from './deposits'

describe('loans', () => {
  test('EMI matches the standard formula', () => {
    expect(emi(100000000, '8.5', 240)).toBe(867823) // ₹10,00,000 at 8.5% for 20 years = ₹8,678.23
    expect(emi(500000000, '8.5', 240)).toBe(4339116)
    expect(emi(1200000, null, 12)).toBe(100000) // interest-free
    expect(emi(100000, null, 3)).toBe(33334)
  })
  test('monthly interest is exact and rounds half up', () => {
    expect(monthlyInterest(100000000, '8.5')).toBe(708333)
    expect(monthlyInterest(120000, '10')).toBe(1000)
    expect(monthlyInterest(6, '10')).toBe(0) // 0.05 paise rounds down
    expect(monthlyInterest(60, '10')).toBe(1) // 0.5 paise rounds up
    expect(monthlyInterest(100000, null)).toBe(0)
  })
  test('schedule repays exactly the principal', () => {
    const s = schedule(100000000, '8.5', 867823, '2026-01-05')
    expect(s.rows.length).toBe(240)
    expect(s.rows.reduce((a, r) => a + r.principal, 0)).toBe(100000000)
    expect(s.totalInterest).toBe(108277663)
    expect(s.rows[239]).toEqual({ n: 240, date: '2045-12-05', opening: 861861, interest: 6105, principal: 861861, payment: 867966, closing: 0 })
    const free = schedule(100000, null, 33334, '2026-01-01')
    expect(free.rows.map((r) => r.payment)).toEqual([33334, 33334, 33332])
  })
  test('due dates on the 31st stay on the month end', () => {
    expect(schedule(300000, null, 100000, '2026-01-31').rows.map((r) => r.date)).toEqual(['2026-01-31', '2026-02-28', '2026-03-31'])
  })
  test('an EMI below the interest never repays', () => {
    const s = schedule(100000000, '12', 500000, '2026-01-01')
    expect(s.neverRepaid).toBe(true)
    expect(s.rows).toEqual([])
  })
  test('status counts only recorded payments', () => {
    const st = loanStatus({ principal: 100000000, annual_rate: '8.5', first_due_date: '2026-01-05', tenure_months: 240, emi_amount: null, paid_before: 0, closed: false },
      [{ type: 'transfer', amount: 159490, to_amount: 159490, part: 'principal', local_date: '2026-01-05', to_account_id: 'L', account_id: 'B' },
        { type: 'expense', amount: 708333, part: 'interest', local_date: '2026-01-05', account_id: 'B' }],
      -(100000000 - 159490), '2026-03-10', 'L')
    expect(st).toMatchObject({ emi: 867823, outstanding: 99840510, paidInstalments: 1, remainingInstalments: 239, nextDue: '2026-02-05',
      nextInterest: 707204, nextPrincipal: 160619, overdue: 2, principalPaid: 159490, interestPaid: 708333, endDate: '2045-12-05', projectedInterest: 107569330 })
  })
  test('a closed or repaid loan has nothing due', () => {
    const st = loanStatus({ principal: 100, annual_rate: '9', first_due_date: '2026-01-05', tenure_months: 1, emi_amount: null, paid_before: 1, closed: false }, [], 0, '2027-01-01', 'L')
    expect(st).toMatchObject({ outstanding: 0, nextDue: null, overdue: 0, remainingInstalments: 0, progress: 1 })
  })
  test('payments split into interest first', () => {
    expect(splitPayment(867823, 100000000, '8.5')).toEqual({ interest: 708333, principal: 159490 })
    expect(splitPayment(500000, 100000000, '8.5')).toEqual({ interest: 500000, principal: 0 }) // short payment covers interest only
    expect(splitPayment(1000000, 200000, '12')).toEqual({ interest: 2000, principal: 200000 }) // never more than owed
  })
  test('prepayment: keep EMI or keep tenure', () => {
    const po = prepaymentOptions(50000000, '9', 450000, 150, 5000000, '2026-01-05', 91)
    expect([po.before.rows.length, po.before.totalInterest, po.keepEmi.months, po.keepEmi.interestSaved, po.keepTenure.emi, po.keepTenure.interestSaved])
      .toEqual([240, 57908514, 186, 19418916, 500755, 27795166])
  })
  test('rate validation explains the problem', () => {
    expect(rateError('8.5')).toBeNull()
    expect(rateError('8.5%')).toMatch(/without the % sign/)
    expect(rateError('0')).toMatch(/more than 0/)
    expect(rateError('0', { allowZero: true })).toBeNull()
    expect(rateError('75')).toMatch(/above 60%/)
  })
})

describe('deposits', () => {
  test('cumulative FD', () => {
    expect(fdValue(10000000, '7', '2026-01-01', '2027-01-01', 'quarterly')).toBe(10718590) // ₹1,00,000 → ₹1,07,185.90
    expect(fdValue(10000000, '7.25', '2026-01-15', '2027-04-25', 'quarterly')).toBe(10961432) // 5 quarters + 10 days simple
    expect(fdValue(10000000, '6.5', '2026-01-01', '2026-04-11', 'simple')).toBe(10178082)
    expect(fdValue(5000000, '7', '2026-03-31', '2027-03-31', 'monthly')).toBe(5361450)
  })
  test('recurring deposit', () => {
    expect(rdValue(500000, '7', '2026-01-01', '2027-01-01', 'quarterly')).toEqual({ deposited: 6000000, value: 6231066, instalments: 12 })
    expect(rdValue(500000, '7', '2026-01-01', '2027-01-01', 'quarterly', '2026-06-15')).toEqual({ deposited: 3000000, value: 3051948, instalments: 6 })
  })
  test('payout FD pays interest during the term and the principal at maturity', () => {
    const e = estimate({ kind: 'fd', amount: 10000000, annual_rate: '7', start_date: '2026-01-01', maturity_date: '2026-11-15', compounding: 'quarterly', payout: 'quarterly', tax_percent: '10' }, '2026-06-01')
    expect(e.payouts).toEqual([{ date: '2026-04-01', amount: 175000 }, { date: '2026-07-01', amount: 175000 }, { date: '2026-10-01', amount: 175000 }, { date: '2026-11-15', amount: 86301 }])
    expect([e.maturity, e.interest, e.tax, e.days]).toEqual([10000000, 611301, 61130, 318])
  })
  test("the bank's maturity value wins", () => {
    const e = estimate({ kind: 'fd', amount: 10000000, annual_rate: '7', start_date: '2026-01-01', maturity_date: '2027-01-01', compounding: 'quarterly', payout: 'cumulative', maturity_amount: 10718000 }, '2026-01-01')
    expect([e.maturity, e.interest, e.fromBank]).toEqual([10718000, 718000, true])
  })
  test('tenure label', () => {
    expect(tenureLabel('2026-01-15', '2027-04-25')).toBe('1 year 3 months 10 days')
    expect(tenureLabel('2026-01-01', '2026-01-01')).toBe('0 days')
  })
})
