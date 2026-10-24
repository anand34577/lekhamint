import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type BatchOp, type Debt, type Transaction } from '../lib/api'
import { useAccounts, useCanWrite, useDebts, useLedger, useWrite, type WithId } from '../lib/data'
import { parseMoney, toPlain } from '../lib/money'
import { dayLabel, isoDate, uuidv7 } from '../lib/util'
import { Avatar, Card, Check, CurrencySelect, Empty, Field, Modal, Money, Segmented, Skeleton, errorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'

/**
 * Lent & borrowed (docs/02 §9). Money that leaves or enters an account is a
 * transaction linked to the debt (debt_id), so balances stay right while
 * reports ignore it. Repayments are linked the same way; the debt shows what
 * is still outstanding.
 */
type Movement = { id: string; data: Transaction }

/** Outstanding = amount − repayments. A lent debt is repaid by money coming in, a borrowed one by money going out. */
export function outstanding(d: Debt, moves: Movement[]): number {
  const repaidType = d.direction === 'lent' ? 'income' : 'expense'
  return d.amount - moves.filter((m) => m.data.type === repaidType).reduce((a, m) => a + m.data.amount, 0)
}

function useDebtMoves() {
  const l = useLedger()
  // Debt movements are few: one filtered, paged query covers them all.
  return useQuery({
    queryKey: [l.id, 'transactions', 'debts'],
    queryFn: async () => {
      const m = new Map<string, Movement[]>()
      for (let cursor = '', more = true; more;) {
        const page = await api.transactions(l.id, { debt_id: 'any', limit: '500', ...(cursor ? { cursor } : {}) })
        for (const r of page.transactions) {
          const id = (r.data as Transaction & { debt_id?: string | null }).debt_id
          if (id) m.set(id, [...(m.get(id) ?? []), { id: r.id, data: r.data }])
        }
        cursor = page.next_cursor ?? ''; more = !!cursor
      }
      for (const list of m.values()) list.sort((a, b) => a.data.local_date.localeCompare(b.data.local_date))
      return m
    },
  })
}

export function Debts(_: PageProps) {
  const debts = useDebts()
  const moves = useDebtMoves()
  const can = useCanWrite()
  const [edit, setEdit] = useState<WithId<Debt> | 'new' | null>(null)
  const [repay, setRepay] = useState<WithId<Debt> | null>(null)
  const [showClosed, setShowClosed] = useState(false)
  const rows = useMemo(() => (debts.data ?? []).filter((d) => !d.deleted)
    .map((d) => ({ d, left: outstanding(d, moves.data?.get(d.id) ?? []) }))
    .sort((a, b) => Number(a.d.closed) - Number(b.d.closed) || (a.d.due_date ?? '9').localeCompare(b.d.due_date ?? '9') || b.d.created_at - a.d.created_at), [debts.data, moves.data])
  const open = rows.filter((x) => !x.d.closed)
  const sum = (dir: string) => {
    const m = new Map<string, number>()
    for (const { d, left } of open) if (d.direction === dir && left > 0) m.set(d.currency, (m.get(d.currency) ?? 0) + left)
    return [...m.entries()]
  }
  const owed = sum('lent'), owe = sum('borrowed')
  const today = isoDate()
  const shown = rows.filter((x) => showClosed || !x.d.closed)

  return (
    <>
      <div className="page-head"><h1>Lent & borrowed</h1>{can && <button className="primary" onClick={() => setEdit('new')}><Icon name="plus" size={16} /> Add</button>}</div>
      {rows.length > 0 && (
        <div className="grid" style={{ marginBottom: 16 }}>
          <Card><div className="stat-label">Owed to you</div>{owed.length ? owed.map(([c, v]) => <div key={c} className="stat-value pos-text"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">—</div>}</Card>
          <Card><div className="stat-label">You owe</div>{owe.length ? owe.map(([c, v]) => <div key={c} className="stat-value neg-text"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">—</div>}</Card>
        </div>
      )}
      {debts.isPending ? <Card><Skeleton /></Card> : rows.length === 0 ? (
        <Card><Empty icon="hand" title="Nothing owed" body="Keep track of money you lent to friends or borrowed, record repayments as they come, and settle up later."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Record a loan</button> : undefined} /></Card>
      ) : (
        <Card action={rows.some((x) => x.d.closed) ? <Check checked={showClosed} onChange={setShowClosed}>Show settled</Check> : undefined} title={`${open.length} open`}>
          <ul className="list">
            {shown.map(({ d, left }) => {
              const lent = d.direction === 'lent'
              const overdue = !d.closed && d.due_date && d.due_date < today
              return (
                <li key={d.id} className={`clickable ${d.closed ? 'muted-row' : ''}`} onClick={() => setEdit(d)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') setEdit(d) }}>
                  <Avatar name={d.person} color={lent ? 'var(--pos)' : 'var(--neg)'} />
                  <div className="grow">
                    <div className={d.closed ? 'strike' : ''}>{d.person}</div>
                    <div className="sub">{lent ? 'Owes you' : 'You owe'}{left !== d.amount && !d.closed ? <> · <Money value={left} currency={d.currency} /> of <Money value={d.amount} currency={d.currency} /> left</> : ''}
                      {d.due_date && !d.closed ? <span className={overdue ? 'error' : ''}> · due {dayLabel(d.due_date)}</span> : ''}{d.closed ? ' · settled' : ''}{d.note ? ` · ${d.note}` : ''}</div>
                  </div>
                  {!d.closed && can && left > 0 && <button onClick={(e) => { e.stopPropagation(); setRepay(d) }}>{lent ? 'Got paid' : 'Paid back'}</button>}
                  <Money value={d.closed ? d.amount : left} currency={d.currency} className={d.closed ? 'muted' : lent ? 'pos' : 'neg'} />
                </li>
              )
            })}
          </ul>
        </Card>
      )}
      <p className="muted small">Loans and repayments move money between your accounts and the other person, so they change balances but never count as spending or income.</p>
      {edit && <DebtForm debt={edit === 'new' ? undefined : edit} moves={edit === 'new' ? [] : moves.data?.get(edit.id) ?? []} onClose={() => setEdit(null)} onRepay={(d) => { setEdit(null); setRepay(d) }} />}
      {repay && <RepayForm debt={repay} left={outstanding(repay, moves.data?.get(repay.id) ?? [])} onClose={() => setRepay(null)} />}
    </>
  )
}

function txData(moneyIn: boolean, accountId: string, amount: number, debtId: string, payee: string, date: string, note: string | null) {
  const at = new Date(date + 'T12:00:00').getTime()
  return {
    type: moneyIn ? 'income' : 'expense', account_id: accountId, amount, debt_id: debtId, payee, note, category_id: null,
    local_date: date, occurred_at: at, tz_offset_min: -new Date(at).getTimezoneOffset(), status: 'cleared', tag_ids: [], created_at: Date.now(),
  }
}

function DebtForm({ debt, moves, onClose, onRepay }: { debt?: WithId<Debt>; moves: Movement[]; onClose: () => void; onRepay: (d: WithId<Debt>) => void }) {
  const l = useLedger()
  const accounts = useAccounts()
  const write = useWrite()
  const can = useCanWrite()
  const live = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived)
  const [direction, setDirection] = useState<Debt['direction']>(debt?.direction ?? 'lent')
  const [person, setPerson] = useState(debt?.person ?? '')
  const [cur, setCur] = useState(debt?.currency ?? l.base_currency)
  const [amount, setAmount] = useState(debt ? toPlain(debt.amount, debt.currency) : '')
  const [due, setDue] = useState(debt?.due_date ?? '')
  const [note, setNote] = useState(debt?.note ?? '')
  const [date, setDate] = useState(isoDate())
  const matching = live.filter((a) => a.currency === cur)
  const [accountId, setAccountId] = useState('')
  const [link, setLink] = useState(!debt)
  const [err, setErr] = useState('')
  const acct = matching.find((a) => a.id === accountId) ?? matching[0]
  const left = debt ? outstanding(debt, moves) : 0
  const accName = (id: string) => accounts.data?.find((a) => a.id === id)?.name ?? '?'

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const minor = parseMoney(amount, cur)
    if (!person.trim()) return setErr('Who is it with?')
    if (!minor || minor <= 0) return setErr('Enter the amount')
    if (debt && minor < debt.amount - left) return setErr('The amount cannot be less than what has already been repaid')
    try {
      const id = debt?.id ?? uuidv7()
      const data = { person: person.trim(), direction, amount: minor, currency: cur, due_date: due || null, note: note.trim() || null }
      const ops: BatchOp[] = [debt ? { op: 'update', entity: 'debts', id, data } : { op: 'create', entity: 'debts', id, data: { ...data, closed: false, created_at: Date.now() } }]
      // Lending takes money out of an account; borrowing puts money in.
      if (!debt && link && acct) ops.push({ op: 'create', entity: 'transactions', id: uuidv7(), data: txData(direction === 'borrowed', acct.id, minor, id, person.trim(), date, direction === 'lent' ? 'Lent' : 'Borrowed') })
      await write.batch.mutateAsync(ops)
      toast(debt ? 'Saved' : direction === 'lent' ? `Recorded: ${person.trim()} owes you` : `Recorded: you owe ${person.trim()}`)
      onClose()
    } catch (x) { setErr(errorText(x)) }
  }
  const settle = async () => {
    if (!debt) return
    await write.save.mutateAsync({ entity: 'debts', id: debt.id, data: { closed: !debt.closed } })
    toast(debt.closed ? 'Reopened' : `Settled with ${debt.person}`, debt.closed ? undefined : { label: 'Undo', run: () => write.save.mutate({ entity: 'debts', id: debt.id, data: { closed: false } }) })
    onClose()
  }
  return (
    <Modal wide title={debt ? debt.person : 'Lent or borrowed'} onClose={onClose} footer={can ? <>
      {debt && <button type="button" className="danger" onClick={async () => {
        await write.remove.mutateAsync({ entity: 'debts', id: debt.id })
        toast('Deleted. Linked transactions stay in your accounts.', { label: 'Undo', run: () => write.restore.mutate({ entity: 'debts', id: debt.id }) }); onClose()
      }}><Icon name="trash" size={16} /> Delete</button>}
      {debt && <button type="button" onClick={settle}>{debt.closed ? 'Reopen' : 'Mark settled'}</button>}
      {debt && !debt.closed && left > 0 && <button type="button" onClick={() => onRepay(debt)}>{debt.direction === 'lent' ? 'Record a repayment' : 'Record a payment'}</button>}
      <span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="debtform">Save</button></> : <button onClick={onClose}>Close</button>}>
      <form id="debtform" onSubmit={submit}>
        <fieldset disabled={!can} className="plain">
          <Segmented label="Direction" value={direction} onChange={setDirection} options={[['lent', 'I lent'], ['borrowed', 'I borrowed']]} />
          <div className="two">
            <Field label={direction === 'lent' ? 'To whom' : 'From whom'}><input value={person} onChange={(e) => setPerson(e.target.value)} autoFocus={!debt} placeholder="Name" /></Field>
            <Field label="Amount"><input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          </div>
          <div className="two">
            <Field label="Currency"><CurrencySelect value={cur} onChange={setCur} disabled={!!debt} /></Field>
            <Field label="Due (optional)"><input type="date" value={due} onChange={(e) => setDue(e.target.value)} /></Field>
          </div>
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What it was for" /></Field>
          {!debt && (matching.length > 0 ? (
            <div className="subtle-box">
              <Check checked={link} onChange={setLink}>{direction === 'lent' ? 'The money left one of my accounts' : 'The money came into one of my accounts'}</Check>
              {link && <div className="two" style={{ marginTop: 8 }}>
                <Field label="Account"><select value={acct?.id ?? ''} onChange={(e) => setAccountId(e.target.value)}>{matching.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></Field>
                <Field label="On"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
              </div>}
            </div>
          ) : <p className="muted small">No {cur} account to take the money from, so only the loan is recorded.</p>)}
          {debt && (
            <>
              <h3 className="section-title">History</h3>
              {moves.length === 0 ? <p className="muted small">No money movements are linked to this loan.</p> : (
                <ul className="list">
                  {moves.map((m) => (
                    <li key={m.id}><div className="grow"><div>{m.data.note ?? (m.data.type === (debt.direction === 'lent' ? 'income' : 'expense') ? 'Repayment' : debt.direction === 'lent' ? 'Lent' : 'Borrowed')}</div>
                      <div className="sub">{dayLabel(m.data.local_date)} · {accName(m.data.account_id)}</div></div>
                      <Money value={m.data.type === 'income' ? m.data.amount : -m.data.amount} currency={debt.currency} signed /></li>
                  ))}
                </ul>
              )}
              <p className="small">Outstanding: <strong><Money value={left} currency={debt.currency} /></strong></p>
            </>
          )}
          {err && <p className="error" role="alert">{err}</p>}
        </fieldset>
      </form>
    </Modal>
  )
}

function RepayForm({ debt, left, onClose }: { debt: WithId<Debt>; left: number; onClose: () => void }) {
  const accounts = useAccounts()
  const write = useWrite()
  const matching = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === debt.currency)
  const [amount, setAmount] = useState(toPlain(left, debt.currency))
  const [accountId, setAccountId] = useState(matching[0]?.id ?? '')
  const [date, setDate] = useState(isoDate())
  const [err, setErr] = useState('')
  const lent = debt.direction === 'lent'
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const minor = parseMoney(amount, debt.currency)
    if (!minor || minor <= 0) return setErr('Enter the amount')
    if (minor > left) return setErr('That is more than what is outstanding')
    if (!accountId) return setErr(`Add a ${debt.currency} account first`)
    try {
      const ops: BatchOp[] = [{ op: 'create', entity: 'transactions', id: uuidv7(), data: txData(lent, accountId, minor, debt.id, debt.person, date, 'Repayment') }]
      // Fully repaid: settle it.
      if (minor === left) ops.push({ op: 'update', entity: 'debts', id: debt.id, data: { closed: true } })
      await write.batch.mutateAsync(ops)
      toast(minor === left ? `All settled with ${debt.person}` : 'Repayment recorded')
      onClose()
    } catch (x) { setErr(errorText(x)) }
  }
  return (
    <Modal title={lent ? `${debt.person} paid you back` : `You paid ${debt.person} back`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="repayform">Record</button></>}>
      <form id="repayform" onSubmit={submit}>
        <p className="muted">Outstanding: <Money value={left} currency={debt.currency} /></p>
        <div className="two">
          <Field label="Amount"><input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
          <Field label="On"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        </div>
        <Field label={lent ? 'Into account' : 'From account'}><select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{matching.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></Field>
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}
