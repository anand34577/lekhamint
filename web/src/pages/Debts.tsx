import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type BatchOp, type Debt } from '../lib/api'
import { useAccounts, useBalances, useCanWrite, useCategories, useDebts, useLedger, useStableId, useWrite, type WithId } from '../lib/data'
import { allocate, debtStart, interestTotal, isInterest, outstanding, people, personKey, repaid, statement, type DebtRec, type Move, type PersonSummary } from '../lib/debts'
import { createOp, interestExpenseCategory, interestIncomeCategory, linkedTxData } from '../lib/links'
import { formatMoney, parseMoney, toPlain } from '../lib/money'
import { dayLabel, isoDate, uuidv7 } from '../lib/util'
import { Avatar, Card, Check, Confirm, CurrencySelect, Empty, Field, Modal, Money, Segmented, Skeleton, saveErrorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, DateField, NumInput } from '../controls'

/**
 * Lent & borrowed (docs/02 §9). Money that leaves or enters an account is a
 * transaction linked to the debt (debt_id), so balances stay right while
 * reports ignore it; interest is linked too but counts as income or cost.
 * People are grouped by name, with a running statement for each.
 */

export function useDebtMoves() {
  const l = useLedger()
  return useQuery({
    queryKey: [l.id, 'transactions', 'debts'],
    queryFn: async () => {
      const m = new Map<string, Move[]>()
      for (let cursor = '', more = true; more;) {
        const page = await api.transactions(l.id, { debt_id: 'any', limit: '500', ...(cursor ? { cursor } : {}) })
        for (const r of page.transactions) {
          const id = r.data.debt_id
          if (id) m.set(id, [...(m.get(id) ?? []), { ...r.data, id: r.id }])
        }
        cursor = page.next_cursor ?? ''; more = !!cursor
      }
      for (const list of m.values()) list.sort((a, b) => a.local_date.localeCompare(b.local_date) || a.occurred_at - b.occurred_at)
      return m
    },
  })
}

export function Debts({ go }: PageProps) {
  const debts = useDebts()
  const moves = useDebtMoves()
  const can = useCanWrite()
  const today = isoDate()
  const [edit, setEdit] = useState<WithId<Debt> | { person?: string; direction?: Debt['direction']; currency?: string } | null>(null)
  const [person, setPerson] = useState<string | null>(null)
  const [view, setView] = useState<'people' | 'loans'>('people')
  const [showSettled, setShowSettled] = useState(false)
  const live = useMemo(() => (debts.data ?? []).filter((d) => !d.deleted), [debts.data])
  const mv = moves.data ?? new Map<string, Move[]>()
  const list = useMemo(() => people(live, mv, today), [live, mv, today])
  const totals = useMemo(() => {
    const owed = new Map<string, number>(), owe = new Map<string, number>()
    for (const p of list) {
      if (p.net > 0) owed.set(p.currency, (owed.get(p.currency) ?? 0) + p.net)
      if (p.net < 0) owe.set(p.currency, (owe.get(p.currency) ?? 0) - p.net)
    }
    return { owed: [...owed], owe: [...owe], overdue: list.reduce((a, p) => a + p.overdue, 0) }
  }, [list])
  const shownPeople = list.filter((p) => showSettled || p.open > 0)
  const loans = live.map((d) => ({ d, left: outstanding(d, mv.get(d.id) ?? []) }))
    .filter((x) => showSettled || (!x.d.closed && x.left !== 0))
    .sort((a, b) => Number(a.d.closed) - Number(b.d.closed) || (a.d.due_date ?? '9').localeCompare(b.d.due_date ?? '9') || b.d.created_at - a.d.created_at)
  const sel = person ? list.find((p) => p.key === person) : undefined
  const hasSettled = list.some((p) => p.open === 0) || live.some((d) => d.closed)

  return (
    <>
      <div className="page-head"><h1>Lent & borrowed</h1>{can && <button className="primary" onClick={() => setEdit({})}><Icon name="plus" size={16} /> Add</button>}</div>
      {live.length > 0 && (
        <div className="grid stats" style={{ marginBottom: 16 }}>
          <Card className="stat-card"><div className="stat-label">Others owe you</div>{totals.owed.length ? totals.owed.map(([c, v]) => <div key={c} className="stat-value pos-text"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">Nothing</div>}</Card>
          <Card className="stat-card"><div className="stat-label">You owe others</div>{totals.owe.length ? totals.owe.map(([c, v]) => <div key={c} className="stat-value neg-text"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">Nothing</div>}</Card>
          <Card className="stat-card"><div className="stat-label">Past the due date</div><div className={`stat-value ${totals.overdue ? 'neg-text' : 'muted'}`}>{totals.overdue ? `${totals.overdue} ${totals.overdue === 1 ? 'debt' : 'debts'}` : 'None'}</div></Card>
        </div>
      )}
      {debts.isPending || moves.isPending ? <Card><Skeleton rows={4} /></Card> : live.length === 0 ? (
        <Card><Empty icon="hand" title="Nobody owes anything" body="Record money you lend to friends and family, or borrow from them. Add repayments as they come in, partly or in full, and LekhaMint keeps a clear statement for each person."
          action={can ? <button className="primary" onClick={() => setEdit({})}>Record money lent or borrowed</button> : undefined} /></Card>
      ) : (
        <Card title={<Segmented label="View" value={view} onChange={setView} options={[['people', 'By person'], ['loans', 'Each loan']]} />}
          action={hasSettled ? <Check checked={showSettled} onChange={setShowSettled}>Show settled</Check> : undefined}>
          {view === 'people' ? (
            shownPeople.length === 0 ? <p className="muted pad">Everything is settled. Tick “Show settled” to see past loans.</p> : (
              <ul className="list">
                {shownPeople.map((p) => (
                  <li key={p.key} className="clickable" onClick={() => setPerson(p.key)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') setPerson(p.key) }}>
                    <Avatar name={p.name} color={p.net > 0 ? 'var(--pos)' : p.net < 0 ? 'var(--neg)' : 'var(--muted)'} />
                    <div className="grow">
                      <div>{p.name}</div>
                      <div className="sub">{p.net > 0 ? 'Owes you' : p.net < 0 ? 'You owe' : 'Settled'}{p.open > 1 ? ` · ${p.open} open` : ''}
                        {p.nextDue && p.open > 0 ? <span className={p.overdue ? 'error' : ''}> · {p.overdue ? 'overdue since' : 'due'} {dayLabel(p.nextDue)}</span> : ''}
                        {p.lastActivity ? ` · last activity ${dayLabel(p.lastActivity)}` : ''}</div>
                    </div>
                    <Money value={Math.abs(p.net)} currency={p.currency} className={p.net > 0 ? 'pos' : p.net < 0 ? 'neg' : 'muted'} />
                  </li>
                ))}
              </ul>
            )
          ) : loans.length === 0 ? <p className="muted pad">No open loans.</p> : (
            <ul className="list">
              {loans.map(({ d, left }) => {
                const lent = d.direction === 'lent'
                const overdue = !d.closed && d.due_date && d.due_date < today
                return (
                  <li key={d.id} className={`clickable ${d.closed ? 'muted-row' : ''}`} onClick={() => setEdit(d)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') setEdit(d) }}>
                    <Avatar name={d.person} color={lent ? 'var(--pos)' : 'var(--neg)'} />
                    <div className="grow">
                      <div className={d.closed ? 'strike' : ''}>{d.person}{d.note ? <span className="muted"> · {d.note}</span> : ''}</div>
                      <div className="sub">{lent ? 'Lent' : 'Borrowed'} <Money value={d.amount} currency={d.currency} />
                        {left < 0 ? <span className="warn-text"> · overpaid by <Money value={-left} currency={d.currency} /></span> : left !== d.amount && !d.closed ? <> · <Money value={left} currency={d.currency} /> left</> : ''}
                        {d.due_date && !d.closed ? <span className={overdue ? 'error' : ''}> · due {dayLabel(d.due_date)}</span> : ''}{d.closed ? ' · settled' : ''}</div>
                    </div>
                    <Money value={d.closed ? d.amount : Math.abs(left)} currency={d.currency} className={d.closed ? 'muted' : lent ? 'pos' : 'neg'} />
                  </li>
                )
              })}
            </ul>
          )}
        </Card>
      )}
      <p className="muted small">Money you lend stays yours: it counts in your net worth as owed to you, never as spending. Borrowed money counts as owed, never as income. Interest you record is real income or cost and shows in reports.</p>
      {edit && <DebtForm debt={'id' in edit ? edit as WithId<Debt> : undefined} preset={'id' in edit ? undefined : edit} moves={'id' in edit ? mv.get((edit as WithId<Debt>).id) ?? [] : []}
        knownPeople={list.map((p) => p.name)} onClose={() => setEdit(null)} />}
      {sel && <PersonSheet p={sel} moves={mv} onClose={() => setPerson(null)} onAdd={(dir) => { setPerson(null); setEdit({ person: sel.name, direction: dir, currency: sel.currency }) }}
        onEditDebt={(d) => { setPerson(null); setEdit(d) }} onPrint={() => go('print' as never, { kind: 'person', person: sel.name, currency: sel.currency })} />}
    </>
  )
}

function useAccountChoices(currency: string) {
  const accounts = useAccounts()
  const balances = useBalances()
  const list = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === currency && a.type !== 'loan' && a.type !== 'deposit')
  const bal = (id: string) => balances.data?.find((b) => b.account_id === id)?.balance
  return { list, bal, all: accounts.data ?? [] }
}

function DebtForm({ debt, preset, moves, knownPeople, onClose }: { debt?: WithId<Debt>; preset?: { person?: string; direction?: Debt['direction']; currency?: string }; moves: Move[]; knownPeople: string[]; onClose: () => void }) {
  const l = useLedger()
  const write = useWrite()
  const can = useCanWrite()
  const id = useStableId(debt?.id)
  const moveId = useStableId()
  const [direction, setDirection] = useState<Debt['direction']>(debt?.direction ?? preset?.direction ?? 'lent')
  const [person, setPerson] = useState(debt?.person ?? preset?.person ?? '')
  const [cur, setCur] = useState(debt?.currency ?? preset?.currency ?? l.base_currency)
  const [amount, setAmount] = useState(debt ? toPlain(debt.amount, debt.currency) : '')
  const [due, setDue] = useState(debt?.due_date ?? '')
  const [note, setNote] = useState(debt?.note ?? '')
  const [date, setDate] = useState(isoDate())
  const choices = useAccountChoices(cur)
  const [accountId, setAccountId] = useState('')
  const [link, setLink] = useState(!debt)
  const [err, setErr] = useState('')
  const [repay, setRepay] = useState(false)
  const [confirm, setConfirm] = useState<'settle' | 'delete' | null>(null)
  const acct = choices.list.find((a) => a.id === accountId) ?? choices.list[0]
  const left = debt ? outstanding(debt, moves) : 0
  const paidBack = debt ? repaid(debt, moves) : 0
  const accName = (aid: string) => choices.all.find((a) => a.id === aid)?.name ?? 'a deleted account'
  const minor = parseMoney(amount, cur)
  const balance = acct ? choices.bal(acct.id) : undefined
  const lowBalance = !debt && link && direction === 'lent' && acct && minor && balance !== undefined && balance - minor < 0
  const sameName = !debt && person.trim() && knownPeople.find((n) => personKey(n) === personKey(person) && n !== person.trim())

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (!person.trim()) return setErr(direction === 'lent' ? 'Who did you lend the money to?' : 'Who did you borrow from?')
    if (!minor || minor <= 0) return setErr('Enter the amount, more than zero')
    if (debt && minor < paidBack) return setErr(`${formatMoney(paidBack, cur)} has already been repaid, so the amount cannot be less than that. To correct a repayment, open it from the history below and edit or delete it.`)
    if (due && !debt && due < date) return setErr('The due date is before the date of the loan. Pick a later date or leave it empty.')
    try {
      const data = { person: person.trim(), direction, amount: minor, currency: cur, due_date: due || null, note: note.trim() || null }
      const ops: BatchOp[] = [debt ? { op: 'update', entity: 'debts', id, data } : createOp('debts', id, { ...data, closed: false, created_at: Date.now() })]
      // Lending takes money out of an account; borrowing puts money in.
      if (!debt && link && acct) ops.push(createOp('transactions', moveId, linkedTxData({
        type: direction === 'borrowed' ? 'income' : 'expense', account_id: acct.id, amount: minor, payee: person.trim(),
        note: direction === 'lent' ? 'Lent' : 'Borrowed', date, debt_id: id, part: 'principal',
      })))
      await write.batch.mutateAsync(ops)
      // A debt fully repaid by editing its amount down is settled.
      if (debt && minor === paidBack && !debt.closed) await write.save.mutateAsync({ entity: 'debts', id, data: { closed: true } })
      toast(debt ? 'Saved' : direction === 'lent' ? `Recorded: ${person.trim()} owes you ${formatMoney(minor, cur)}` : `Recorded: you owe ${person.trim()} ${formatMoney(minor, cur)}`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }
  const settle = async () => {
    if (!debt) return
    await write.save.mutateAsync({ entity: 'debts', id: debt.id, data: { closed: !debt.closed } })
    toast(debt.closed ? 'Reopened' : `Settled with ${debt.person}`, debt.closed ? undefined : { label: 'Undo', run: () => write.save.mutate({ entity: 'debts', id: debt.id, data: { closed: false } }) })
    onClose()
  }
  if (repay && debt) return <RepayForm debts={[debt]} moves={new Map([[debt.id, moves]])} name={debt.person} currency={debt.currency} direction={debt.direction} onClose={onClose} />
  return (
    <Modal wide title={debt ? `${debt.direction === 'lent' ? 'Lent to' : 'Borrowed from'} ${debt.person}` : 'Lent or borrowed'} onClose={onClose} footer={can ? <>
      {debt && <button type="button" className="danger" onClick={() => setConfirm('delete')}><Icon name="trash" size={16} /> Delete</button>}
      {debt && <button type="button" onClick={() => (debt.closed || left === 0 ? settle() : setConfirm('settle'))}>{debt.closed ? 'Reopen' : 'Mark settled'}</button>}
      {debt && !debt.closed && left > 0 && <button type="button" onClick={() => setRepay(true)}>{debt.direction === 'lent' ? 'Record repayment' : 'Record payment'}</button>}
      <span className="spacer" /><button type="button" onClick={onClose}>Cancel</button>
      <button className="primary" form="debtform" disabled={write.batch.isPending}>{write.batch.isPending ? 'Saving…' : 'Save'}</button></> : <button onClick={onClose}>Close</button>}>
      <form id="debtform" onSubmit={submit}>
        <fieldset disabled={!can} className="plain">
          <Segmented label="Direction" value={direction} onChange={setDirection} options={[['lent', 'I lent money'], ['borrowed', 'I borrowed money']]} />
          <div className="two">
            <Field label={direction === 'lent' ? 'To whom' : 'From whom'} hint={sameName ? `Saved as “${sameName}” before; use the same spelling to keep one statement.` : undefined}>
              <input value={person} onChange={(e) => setPerson(e.target.value)} autoFocus={!debt && !preset?.person} placeholder="Name" list="debt-people" maxLength={100} />
              <datalist id="debt-people">{knownPeople.map((n) => <option key={n} value={n} />)}</datalist>
            </Field>
            <Field label="Amount"><NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0" autoFocus={!!preset?.person} /></Field>
          </div>
          <div className="two">
            <Field label="Currency" hint={debt ? 'Fixed once recorded' : undefined}><CurrencySelect value={cur} onChange={setCur} disabled={!!debt} /></Field>
            <Field label="Due back by (optional)"><DateField value={due} onChange={(e) => setDue(e.target.value)} /></Field>
          </div>
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What it was for, any agreement" maxLength={2000} /></Field>
          {!debt && (choices.list.length > 0 ? (
            <div className="subtle-box">
              <Check checked={link} onChange={setLink}>{direction === 'lent' ? 'The money left one of my accounts' : 'The money came into one of my accounts'}</Check>
              {link && <div className="two" style={{ marginTop: 8 }}>
                <Field label="Account" hint={balance !== undefined ? <>Balance <Money value={balance} currency={cur} /></> : undefined}>
                  <Select value={acct?.id ?? ''} onChange={(e) => setAccountId(e.target.value)}>{choices.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>
                </Field>
                <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
              </div>}
              {!link && <p className="muted small">Only the debt is recorded; no account balance changes. Use this for money that moved before you started using LekhaMint.</p>}
              {lowBalance && <p className="warn-text small"><Icon name="alert" size={14} /> This takes {acct!.name} below zero, to {formatMoney(balance! - minor!, cur)}. Check the account, or save anyway if it is right.</p>}
            </div>
          ) : <p className="muted small">You have no {cur} account, so only the debt is recorded and no balance changes.</p>)}
          {debt && (
            <>
              <h3 className="section-title">History</h3>
              {moves.length === 0 ? <p className="muted small">No money movements are linked to this debt.</p> : (
                <ul className="list">
                  {moves.map((m) => (
                    <li key={m.id}><div className="grow"><div>{isInterest(m) ? (m.part === 'fee' ? 'Fee' : 'Interest') : m.note ?? (m.type === (debt.direction === 'lent' ? 'income' : 'expense') ? 'Repayment' : debt.direction === 'lent' ? 'Lent' : 'Borrowed')}</div>
                      <div className="sub">{dayLabel(m.local_date)} · {accName(m.account_id)}</div></div>
                      <Money value={m.type === 'income' ? m.amount : -m.amount} currency={debt.currency} signed /></li>
                  ))}
                </ul>
              )}
              <p className="small">Repaid <strong><Money value={paidBack} currency={debt.currency} /></strong> of <Money value={debt.amount} currency={debt.currency} />
                {left > 0 ? <> · outstanding <strong><Money value={left} currency={debt.currency} /></strong></> : left < 0 ? <span className="warn-text"> · overpaid by <Money value={-left} currency={debt.currency} />{debt.direction === 'lent' ? '; you owe them that back' : '; they owe you that back'}</span> : ' · fully repaid'}
                {interestTotal(moves) > 0 && <> · interest <Money value={interestTotal(moves)} currency={debt.currency} /></>}</p>
            </>
          )}
          {err && <p className="error" role="alert">{err}</p>}
        </fieldset>
      </form>
      {confirm === 'settle' && debt && (
        <Confirm title="Mark as settled?" confirm="Mark settled" onClose={() => setConfirm(null)} onConfirm={settle}
          body={<p><Money value={left} currency={debt.currency} /> is still outstanding. Marking this settled writes that amount off: it stops counting {debt.direction === 'lent' ? 'as owed to you' : 'as what you owe'}, and no money moves. If money was paid, record the repayment instead.</p>} />
      )}
      {confirm === 'delete' && debt && <DeleteDebt debt={debt} moves={moves} onClose={() => setConfirm(null)} onDone={onClose} />}
    </Modal>
  )
}

function DeleteDebt({ debt, moves, onClose, onDone }: { debt: WithId<Debt>; moves: Move[]; onClose: () => void; onDone: () => void }) {
  const write = useWrite()
  const [alsoMoves, setAlsoMoves] = useState(false)
  return (
    <Confirm title={`Delete this debt with ${debt.person}?`} confirm="Delete" danger onClose={onClose} onConfirm={async () => {
      const ops: BatchOp[] = [{ op: 'delete', entity: 'debts', id: debt.id }, ...(alsoMoves ? moves.map((m) => ({ op: 'delete' as const, entity: 'transactions', id: m.id })) : [])]
      await write.batch.mutateAsync(ops)
      toast(alsoMoves ? 'Deleted with its money movements. Find them in the Trash.' : 'Deleted. The money movements stay in your accounts.', {
        label: 'Undo', run: () => write.batch.mutate(ops.map((o) => ({ op: 'restore' as const, entity: o.entity, id: o.id }))),
      })
      onDone()
    }} body={<>
      <p>The debt goes to the Trash; you can restore it from there.</p>
      {moves.length > 0 && <Check checked={alsoMoves} onChange={setAlsoMoves}>Also delete its {moves.length} money {moves.length === 1 ? 'movement' : 'movements'}, which puts the account balances back as they were</Check>}
      {moves.length > 0 && !alsoMoves && <p className="muted small">If kept, the movements stay in your accounts as plain transactions so balances do not change.</p>}
    </>} />
  )
}

/**
 * Records money paid back, spread over the person's open debts oldest first.
 * Anything more than what is owed is refused with an explanation.
 */
function RepayForm({ debts, moves, name, currency, direction, onClose }: { debts: DebtRec[]; moves: Map<string, Move[]>; name: string; currency: string; direction: Debt['direction']; onClose: () => void }) {
  const write = useWrite()
  const cats = useCategories()
  const choices = useAccountChoices(currency)
  const [ids] = useState(() => debts.map(() => uuidv7()))
  const interestId = useStableId()
  const open = debts.filter((d) => !d.closed && d.direction === direction).map((d) => ({ d, left: outstanding(d, moves.get(d.id) ?? []), start: debtStart(d, moves.get(d.id) ?? []) })).filter((o) => o.left > 0)
  const owed = open.reduce((a, o) => a + o.left, 0)
  const [amount, setAmount] = useState(toPlain(owed, currency))
  const [interest, setInterest] = useState('')
  const [accountId, setAccountId] = useState(choices.list[0]?.id ?? '')
  const [date, setDate] = useState(isoDate())
  const [err, setErr] = useState('')
  const lent = direction === 'lent'
  const minor = parseMoney(amount, currency)
  const extra = interest.trim() ? parseMoney(interest, currency) : 0
  const plan = minor && minor > 0 ? allocate(minor, open) : null
  const balance = accountId ? choices.bal(accountId) : undefined
  const low = !lent && minor && balance !== undefined && balance - minor - (extra ?? 0) < 0

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (!minor || minor <= 0) return setErr('Enter the amount repaid, more than zero')
    if (extra === null || extra < 0) return setErr('Enter the interest as an amount, or leave it empty')
    if (minor > owed) return setErr(`${lent ? `${name} owes you` : `You owe ${name}`} ${formatMoney(owed, currency)} in total. Enter up to that amount. If ${lent ? 'they' : 'you'} paid more as interest or a gift, put the extra in “Interest or extra”.`)
    if (!accountId) return setErr(`Add a ${currency} account first, so the money has somewhere to go`)
    if (date > isoDate()) return setErr('The date is in the future. Record repayments when they happen.')
    const ops: BatchOp[] = []
    plan!.parts.forEach((p, i) => {
      ops.push(createOp('transactions', ids[i], linkedTxData({ type: lent ? 'income' : 'expense', account_id: accountId, amount: p.amount, payee: p.debt.person, note: 'Repayment', date, debt_id: p.debt.id, part: 'principal' })))
      if (p.settles) ops.push({ op: 'update', entity: 'debts', id: p.debt.id, data: { closed: true } })
    })
    if (extra && extra > 0) {
      const cat = lent ? interestIncomeCategory(cats.data ?? []) : interestExpenseCategory(cats.data ?? [], 'personal')
      ops.push(createOp('transactions', interestId, linkedTxData({ type: lent ? 'income' : 'expense', account_id: accountId, amount: extra, payee: name, note: 'Interest', category_id: cat, date, debt_id: plan!.parts[0].debt.id, part: 'interest' })))
    }
    try {
      await write.batch.mutateAsync(ops)
      toast(minor === owed ? `All settled with ${name}` : `Recorded ${formatMoney(minor, currency)}; ${formatMoney(owed - minor, currency)} still outstanding`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }
  if (open.length === 0) return <Modal title="Nothing to repay" onClose={onClose} footer={<button onClick={onClose}>Close</button>}><p>Nothing is outstanding {lent ? `from ${name}` : `to ${name}`}.</p></Modal>
  return (
    <Modal title={lent ? `${name} paid you back` : `You paid ${name} back`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button>
      <button className="primary" form="repayform" disabled={write.batch.isPending}>{write.batch.isPending ? 'Saving…' : 'Record'}</button></>}>
      <form id="repayform" onSubmit={submit}>
        <p className="muted">Outstanding: <strong><Money value={owed} currency={currency} /></strong>{open.length > 1 ? ` across ${open.length} debts` : ''}</p>
        <div className="two">
          <Field label="Amount repaid"><NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
          <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
        </div>
        <div className="two">
          <Field label={lent ? 'Into account' : 'From account'} hint={balance !== undefined ? <>Balance <Money value={balance} currency={currency} /></> : undefined}>
            <Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{choices.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>
          </Field>
          <Field label="Interest or extra (optional)" hint="Counts as income or cost, not as repayment"><NumInput kind="decimal" value={interest} onChange={(e) => setInterest(e.target.value)} placeholder="0" /></Field>
        </div>
        {plan && open.length > 1 && plan.parts.length > 0 && (
          <div className="subtle-box small">
            <strong>Applied oldest first</strong>
            <ul className="plain-list">{plan.parts.map((p) => <li key={p.debt.id}>{p.debt.note || dayLabel(debtStart(p.debt, moves.get(p.debt.id) ?? []))}: <Money value={p.amount} currency={currency} />{p.settles ? ' · settles it' : ''}</li>)}</ul>
          </div>
        )}
        {minor && minor < owed && minor > 0 && <p className="muted small">A part repayment. <Money value={owed - minor} currency={currency} /> will still be outstanding.</p>}
        {low && <p className="warn-text small"><Icon name="alert" size={14} /> This takes the account below zero. Check the amount, or save anyway if it is right.</p>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

/** Everything with one person: balance, statement, and actions. */
function PersonSheet({ p, moves, onClose, onAdd, onEditDebt, onPrint }: { p: PersonSummary; moves: Map<string, Move[]>; onClose: () => void; onAdd: (dir: Debt['direction']) => void; onEditDebt: (d: WithId<Debt>) => void; onPrint: () => void }) {
  const can = useCanWrite()
  const [repay, setRepay] = useState<Debt['direction'] | null>(null)
  const [notes, setNotes] = useState(false)
  const rows = statement(p.debts, moves)
  const owedToMe = p.debts.some((d) => !d.closed && d.direction === 'lent' && outstanding(d, moves.get(d.id) ?? []) > 0)
  const iOwe = p.debts.some((d) => !d.closed && d.direction === 'borrowed' && outstanding(d, moves.get(d.id) ?? []) > 0)
  const copy = async () => {
    const lines = [`Statement with ${p.name} (${p.currency})`, '']
    for (const r of rows) lines.push(`${r.date ?? ''}  ${notes ? r.text : r.text.replace(/ · .*/, '')}  ${r.change ? formatMoney(r.change, p.currency, { signed: true }) : ''}${r.interest ? ` interest ${formatMoney(r.interest, p.currency)}` : ''}  balance ${formatMoney(r.balance, p.currency)}`)
    lines.push('', p.net > 0 ? `${p.name} owes ${formatMoney(p.net, p.currency)}` : p.net < 0 ? `I owe ${p.name} ${formatMoney(-p.net, p.currency)}` : 'All settled')
    try { await navigator.clipboard.writeText(lines.join('\n')); toast('Statement copied. Paste it into a message to share.') } catch { toast('Could not copy. Use Print / PDF instead.') }
  }
  if (repay) return <RepayForm debts={p.debts} moves={moves} name={p.name} currency={p.currency} direction={repay} onClose={() => setRepay(null)} />
  return (
    <Modal wide title={p.name} onClose={onClose} footer={<>
      <button type="button" onClick={copy}><Icon name="copy" size={16} /> Copy statement</button>
      <button type="button" onClick={onPrint}><Icon name="printer" size={16} /> Print / PDF</button>
      <span className="spacer" />
      {can && owedToMe && <button type="button" className="primary" onClick={() => setRepay('lent')}>They paid me</button>}
      {can && iOwe && <button type="button" className="primary" onClick={() => setRepay('borrowed')}>I paid them</button>}
    </>}>
      <div className="person-head">
        <div className={`stat-big ${p.net > 0 ? 'pos-text' : p.net < 0 ? 'neg-text' : 'muted'}`}><Money value={Math.abs(p.net)} currency={p.currency} /></div>
        <div className="muted">{p.net > 0 ? `${p.name} owes you` : p.net < 0 ? `You owe ${p.name}` : 'All settled'}</div>
        {can && <div className="row" style={{ marginTop: 8 }}><button onClick={() => onAdd('lent')}><Icon name="plus" size={14} /> Lend more</button><button onClick={() => onAdd('borrowed')}><Icon name="plus" size={14} /> Borrow</button></div>}
      </div>
      <h3 className="section-title">Statement</h3>
      <ul className="list">
        {rows.map((r, i) => (
          <li key={i} className="clickable" tabIndex={0} onClick={() => { const d = p.debts.find((x) => x.id === r.debtId); if (d) onEditDebt(d as WithId<Debt>) }}
            onKeyDown={(e) => { if (e.key === 'Enter') { const d = p.debts.find((x) => x.id === r.debtId); if (d) onEditDebt(d as WithId<Debt>) } }}>
            <div className="grow"><div>{r.text}</div><div className="sub">{r.date ? dayLabel(r.date) : 'No date'}</div></div>
            <div className="right">
              {r.change ? <Money value={r.change} currency={p.currency} signed /> : r.interest ? <span className="muted small"><Money value={r.interest} currency={p.currency} /> interest</span> : null}
              <div className="muted small">balance <Money value={r.balance} currency={p.currency} /></div>
            </div>
          </li>
        ))}
      </ul>
      <p className="muted small">Balance: positive means {p.name} owes you, negative means you owe {p.name}. Click a row to open that debt. <Check checked={notes} onChange={setNotes}>Include notes when copying</Check></p>
    </Modal>
  )
}
