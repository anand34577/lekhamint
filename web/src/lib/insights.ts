// Plain-language insights for the dashboard. Pure: the caller passes this
// month so far and the same days of last month, so partial months compare fairly.

export interface Insight { tone: 'good' | 'warn' | 'info'; title: string; body: string; categoryId?: string | null }

export interface InsightInput {
  /** Spending by top-level category this month so far. */
  now: { id: string | null; amount: number }[]
  /** Spending by top-level category over the same days of last month. */
  prev: { id: string | null; amount: number }[]
  income: number
  expense: number
  prevExpense: number
  dayOfMonth: number
  daysInMonth: number
  name: (id: string | null) => string
}

const pct = (x: number) => Math.round(Math.abs(x) * 100)

export function insights(i: InsightInput): Insight[] {
  const out: Insight[] = []
  const scale = Math.max(i.expense, i.prevExpense, 1)
  const prev = new Map(i.prev.map((c) => [c.id, c.amount]))
  const ids = new Set([...i.now.map((c) => c.id), ...i.prev.map((c) => c.id)])
  const changes: (Insight & { weight: number })[] = []
  for (const id of ids) {
    const a = i.now.find((c) => c.id === id)?.amount ?? 0
    const b = prev.get(id) ?? 0
    const diff = a - b
    // Uncategorised has its own prompt. Ignore small bases and small moves (under 3% and 5% of spending):
    // a jump from almost nothing is noise, not a trend.
    if (id === null || b < scale * 0.03 || Math.abs(diff) < scale * 0.05) continue
    const ratio = diff / b
    if (Math.abs(ratio) < 0.25) continue
    const n = i.name(id)
    changes.push(ratio > 0
      ? { tone: 'warn', title: `${n} is up ${pct(ratio)}%`, body: 'Compared with the same days last month.', categoryId: id, weight: Math.abs(diff) }
      : { tone: 'good', title: `${n} is down ${pct(ratio)}%`, body: 'Compared with the same days last month.', categoryId: id, weight: Math.abs(diff) })
  }
  changes.sort((a, b) => b.weight - a.weight)
  out.push(...changes.filter((c) => c.tone === 'warn').slice(0, 2).map(({ weight: _, ...c }) => c))
  out.push(...changes.filter((c) => c.tone === 'good').slice(0, 1).map(({ weight: _, ...c }) => c))

  if (i.income > 0) {
    const kept = (i.income - i.expense) / i.income
    out.push(kept >= 0
      ? { tone: kept >= 0.2 ? 'good' : 'info', title: `You kept ${pct(kept)}% of your income`, body: kept >= 0.2 ? 'Saving a fifth or more is a healthy habit.' : 'Many people aim to keep at least 20%.' }
      : { tone: 'warn', title: 'Spending is above income this month', body: `You have spent ${pct(-kept)}% more than you earned so far.` })
  }
  // Pace needs a real base: last month's spending so far must be at least a fifth of this month's.
  if (i.dayOfMonth >= 5 && i.prevExpense >= i.expense * 0.2 && i.expense > 0) {
    const ahead = i.expense / i.prevExpense - 1
    if (Math.abs(ahead) >= 0.1) {
      out.push({ tone: ahead > 0 ? 'warn' : 'good', title: `Spending is ${ahead > 0 ? 'ahead of' : 'behind'} last month's pace`,
        body: `${pct(ahead)}% ${ahead > 0 ? 'more' : 'less'} than by this day last month.` })
    }
  }
  const order = { warn: 0, good: 1, info: 2 }
  return out.sort((a, b) => order[a.tone] - order[b.tone]).slice(0, 4)
}
