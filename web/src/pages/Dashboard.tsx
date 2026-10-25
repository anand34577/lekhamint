import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAccounts, usePosition, useBudgetProgress, useBudgets, useCanWrite, useCategoryIndex, useLedger, useSplitIndex, useSummary } from '../lib/data'
import { insights } from '../lib/insights'
import { formatMoney } from '../lib/money'
import { useObligations, type Obligation } from './obligations'
import { addDays, dayLabel, isoDate, monthRange, parseDate } from '../lib/util'
import { Bar, Card, Empty, Money, PaceChart, Skeleton } from '../components'
import { CatIcon, Icon } from '../icons'
import { BudgetCard } from './Budgets'
import { QuickAddBar } from './QuickAdd'
import type { PageProps } from '../App'

const OB_ICON: Record<Obligation['kind'], string> = { bill: 'repeat', income: 'coins', emi: 'bank', rd: 'piggy', maturity: 'piggy', payout: 'coins', debt: 'hand' }

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
  const accounts = useAccounts()
  const summary = useSummary(from, to)
  const prevSummary = useSummary(prevFrom, prevSame)
  const cats = useCategoryIndex()
  const splits = useSplitIndex()
  const budgets = useBudgets()
  const progress = useBudgetProgress()
  const can = useCanWrite()
  const recent = useQuery({ queryKey: [l.id, 'transactions', 'recent'], queryFn: () => api.transactions(l.id, { limit: '8' }) })
  const uncategorised = useQuery({ queryKey: [l.id, 'transactions', 'uncategorised'], queryFn: () => api.transactions(l.id, { category_id: 'none', limit: '100' }) })
  const daily = useQuery({ queryKey: [l.id, 'trend', 'daily', prevFrom, to], queryFn: () => api.trend(l.id, prevFrom, to, 'day') })
  const multi = (summary.data?.length ?? 0) > 1
  const converted = useQuery({ queryKey: [l.id, 'summary-converted', from, to, base], queryFn: () => api.summaryConverted(l.id, from, to, base), enabled: multi })

  // Spendable cash, investments, debt and net worth are different things (docs/02).
  // The server works the position out once, so every screen and device agrees.
  const position = usePosition()
  const pos = useMemo(() => {
    const list = position.data ?? []
    const p = list.find((x) => x.currency === base) ?? { currency: base, cash: 0, investments: 0, liabilities: 0, receivable: 0, payable: 0, net_worth: 0, excluded_accounts: 0, excluded_balance: 0 }
    return { ...p, other: list.filter((x) => x.currency !== base && x.net_worth !== 0) }
  }, [position.data, base])

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

  // Everything due: bills, EMIs, RD instalments, maturities and personal debts.
  const obligations = useObligations(Math.max(14, daysInMonth - day + 1))
  const upcoming = obligations.items.filter((o) => o.date <= addDays(today, 14)).slice(0, 7)
  // Safe to spend: cash minus what is still due from it before the month ends (expected money in is not counted).
  const dueThisMonth = obligations.items.filter((o) => o.direction === 'out' && o.currency === base && o.date <= to && o.amount).reduce((a, o) => a + (o.amount ?? 0), 0)
  const safe = pos.cash - dueThisMonth
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
          <div className="stat-big">{position.isPending ? <Skeleton rows={1} height={34} /> : <Money value={pos.cash} currency={base} />}</div>
          <div className="muted small">Cash, bank, savings and wallet accounts. Credit limits are not counted.</div>
          {dueThisMonth > 0 && !position.isPending && (
            <div className="safe-line small">
              <span>After bills & EMIs due by {dayLabel(to)}</span>
              <Money value={safe} currency={base} className={safe < 0 ? 'neg' : ''} />
            </div>
          )}
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
          <div className="stat-big">{position.isPending ? <Skeleton rows={1} height={34} /> : <Money value={pos.net_worth} currency={base} />}</div>
          <ul className="nw-lines small">
            {pos.investments !== 0 && <li><span>Investments & deposits</span><Money value={pos.investments} currency={base} /></li>}
            {pos.receivable !== 0 && <li><span>Others owe you</span><Money value={pos.receivable} currency={base} /></li>}
            {pos.liabilities !== 0 && <li><span>Owed on cards & loans</span><Money value={-pos.liabilities} currency={base} /></li>}
            {pos.payable !== 0 && <li><span>You owe people</span><Money value={-pos.payable} currency={base} /></li>}
            {pos.other.map((o) => <li key={o.currency}><span>In {o.currency}</span><Money value={o.net_worth} currency={o.currency} /></li>)}
          </ul>
          {pos.excluded_accounts > 0 && <div className="muted small">{pos.excluded_accounts} archived or excluded {pos.excluded_accounts === 1 ? 'account holds' : 'accounts hold'} <Money value={pos.excluded_balance} currency={base} /> not counted here.</div>}
        </Card>
      </div>

      {(tips.length > 0 || nUncat > 0 || obligations.shortfalls.length > 0) && (
        <div className="insights">
          {obligations.shortfalls.map((s) => (
            <button key={s.accountId} className="insight warn" onClick={() => go('accounts')}>
              <Icon name="alert" /><div><strong>{s.name} may run short</strong>
                <span>{formatMoney(s.need, s.currency)} is due from it soon (first on {dayLabel(s.first)}), but it holds {formatMoney(s.have, s.currency)}. Move money in before then.</span></div><Icon name="right" />
            </button>
          ))}
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
                      {transfer ? <CatIcon name="transfer" color="var(--transfer)" /> : split ? <CatIcon name="split" color="#7C3AED" /> : <CatIcon name={cats.icon(t.category_id)} color={cats.color(t.category_id)} />}
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
                {upcoming.map((o) => (
                  <li key={o.key} className="clickable" onClick={() => go(o.link.route, o.link.params)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') go(o.link.route, o.link.params) }}>
                    <span className={`ob-icon ${o.kind}`}><Icon name={OB_ICON[o.kind]} size={14} /></span>
                    <div className="grow"><div className="ellipsis">{o.title}</div><div className={`sub ${o.overdue ? 'error' : ''}`}>{o.overdue ? 'Overdue · ' : ''}{dayLabel(o.date)} · {o.detail}</div></div>
                    {o.amount !== null && <Money value={o.direction === 'in' ? o.amount : -o.amount} currency={o.currency || base} signed />}
                  </li>
                ))}
              </ul>
              <p className="muted small">Expected, not yet paid. Record each one when it happens.</p>
            </Card>
          )}
          {top.length > 0 && (
            <Card title="Where your money went" action={<button className="link" onClick={() => go('reports')}>Reports</button>}>
              <ul className="list">
                {top.map(([id, v]) => (
                  <li key={id ?? 'none'} className="clickable" onClick={() => go('transactions', id ? { category: id } : { category: 'none' })}>
                    <CatIcon name={cats.icon(id)} color={cats.color(id)} /><div className="grow"><div className="row between"><span>{cats.name(id)}</span><Money value={v} currency={base} /></div><Bar fraction={v / top[0][1]} color={cats.color(id)} /></div>
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
