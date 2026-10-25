import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useAccounts, useBalances, useBudgets, useCanWrite, useGoals, useLedger, useRules } from '../lib/data'
import { goalProgress } from '../lib/goals'
import { Bar, Card, Money } from '../components'
import { Icon } from '../icons'
import type { Route } from '../App'

const key = (lid: string) => `kosh.start.hidden.${lid}`

/**
 * A short checklist for a new book. Each step ticks itself when it is done,
 * and the card goes away when everything is done or when it is hidden.
 */
export function GettingStarted({ go, onAdd }: { go: (r: Route, p?: Record<string, string>) => void; onAdd: () => void }) {
  const l = useLedger()
  const can = useCanWrite()
  const accounts = useAccounts()
  const rules = useRules()
  const budgets = useBudgets()
  const goals = useGoals()
  const recent = useQuery({ queryKey: [l.id, 'transactions', 'recent'], queryFn: () => api.transactions(l.id, { limit: '8' }) })
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, staleTime: 5 * 60_000 })
  const [hidden, setHidden] = useState(() => { try { return localStorage.getItem(key(l.id)) === '1' } catch { return false } })
  if (hidden || !can || accounts.isPending || recent.isPending || rules.isPending || budgets.isPending || goals.isPending) return null
  const live = <T extends { deleted: boolean }>(x?: T[]) => (x ?? []).filter((r) => !r.deleted)
  const steps: { done: boolean; title: string; body: string; action: string; run: () => void }[] = [
    { done: live(accounts.data).length > 0, title: 'Add your accounts', body: 'Cash, bank accounts, cards and wallets, with today’s balance.', action: 'Add accounts', run: () => go('accounts') },
    { done: (recent.data?.transactions.length ?? 0) > 0, title: 'Record your spending', body: 'Type “250 lunch” in the box above, or import a bank statement.', action: 'Add one', run: onAdd },
    { done: live(rules.data).some((r) => r.type === 'income'), title: 'Add your salary or regular income', body: 'As a recurring item, so the forecast and “safe to spend” know when money comes in.', action: 'Add income', run: () => go('recurring') },
    { done: live(budgets.data).length > 0, title: 'Set a monthly budget', body: 'See what you can still spend per day. LekhaMint suggests amounts from your spending.', action: 'Set a budget', run: () => go('budgets') },
    { done: live(goals.data).length > 0, title: 'Save for something', body: 'An emergency fund, a trip, a gadget: see how much to put aside each month.', action: 'Create a goal', run: () => go('goals') },
    { done: !!me.data?.user.totp_enabled, title: 'Protect your account', body: 'Turn on two-step sign-in with an authenticator app.', action: 'Open security', run: () => go('settings') },
  ]
  const done = steps.filter((s) => s.done).length
  if (done === steps.length) return null
  const next = steps.find((s) => !s.done)!
  const hide = () => { setHidden(true); try { localStorage.setItem(key(l.id), '1') } catch { /* ignore */ } }
  return (
    <Card className="mb onboarding" title={<><Icon name="sparkles" size={16} /> Get the most out of LekhaMint</>}
      action={<button className="link small" onClick={hide}>Hide</button>}>
      <div className="row nowrap start-progress"><Bar fraction={done / steps.length} /><span className="muted small nowrap">{done} of {steps.length} done</span></div>
      <ol className="start-steps">
        {steps.map((s) => (
          <li key={s.title} className={s.done ? 'done' : s === next ? 'next' : ''}>
            <span className="start-tick" aria-hidden>{s.done ? <Icon name="check" size={14} /> : null}</span>
            <div className="grow"><strong>{s.title}</strong>{!s.done && <div className="muted small">{s.body}</div>}</div>
            {!s.done && <button className={s === next ? 'primary' : ''} onClick={s.run}>{s.action}</button>}
          </li>
        ))}
      </ol>
    </Card>
  )
}

/** Goals in brief on the home page: the three closest to their date. */
export function GoalsMini({ go }: { go: (r: Route) => void }) {
  const goals = useGoals()
  const balances = useBalances()
  const list = (goals.data ?? []).filter((g) => !g.deleted && !g.archived)
  if (!list.length) return null
  const rows = list.map((g) => ({ g, p: goalProgress(g, balances.data ?? []) }))
    .sort((a, b) => (a.g.target_date ?? '9999').localeCompare(b.g.target_date ?? '9999')).slice(0, 3)
  return (
    <Card title="Goals" action={<button className="link" onClick={() => go('goals')}>All goals</button>}>
      <ul className="pct-bars">{rows.map(({ g, p }) => (
        <li key={g.id}><span className="grow">{g.name}</span><span className="pct-val"><Money value={p.saved} currency={g.currency} /> · {Math.floor(p.fraction * 100)}%</span>
          <Bar fraction={p.fraction} color={g.color ?? undefined} />
          {p.perMonth ? <span className="muted small">Put aside <Money value={p.perMonth} currency={g.currency} /> a month</span> : null}</li>
      ))}</ul>
    </Card>
  )
}
