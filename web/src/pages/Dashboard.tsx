import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { isLiability, useAccounts, useBalances, useBudgetProgress, useBudgets, useCanWrite, useCategoryIndex, useLedger, useRules, useSplitIndex, useSummary } from '../lib/data'
import { dueDates } from '../lib/recurrence'
import { insights } from '../lib/insights'
import { addDays, dayLabel, isoDate, monthRange, parseDate } from '../lib/util'
import { Bar, Card, Dot, Empty, Money, PaceChart, Skeleton } from '../components'
import { Icon } from '../icons'
import { BudgetCard } from './Budgets'
import { QuickAddBar } from './QuickAdd'
import type { PageProps } from '../App'

function greeting() {
  const h = new Date().getHours()
  return h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

export function Dashboard({ go, openAdd }: PageProps) {
  const l = useLedger()
  const base = l.base_currency
  const today = isoDate()
  const [from, to] = monthRange(today)
  const day = parseDate(today).getDate()
  const daysInMonth = parseDate(to).getDate()
  const [prevFrom, prevTo] = monthRange(today, -1)
  // The same days of last month, so a partial month compares fairly.
  const prevSame = addDays(prevFrom, Math.min(day, parseDate(prevTo).getDate()) - 1)
  const balances = useBalances()
  const accounts = useAccounts()
  const summary = useSummary(from, to)
  const prevSummary = useSummary(prevFrom, prevSame)
  const cats = useCategoryIndex()
  const splits = useSplitIndex()
  const budgets = useBudgets()
  const progress = useBudgetProgress()
  const rules = useRules()
  const can = useCanWrite()
  const recent = useQuery({ queryKey: [l.id, 'transactions', 'recent'], queryFn: () => api.transactions(l.id, { limit: '8' }) })
  const uncategorised = useQuery({ queryKey: [l.id, 'transactions', 'uncategorised'], queryFn: () => api.transactions(l.id, { category_id: 'none', limit: '100' }) })
  const daily = useQuery({ queryKey: [l.id, 'trend', 'daily', prevFrom, to], queryFn: () => api.trend(l.id, prevFrom, to, 'day') })
  const multi = (summary.data?.length ?? 0) > 1
  const converted = useQuery({ queryKey: [l.id, 'summary-converted', from, to, base], queryFn: () => api.summaryConverted(l.id, from, to, base), enabled: multi })

  // Spendable cash, investments, debt and net worth are different things (docs/02).
  const pos = useMemo(() => {
    const out = { available: 0, investments: 0, debt: 0, net: 0, other: new Map<string, number>() }
    for (const b of balances.data ?? []) {
      if (b.archived || !b.include_in_net_worth) continue
      if (b.currency !== base) { out.other.set(b.currency, (out.other.get(b.currency) ?? 0) + b.balance); continue }
      out.net += b.balance
      if (b.type === 'investment') out.investments += b.balance
      else if (isLiability(b.type)) out.debt += Math.max(0, -b.balance)
      else out.available += b.balance
    }
    return out
  }, [balances.data, base])

  const month = summary.data?.find((s) => s.currency === base)
  const prev = prevSummary.data?.find((s) => s.currency === base)
  const roots = (list: { category_id: string | null; amount: number }[] = []) => {
    const m = new Map<string | null, number>()
    for (const c of list) m.set(cats.rootOf(c.category_id), (m.get(cats.rootOf(c.category_id)) ?? 0) + c.amount)
    return [...m.entries()].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1])
  }
  const top = useMemo(() => roots(month?.expense_by_category).slice(0, 5), [month, cats]) // eslint-disable-line
  const tips = useMemo(() => month ? insights({
    now: roots(month.expense_by_category).map(([id, amount]) => ({ id, amount })),
    prev: roots(prev?.expense_by_category).map(([id, amount]) => ({ id, amount })),
    income: month.income, expense: month.expense, prevExpense: prev?.expense ?? 0, dayOfMonth: day, daysInMonth, name: (id) => cats.name(id),
  }) : [], [month, prev, cats]) // eslint-disable-line

  // Cumulative spending by day, this month against last month.
  const pace = useMemo(() => {
    const cum = (start: string, n: number) => {
      const out: number[] = []
      let acc = 0
      for (let i = 0; i < n; i++) {
        const d = addDays(start, i)
        acc += daily.data?.find((p) => p.period === d && p.currency === base)?.expense ?? 0
        out.push(acc)
      }
      return out
    }
    return { now: cum(from, day), prev: cum(prevFrom, parseDate(prevTo).getDate()) }
  }, [daily.data, base, from, prevFrom, prevTo, day])

  const upcoming = useMemo(() => (rules.data ?? []).filter((r) => !r.deleted && !r.paused)
    .flatMap((r) => dueDates(r, addDays(today, 10), 2).map((d) => ({ r, d })))
    .sort((a, b) => a.d.localeCompare(b.d)).slice(0, 5), [rules.data, today])
  const accCur = (id: string) => accounts.data?.find((a) => a.id === id)?.currency ?? base
  const accName = (id: string | null) => accounts.data?.find((a) => a.id === id)?.name ?? '?'
  const activeBudgets = (budgets.data ?? []).filter((b) => !b.deleted && !b.archived)
  const nUncat = uncategorised.data?.transactions.length ?? 0
  const noAccounts = accounts.data && accounts.data.filter((a) => !a.deleted).length === 0

  return (
    <>
      <div className="page-head hero">
        <div className="grow">
          <p className="eyebrow">{parseDate(today).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })}</p>
          <h1>{greeting()}</h1>
        </div>
      </div>
      {noAccounts && (
        <Card className="mb onboarding">
          <h2><Icon name="sparkles" /> Welcome to LekhaMint</h2>
          <p className="muted">Three steps to a clear picture of your money:</p>
          <ol className="steps">
            <li><button className="link" onClick={() => go('accounts')}>Add your accounts</button>: cash, bank, cards and wallets, with today's balance.</li>
            <li>Log a few expenses below, or <button className="link" onClick={() => go('import')}>import a bank statement</button>.</li>
            <li><button className="link" onClick={() => go('budgets')}>Set a monthly budget</button> to see what you can still spend per day.</li>
          </ol>
        </Card>
      )}
      {can && !noAccounts && <QuickAddBar onOpenForm={(r) => openAdd(r)} />}

      <div className="grid stats">
        <Card className="stat-card hero-card">
          <div className="stat-label">Available to spend</div>
          <div className="stat-big">{balances.isPending ? <Skeleton rows={1} height={34} /> : <Money value={pos.available} currency={base} />}</div>
          <div className="muted small">Cash, bank and wallet accounts</div>
        </Card>
        <Card className="stat-card">
          <div className="stat-label">This month</div>
          <div className="split-stat">
            <div><div className="muted small">In</div><div className="stat-value"><Money value={month?.income ?? 0} currency={base} /></div></div>
            <div><div className="muted small">Out</div><div className="stat-value"><Money value={month?.expense ?? 0} currency={base} /></div></div>
          </div>
          {multi && converted.data && (
            <div className="muted small">All currencies in {base}: <Money value={converted.data.converted.expense} currency={base} /> out
              {converted.data.converted.missing_rates.length > 0 && <> · <button className="link" onClick={() => go('settings', { section: 'rates' })}>add a rate for {converted.data.converted.missing_rates.join(', ')}</button></>}</div>
          )}
        </Card>
        <Card className="stat-card">
          <div className="stat-label">Net worth</div>
          <div className="stat-big"><Money value={pos.net} currency={base} /></div>
          <div className="muted small">
            {pos.investments !== 0 && <>Investments <Money value={pos.investments} currency={base} /> · </>}Owed on cards & loans <Money value={pos.debt} currency={base} />
            {[...pos.other.entries()].map(([c, v]) => <span key={c}> · <Money value={v} currency={c} /></span>)}
          </div>
        </Card>
      </div>

      {(tips.length > 0 || nUncat > 0) && (
        <div className="insights">
          {nUncat > 0 && can && (
            <button className="insight info" onClick={() => go('transactions', { category: 'none', all: '1' })}>
              <Icon name="inbox" /><div><strong>{nUncat >= 100 ? '100+' : nUncat} {nUncat === 1 ? 'transaction needs' : 'transactions need'} a category</strong><span>Sort them in a minute for better reports.</span></div><Icon name="right" />
            </button>
          )}
          {tips.map((t) => (
            <button key={t.title} className={`insight ${t.tone}`} onClick={() => t.categoryId !== undefined ? go('transactions', t.categoryId ? { category: t.categoryId } : {}) : go('reports')}>
              <Icon name={t.tone === 'warn' ? 'alert' : t.tone === 'good' ? 'check' : 'sparkles'} /><div><strong>{t.title}</strong><span>{t.body}</span></div><Icon name="right" />
            </button>
          ))}
        </div>
      )}

      <div className="grid-2">
        <div className="stack">
          <Card title="Spending pace" action={<span className="legend small"><span><i className="sw now" />This month</span><span><i className="sw prev" />Last month</span></span>}>
            {daily.isPending ? <Skeleton rows={3} /> : <>
              <PaceChart now={pace.now} prev={pace.prev} days={daysInMonth} today={day - 1} label="Cumulative spending this month compared with last month" />
              <p className="muted small">
                <Money value={pace.now[pace.now.length - 1] ?? 0} currency={base} /> so far; by this day last month it was <Money value={pace.prev[day - 1] ?? pace.prev[pace.prev.length - 1] ?? 0} currency={base} />.
              </p>
            </>}
          </Card>
          <Card title="Recent activity" action={<button className="link" onClick={() => go('transactions')}>See all</button>}>
            {recent.isPending ? <Skeleton rows={5} /> : recent.data?.transactions.length === 0 ? (
              <Empty title="Nothing recorded yet" body="Add your first expense; it takes a few seconds." action={can && !noAccounts ? <button className="primary" onClick={() => openAdd()}>Add an expense</button> : undefined} />
            ) : (
              <ul className="list">
                {recent.data?.transactions.map((r) => {
                  const t = r.data
                  const transfer = t.type === 'transfer'
                  const split = !transfer && !t.category_id ? splits.get(r.id) : undefined
                  const label = transfer ? 'Transfer' : split ? `Split · ${split.map((s) => cats.name(s.category_id)).join(', ')}` : cats.name(t.category_id)
                  const title = transfer ? `${accName(t.account_id)} → ${accName(t.to_account_id)}` : t.payee || label
                  return (
                    <li key={r.id}>
                      <Dot color={transfer ? 'var(--transfer)' : split ? '#7C3AED' : cats.color(t.category_id)} />
                      <div className="grow"><div className="ellipsis">{title}</div><div className="sub ellipsis">{dayLabel(t.local_date)} · {label}</div></div>
                      <Money value={t.type === 'income' ? t.amount : -t.amount} currency={accCur(t.account_id)} signed={!transfer} />
                    </li>
                  )
                })}
              </ul>
            )}
          </Card>
        </div>
        <div className="stack">
          {activeBudgets.slice(0, 3).map((b) => <BudgetCard key={b.id} b={b} p={progress.data?.find((p) => p.budget_id === b.id)} loading={progress.isPending} compact onOpen={() => go('budgets')} />)}
          {activeBudgets.length === 0 && !budgets.isPending && can && (
            <Card title="Budgets"><p className="muted">A monthly budget shows how much you can still spend per day.</p><button onClick={() => go('budgets')}>Create a budget</button></Card>
          )}
          {upcoming.length > 0 && (
            <Card title="Coming up" action={<button className="link" onClick={() => go('recurring')}>All bills</button>}>
              <ul className="list">
                {upcoming.map(({ r, d }) => (
                  <li key={r.id + d}><Dot color={r.type === 'transfer' ? 'var(--transfer)' : cats.color(r.category_id)} />
                    <div className="grow"><div>{r.name || r.payee || cats.name(r.category_id)}</div><div className={`sub ${d < today ? 'error' : ''}`}>{d < today ? 'Overdue · ' : ''}{dayLabel(d)}{r.mode === 'auto' ? ' · automatic' : ''}</div></div>
                    <Money value={r.type === 'income' ? r.amount : -r.amount} currency={accCur(r.account_id)} signed={r.type !== 'transfer'} /></li>
                ))}
              </ul>
            </Card>
          )}
          {top.length > 0 && (
            <Card title="Where your money went" action={<button className="link" onClick={() => go('reports')}>Reports</button>}>
              <ul className="list">
                {top.map(([id, v]) => (
                  <li key={id ?? 'none'} className="clickable" onClick={() => go('transactions', id ? { category: id } : { category: 'none' })}>
                    <Dot color={cats.color(id)} /><div className="grow"><div className="row between"><span>{cats.name(id)}</span><Money value={v} currency={base} /></div><Bar fraction={v / top[0][1]} color={cats.color(id)} /></div>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      </div>
    </>
  )
}
