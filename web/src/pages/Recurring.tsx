import { useEffect, useMemo, useRef, useState } from 'react'
import type { BatchOp, RecurringRule } from '../lib/api'
import { useAccounts, useCanWrite, useCategoryIndex, useLedger, useRules, useWrite, type WithId } from '../lib/data'
import { dueDates, initialNextDate, nextAfter, nextDate, occurrenceData, occurrenceId, type RuleWithId } from '../lib/recurrence'
import { parseMoney, toPlain } from '../lib/money'
import { addDays, dayLabel, isoDate } from '../lib/util'
import { Card, Check, Dot, Empty, Field, Modal, Money, Segmented, Skeleton, errorText, toast } from '../components'
import { Icon } from '../icons'
import { CategorySelect } from './Transactions'
import { TagInput } from './parts'
import type { PageProps } from '../App'

const FREQ: Record<string, string> = { daily: 'day', weekly: 'week', monthly: 'month', yearly: 'year' }
const describe = (r: RecurringRule) => (r.every > 1 ? `Every ${r.every} ${FREQ[r.frequency]}s` : `Every ${FREQ[r.frequency]}`)

/** Moves next_date forward past [processed]; never backwards (another device may be ahead). */
function advanceOp(r: RuleWithId, processed: string): BatchOp | null {
  const next = nextAfter(r, processed) ?? addDays(processed, 1)
  if (r.next_date && next <= r.next_date) return null
  return { op: 'update', entity: 'recurring_rules', id: r.id, data: { next_date: next } }
}

/** Records the occurrence on [date] unless it exists (or was deleted on purpose), and advances the rule. */
async function confirmOps(r: RuleWithId, date: string, amount = r.amount): Promise<BatchOp[]> {
  const ops: BatchOp[] = [{ op: 'create_if_missing', entity: 'transactions', id: await occurrenceId(r.id, date), data: { ...occurrenceData(r, date, amount), created_at: Date.now() } }]
  const adv = advanceOp(r, date)
  if (adv) ops.push(adv)
  return ops
}

/**
 * Creates due occurrences of automatic rules, once per book per session, so
 * a book used only on the web still gets its salary and rent. The IDs are the
 * same ones Android uses, so a phone that also runs them never duplicates.
 */
export function useAutoRecurring() {
  const l = useLedger()
  const rules = useRules()
  const write = useWrite()
  const can = useCanWrite()
  const done = useRef(false)
  useEffect(() => {
    if (done.current || !can || !rules.data) return
    done.current = true
    const today = isoDate()
    const auto = rules.data.filter((r) => !r.deleted && !r.paused && r.mode === 'auto') as RuleWithId[]
    ;(async () => {
      const ops: BatchOp[] = []
      for (const r of auto) {
        const dates = dueDates(r, today)
        if (!dates.length) continue
        for (const d of dates) ops.push({ op: 'create_if_missing', entity: 'transactions', id: await occurrenceId(r.id, d), data: { ...occurrenceData(r, d), created_at: Date.now() } })
        const adv = advanceOp(r, dates[dates.length - 1])
        if (adv) ops.push(adv)
      }
      if (!ops.length) return
      const res = await write.batch.mutateAsync(ops)
      const created = res.filter((x) => x.entity === 'transactions' && x.status === 'created').length
      if (created) toast(`Recorded ${created} recurring ${created === 1 ? 'item' : 'items'} that were due`)
    })().catch(() => { done.current = false })
  }, [rules.data, can, l.id]) // eslint-disable-line
}

export function Recurring({ go }: PageProps) {
  const l = useLedger()
  const rules = useRules()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const write = useWrite()
  const can = useCanWrite()
  const [edit, setEdit] = useState<WithId<RecurringRule> | 'new' | null>(null)
  const today = isoDate()
  const live = useMemo(() => (rules.data ?? []).filter((r) => !r.deleted).map((r) => ({ r: r as WithId<RuleWithId>, next: nextDate(r) }))
    .sort((a, b) => Number(a.r.paused) - Number(b.r.paused) || (a.next ?? '9') .localeCompare(b.next ?? '9')), [rules.data])
  const cur = (id: string) => accounts.data?.find((a) => a.id === id)?.currency ?? l.base_currency
  const accName = (id: string | null | undefined) => accounts.data?.find((a) => a.id === id)?.name ?? '?'
  // Reminder items that are due now (the phone would notify).
  const due = live.flatMap(({ r }) => (r.mode === 'remind' ? dueDates(r, today, 3).map((d) => ({ r, d })) : []))
  const monthly = useMemo(() => {
    const per: Record<string, number> = { daily: 30.44, weekly: 4.348, monthly: 1, yearly: 1 / 12 }
    const m = new Map<string, number>()
    for (const { r, next } of live) {
      if (r.paused || !next || r.type !== 'expense') continue
      const c = cur(r.account_id)
      m.set(c, (m.get(c) ?? 0) + Math.round(r.amount * (per[r.frequency] ?? 1) / Math.max(1, r.every)))
    }
    return [...m.entries()]
  }, [live, accounts.data]) // eslint-disable-line

  const confirm = async (r: RuleWithId, d: string) => {
    try { await write.batch.mutateAsync(await confirmOps(r, d)); toast(`Recorded ${r.name || r.payee || 'bill'} for ${dayLabel(d)}`) } catch (x) { toast(errorText(x)) }
  }
  const skip = async (r: RuleWithId, d: string) => {
    const adv = advanceOp(r, d)
    try { if (adv) await write.batch.mutateAsync([adv]); toast(`Skipped ${dayLabel(d)}`) } catch (x) { toast(errorText(x)) }
  }

  return (
    <>
      <div className="page-head"><h1>Bills & recurring</h1>{can && <button className="primary" onClick={() => setEdit('new')}><Icon name="plus" size={16} /> Recurring</button>}</div>
      {monthly.length > 0 && (
        <div className="grid" style={{ marginBottom: 16 }}>
          {monthly.map(([c, v]) => <Card key={c}><div className="stat-label">Fixed costs per month ({c})</div><div className="stat-value"><Money value={v} currency={c} /></div><div className="muted small">Active recurring expenses, averaged to a month</div></Card>)}
        </div>
      )}
      {due.length > 0 && can && (
        <Card title={<><Icon name="bell" size={16} /> Due now</>} className="mb accent">
          <ul className="list">
            {due.map(({ r, d }) => (
              <li key={r.id + d}>
                <Dot color={r.type === 'transfer' ? 'var(--transfer)' : cats.color(r.category_id)} />
                <div className="grow"><div>{r.name || r.payee || cats.name(r.category_id)}</div><div className="sub">{dayLabel(d)}{d < today ? ' · overdue' : ''}</div></div>
                <Money value={r.type === 'income' ? r.amount : -r.amount} currency={cur(r.account_id)} signed={r.type !== 'transfer'} />
                <button onClick={() => skip(r, d)}>Skip</button>
                <button className="primary" onClick={() => confirm(r, d)}>{r.type === 'income' ? 'Received' : 'Paid'}</button>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {rules.isPending ? <Card><Skeleton /></Card> : live.length === 0 ? (
        <Card><Empty icon="repeat" title="No recurring items" body="Add rent, subscriptions, salary or EMIs. LekhaMint reminds you when they are due, or records them for you."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Add a recurring item</button> : undefined} /></Card>
      ) : (
        <Card><ul className="list">{live.map(({ r, next }) => (
          <li key={r.id} className={`clickable ${r.paused || !next ? 'muted-row' : ''}`} onClick={() => setEdit(r)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') setEdit(r) }}>
            <Dot color={r.type === 'transfer' ? 'var(--transfer)' : cats.color(r.category_id)} />
            <div className="grow"><div>{r.name || r.payee || cats.name(r.category_id)}</div>
              <div className="sub">{describe(r)} · {r.paused ? 'paused' : next ? `next ${dayLabel(next)}` : 'ended'} · {r.mode === 'auto' ? 'automatic' : 'reminder'}
                {r.type === 'transfer' ? ` · ${accName(r.account_id)} → ${accName(r.to_account_id)}` : ` · ${cats.name(r.category_id)}`}</div></div>
            <Money value={r.type === 'income' ? r.amount : -r.amount} currency={cur(r.account_id)} signed={r.type !== 'transfer'} />
          </li>))}</ul></Card>
      )}
      <p className="muted small">Automatic items are recorded on their due date by whichever device opens LekhaMint first; the phone and the web never create the same one twice. <button className="link" onClick={() => go('transactions', { all: '1' })}>View transactions</button></p>
      {edit && <RuleForm rule={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} onConfirm={confirm} />}
    </>
  )
}

function RuleForm({ rule, onClose, onConfirm }: { rule?: WithId<RuleWithId>; onClose: () => void; onConfirm: (r: RuleWithId, d: string) => Promise<void> }) {
  const l = useLedger()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const write = useWrite()
  const can = useCanWrite()
  const live = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived)
  const [type, setType] = useState(rule?.type ?? 'expense')
  const [name, setName] = useState(rule?.name ?? '')
  const [accountId, setAccountId] = useState(rule?.account_id ?? live[0]?.id ?? '')
  useEffect(() => { if (!accountId && live[0]) setAccountId(live[0].id) }, [accountId, live])
  const [toAccountId, setToAccountId] = useState(rule?.to_account_id ?? '')
  useEffect(() => { if (type === 'transfer' && (!toAccountId || toAccountId === accountId)) setToAccountId(live.find((a) => a.id !== accountId)?.id ?? '') }, [type, accountId, toAccountId, live])
  const code = live.find((a) => a.id === accountId)?.currency ?? l.base_currency
  const toCode = live.find((a) => a.id === toAccountId)?.currency ?? code
  const [amount, setAmount] = useState(rule ? toPlain(rule.amount, code) : '')
  const [toAmount, setToAmount] = useState(rule?.to_amount ? toPlain(rule.to_amount, toCode) : '')
  const [categoryId, setCategoryId] = useState(rule?.category_id ?? '')
  const [frequency, setFrequency] = useState(rule?.frequency ?? 'monthly')
  const [every, setEvery] = useState(rule?.every ?? 1)
  const [start, setStart] = useState(rule?.start_date ?? isoDate())
  const [endMode, setEndMode] = useState<'never' | 'date' | 'count'>(rule?.end_date ? 'date' : rule?.max_occurrences ? 'count' : 'never')
  const [endDate, setEndDate] = useState(rule?.end_date ?? '')
  const [count, setCount] = useState(rule?.max_occurrences ?? 12)
  const [mode, setMode] = useState(rule?.mode ?? 'remind')
  const [paused, setPaused] = useState(rule?.paused ?? false)
  const [note, setNote] = useState(rule?.note ?? '')
  const [tagIds, setTagIds] = useState<string[]>(rule?.tag_ids ?? [])
  const [err, setErr] = useState('')
  const cross = type === 'transfer' && toCode !== code
  const preview = useMemo(() => {
    const sched = { start_date: start, frequency, every, next_date: null, paused: false, end_date: endMode === 'date' ? endDate || null : null, max_occurrences: endMode === 'count' ? count : null }
    return dueDates({ ...sched, next_date: initialNextDate(start, frequency, every, isoDate()) }, '9999-12-31', 3)
  }, [start, frequency, every, endMode, endDate, count])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const minor = parseMoney(amount, code)
    if (!minor || minor <= 0) return setErr('Enter the amount')
    if (!accountId) return setErr('Choose an account')
    if (type === 'transfer' && (!toAccountId || toAccountId === accountId)) return setErr('Choose a different destination account')
    const toMinor = type === 'transfer' ? (cross ? parseMoney(toAmount, toCode) : minor) : null
    if (type === 'transfer' && !toMinor) return setErr(`Enter the amount received in ${toCode}`)
    if (endMode === 'date' && (!endDate || endDate < start)) return setErr('The end date must be after the start')
    const scheduleChanged = !rule || rule.start_date !== start || rule.frequency !== frequency || rule.every !== every
    try {
      await write.save.mutateAsync({ entity: 'recurring_rules', id: rule?.id, data: {
        type, name: name.trim() || null, payee: name.trim() || null, account_id: accountId, amount: minor,
        to_account_id: type === 'transfer' ? toAccountId : null, to_amount: toMinor,
        category_id: type === 'transfer' ? null : categoryId || null, frequency, every, start_date: start, mode, paused,
        end_date: endMode === 'date' ? endDate : null, max_occurrences: endMode === 'count' ? count : null,
        note: note.trim() || null, tag_ids: tagIds,
        // A changed schedule restarts from the first occurrence on or after today (as on Android).
        ...(scheduleChanged ? { next_date: initialNextDate(start, frequency, every, isoDate()) } : {}),
      } satisfies Partial<RuleWithId> })
      toast(rule ? 'Saved' : mode === 'auto' ? 'Saved. It will be recorded on each due date.' : 'Saved. You will be reminded on each due date.')
      onClose()
    } catch (x) { setErr(errorText(x)) }
  }
  const nextDue = rule && !rule.paused ? nextDate(rule) : null
  return (
    <Modal wide title={rule ? 'Edit recurring' : 'New recurring'} onClose={onClose} footer={can ? <>
      {rule && <button type="button" className="danger" onClick={async () => {
        await write.remove.mutateAsync({ entity: 'recurring_rules', id: rule.id })
        toast('Recurring item deleted', { label: 'Undo', run: () => write.restore.mutate({ entity: 'recurring_rules', id: rule.id }) }); onClose()
      }}><Icon name="trash" size={16} /> Delete</button>}
      {rule && nextDue && nextDue <= addDays(isoDate(), 31) && <button type="button" onClick={async () => { await onConfirm(rule, nextDue); onClose() }} title="Record the next occurrence now">Record {dayLabel(nextDue)} now</button>}
      <span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="ruleform">Save</button></> : <button onClick={onClose}>Close</button>}>
      <form id="ruleform" onSubmit={submit}>
        <fieldset disabled={!can} className="plain">
          <Segmented label="Type" value={type} onChange={(t) => { setType(t); setCategoryId('') }} options={[['expense', 'Expense'], ['income', 'Income'], ['transfer', 'Transfer']]} />
          <div className="two">
            <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} placeholder={type === 'transfer' ? 'SIP, savings…' : 'Rent, Netflix, Salary…'} /></Field>
            <Field label={`Amount (${code})`} hint="Reminders let you adjust it each time"><input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required /></Field>
          </div>
          <div className="two">
            <Field label={type === 'transfer' ? 'From account' : 'Account'}><select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{live.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></Field>
            {type === 'transfer'
              ? <Field label="To account"><select value={toAccountId} onChange={(e) => setToAccountId(e.target.value)}>{live.filter((a) => a.id !== accountId).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></Field>
              : <Field label="Category"><CategorySelect value={categoryId} onChange={setCategoryId} cats={cats} kind={type === 'expense' ? 'expense' : 'income'} /></Field>}
          </div>
          {cross && <Field label={`Amount received (${toCode})`}><input inputMode="decimal" value={toAmount} onChange={(e) => setToAmount(e.target.value)} /></Field>}
          <div className="two">
            <Field label="Repeats"><div className="row nowrap">
              <span>Every</span><input type="number" min={1} max={366} value={every} onChange={(e) => setEvery(Math.min(366, Math.max(1, Number(e.target.value) || 1)))} style={{ width: 80 }} />
              <select value={frequency} onChange={(e) => setFrequency(e.target.value)}>{Object.entries(FREQ).map(([k, v]) => <option key={k} value={k}>{v}{every > 1 ? 's' : ''}</option>)}</select></div></Field>
            <Field label="Starts" hint="Day 29–31 falls on the last day of shorter months"><input type="date" value={start} onChange={(e) => setStart(e.target.value)} required /></Field>
          </div>
          <div className="two">
            <Field label="Ends">
              <select value={endMode} onChange={(e) => setEndMode(e.target.value as typeof endMode)}><option value="never">Never</option><option value="date">On a date</option><option value="count">After a number of times</option></select>
            </Field>
            {endMode === 'date' && <Field label="Last date"><input type="date" value={endDate} min={start} onChange={(e) => setEndDate(e.target.value)} /></Field>}
            {endMode === 'count' && <Field label="Times" hint="Counted from the start date, e.g. 12 EMIs"><input type="number" min={1} value={count} onChange={(e) => setCount(Math.max(1, Number(e.target.value) || 1))} /></Field>}
          </div>
          <p className="muted small">{preview.length ? <>Next: {preview.map(dayLabel).join(', ')}{preview.length === 3 ? '…' : ''}</> : 'No more occurrences with these settings.'}</p>
          <Field label="When it is due"><Segmented label="Mode" value={mode} onChange={setMode} options={[['remind', 'Remind me'], ['auto', 'Record automatically']]} /></Field>
          <div className="two">
            <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
            <Field label="Tags"><TagInput value={tagIds} onChange={setTagIds} /></Field>
          </div>
          {rule && <Check checked={paused} onChange={setPaused}>Paused</Check>}
          {err && <p className="error" role="alert">{err}</p>}
        </fieldset>
      </form>
    </Modal>
  )
}
