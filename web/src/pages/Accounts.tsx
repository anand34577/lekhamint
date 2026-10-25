import { useMemo, useState } from 'react'
import { api, type Account, type Balance } from '../lib/api'
import { ACCOUNT_TYPES, accountTypeLabel, isLiability, useAccounts, useBalances, useCanWrite, useDeposits, useLedger, useLoans, usePosition, useStableId, useValuations, useWrite, type WithId } from '../lib/data'
import { parseMoney, toPlain } from '../lib/money'
import { daysBetween, isoDate, nextDayOfMonth } from '../lib/util'
import { TxForm } from './Transactions'
import { Bar, Card, Check, Confirm, CurrencySelect, Empty, Field, LineChart, Modal, Money, Skeleton, errorText, saveErrorText, toast } from '../components'
import { SERIES, StackBar } from '../charts'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, NumInput, DateField } from '../controls'
import { shortDate } from '../lib/util'

/** Account types whose worth can differ from the money put in (funds, shares, gold, property). */
export const VALUED_TYPES = new Set(['investment', 'savings', 'other'])

/** Gain or loss of a valued account, in minor units and as a share of what was put in. */
export function gainOf(b?: Balance): { value: number; cost: number; gain: number; pct: number | null } | null {
  if (!b || b.value === undefined) return null
  const gain = b.value - b.balance
  return { value: b.value, cost: b.balance, gain, pct: b.balance > 0 ? (gain / b.balance) * 100 : null }
}

const PALETTE = ['#2E7D32', '#1E88E5', '#00897B', '#5E35B1', '#D81B60', '#EF6C00', '#546E7A', '#8D6E63']
const TYPE_ICON: Record<string, string> = { cash: 'coins', bank: 'bank', wallet: 'device', credit_card: 'wallet', savings: 'target', investment: 'chart', deposit: 'piggy', loan: 'hand', other: 'grid' }

export function Accounts({ go }: PageProps) {
  const l = useLedger()
  const accounts = useAccounts()
  const balances = useBalances()
  const can = useCanWrite()
  const [edit, setEdit] = useState<WithId<Account> | 'new' | null>(null)
  const [reconcile, setReconcile] = useState<WithId<Account> | null>(null)
  const [pay, setPay] = useState<{ cardId: string; minor: number } | null>(null)
  const [valuing, setValuing] = useState<WithId<Account> | null>(null)
  const bal = (id: string) => balances.data?.find((b) => b.account_id === id)?.balance ?? 0
  const balOf = (id: string) => balances.data?.find((b) => b.account_id === id)
  const live = (accounts.data ?? []).filter((a) => !a.deleted)
  const groups = ACCOUNT_TYPES.map(([t, label]) => [label, live.filter((a) => a.type === t && !a.archived).sort((a, b) => a.sort_order - b.sort_order || a.name.localeCompare(b.name))] as const).filter(([, list]) => list.length)
  const archived = live.filter((a) => a.archived)
  const position = usePosition()
  const loans = useLoans()
  const deposits = useDeposits()
  // Accounts that belong to a loan or deposit open that page instead of the plain transaction list.
  const linked = useMemo(() => {
    const m = new Map<string, 'loans' | 'deposits'>()
    for (const x of loans.data ?? []) if (!x.deleted) m.set(x.account_id, 'loans')
    for (const x of deposits.data ?? []) if (!x.deleted) m.set(x.account_id, 'deposits')
    return m
  }, [loans.data, deposits.data])
  // Credit still available on cards: limit minus what is owed. Not an asset, so shown on its own.
  const credit = new Map<string, number>()
  for (const a of live) if (a.type === 'credit_card' && a.credit_limit && !a.archived) credit.set(a.currency, (credit.get(a.currency) ?? 0) + Math.max(0, a.credit_limit + bal(a.id)))
  const open = (a: WithId<Account>) => { const to = linked.get(a.id); if (to) go(to, { id: a.id }); else go('transactions', { account: a.id }) }

  const row = (a: WithId<Account>) => {
    const b = bal(a.id)
    const owed = isLiability(a.type) ? Math.max(0, -b) : 0
    const g = gainOf(balOf(a.id))
    const valued = VALUED_TYPES.has(a.type) && !linked.get(a.id)
    return (
      <li key={a.id} className="clickable account-row" onClick={() => open(a)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') open(a) }}>
        <span className="acc-icon" style={{ ['--c' as string]: a.color ?? 'var(--brand)' }}><Icon name={TYPE_ICON[a.type] ?? 'wallet'} size={18} /></span>
        <div className="grow">
          <div>{a.name}</div>
          <div className="sub">{accountTypeLabel(a.type)} · {a.currency}{!a.include_in_net_worth && ' · not in net worth'}{linked.get(a.id) === 'loans' ? ' · managed in Loans' : linked.get(a.id) === 'deposits' ? ' · managed in Deposits' : ''}{a.note ? ` · ${a.note}` : ''}</div>
          {a.type === 'credit_card' && a.credit_limit ? (
            <div className="limit"><Bar fraction={owed / a.credit_limit} danger={owed > a.credit_limit * 0.9} /><span className="muted small"><Money value={Math.max(0, a.credit_limit + b)} currency={a.currency} /> available of <Money value={a.credit_limit} currency={a.currency} /></span></div>
          ) : null}
          {a.type === 'credit_card' && a.due_day && owed > 0 ? (() => {
            const due = nextDayOfMonth(isoDate(), a.due_day), left = daysBetween(isoDate(), due)
            return <div className={`small ${left <= 3 ? 'warn-text' : 'muted'}`}>Payment due {left === 0 ? 'today' : left === 1 ? 'tomorrow' : `in ${left} days`} ({due})</div>
          })() : null}
        </div>
        <div className="acc-actions">
          {can && isLiability(a.type) && owed > 0 && <button className="link" onClick={(e) => { e.stopPropagation(); setPay({ cardId: a.id, minor: owed }) }} title="Transfer money to pay what is owed">Pay {a.type === 'credit_card' ? 'bill' : 'now'}</button>}
          {can && valued && <button className="link" onClick={(e) => { e.stopPropagation(); setValuing(a) }} title="Record what it is worth today, for example from your fund statement">Update value</button>}
          {can && !g && <button className="link" onClick={(e) => { e.stopPropagation(); setReconcile(a) }} title="Match the balance your bank shows">Reconcile</button>}
          {can && <button className="icon-btn" onClick={(e) => { e.stopPropagation(); setEdit(a) }} aria-label={`Edit ${a.name}`}><Icon name="edit" size={16} /></button>}
        </div>
        <div className="acc-balance">
          {g ? <>
            <Money value={g.value} currency={a.currency} />
            <div className={`small ${g.gain >= 0 ? 'pos' : 'neg'}`} title={`Put in ${toPlain(g.cost, a.currency)}; valued ${shortDate(balOf(a.id)!.valued_on!)}`}>
              {g.gain >= 0 ? '▲' : '▼'} <Money value={Math.abs(g.gain)} currency={a.currency} />{g.pct !== null && ` (${g.pct >= 0 ? '+' : '−'}${Math.abs(g.pct).toFixed(1)}%)`}
            </div>
          </> : <Money value={isLiability(a.type) ? owed : b} currency={a.currency} className={b < 0 && !isLiability(a.type) ? 'neg' : ''} />}
          {isLiability(a.type) && <div className="muted small">{owed > 0 ? 'owed' : b > 0 ? <>credit <Money value={b} currency={a.currency} /></> : 'paid off'}</div>}
        </div>
      </li>
    )
  }

  return (
    <>
      <div className="page-head"><h1>Accounts</h1>{can && <button className="primary" onClick={() => setEdit('new')}><Icon name="plus" size={16} /> Account</button>}</div>
      <div className="grid stats" style={{ marginBottom: 16 }}>
        {(position.data ?? []).map((p) => (
          <Card key={p.currency} className="stat-card">
            <div className="stat-label">Net worth{(position.data?.length ?? 0) > 1 ? ` (${p.currency})` : ''}</div>
            <div className="stat-big"><Money value={p.net_worth} currency={p.currency} /></div>
            {p.liabilities + p.payable > 0 && (
              <StackBar legend={false} currency={p.currency} label="What you have against what you owe" parts={[
                { name: 'What you have', value: p.cash + p.investments + p.receivable, color: SERIES[0] },
                { name: 'What you owe', value: p.liabilities + p.payable, color: SERIES[1] },
              ]} />
            )}
            <ul className="nw-lines small">
              <li><span>Cash & bank</span><Money value={p.cash} currency={p.currency} /></li>
              {p.investments !== 0 && <li><span>Investments & deposits</span><Money value={p.investments} currency={p.currency} /></li>}
              {!!p.gain && <li className="muted"><span>of which {p.gain >= 0 ? 'gain' : 'loss'} on what you put in</span><Money value={p.gain} currency={p.currency} signed /></li>}
              {p.receivable !== 0 && <li><span>Others owe you</span><Money value={p.receivable} currency={p.currency} /></li>}
              {p.liabilities !== 0 && <li><span>Owed on cards & loans</span><Money value={-p.liabilities} currency={p.currency} /></li>}
              {p.payable !== 0 && <li><span>You owe people</span><Money value={-p.payable} currency={p.currency} /></li>}
              {credit.get(p.currency) ? <li className="muted"><span>Unused card credit (borrowing, not savings)</span><Money value={credit.get(p.currency)!} currency={p.currency} /></li> : null}
            </ul>
          </Card>
        ))}
        {(position.data?.length ?? 0) === 0 && !position.isPending &&<Card className="stat-card"><div className="stat-label">Net worth</div><div className="stat-big"><Money value={0} currency={l.base_currency} /></div></Card>}
      </div>
      {accounts.isPending ? <Card><Skeleton rows={4} /></Card> : live.length === 0 && (
        <Card><Empty icon="wallet" title="No accounts yet" body="Add cash, bank accounts, credit cards and wallets with today's balance. LekhaMint calculates every balance from there."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Add your first account</button> : undefined} /></Card>
      )}
      <InvestmentSummary balances={balances.data ?? []} accounts={live} />
      {groups.map(([label, list]) => <Card key={label} title={label} className="mb"><ul className="list">{list.map(row)}</ul></Card>)}
      {archived.length > 0 && <Card title="Archived" className="mb"><ul className="list">{archived.map(row)}</ul></Card>}
      {live.length > 0 && <p className="muted small">Balances are calculated from transactions, so they are always exact. Reconcile when your bank shows a different number. Click an account to see its transactions.</p>}
      {edit && <AccountForm account={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
      {pay && <TxForm payCard={pay} onClose={() => setPay(null)} />}
      {reconcile && <ReconcileForm account={reconcile} current={bal(reconcile.id)} onClose={() => setReconcile(null)} />}
      {valuing && <ValuationForm account={valuing} balance={balOf(valuing.id)} onClose={() => setValuing(null)} />}
    </>
  )
}

/** Invested against current worth, per currency, for accounts that have a value recorded. */
function InvestmentSummary({ balances, accounts }: { balances: Balance[]; accounts: WithId<Account>[] }) {
  const live = new Set(accounts.filter((a) => !a.archived).map((a) => a.id))
  const valued = balances.filter((b) => b.value !== undefined && live.has(b.account_id))
  if (!valued.length) return null
  const byCur = new Map<string, Balance[]>()
  for (const b of valued) byCur.set(b.currency, [...(byCur.get(b.currency) ?? []), b])
  return <>{[...byCur].map(([cur, list]) => {
    const cost = list.reduce((s, b) => s + b.balance, 0), value = list.reduce((s, b) => s + (b.value ?? 0), 0), gain = value - cost
    return (
      <Card key={cur} title={<><Icon name="growth" size={16} /> Investments{byCur.size > 1 ? ` (${cur})` : ''}</>} className="mb">
        <div className="inv-head">
          <div><div className="stat-label">Worth now</div><div className="stat-big"><Money value={value} currency={cur} /></div></div>
          <div><div className="stat-label">You put in</div><div className="stat-mid"><Money value={cost} currency={cur} /></div></div>
          <div><div className="stat-label">{gain >= 0 ? 'Gain' : 'Loss'}</div><div className={`stat-mid ${gain >= 0 ? 'pos' : 'neg'}`}><Money value={gain} currency={cur} signed />{cost > 0 && <span className="small"> ({((gain / cost) * 100).toFixed(1)}%)</span>}</div></div>
        </div>
        {gain >= 0
          ? <StackBar currency={cur} label="Worth now: what you put in plus the gain" parts={[{ name: 'You put in', value: cost, color: SERIES[0] }, { name: 'Gain', value: gain, color: SERIES[2] }]} />
          : <StackBar currency={cur} label="What you put in: worth now plus the loss" parts={[{ name: 'Worth now', value: value, color: SERIES[0] }, { name: 'Loss', value: -gain, color: SERIES[7] }]} />}
        <p className="muted small">“Put in” is what moved into these accounts (minus what came out). “Worth now” is the latest value you recorded with <em>Update value</em>, plus money moved since. Values are your own estimates, not live prices.</p>
      </Card>
    )
  })}</>
}

/**
 * Records what an account is worth on a date (fund statement, broker app, a
 * gold rate). Money moved in or out later is added on top until the next value.
 */
function ValuationForm({ account, balance, onClose }: { account: WithId<Account>; balance?: Balance; onClose: () => void }) {
  const write = useWrite()
  const valuations = useValuations()
  const newId = useStableId()
  const [value, setValue] = useState(balance?.value !== undefined ? toPlain(balance.value, account.currency) : balance ? toPlain(balance.balance, account.currency) : '')
  const [date, setDate] = useState(isoDate())
  const [note, setNote] = useState('')
  const [err, setErr] = useState('')
  const history = (valuations.data ?? []).filter((v) => !v.deleted && v.account_id === account.id).sort((a, b) => a.date.localeCompare(b.date) || a.created_at - b.created_at)
  const minor = parseMoney(value, account.currency)
  const cost = balance?.balance ?? 0
  return (
    <Modal title={`What is ${account.name} worth?`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="valform" disabled={write.save.isPending}>Save value</button></>}>
      <form id="valform" onSubmit={async (e) => {
        e.preventDefault(); setErr('')
        if (minor === null || minor < 0) return setErr('Enter the value as a number, like 125000')
        try {
          await write.save.mutateAsync({ entity: 'valuations', newId, data: { account_id: account.id, date, value: minor, note: note.trim() || null } })
          toast('Value saved'); onClose()
        } catch (x) { setErr(saveErrorText(x)) }
      }}>
        <p className="muted small">Copy the current value from your fund statement, broker or bank app. LekhaMint keeps tracking money you put in or take out, so you only update this now and then.</p>
        <div className="two">
          <Field label="Worth on that day"><NumInput kind="decimal" value={value} onChange={(e) => setValue(e.target.value)} autoFocus /></Field>
          <Field label="Date"><DateField value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        </div>
        <Field label="Note (optional)"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="NAV 152.3 × 820 units" maxLength={200} /></Field>
        {minor !== null && cost > 0 && <p className="small">Against <Money value={cost} currency={account.currency} /> put in, that is a {minor >= cost ? 'gain' : 'loss'} of{' '}
          <strong className={minor >= cost ? 'pos' : 'neg'}><Money value={Math.abs(minor - cost)} currency={account.currency} /> ({(((minor - cost) / cost) * 100).toFixed(1)}%)</strong>.</p>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
      {history.length > 1 && <LineChart height={130} fit area currency={account.currency} label={`${account.name}: recorded values`}
        labels={history.map((v) => shortDate(v.date))} series={[{ name: 'Value', color: 'var(--series-1)', values: history.map((v) => v.value) }]} />}
      {history.length > 0 && <>
        <h3 className="sub-head">Recorded values</h3>
        <ul className="list">{[...history].reverse().map((v) => (
          <li key={v.id}><div className="grow"><Money value={v.value} currency={account.currency} /><div className="sub">{shortDate(v.date)}{v.note ? ` · ${v.note}` : ''}</div></div>
            <button className="icon-btn" aria-label="Delete this value" onClick={async () => { await write.remove.mutateAsync({ entity: 'valuations', id: v.id }); toast('Value deleted', { label: 'Undo', run: () => write.restore.mutate({ entity: 'valuations', id: v.id }) }) }}><Icon name="trash" size={15} /></button></li>
        ))}</ul>
      </>}
    </Modal>
  )
}

function AccountForm({ account, onClose }: { account?: WithId<Account>; onClose: () => void }) {
  const l = useLedger()
  const balances = useBalances()
  const loans = useLoans()
  const deposits = useDeposits()
  const balance = account ? balances.data?.find((b) => b.account_id === account.id)?.balance ?? 0 : 0
  const owner = account && ((loans.data ?? []).find((x) => !x.deleted && x.account_id === account.id)?.name ?? (deposits.data ?? []).find((x) => !x.deleted && x.account_id === account.id)?.name)
  const write = useWrite()
  const newId = useStableId()
  const [name, setName] = useState(account?.name ?? '')
  const [type, setType] = useState(account?.type ?? 'bank')
  const [cur, setCur] = useState(account?.currency ?? l.base_currency)
  const liability = isLiability(type)
  const [opening, setOpening] = useState(account && account.opening_balance ? toPlain(isLiability(account.type) ? -account.opening_balance : account.opening_balance, account.currency) : '')
  const [limit, setLimit] = useState(account?.credit_limit ? toPlain(account.credit_limit, account.currency) : '')
  const [stmtDay, setStmtDay] = useState(account?.statement_day ? String(account.statement_day) : '')
  const [dueDay, setDueDay] = useState(account?.due_day ? String(account.due_day) : '')
  const [color, setColor] = useState(account?.color ?? PALETTE[1])
  const [note, setNote] = useState(account?.note ?? '')
  const [inWorth, setInWorth] = useState(account?.include_in_net_worth ?? true)
  const [archived, setArchived] = useState(account?.archived ?? false)
  const [err, setErr] = useState('')
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.save.isPending) return
    if (!name.trim()) return setErr('Give the account a name')
    const raw = opening.trim() ? parseMoney(opening, cur) : 0
    if (raw === null) return setErr('The starting balance should be a number, like 25000 or 25,000.50')
    
    const lim = type === 'credit_card' && limit.trim() ? parseMoney(limit, cur) : null
    if (type === 'credit_card' && limit.trim() && (lim === null || lim <= 0)) return setErr('The credit limit should be a positive number, or leave it empty')
    const dayOf = (v: string) => (v.trim() === '' ? null : /^\d{1,2}$/.test(v.trim()) && +v >= 1 && +v <= 31 ? +v : NaN)
    const sd = type === 'credit_card' ? dayOf(stmtDay) : null, dd = type === 'credit_card' ? dayOf(dueDay) : null
    if (Number.isNaN(sd) || Number.isNaN(dd)) return setErr('Statement and due days should be a day of the month, from 1 to 31')
    try {
      await write.save.mutateAsync({ entity: 'accounts', id: account?.id, newId, data: {
        name: name.trim(), type, currency: cur, opening_balance: liability ? -raw : raw, credit_limit: lim, statement_day: sd, due_day: dd, color,
        include_in_net_worth: inWorth, archived, icon: account?.icon ?? null, note: note.trim() || null,
      } satisfies Partial<Account> })
      toast(account ? 'Account saved' : `${name.trim()} added`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }
  const askDelete = async () => {
    if (!account) return
    if (owner) return setErr(`This account belongs to “${owner}”. Delete or close that from its own page first, so its schedule and history stay consistent.`)
    try {
      const page = await api.transactions(l.id, { account_id: account.id, limit: '100' })
      setConfirmDelete(page.transactions.length)
    } catch (x) { setErr(errorText(x)) }
  }
  const doDelete = async () => {
    if (!account) return
    await write.remove.mutateAsync({ entity: 'accounts', id: account.id }); onClose()
    toast('Account moved to Trash', { label: 'Undo', run: () => write.restore.mutate({ entity: 'accounts', id: account.id }) })
  }
  return (
    <>
      <Modal title={account ? 'Edit account' : 'New account'} onClose={onClose} footer={<>
        {account && <button type="button" className="danger" onClick={askDelete}><Icon name="trash" size={16} /> Delete</button>}
        <span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="accform" disabled={write.save.isPending}>{write.save.isPending ? 'Saving…' : 'Save'}</button></>}>
        <form id="accform" onSubmit={submit}>
          <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} required placeholder="HDFC Savings, Amex, Paytm…" autoFocus={!account} /></Field>
          <div className="two">
            <Field label="Type"><Select value={type} onChange={(e) => setType(e.target.value)}>{ACCOUNT_TYPES.map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></Field>
            <Field label="Currency" hint={account ? 'Changing it does not convert past amounts' : undefined}><CurrencySelect value={cur} onChange={setCur} /></Field>
          </div>
          <Field label={liability ? 'Amount owed when you start tracking' : 'Balance when you start tracking'} hint={liability ? 'What the statement shows you owe; 0 if nothing. If you have paid more than you owe, enter it as a negative number, like -500' : 'Today’s balance is fine'}>
            <NumInput kind="decimal" value={opening} onChange={(e) => setOpening(e.target.value)} placeholder="0" />
          </Field>
          {type === 'credit_card' && <Field label="Credit limit (optional)" hint="Shows how much of the limit you have used"><NumInput kind="decimal" value={limit} onChange={(e) => setLimit(e.target.value)} /></Field>}
          {type === 'credit_card' && <div className="two">
            <Field label="Statement day (optional)" hint="Day of the month your statement is generated"><NumInput kind="int" value={stmtDay} onChange={(e) => setStmtDay(e.target.value)} placeholder="e.g. 5" /></Field>
            <Field label="Payment due day (optional)" hint="You get a reminder a few days before"><NumInput kind="int" value={dueDay} onChange={(e) => setDueDay(e.target.value)} placeholder="e.g. 25" /></Field>
          </div>}
          <Field label="Colour"><div className="row">{PALETTE.map((c) => <button type="button" key={c} className={`swatch ${c === color ? 'on' : ''}`} style={{ background: c }} aria-label={`Colour ${c}`} aria-pressed={c === color} onClick={() => setColor(c)} />)}</div></Field>
          <Field label="Note (optional)"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Last 4 digits, branch…" /></Field>
          <Check checked={inWorth} onChange={setInWorth}>Include in net worth</Check>
          {account && <Check checked={archived} onChange={setArchived}>Archived (hidden from pickers, history kept)</Check>}
          {account && archived && !account.archived && balance !== 0 && <p className="warn-text small"><Icon name="alert" size={14} /> {name || 'This account'} still shows <Money value={liability ? -balance : balance} currency={cur} />{liability ? ' owed' : ''}. Archived accounts are not counted in net worth. Move the money out, or reconcile to zero if it is really gone.</p>}
          {account && account.currency !== cur && <p className="warn-text small"><Icon name="alert" size={14} /> Existing amounts are not converted; they will be read as {cur}.</p>}
          {err && <p className="error" role="alert">{err}</p>}
        </form>
      </Modal>
      {confirmDelete !== null && account && (
        <Confirm title={`Delete ${account.name}?`} danger confirm="Delete account" onClose={() => setConfirmDelete(null)} onConfirm={doDelete}
          body={confirmDelete > 0 ? <>
            <p>This account has {confirmDelete >= 100 ? '100+' : confirmDelete} {confirmDelete === 1 ? 'transaction' : 'transactions'}. They stay in your book but will show an unknown account.</p>
            <p><strong>Archiving is usually better:</strong> it hides the account from pickers and keeps its history.</p>
            <button type="button" onClick={async () => { await write.save.mutateAsync({ entity: 'accounts', id: account.id, data: { archived: true } }); toast(`${account.name} archived`); setConfirmDelete(null); onClose() }}>Archive instead</button>
          </> : <p>It has no transactions. You can restore it from the Trash later.</p>} />
      )}
    </>
  )
}

/**
 * Records the difference between LekhaMint's balance and the real one as an
 * adjustment, excluded from reports. Cards and loans are entered as the
 * amount owed, the way statements show them.
 */
function ReconcileForm({ account, current, onClose }: { account: WithId<Account>; current: number; onClose: () => void }) {
  const write = useWrite()
  const newId = useStableId()
  const [err, setErr] = useState('')
  const liability = isLiability(account.type)
  const [actual, setActual] = useState(toPlain(liability ? -current : current, account.currency))
  const value = parseMoney(actual, account.currency)
  const target = value === null ? null : liability ? -value : value
  const diff = target === null ? null : target - current
  return (
    <Modal title={`Reconcile ${account.name}`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button>
      <button className="primary" disabled={!diff || write.save.isPending} onClick={async () => {
        const now = Date.now()
        setErr('')
        try {
          await write.save.mutateAsync({ entity: 'transactions', newId, data: {
            type: diff! > 0 ? 'income' : 'expense', account_id: account.id, amount: Math.abs(diff!), payee: 'Balance adjustment',
            occurred_at: now, local_date: isoDate(), tz_offset_min: -new Date().getTimezoneOffset(), is_adjustment: true, status: 'cleared', tag_ids: [],
          } })
          toast('Balance adjusted'); onClose()
        } catch (x) { setErr(saveErrorText(x)) }
      }}>Adjust</button></>}>
      <p>LekhaMint shows <strong><Money value={liability ? -current : current} currency={account.currency} /></strong>{liability ? ' owed' : ''}. Enter what your {liability ? 'statement says you owe' : 'bank or wallet shows'} now.</p>
      <Field label={liability ? 'Amount owed' : 'Actual balance'}><NumInput kind="decimal" value={actual} onChange={(e) => setActual(e.target.value)} autoFocus /></Field>
      {diff !== null && diff !== 0 && <p>Adjustment: <Money value={diff} currency={account.currency} signed /> <span className="muted small">(not counted as spending or income)</span></p>}
      {diff === 0 && <p className="muted"><Icon name="check" size={14} /> Already matches.</p>}
      {actual.trim() && value === null && <p className="error">Enter the amount as a number, like 12500.50</p>}
      {err && <p className="error" role="alert">{err}</p>}
    </Modal>
  )
}
