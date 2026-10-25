import { describe, expect, it } from 'vitest'
import { describe as describeRun, pickIntent } from './quickanswer'

describe('quick answers', () => {
  const today = '2026-10-25'
  it('matches common questions to a look-up', () => {
    expect(pickIntent('How much did I spend this month?', today)).toMatchObject({ tool: 'spending_summary', args: { from: '2026-10-01', to: '2026-10-31' } })
    expect(pickIntent('spending last month', today)?.args).toEqual({ from: '2026-09-01', to: '2026-09-30' })
    expect(pickIntent('How much did I spend on swiggy this month?', today)).toMatchObject({ tool: 'search_transactions', args: { text: 'swiggy', type: 'expense' } })
    expect(pickIntent('how much did I spend on food', today)?.tool).toBe('spending_summary')
    expect(pickIntent('What is coming up?', today)?.tool).toBe('upcoming')
    expect(pickIntent('Who owes me money?', today)?.tool).toBe('people_debts')
    expect(pickIntent('my net worth over time', today)?.tool).toBe('net_worth_history')
    expect(pickIntent('What is my net worth?', today)?.tool).toBe('overview')
    expect(pickIntent('how are my goals', today)?.tool).toBe('goals')
    expect(pickIntent('Write me a poem', today)).toBeNull()
  })
  it('writes answers from the figures', () => {
    const m = (amount: string, minor_units: number) => ({ amount, minor_units })
    const text = describeRun({ name: 'spending_summary', args: {}, result: {
      current: [{ income: m('₹50,000', 5000000), spending: m('₹30,000', 3000000), spending_by_category: [{ category: 'Food', total: m('₹12,000', 1200000), share_of_spending: '40.0%' }] }],
      previous: [{ spending: m('₹25,000', 2500000) }],
    } }, 'income and spending this month')
    expect(text).toContain('you spent **₹30,000**')
    expect(text).toContain('20% more spending')
    expect(text).toContain('| Food | ₹12,000 | 40.0% |')
    expect(describeRun({ name: 'people_debts', args: {}, result: [] }, '')).toContain('All settled')
  })
})
