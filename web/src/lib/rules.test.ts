import { describe, expect, it } from 'vitest'
import { applyRules, type RuleInput, type RuleRec } from './rules'

const base = { name: null, match_field: 'payee', match_type: 'contains', tx_type: null, account_id: null, min_amount: null, max_amount: null,
  set_category_id: null, set_payee: null, add_tag_ids: [], enabled: true, created_at: 0 } as const
const rule = (r: Partial<RuleRec> & { id: string; pattern: string; sort_order: number }): RuleRec => ({ ...base, add_tag_ids: [], ...r }) as RuleRec

// Same rules and cases as android/app/src/test/.../FeaturesTest.kt (RulesTest).
const rules = [
  rule({ id: 'r1', pattern: 'swiggy', set_category_id: 'food', add_tag_ids: ['t1'], sort_order: 0 }),
  rule({ id: 'r2', pattern: 'SWIGGY INSTAMART', match_type: 'starts_with', set_category_id: 'groceries', set_payee: 'Instamart', sort_order: -1 }),
  rule({ id: 'r3', pattern: 'rent', match_type: 'equals', match_field: 'any', tx_type: 'expense', set_category_id: 'rent', sort_order: 1 }),
  rule({ id: 'r4', pattern: 'uber', min_amount: 10_000, max_amount: 50_000, set_category_id: 'taxi', sort_order: 2 }),
  rule({ id: 'r5', pattern: 'salary', tx_type: 'income', set_category_id: 'salary', enabled: false, sort_order: 3 }),
  rule({ id: 'r6', pattern: 'amzn', account_id: 'card', set_payee: 'Amazon', add_tag_ids: ['t2'], sort_order: 4 }),
]
const run = (payee: string | null, o: Partial<RuleInput> = {}) =>
  applyRules(rules, { payee, note: null, type: 'expense', accountId: 'bank', amount: 0, ...o })

describe('rules', () => {
  it('first category wins, tags combine', () => {
    expect(run('Swiggy order 123')).toMatchObject({ categoryId: 'food', payee: null, tagIds: ['t1'] })
    const b = run('  swiggy instamart BLR ')
    expect(b).toMatchObject({ categoryId: 'groceries', payee: 'Instamart', tagIds: ['t1'] })
    expect(b.matched.map((r) => r.id)).toEqual(['r2', 'r1'])
  })
  it('filters by field, type, account and amount', () => {
    expect(run(null, { note: 'Rent' }).categoryId).toBe('rent')
    expect(run('rent', { type: 'income' }).matched).toHaveLength(0)
    expect(run('Uber trip').matched).toHaveLength(0)
    expect(run('Uber trip', { amount: 25_000 }).categoryId).toBe('taxi')
    expect(run('Uber trip', { amount: 60_000 }).matched).toHaveLength(0)
    expect(run('Salary Oct', { type: 'income' }).matched).toHaveLength(0)
    expect(run('AMZN Mktp', { accountId: 'card' })).toMatchObject({ categoryId: null, payee: 'Amazon', tagIds: ['t2'] })
    expect(run('AMZN Mktp', { accountId: 'bank' }).matched).toHaveLength(0)
  })
})
