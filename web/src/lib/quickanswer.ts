// Answers common questions without any AI model: the question is matched to
// one of the assistant's own look-ups, and the answer is written from its
// figures (the chat also draws them as a chart). Used when no model is set up,
// so the Assistant is useful from the first minute and nothing leaves the server.

import { runTool, type ToolRun, type Turn } from './ai'
import { addDays, isoDate, monthRange, plural } from './util'

export interface Intent { tool: string; args: Record<string, unknown>; about: string }

const has = (q: string, ...words: string[]) => words.some((w) => q.includes(w))

/** Picks a look-up for a question, or null when it needs a real model. */
export function pickIntent(question: string, today = isoDate()): Intent | null {
  const q = ` ${question.toLowerCase().replace(/[?!.,]/g, ' ')} `
  const [mFrom, mTo] = monthRange(today)
  const period = (): { from: string; to: string; label: string } => {
    if (has(q, 'last month', 'previous month')) { const [f, t] = monthRange(today, -1); return { from: f, to: t, label: 'last month' } }
    if (has(q, 'this year', 'year so far')) return { from: `${today.slice(0, 4)}-01-01`, to: today, label: 'this year' }
    if (has(q, 'last 3 months', 'past 3 months', 'three months')) return { from: monthRange(today, -2)[0], to: today, label: 'the last 3 months' }
    if (has(q, 'this week', 'past week', 'last 7 days')) return { from: addDays(today, -6), to: today, label: 'the last 7 days' }
    if (has(q, 'today')) return { from: today, to: today, label: 'today' }
    return { from: mFrom, to: mTo, label: 'this month' }
  }
  // "how much did I spend on swiggy", "spent at amazon"
  const on = / (?:on|at|for) ([a-z0-9][a-z0-9 &'-]{2,30}?)(?: this| last| in| during| so far| today|$| )/.exec(q)
  const generic = new Set(['food', 'groceries', 'travel', 'shopping', 'fuel', 'rent', 'bills', 'health', 'entertainment', 'dining', 'transport', 'education', 'utilities'])
  if (has(q, ' goal', 'saving for', 'savings target')) return { tool: 'goals', args: {}, about: 'your goals' }
  if (has(q, ' owe', 'lent', 'borrow', 'repay me', 'friend')) return { tool: 'people_debts', args: {}, about: 'money between you and other people' }
  if (has(q, ' emi', ' loan', 'mortgage')) return { tool: 'loans', args: {}, about: 'your loans' }
  if (has(q, ' fd ', 'deposit', ' rd ', 'matur')) return { tool: 'deposits', args: {}, about: 'your deposits' }
  if (has(q, 'budget')) return { tool: 'budgets', args: {}, about: 'your budgets' }
  if (has(q, ' due', 'upcoming', 'coming up', 'bills', ' pay next', 'next week')) return { tool: 'upcoming', args: { days: has(q, 'month') ? 30 : 14 }, about: 'what is coming up' }
  if (has(q, 'net worth', 'worth', 'wealth')) return { tool: has(q, 'history', 'over time', 'grow', 'trend') ? 'net_worth_history' : 'overview', args: { months: 12 }, about: 'your net worth' }
  if (has(q, 'trend', 'every month', 'per month', 'monthly', 'each month', 'over time')) return { tool: 'monthly_trend', args: { months: 12 }, about: 'month by month' }
  if (has(q, 'balance', 'account', 'how much money do i have', 'cash')) return { tool: 'accounts', args: {}, about: 'your account balances' }
  if (on && !generic.has(on[1].trim()) && has(q, 'spend', 'spent', 'paid', 'pay ')) {
    const p = period()
    return { tool: 'search_transactions', args: { text: on[1].trim(), from: p.from, to: p.to, type: 'expense' }, about: `payments to “${on[1].trim()}” ${p.label}` }
  }
  if (has(q, 'spend', 'spent', 'spending', 'expense', 'category', 'categories', 'where did my money', 'income', 'earn', 'save', 'saved')) {
    const p = period()
    return { tool: 'spending_summary', args: { from: p.from, to: p.to }, about: `income and spending ${p.label}` }
  }
  if (has(q, 'overview', 'how am i doing', 'summary', 'status')) return { tool: 'overview', args: {}, about: 'your money overview' }
  return null
}

type Money = { amount: string; minor_units: number }
const amt = (m?: Money) => m?.amount ?? '—'

/** Writes a short answer from a look-up's figures. */
export function describe(run: ToolRun, about: string): string {
  if (run.error) return `I could not look that up: ${run.error}`
  const r = run.result as Record<string, unknown> & unknown[]
  switch (run.name) {
    case 'overview': {
      const p = (r.positions as Record<string, Money>[])?.[0]
      const m = (r.this_month as { by_currency: Record<string, Money>[] })?.by_currency?.[0]
      if (!p) return 'There is nothing recorded yet.'
      return `**Net worth: ${amt(p.net_worth)}**\n\n- Cash and bank: ${amt(p.cash_and_bank)}\n- Investments and deposits: ${amt(p.investments_and_deposits)}\n- Owed on cards and loans: ${amt(p.owed_on_cards_and_loans)}` +
        (m ? `\n\nThis month so far you received **${amt(m.income)}** and spent **${amt(m.spending)}**.` : '')
    }
    case 'spending_summary': {
      const c = (r.current as Record<string, unknown>[])?.[0] as { income: Money; spending: Money; spending_by_category: { category: string; total: Money; share_of_spending: string }[] } | undefined
      const prev = (r.previous as { spending: Money }[])?.[0]
      if (!c) return `Nothing was recorded for ${about}.`
      const top = c.spending_by_category.slice(0, 5).map((x) => `| ${x.category} | ${x.total.amount} | ${x.share_of_spending} |`).join('\n')
      const diff = prev && prev.spending.minor_units ? Math.round(((c.spending.minor_units - prev.spending.minor_units) / prev.spending.minor_units) * 100) : null
      return `For ${about.replace('income and spending ', '')}: you spent **${c.spending.amount}** and received **${c.income.amount}**.` +
        (diff !== null ? ` That is ${Math.abs(diff)}% ${diff >= 0 ? 'more' : 'less'} spending than the period before (${prev!.spending.amount}).` : '') +
        (top ? `\n\n| Category | Spent | Share |\n|---|---:|---:|\n${top}` : '')
    }
    case 'search_transactions': {
      const t = (r.totals as { spent: Money }[])?.[0]
      return Number(r.matches) ? `I found **${r.matches}** ${Number(r.matches) === 1 ? 'payment' : 'payments'} for ${about}, **${amt(t?.spent)}** in total.` : `I found no ${about}. Try a shorter name, as it appears on the transactions.`
    }
    case 'upcoming': {
      const items = (r.items as { date: string; what: string; amount?: Money; overdue?: boolean }[]) ?? []
      if (!items.length) return 'Nothing is due in that time.'
      return `Coming up:\n\n${items.slice(0, 12).map((i) => `- ${i.date}${i.overdue ? ' (overdue)' : ''}: ${i.what}${i.amount ? `, ${i.amount.amount}` : ''}`).join('\n')}`
    }
    case 'people_debts': {
      const list = (r as unknown as { person: string; owes_user?: Money; user_owes?: Money; overdue?: boolean }[]) ?? []
      if (!list.length) return 'Nobody owes you anything, and you owe nobody. All settled.'
      return list.map((p) => `- ${p.person}: ${p.owes_user ? `owes you **${p.owes_user.amount}**` : `you owe **${p.user_owes?.amount}**`}${p.overdue ? ' (overdue)' : ''}`).join('\n')
    }
    case 'loans': {
      const list = (r as unknown as { name: string; outstanding: Money; emi: Money; next_due?: string; closed: boolean }[]).filter((x) => !x.closed)
      if (!list.length) return 'You have no open loans recorded.'
      return list.map((x) => `- **${x.name}**: ${x.outstanding.amount} still owed, EMI ${x.emi.amount}${x.next_due ? `, next on ${x.next_due}` : ''}`).join('\n')
    }
    case 'deposits': {
      const list = (r as unknown as { name: string; status: string; matures: string; at_maturity: Money }[]).filter((x) => x.status === 'active')
      if (!list.length) return 'You have no active deposits recorded.'
      return list.map((x) => `- **${x.name}**: about ${x.at_maturity.amount} on ${x.matures}`).join('\n')
    }
    case 'budgets': {
      const list = (r as unknown as { budget: string; spent: Money; limit: Money; left: Money; used: string; days_left: number }[])
      if (!list.length) return 'You have no active budgets. Create one under Budgets.'
      return list.map((b) => `- **${b.budget}**: ${b.used} used (${b.spent.amount} of ${b.limit.amount}), ${b.left.amount} left for ${plural(b.days_left, 'day')}`).join('\n')
    }
    case 'accounts': {
      const list = r as unknown as { name: string; balance: Money }[]
      return list.length ? list.map((a) => `- ${a.name}: **${a.balance.amount}**`).join('\n') : 'No accounts yet.'
    }
    case 'goals': {
      const list = r as unknown as { name: string; saved: Money; target: Money; percent: string; per_month?: Money; status: string }[]
      return list.length ? list.map((g) => `- **${g.name}**: ${g.saved.amount} of ${g.target.amount} (${g.percent}), ${g.status}${g.per_month ? `; put aside ${g.per_month.amount} a month` : ''}`).join('\n') : 'You have no goals yet. Create one under Goals.'
    }
    default: return 'Here is what I found.'
  }
}

/** Answers [question] with a look-up only; null when it needs a model. */
export async function quickAnswer(c: { ledgerId: string; base: string; share: boolean }, question: string): Promise<Turn | null> {
  const intent = pickIntent(question)
  if (!intent) return null
  const run = await runTool(c, intent.tool, intent.args)
  return { question, answer: describe(run, intent.about), runs: [run], mode: 'quick', at: Date.now() }
}
