// Categorisation rules (docs/02, "Rules"). Mirrors android/.../domain/Rules.kt;
// both run the same test cases so the apps always suggest the same thing.

import type { Rule } from './api'

export type RuleRec = Rule & { id: string; deleted?: boolean }

/** What a rule looks at. Amount is in minor units; 0 means "not entered yet". */
export interface RuleInput { payee: string | null; note: string | null; type: string; accountId: string | null; amount: number }

export interface RuleOutcome { categoryId: string | null; payee: string | null; tagIds: string[]; matched: RuleRec[] }

const norm = (s: string | null | undefined): string | null => {
  const t = (s ?? '').trim().toLowerCase()
  return t === '' ? null : t
}

/** Enabled, live rules in evaluation order: sort_order, then creation, then id. */
export function orderedRules(rules: RuleRec[]): RuleRec[] {
  return rules.filter((r) => r.enabled && !r.deleted && r.pattern.trim() !== '')
    .sort((a, b) => a.sort_order - b.sort_order || (a.created_at ?? 0) - (b.created_at ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
}

export function ruleMatches(r: RuleRec, input: RuleInput): boolean {
  if (!r.enabled || r.deleted) return false
  if (r.tx_type && r.tx_type !== input.type) return false
  if (r.account_id && r.account_id !== input.accountId) return false
  if (r.min_amount != null || r.max_amount != null) {
    if (input.amount <= 0) return false
    if (r.min_amount != null && input.amount < r.min_amount) return false
    if (r.max_amount != null && input.amount > r.max_amount) return false
  }
  const pattern = norm(r.pattern)
  if (!pattern) return false
  const texts = r.match_field === 'note' ? [input.note] : r.match_field === 'any' ? [input.payee, input.note] : [input.payee]
  return texts.some((t) => {
    const s = norm(t)
    if (!s) return false
    if (r.match_type === 'equals') return s === pattern
    if (r.match_type === 'starts_with') return s.startsWith(pattern)
    return s.includes(pattern)
  })
}

/** First matching rule with a category wins (likewise payee); tags from every match are combined. */
export function applyRules(rules: RuleRec[], input: RuleInput): RuleOutcome {
  const out: RuleOutcome = { categoryId: null, payee: null, tagIds: [], matched: [] }
  for (const r of orderedRules(rules)) {
    if (!ruleMatches(r, input)) continue
    out.matched.push(r)
    if (out.categoryId === null && r.set_category_id) out.categoryId = r.set_category_id
    if (out.payee === null && r.set_payee?.trim()) out.payee = r.set_payee.trim()
    for (const t of r.add_tag_ids) if (!out.tagIds.includes(t)) out.tagIds.push(t)
  }
  return out
}

/** "Payee contains “swiggy” → Food · Delivery, #work". */
export function describeRule(r: Rule, categoryPath: (id: string) => string, tagName: (id: string) => string | undefined): string {
  const field = r.match_field === 'note' ? 'Note' : r.match_field === 'any' ? 'Payee or note' : 'Payee'
  const how = r.match_type === 'equals' ? 'is' : r.match_type === 'starts_with' ? 'starts with' : 'contains'
  const actions = [
    ...(r.set_category_id ? [categoryPath(r.set_category_id)] : []),
    ...(r.set_payee ? [`payee “${r.set_payee}”`] : []),
    ...r.add_tag_ids.map(tagName).filter(Boolean).map((n) => `#${n}`),
  ]
  return `${field} ${how} “${r.pattern}” → ${actions.join(', ') || 'nothing'}`
}
