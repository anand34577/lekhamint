import { useEffect, useMemo, useState } from 'react'
import type { Goal } from '../lib/api'
import { isLiability, useAccounts, useBalances, useCanWrite, useGoals, useLedger, useStableId, useWrite, type WithId } from '../lib/data'
import { goalProgress, STATUS_TEXT, type GoalProgress } from '../lib/goals'
import { parseMoney, toPlain } from '../lib/money'
import { addMonths, isoDate, shortDate } from '../lib/util'
import { Bar, Card, Check, CurrencySelect, Empty, Field, Modal, Money, Skeleton, saveErrorText, toast } from '../components'
import { Explain, Meter, SERIES } from '../charts'
import { DateField, NumInput } from '../controls'
import { Icon } from '../icons'
import type { PageProps } from '../App'

const COLORS = ['#0f9488', '#2f7de1', '#9b51d6', '#e0436b', '#d98a00', '#12a15d', '#5b5ce0', '#ef6c00']
const TONE: Record<string, 'good' | 'brand' | 'warn' | 'danger'> = { done: 'good', ahead: 'good', on_track: 'brand', behind: 'warn', overdue: 'danger', no_date: 'brand' }

/** Savings goals: a target, an optional date, and the accounts that hold the money. */
export function Goals({ params, go }: PageProps) {
  const goals = useGoals()
  const balances = useBalances()
  const can = useCanWrite()
  const write = useWrite()
  const [edit, setEdit] = useState<WithId<Goal> | 'new' | null>(null)
  const [preset, setPreset] = useState<Partial<Goal> | null>(null)
  // Calculators link here with ?new=1&name=…&target=…&date=… to save a plan as a goal.
  useEffect(() => {
    if (params.new === '1' && can) {
      setPreset({ name: params.name ?? '', target_amount: Number(params.target) || 0, target_date: params.date || null, saved_amount: Number(params.saved) || 0 })
      setEdit('new'); go('goals')
    }
  }, [params.new]) // eslint-disable-line react-hooks/exhaustive-deps
  const list = (goals.data ?? []).filter((g) => !g.deleted)
  const active = list.filter((g) => !g.archived)
  const archived = list.filter((g) => g.archived)
  const prog = useMemo(() => new Map(list.map((g) => [g.id, goalProgress(g, balances.data ?? [])])), [list, balances.data])

  // Mark goals reached once, so the date it happened is remembered.
  useEffect(() => {
    if (!can) return
    for (const g of active) if (prog.get(g.id)?.status === 'done' && !g.achieved_date) write.save.mutate({ entity: 'goals', id: g.id, data: { achieved_date: isoDate() } })
  }, [prog]) // eslint-disable-line react-hooks/exhaustive-deps

  const totalByCur = new Map<string, { saved: number; target: number }>()
  for (const g of active) {
    const p = prog.get(g.id)!
    const t = totalByCur.get(g.currency) ?? { saved: 0, target: 0 }
    totalByCur.set(g.currency, { saved: t.saved + Math.min(p.saved, p.target), target: t.target + p.target })
  }
  const perMonth = new Map<string, number>()
  for (const g of active) { const p = prog.get(g.id)!; if (p.perMonth) perMonth.set(g.currency, (perMonth.get(g.currency) ?? 0) + p.perMonth) }

  return (
    <>
      <div className="page-head"><h1>Goals</h1>{can && <button className="primary" onClick={() => { setPreset(null); setEdit('new') }}><Icon name="plus" size={16} /> Goal</button>}</div>
      {goals.isPending ? <Card><Skeleton rows={4} /></Card> : active.length === 0 ? (
        <Card><Empty icon="target" title="Save for what matters"
          body="Set a target, like an emergency fund, a trip or a new phone. Link the accounts where you keep the money, and LekhaMint shows how close you are and how much to put aside each month."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Create your first goal</button> : undefined} /></Card>
      ) : <>
        <div className="grid stats mb">
          {[...totalByCur].map(([cur, t]) => (
            <Card key={cur} className="stat-card">
              <div className="stat-label">Saved towards your goals{totalByCur.size > 1 ? ` (${cur})` : ''}</div>
              <div className="stat-big"><Money value={t.saved} currency={cur} /> <span className="muted small">of <Money value={t.target} currency={cur} /></span></div>
              <Meter value={t.saved} max={t.target} label="Saved of all targets" tone="brand" />
              {perMonth.get(cur) ? <p className="muted small">To stay on time, put aside about <strong><Money value={perMonth.get(cur)!} currency={cur} /></strong> a month in total.</p> : null}
            </Card>
          ))}
          {active.length > 1 && (
            <Card title="Closest to done">
              <ul className="pct-bars">{[...active].sort((a, b) => prog.get(b.id)!.fraction - prog.get(a.id)!.fraction).slice(0, 6).map((g, i) => (
                <li key={g.id}><span className="grow">{g.name}</span><span className="pct-val">{Math.floor(prog.get(g.id)!.fraction * 100)}%</span>
                  <Bar fraction={prog.get(g.id)!.fraction} color={g.color ?? SERIES[i % 8]} /></li>
              ))}</ul>
              <p className="muted small">Bars show the percentage saved of each target.</p>
            </Card>
          )}
        </div>
        <div className="goal-grid">
          {active.map((g) => <GoalCard key={g.id} goal={g} p={prog.get(g.id)!} onEdit={can ? () => setEdit(g) : undefined} />)}
        </div>
      </>}
      {archived.length > 0 && <Card title="Finished and archived" className="mb">
        <ul className="list">{archived.map((g) => (
          <li key={g.id} className={can ? 'clickable' : ''} onClick={() => can && setEdit(g)}>
            <span className="dot" style={{ background: g.color ?? 'var(--brand)' }} />
            <div className="grow">{g.name}<div className="sub">{g.achieved_date ? `Reached ${shortDate(g.achieved_date)}` : 'Archived'}</div></div>
            <Money value={g.target_amount} currency={g.currency} />
          </li>
        ))}</ul>
      </Card>}
      {edit && <GoalForm goal={edit === 'new' ? undefined : edit} preset={edit === 'new' ? preset ?? undefined : undefined} onClose={() => setEdit(null)} />}
    </>
  )
}

function GoalCard({ goal: g, p, onEdit }: { goal: WithId<Goal>; p: GoalProgress; onEdit?: () => void }) {
  const accounts = useAccounts()
  const names = (g.account_ids ?? []).map((id) => accounts.data?.find((a) => a.id === id)?.name).filter(Boolean)
  const marks = p.expected !== null && p.status !== 'done' && p.status !== 'overdue' ? [{ at: p.expected, label: 'plan for today' }] : []
  return (
    <Card className="goal-card">
      <div className="goal-top">
        <span className="goal-icon" style={{ ['--c' as string]: g.color ?? 'var(--brand)' }}><Icon name={p.status === 'done' ? 'check' : 'target'} size={18} /></span>
        <div className="grow">
          <h3>{g.name}</h3>
          <div className="sub"><span className={`chip ${TONE[p.status]}`}>{STATUS_TEXT[p.status]}</span> {g.target_date ? `by ${shortDate(g.target_date)}` : 'no date'}{names.length ? ` · in ${names.join(', ')}` : ''}</div>
        </div>
        {onEdit && <button className="icon-btn" onClick={onEdit} aria-label={`Edit ${g.name}`}><Icon name="edit" size={16} /></button>}
      </div>
      <div className="goal-amounts"><Money value={p.saved} currency={g.currency} /><span className="muted"> of <Money value={p.target} currency={g.currency} /></span>
        <span className="goal-pct">{Math.floor(p.fraction * 100)}%</span></div>
      <Meter value={p.saved} max={p.target} marks={marks} tone={TONE[p.status]} label={`${g.name}: saved of target`} />
      {p.status === 'done' ? <p className="small pos"><Icon name="sparkles" size={14} /> Goal reached{g.achieved_date ? ` on ${shortDate(g.achieved_date)}` : ''}. Well done! Archive it when you have used the money.</p>
        : p.perMonth ? <p className="small">Put aside <strong><Money value={p.perMonth} currency={g.currency} /></strong> a month for {Math.max(1, Math.round(p.monthsLeft!))} more {Math.round(p.monthsLeft!) === 1 ? 'month' : 'months'} to reach it on time.</p>
          : p.status === 'overdue' ? <p className="small warn-text"><Money value={p.left} currency={g.currency} /> still to go and the date has passed. Pick a new date or a smaller target.</p>
            : <p className="small muted"><Money value={p.left} currency={g.currency} /> still to go. Add a date to see how much to save each month.</p>}
      {p.otherCurrency > 0 && <p className="small muted">{p.otherCurrency} linked {p.otherCurrency === 1 ? 'account is' : 'accounts are'} in another currency and not counted.</p>}
      {g.note && <p className="small muted">{g.note}</p>}
    </Card>
  )
}

function GoalForm({ goal, preset, onClose }: { goal?: WithId<Goal>; preset?: Partial<Goal>; onClose: () => void }) {
  const l = useLedger()
  const write = useWrite()
  const accounts = useAccounts()
  const balances = useBalances()
  const newId = useStableId()
  const src = goal ?? preset
  const [name, setName] = useState(src?.name ?? '')
  const [cur, setCur] = useState(goal?.currency ?? l.base_currency)
  const [target, setTarget] = useState(src?.target_amount ? toPlain(src.target_amount, cur) : '')
  const [date, setDate] = useState(src?.target_date ?? addMonths(isoDate(), 12))
  const [hasDate, setHasDate] = useState(goal ? !!goal.target_date : true)
  const [saved, setSaved] = useState(src?.saved_amount ? toPlain(src.saved_amount, cur) : '')
  const [linked, setLinked] = useState<string[]>(goal?.account_ids ?? [])
  const [color, setColor] = useState(goal?.color ?? COLORS[0])
  const [note, setNote] = useState(goal?.note ?? '')
  const [archived, setArchived] = useState(goal?.archived ?? false)
  const [err, setErr] = useState('')
  const pool = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && !isLiability(a.type) && a.currency === cur)
  const targetMinor = parseMoney(target, cur)
  const savedMinor = saved.trim() ? parseMoney(saved, cur) : 0
  const preview = targetMinor && targetMinor > 0 && savedMinor !== null
    ? goalProgress({ name, target_amount: targetMinor, currency: cur, target_date: hasDate ? date : null, start_date: goal?.start_date ?? isoDate(), account_ids: linked, saved_amount: savedMinor,
      color, icon: null, archived, achieved_date: null, note: null, created_at: goal?.created_at ?? Date.now() }, balances.data ?? []) : null
  return (
    <Modal title={goal ? 'Edit goal' : 'New goal'} onClose={onClose} footer={<>
      {goal && <button type="button" className="danger" onClick={async () => {
        await write.remove.mutateAsync({ entity: 'goals', id: goal.id }); onClose()
        toast('Goal deleted', { label: 'Undo', run: () => write.restore.mutate({ entity: 'goals', id: goal.id }) })
      }}><Icon name="trash" size={16} /> Delete</button>}
      <span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="goalform" disabled={write.save.isPending}>Save</button></>}>
      <form id="goalform" onSubmit={async (e) => {
        e.preventDefault(); setErr('')
        if (!name.trim()) return setErr('Give the goal a name, like “Emergency fund” or “Goa trip”')
        if (!targetMinor || targetMinor <= 0) return setErr('Enter the amount you want to save, like 60000')
        if (savedMinor === null || savedMinor < 0) return setErr('“Already set aside” should be a number, or leave it empty')
        if (hasDate && date <= isoDate() && !goal) return setErr('Pick a date in the future, or untick “By a date”')
        try {
          await write.save.mutateAsync({ entity: 'goals', id: goal?.id, newId, data: {
            name: name.trim(), target_amount: targetMinor, currency: cur, target_date: hasDate ? date : null, start_date: goal?.start_date ?? isoDate(),
            account_ids: linked.filter((id) => pool.some((a) => a.id === id)), saved_amount: savedMinor, color, archived, note: note.trim() || null,
            ...(goal && preview && preview.status !== 'done' ? { achieved_date: null } : {}),
          } satisfies Partial<Goal> })
          toast(goal ? 'Goal saved' : `Goal “${name.trim()}” created`); onClose()
        } catch (x) { setErr(saveErrorText(x)) }
      }}>
        <Field label="What are you saving for?"><input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="Emergency fund, Goa trip, new laptop…" autoFocus={!goal} /></Field>
        <div className="two">
          <Field label="Target amount"><NumInput kind="decimal" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="60000" /></Field>
          <Field label="Currency"><CurrencySelect value={cur} onChange={(c) => { setCur(c); setLinked([]) }} /></Field>
        </div>
        <Check checked={hasDate} onChange={setHasDate}>By a date</Check>
        {hasDate && <Field label="Target date"><DateField value={date} onChange={(e) => setDate(e.target.value)} /></Field>}
        <div className="field"><span className="field-label">Where is the money kept? (optional)</span>
          {pool.length === 0 ? <p className="muted small">No {cur} accounts yet. You can still track it with “Already set aside”.</p> : (
            <div className="check-list">{pool.map((a) => (
              <Check key={a.id} checked={linked.includes(a.id)} onChange={(v) => setLinked(v ? [...linked, a.id] : linked.filter((x) => x !== a.id))}>
                {a.name} <span className="muted small">· <Money value={(() => { const b = balances.data?.find((x) => x.account_id === a.id); return b ? b.value ?? b.balance : 0 })()} currency={a.currency} /></span>
              </Check>
            ))}</div>
          )}
          <span className="field-hint">Their whole balance counts. Use a separate savings account or deposit for each goal for the clearest picture.</span>
        </div>
        <Field label="Already set aside elsewhere (optional)" hint="Cash at home, or money in an account you do not track here"><NumInput kind="decimal" value={saved} onChange={(e) => setSaved(e.target.value)} placeholder="0" /></Field>
        <Field label="Colour"><div className="row">{COLORS.map((c) => <button type="button" key={c} className={`swatch ${c === color ? 'on' : ''}`} style={{ background: c }} aria-label={`Colour ${c}`} aria-pressed={c === color} onClick={() => setColor(c)} />)}</div></Field>
        <Field label="Note (optional)"><input value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} /></Field>
        {goal && <Check checked={archived} onChange={setArchived}>Archived (finished or no longer needed)</Check>}
        {preview && preview.status !== 'done' && (
          <Explain title="Your plan in simple words" steps={[
            { title: 'Saved so far', body: linked.length ? 'The linked accounts plus what is set aside elsewhere.' : 'What you have set aside.', value: <Money value={preview.saved} currency={cur} /> },
            { title: 'Still to save', value: <Money value={preview.left} currency={cur} /> },
            ...(preview.perMonth ? [{ title: `Over ${Math.max(1, Math.round(preview.monthsLeft!))} months`, body: 'Split evenly, this is what to put aside each month.', value: <Money value={preview.perMonth} currency={cur} /> }] : []),
          ]} />
        )}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}
