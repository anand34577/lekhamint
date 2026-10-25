import { useState } from 'react'
import type { Budget, BudgetProgress } from '../lib/api'
import { useAccounts, useBudgetProgress, useBudgets, useCanWrite, useCategoryIndex, useLedger, useStableId, useWrite, type WithId } from '../lib/data'
import { parseMoney, toPlain } from '../lib/money'
import { isoDate, monthRange, shortDate } from '../lib/util'
import { Bar, Card, Check, CurrencySelect, Empty, Field, Modal, Money, Skeleton, errorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'

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
      {archived.length > 0 && (
        <Card title="Archived" className="mt">
          <ul className="list">{archived.map((b) => <li key={b.id} className="clickable" onClick={() => setEdit(b)}><span className="grow">{b.name}</span><Money value={b.amount} currency={b.currency} /></li>)}</ul>
        </Card>
      )}
      {edit && <BudgetForm budget={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
    </>
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
            <Field label="Amount per period"><input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required autoFocus={!budget} /></Field>
          </div>
          <div className="two">
            <Field label="Period"><select value={period} onChange={(e) => setPeriod(e.target.value)}>
              <option value="weekly">Weekly</option><option value="monthly">Monthly</option><option value="quarterly">Quarterly</option><option value="yearly">Yearly</option><option value="custom">Custom dates</option></select></Field>
            <Field label="Currency" hint="Only accounts in this currency count"><CurrencySelect value={cur} onChange={setCur} /></Field>
          </div>
          <div className="two">
            <Field label="Starts" hint="Anchors each period, e.g. your payday"><input type="date" value={start} onChange={(e) => setStart(e.target.value)} required /></Field>
            {period === 'custom' && <Field label="Ends"><input type="date" value={end} onChange={(e) => setEnd(e.target.value)} required /></Field>}
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
            <Field label={`Warn me at ${alert}%`}><input type="range" min={50} max={100} step={5} value={alert} onChange={(e) => setAlert(Number(e.target.value))} /></Field>
          </div>
          {err && <p className="error" role="alert">{err}</p>}
        </fieldset>
      </form>
    </Modal>
  )
}
