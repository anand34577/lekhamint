import { useMemo, useState } from 'react'
import { api, type Account } from '../lib/api'
import { ACCOUNT_TYPES, accountTypeLabel, isLiability, useAccounts, useBalances, useCanWrite, useDeposits, useLedger, useLoans, usePosition, useStableId, useWrite, type WithId } from '../lib/data'
import { parseMoney, toPlain } from '../lib/money'
import { isoDate } from '../lib/util'
import { Bar, Card, Check, Confirm, CurrencySelect, Empty, Field, Modal, Money, Skeleton, errorText, saveErrorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, NumInput } from '../controls'

const PALETTE = ['#2E7D32', '#1E88E5', '#00897B', '#5E35B1', '#D81B60', '#EF6C00', '#546E7A', '#8D6E63']
const TYPE_ICON: Record<string, string> = { cash: 'coins', bank: 'bank', wallet: 'device', credit_card: 'wallet', savings: 'target', investment: 'chart', deposit: 'piggy', loan: 'hand', other: 'grid' }

export function Accounts({ go }: PageProps) {
  const l = useLedger()
  const accounts = useAccounts()
  const balances = useBalances()
  const can = useCanWrite()
  const [edit, setEdit] = useState<WithId<Account> | 'new' | null>(null)
  const [reconcile, setReconcile] = useState<WithId<Account> | null>(null)
  const bal = (id: string) => balances.data?.find((b) => b.account_id === id)?.balance ?? 0
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
  for (const a of live) if (a.type === 'credit_card' && a.credit_limit && !a.archived) credit.set(a.currency, (credit.get(a.currency) ?? 0) + Math.max(0, a.credit_limit - Math.max(0, -bal(a.id))))
  const open = (a: WithId<Account>) => { const to = linked.get(a.id); if (to) go(to, { id: a.id }); else go('transactions', { account: a.id }) }

  const row = (a: WithId<Account>) => {
    const b = bal(a.id)
    const owed = isLiability(a.type) ? Math.max(0, -b) : 0
    return (
      <li key={a.id} className="clickable account-row" onClick={() => open(a)} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') open(a) }}>
        <span className="acc-icon" style={{ ['--c' as string]: a.color ?? 'var(--brand)' }}><Icon name={TYPE_ICON[a.type] ?? 'wallet'} size={18} /></span>
        <div className="grow">
          <div>{a.name}</div>
          <div className="sub">{accountTypeLabel(a.type)} · {a.currency}{!a.include_in_net_worth && ' · not in net worth'}{linked.get(a.id) === 'loans' ? ' · managed in Loans' : linked.get(a.id) === 'deposits' ? ' · managed in Deposits' : ''}{a.note ? ` · ${a.note}` : ''}</div>
          {a.type === 'credit_card' && a.credit_limit ? (
            <div className="limit"><Bar fraction={owed / a.credit_limit} danger={owed > a.credit_limit * 0.9} /><span className="muted small"><Money value={Math.max(0, a.credit_limit - owed)} currency={a.currency} /> available of <Money value={a.credit_limit} currency={a.currency} /></span></div>
          ) : null}
        </div>
        <div className="acc-actions">
          {can && <button className="link" onClick={(e) => { e.stopPropagation(); setReconcile(a) }} title="Match the balance your bank shows">Reconcile</button>}
          {can && <button className="icon-btn" onClick={(e) => { e.stopPropagation(); setEdit(a) }} aria-label={`Edit ${a.name}`}><Icon name="edit" size={16} /></button>}
        </div>
        <div className="acc-balance">
          <Money value={isLiability(a.type) ? owed : b} currency={a.currency} className={b < 0 && !isLiability(a.type) ? 'neg' : ''} />
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
            <ul className="nw-lines small">
              <li><span>Cash & bank</span><Money value={p.cash} currency={p.currency} /></li>
              {p.investments !== 0 && <li><span>Investments & deposits</span><Money value={p.investments} currency={p.currency} /></li>}
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
      {groups.map(([label, list]) => <Card key={label} title={label} className="mb"><ul className="list">{list.map(row)}</ul></Card>)}
      {archived.length > 0 && <Card title="Archived" className="mb"><ul className="list">{archived.map(row)}</ul></Card>}
      {live.length > 0 && <p className="muted small">Balances are calculated from transactions, so they are always exact. Reconcile when your bank shows a different number. Click an account to see its transactions.</p>}
      {edit && <AccountForm account={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
      {reconcile && <ReconcileForm account={reconcile} current={bal(reconcile.id)} onClose={() => setReconcile(null)} />}
    </>
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
    if (liability && raw < 0) return setErr('Enter what you owe as a positive amount. If the card is in credit, enter 0 and record the credit as a refund.')
    const lim = type === 'credit_card' && limit.trim() ? parseMoney(limit, cur) : null
    if (type === 'credit_card' && limit.trim() && (lim === null || lim <= 0)) return setErr('The credit limit should be a positive number, or leave it empty')
    try {
      await write.save.mutateAsync({ entity: 'accounts', id: account?.id, newId, data: {
        name: name.trim(), type, currency: cur, opening_balance: liability ? -raw : raw, credit_limit: lim, color,
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
          <Field label={liability ? 'Amount owed when you start tracking' : 'Balance when you start tracking'} hint={liability ? 'What the statement shows you owe; 0 if nothing' : 'Today’s balance is fine'}>
            <NumInput kind="decimal" value={opening} onChange={(e) => setOpening(e.target.value)} placeholder="0" />
          </Field>
          {type === 'credit_card' && <Field label="Credit limit (optional)" hint="Shows how much of the limit you have used"><NumInput kind="decimal" value={limit} onChange={(e) => setLimit(e.target.value)} /></Field>}
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
