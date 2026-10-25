import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type Budget, type BudgetProgress } from '../lib/api'
import { useAccounts, useBudgetProgress, useBudgets, useCanWrite, useCategoryIndex, useLedger, useStableId, useWrite, type WithId } from '../lib/data'
import { StackBar, SERIES } from '../charts'
import { parseMoney, toPlain } from '../lib/money'
import { isoDate, monthRange, shortDate } from '../lib/util'
import { Bar, Card, Check, CurrencySelect, Empty, Field, Modal, Money, Skeleton, errorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, DateField, NumInput } from '../controls'

export function Budgets({ go }: PageProps) {
  const budgets = useBudgets()
  const progress = useBudgetProgress()
  const can = useCanWrite()
  const [edit, setEdit] = useState<WithId<Budget> | 'new' | null>(null)
  const live = (budgets.data ?? []).filter((b) => !b.deleted)
  const active = live.filter((b) => !b.archived)
  const archived = live.filter((b) => b.archived)
  const byId = new Map((progress.data ?? []).map((p) => [p.budget_id, p]))
  return (
    <>
      <div className="page-head"><h1>Budgets</h1>{can && <button className="primary" onClick={() => setEdit('new')}><Icon name="plus" size={16} /> Budget</button>}</div>
      {budgets.isPending ? <div className="grid"><Card><Skeleton /></Card><Card><Skeleton /></Card></div> : live.length === 0 ? (
        <Card><Empty icon="target" title="No budgets yet" body="A monthly budget shows how much you can still spend, per day, before the month ends. Unspent money can roll over."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Create a monthly budget</button> : undefined} /></Card>
      ) : (
        <div className="grid">{active.map((b) => (
          <BudgetCard key={b.id} b={b} p={byId.get(b.id)} loading={progress.isPending} onOpen={() => setEdit(b)}
            onDrill={b.category_ids.length === 1 ? () => go('transactions', { category: b.category_ids[0] }) : undefined} />
        ))}</div>
      )}
      {can && !budgets.isPending && <PlanCard budgets={active} onCreate={() => setEdit('new')} />}
      {archived.length > 0 && (
        <Card title="Archived" className="mt">
          <ul className="list">{archived.map((b) => <li key={b.id} className="clickable" onClick={() => setEdit(b)}><span className="grow">{b.name}</span><Money value={b.amount} currency={b.currency} /></li>)}</ul>
        </Card>
      )}
      {edit && <BudgetForm budget={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
    </>
  )
}

/**
 * Average monthly spending per top-level category and average monthly income,
 * over the last three full months (main currency). Used to suggest budgets.
 */
export function useMonthlyAverages() {
  const l = useLedger()
  const cats = useCategoryIndex()
  const from = monthRange(isoDate(), -3)[0], to = monthRange(isoDate(), -1)[1]
  const q = useQuery({ queryKey: [l.id, 'summary', from, to], queryFn: () => api.summary(l.id, from, to), staleTime: 10 * 60_000 })
  return useMemo(() => {
    const s = q.data?.find((x) => x.currency === l.base_currency)
    const byRoot = new Map<string, number>()
    for (const c of s?.expense_by_category ?? []) {
      const k = cats.rootOf(c.category_id) ?? ''
      byRoot.set(k, (byRoot.get(k) ?? 0) + c.amount)
    }
    for (const [k, v] of byRoot) byRoot.set(k, Math.round(v / 3))
    return { pending: q.isPending, byRoot, income: Math.round((s?.income ?? 0) / 3), spend: Math.round((s?.expense ?? 0) / 3) }
  }, [q.data, q.isPending, cats, l.base_currency])
}

/** Rounds a suggestion to a friendly number (to 100, or 500 above 10,000, in major units). */
const friendly = (minor: number) => { const step = minor >= 1_000_000 ? 50_000 : 10_000; return Math.max(step, Math.ceil(minor / step) * step) }

const PER_MONTH: Record<string, number> = { weekly: 12 / 52, monthly: 1, quarterly: 3, yearly: 12 }

/**
 * Give every rupee a job: income against what the monthly budgets plan for,
 * and budgets suggested from the categories you spend most on.
 */
function PlanCard({ budgets, onCreate }: { budgets: WithId<Budget>[]; onCreate: () => void }) {
  const l = useLedger()
  const cats = useCategoryIndex()
  const write = useWrite()
  const avg = useMonthlyAverages()
  const cur = l.base_currency
  if (avg.pending || (avg.income === 0 && avg.spend === 0)) return null
  const mine = budgets.filter((b) => b.currency === cur && b.period !== 'custom')
  const monthly = (b: WithId<Budget>) => Math.round(b.amount * (PER_MONTH[b.period] ?? 1))
  // A budget for all spending already contains the category budgets, so they are not added on top.
  const overall = mine.filter((b) => b.category_ids.length === 0).reduce((a, b) => a + monthly(b), 0)
  const specific = mine.filter((b) => b.category_ids.length > 0).reduce((a, b) => a + monthly(b), 0)
  const planned = overall > 0 ? Math.max(overall, specific) : specific
  const covered = new Set(mine.flatMap((b) => b.category_ids))
  const everything = mine.some((b) => b.category_ids.length === 0)
  const ideas = everything ? [] : [...avg.byRoot].filter(([id, v]) => id && !covered.has(id) && v > 0).sort((a, b) => b[1] - a[1]).slice(0, 5)
  const free = avg.income - planned
  return (
    <Card title={<><Icon name="target" size={16} /> Plan your month</>} className="mt">
      <p className="muted small">Based on the last three months: you earned about <Money value={avg.income} currency={cur} /> and spent about <Money value={avg.spend} currency={cur} /> a month.</p>
      {avg.income > 0 && <>
        <StackBar currency={cur} label="Monthly income: planned in budgets and not yet planned" parts={free >= 0
          ? [{ name: 'Planned in budgets', value: planned, color: SERIES[0] }, { name: 'Not planned yet (savings, goals or a new budget)', value: free, color: SERIES[2] }]
          : [{ name: 'Income', value: avg.income, color: SERIES[0] }, { name: 'Budgets beyond income', value: -free, color: SERIES[7] }]} />
        <p className="small">{free > 0 ? <>Give the remaining <strong><Money value={free} currency={cur} /></strong> a job: a goal, a deposit, or a budget below. Money with a plan is less likely to drift away.</>
          : free < 0 ? <>Your budgets plan <strong><Money value={-free} currency={cur} /></strong> more than you usually earn. Trim a budget, or expect to use savings.</>
            : <>Every rupee of your usual income has a job.</>}</p>
      </>}
      {ideas.length > 0 && <>
        <h3 className="sub-head">Suggested budgets</h3>
        <ul className="list compact">{ideas.map(([id, v]) => (
          <li key={id}>
            <span className="grow">{cats.name(id)} <span className="muted small">· you spend about <Money value={v} currency={cur} /> a month</span></span>
            <button className="link" onClick={async () => {
              try {
                await write.save.mutateAsync({ entity: 'budgets', data: { name: cats.name(id), amount: friendly(v), currency: cur, period: 'monthly', start_date: monthRange(isoDate())[0], end_date: null, category_ids: [id], account_ids: [], rollover: false, alert_percent: 80, archived: false } })
                toast(`Budget for ${cats.name(id)} created. Adjust it any time.`)
              } catch (x) { toast(errorText(x)) }
            }}>Budget <Money value={friendly(v)} currency={cur} /></button>
          </li>
        ))}</ul>
        <p className="muted small">Each suggestion is your usual monthly spend, rounded up. <button className="link" onClick={onCreate}>Make your own</button></p>
      </>}
    </Card>
  )
}

/** Progress for the current period from the server (rollover, account filters and splits included). */
export function BudgetCard({ b, p, compact, loading, onOpen, onDrill }: { b: WithId<Budget>; p?: BudgetProgress; compact?: boolean; loading?: boolean; onOpen?: () => void; onDrill?: () => void }) {
  const cats = useCategoryIndex()
  const accounts = useAccounts()
  if (loading && !p) return <Card title={b.name}><Skeleton rows={2} /></Card>
  if (!p || !p.active) {
    if (compact) return null
    const upcoming = b.start_date > isoDate()
    return <div className="clickable-card" onClick={onOpen}><Card title={b.name}><p className="muted">{upcoming ? `Starts ${shortDate(b.start_date)}` : 'This budget has ended.'}</p></Card></div>
  }
  const left = p.limit - p.spent
  const fraction = p.limit > 0 ? p.spent / p.limit : 1
  const warn = fraction * 100 >= b.alert_percent
  const tone = left < 0 ? 'danger' : warn || fraction > p.pace_fraction + 0.1 ? 'warn' : 'ok'
  const status = left < 0 ? 'Over budget' : warn ? `Past ${b.alert_percent}%` : fraction > p.pace_fraction + 0.1 ? 'Ahead of pace' : 'On track'
  const filters = [...b.category_ids.map((id) => cats.name(id)), ...b.account_ids.map((id) => accounts.data?.find((a) => a.id === id)?.name ?? '?')]
  return (
    <div className={onOpen ? 'clickable-card' : ''} onClick={onOpen} role={onOpen ? 'button' : undefined} tabIndex={onOpen ? 0 : undefined} onKeyDown={(e) => { if (e.key === 'Enter') onOpen?.() }}>
      <Card title={b.name} action={<span className={`pill ${tone}`}>{status}</span>}>
        <div className="row baseline">
          <span className="stat-value"><Money value={p.spent} currency={p.currency} /></span>
          <span className="muted">of <Money value={p.limit} currency={p.currency} /></span>
        </div>
        <Bar fraction={fraction} pace={p.pace_fraction} danger={left < 0} color={tone === 'warn' ? 'var(--warn)' : undefined} />
        <p className="small budget-line" style={{ color: left < 0 ? 'var(--danger)' : 'var(--muted)' }}>
          {left < 0 ? <>Over by <Money value={-left} currency={p.currency} /></> : p.days_left > 0
            ? <><Money value={left} currency={p.currency} /> left · <Money value={p.per_day_left} currency={p.currency} />/day for {p.days_left} {p.days_left === 1 ? 'day' : 'days'}</>
            : <><Money value={left} currency={p.currency} /> left</>}
        </p>
        {!compact && (
          <div className="muted small budget-meta">
            <span>{shortDate(p.window.start)} – {shortDate(p.window.end)}</span>
            {p.carry !== 0 && <span> · <Money value={p.carry} currency={p.currency} signed /> rolled over</span>}
            {filters.length > 0 && <span> · {filters.join(', ')}</span>}
            {onDrill && <> · <button className="link" onClick={(e) => { e.stopPropagation(); onDrill() }}>See transactions</button></>}
          </div>
        )}
      </Card>
    </div>
  )
}

function BudgetForm({ budget, onClose }: { budget?: WithId<Budget>; onClose: () => void }) {
  const l = useLedger()
  const cats = useCategoryIndex()
  const accounts = useAccounts()
  const write = useWrite()
  const newId = useStableId()
  const can = useCanWrite()
  const [name, setName] = useState(budget?.name ?? 'Monthly spending')
  const [cur, setCur] = useState(budget?.currency ?? l.base_currency)
  const [amount, setAmount] = useState(budget ? toPlain(budget.amount, budget.currency) : '')
  const [period, setPeriod] = useState(budget?.period ?? 'monthly')
  const [start, setStart] = useState(budget?.start_date ?? monthRange(isoDate())[0])
  const [end, setEnd] = useState(budget?.end_date ?? '')
  const [selected, setSelected] = useState<Set<string>>(new Set(budget?.category_ids ?? []))
  const [accts, setAccts] = useState<Set<string>>(new Set(budget?.account_ids ?? []))
  const [rollover, setRollover] = useState(budget?.rollover ?? false)
  const [alert, setAlert] = useState(budget?.alert_percent ?? 80)
  const [archived, setArchived] = useState(budget?.archived ?? false)
  const [err, setErr] = useState('')
  const toggle = (set: Set<string>, put: (s: Set<string>) => void, id: string) => { const s = new Set(set); if (s.has(id)) s.delete(id); else s.add(id); put(s) }
  const avg = useMonthlyAverages()
  // What these categories usually cost per period, as a starting point.
  const usual = cur !== l.base_currency || avg.pending ? null
    : Math.round((selected.size ? [...selected].reduce((a, id) => a + (avg.byRoot.get(id) ?? 0), 0) : avg.spend) * (PER_MONTH[period] ?? 1))
  const liveAccounts = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === cur)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const minor = parseMoney(amount, cur)
    if (!name.trim()) return setErr('Give the budget a name')
    if (!minor || minor <= 0) return setErr('Enter the budget amount')
    if (period === 'custom' && (!end || end < start)) return setErr('Pick an end date after the start')
    try {
      await write.save.mutateAsync({ entity: 'budgets', id: budget?.id, newId, data: {
        name: name.trim(), amount: minor, currency: cur, period, start_date: start, end_date: period === 'custom' ? end : null,
        category_ids: [...selected], account_ids: [...accts].filter((id) => liveAccounts.some((a) => a.id === id)),
        rollover: rollover && period !== 'custom', alert_percent: alert, archived,
      } satisfies Partial<Budget> })
      toast(budget ? 'Budget saved' : 'Budget created')
      onClose()
    } catch (x) { setErr(errorText(x)) }
  }
  return (
    <Modal wide title={budget ? 'Edit budget' : 'New budget'} onClose={onClose} footer={can ? <>
      {budget && <button type="button" className="danger" onClick={async () => { await write.remove.mutateAsync({ entity: 'budgets', id: budget.id }); toast('Budget deleted', { label: 'Undo', run: () => write.restore.mutate({ entity: 'budgets', id: budget.id }) }); onClose() }}><Icon name="trash" size={16} /> Delete</button>}
      <span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="budgetform" disabled={write.save.isPending}>{write.save.isPending ? 'Saving…' : 'Save'}</button></> : <button onClick={onClose}>Close</button>}>
      <form id="budgetform" onSubmit={submit}>
        <fieldset disabled={!can} className="plain">
          <div className="two">
            <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} required /></Field>
            <Field label="Amount per period" hint={usual ? <>Usually about <Money value={usual} currency={cur} /> <button type="button" className="link" onClick={() => setAmount(toPlain(friendly(usual), cur))}>Use {toPlain(friendly(usual), cur)}</button></> : undefined}>
              <NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required autoFocus={!budget} /></Field>
          </div>
          <div className="two">
            <Field label="Period"><Select value={period} onChange={(e) => setPeriod(e.target.value)}>
              <option value="weekly">Weekly</option><option value="monthly">Monthly</option><option value="quarterly">Quarterly</option><option value="yearly">Yearly</option><option value="custom">Custom dates</option></Select></Field>
            <Field label="Currency" hint="Only accounts in this currency count"><CurrencySelect value={cur} onChange={setCur} /></Field>
          </div>
          <div className="two">
            <Field label="Starts" hint="Anchors each period, e.g. your payday"><DateField value={start} onChange={(e) => setStart(e.target.value)} required /></Field>
            {period === 'custom' && <Field label="Ends"><DateField value={end} onChange={(e) => setEnd(e.target.value)} required /></Field>}
          </div>
          <Field label="Categories" hint="None selected = all spending. A group includes its sub-categories.">
            <div className="check-grid">
              {cats.roots('expense').map((r) => <Check key={r.id} checked={selected.has(r.id)} onChange={() => toggle(selected, setSelected, r.id)}>{r.name}</Check>)}
            </div>
          </Field>
          {liveAccounts.length > 1 && (
            <Field label="Accounts" hint="None selected = every account. Pick a card to budget just that card.">
              <div className="check-grid">
                {liveAccounts.map((a) => <Check key={a.id} checked={accts.has(a.id)} onChange={() => toggle(accts, setAccts, a.id)}>{a.name}</Check>)}
              </div>
            </Field>
          )}
          <div className="two">
            <div className="field-pad">
              {period !== 'custom' && <Check checked={rollover} onChange={setRollover}>Roll over unspent money (and overspending) to the next period</Check>}
              {budget && <Check checked={archived} onChange={setArchived}>Archived</Check>}
            </div>
            <Field label={`Warn me at ${alert}%`}><input type="range" min={50} max={100} step={5} value={alert} style={{ ['--fill' as string]: `${((alert - 50) / 50) * 100}%` }} onChange={(e) => setAlert(Number(e.target.value))} /></Field>
          </div>
          {err && <p className="error" role="alert">{err}</p>}
        </fieldset>
      </form>
    </Modal>
  )
}
