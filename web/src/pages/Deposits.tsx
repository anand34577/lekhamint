import { useMemo, useState } from 'react'
import type { BatchOp, Deposit, Transaction } from '../lib/api'
import { useAccounts, useBalances, useCanWrite, useCategories, useDeposits, useLedger, useLinkedMoves, useStableId, useWrite, type WithId } from '../lib/data'
import { COMPOUNDINGS, DEPOSIT_KINDS, PAYOUTS, depositKindLabel, estimate, tenureLabel, type DepositEstimate } from '../lib/deposits'
import { rateError } from '../lib/loans'
import { createOp, feeCategory, interestIncomeCategory, linkedTxData } from '../lib/links'
import { currency, formatMoney, parseMoney, toPlain } from '../lib/money'
import { addMonths, dayLabel, download, isoDate, uuidv7 } from '../lib/util'
import { workbook, XLSX_TYPE } from '../lib/xlsx'
import { Bar, Card, Check, Confirm, CurrencySelect, Empty, Field, Modal, Money, Segmented, Skeleton, saveErrorText, toast } from '../components'
import { Columns, SERIES } from '../charts'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, DateField, NumInput } from '../controls'

/**
 * Fixed and recurring deposits (docs/02, "Loans and deposits"). The money sits
 * in a deposit account: funding is a transfer into it, interest credited is
 * income, and maturity is a transfer back out. Projected values are estimates;
 * nothing is added to a bank account until the user confirms what arrived.
 */

type DepRec = WithId<Deposit>
type Move = Transaction & { id: string }
export type DepositEntry = { dep: DepRec; moves: Move[]; balance: number; est: DepositEstimate; interestCredited: number; payoutsReceived: number; rdPaid: number; nextEvent: { date: string; text: string } | null }

export function useDepositEntries() {
  const deps = useDeposits()
  const moves = useLinkedMoves('deposit_id')
  const balances = useBalances()
  const accounts = useAccounts()
  const today = isoDate()
  const list = useMemo((): DepositEntry[] => (deps.data ?? []).filter((d) => !d.deleted).map((dep) => {
    const ms = moves.data?.get(dep.id) ?? []
    const balance = balances.data?.find((b) => b.account_id === dep.account_id)?.balance ?? 0
    const est = estimate(dep, today)
    const interestCredited = ms.filter((m) => m.part === 'interest' && m.type === 'income' && m.account_id === dep.account_id).reduce((a, m) => a + m.amount, 0)
    const payoutsReceived = ms.filter((m) => m.part === 'interest' && m.type === 'income' && m.account_id !== dep.account_id).reduce((a, m) => a + m.amount, 0)
    // Instalments recorded here, plus those already in the opening balance of an RD that was running before.
    const opening = accounts.data?.find((a) => a.id === dep.account_id)?.opening_balance ?? 0
    const rdPaid = dep.kind !== 'rd' ? 0 : ms.filter((m) => m.type === 'transfer' && m.to_account_id === dep.account_id && m.part === 'principal').length + Math.floor(Math.max(0, opening) / dep.amount)
    let nextEvent: DepositEntry['nextEvent'] = null
    if (dep.status === 'active') {
      const payout = est.payouts.find((p) => p.date >= today && p.date < dep.maturity_date)
      const rdNext = dep.kind === 'rd' ? addMonths(dep.start_date, rdPaid) : null
      const candidates = [
        { date: dep.maturity_date, text: 'Matures' },
        ...(payout ? [{ date: payout.date, text: `Interest payout ${formatMoney(payout.amount, dep.currency)}` }] : []),
        ...(rdNext && rdNext < dep.maturity_date ? [{ date: rdNext, text: `Instalment ${rdPaid + 1} of ${est.days ? Math.round(est.invested / dep.amount) : ''}` }] : []),
      ]
      nextEvent = candidates.sort((a, b) => a.date.localeCompare(b.date))[0]
    }
    return { dep, moves: ms, balance, est, interestCredited, payoutsReceived, rdPaid, nextEvent }
  }), [deps.data, moves.data, balances.data, accounts.data, today])
  return { list, pending: deps.isPending || moves.isPending || balances.isPending }
}

export function Deposits({ params, go }: PageProps) {
  const { list, pending } = useDepositEntries()
  const can = useCanWrite()
  const today = isoDate()
  const [edit, setEdit] = useState<DepRec | 'new' | null>(null)
  const [openId, setOpenId] = useState<string | null>(params.id ?? null)
  const [view, setView] = useState<'active' | 'calendar' | 'closed'>('active')
  const active = list.filter((x) => x.dep.status === 'active')
  const done = list.filter((x) => x.dep.status !== 'active')
  const sel = openId ? list.find((x) => x.dep.id === openId || x.dep.account_id === openId) : undefined
  const totals = (f: (x: DepositEntry) => number) => {
    const m = new Map<string, number>()
    for (const x of active) m.set(x.dep.currency, (m.get(x.dep.currency) ?? 0) + f(x))
    return [...m].filter(([, v]) => v)
  }
  const invested = totals((x) => x.balance)
  const atMaturity = totals((x) => x.est.maturity)
  const soon = active.filter((x) => x.dep.maturity_date <= addMonths(today, 3)).sort((a, b) => a.dep.maturity_date.localeCompare(b.dep.maturity_date))
  const due = active.filter((x) => x.dep.maturity_date <= today)

  return (
    <>
      <div className="page-head"><h1>Deposits</h1>
        <div className="row">
          <button onClick={() => go('tools', { tool: 'fd' })}><Icon name="calc" size={16} /> FD calculator</button>
          {can && <button className="primary" onClick={() => setEdit('new')}><Icon name="plus" size={16} /> Add deposit</button>}
        </div>
      </div>
      {list.length > 0 && (
        <div className="grid stats" style={{ marginBottom: 16 }}>
          <Card className="stat-card"><div className="stat-label">In active deposits</div>{invested.length ? invested.map(([c, v]) => <div key={c} className="stat-big"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">Nothing</div>}
            <div className="muted small">Principal plus interest credited so far</div></Card>
          <Card className="stat-card"><div className="stat-label">Expected at maturity</div>{atMaturity.length ? atMaturity.map(([c, v]) => <div key={c} className="stat-value"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">—</div>}
            <div className="muted small">Estimate, before tax; payout deposits return the principal only</div></Card>
          <Card className="stat-card"><div className="stat-label">Maturing in 3 months</div><div className="stat-value">{soon.length || 'None'}</div>
            <div className="muted small">{soon[0] ? `Next: ${soon[0].dep.name}, ${dayLabel(soon[0].dep.maturity_date)}` : 'Nothing soon'}</div></Card>
        </div>
      )}
      {active.length > 1 && (() => {
        // When money comes back: maturity values of active deposits in the main currency, in date order.
        const code = active[0].dep.currency
        const rows = active.filter((x) => x.dep.currency === code).sort((a, b) => a.dep.maturity_date.localeCompare(b.dep.maturity_date))
        return (
          <Card title={<><Icon name="calendar" size={16} /> When your deposits pay back</>} className="mb">
            <Columns currency={code} label="Value of each deposit at maturity, in date order" height={150} every={1}
              labels={rows.map((x) => `${new Date(x.dep.maturity_date + 'T00:00:00').toLocaleDateString(undefined, { month: 'short', year: '2-digit' })}`)}
              series={[{ name: 'You put in', color: SERIES[0], values: rows.map((x) => x.est.invested) }, { name: 'Interest', color: SERIES[2], values: rows.map((x) => x.est.interest) }]} />
            <p className="muted small">One column per deposit, at the month it matures: what you put in and the interest it is expected to earn over its term (deposits that pay interest out return only what you put in at maturity). Hover a column for its figures.</p>
          </Card>
        )
      })()}
      {due.length > 0 && (
        <div className="insights">
          {due.map((x) => (
            <button key={x.dep.id} className="insight info" onClick={() => setOpenId(x.dep.id)}>
              <Icon name="piggy" /><div><strong>{x.dep.name} matured {dayLabel(x.dep.maturity_date)}</strong>
                <span>Record what the bank paid so it appears in your account. Until then it stays counted as a deposit.</span></div><Icon name="right" />
            </button>
          ))}
        </div>
      )}
      {pending ? <Card><Skeleton rows={4} /></Card> : list.length === 0 ? (
        <Card><Empty icon="piggy" title="No deposits yet" body="Add fixed or recurring deposits to see what they will be worth, when they mature, and the interest they earn. Projected values are estimates; your bank's figure wins whenever you enter it."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Add a deposit</button> : undefined} /></Card>
      ) : (
        <Card title={<Segmented label="View" value={view} onChange={setView} options={[['active', `Active (${active.length})`], ['calendar', 'Maturity calendar'], ['closed', `Matured & closed (${done.length})`]]} />}
          action={<button onClick={() => exportDeposits(list)}><Icon name="table" size={16} /> Excel</button>}>
          {view === 'calendar' ? <MaturityCalendar list={active} onOpen={setOpenId} /> : (
            <ul className="list">
              {(view === 'active' ? active : done).sort((a, b) => a.dep.maturity_date.localeCompare(b.dep.maturity_date)).map((x) => <DepositRow key={x.dep.id} x={x} today={today} onOpen={() => setOpenId(x.dep.id)} />)}
              {(view === 'active' ? active : done).length === 0 && <li className="muted">{view === 'active' ? 'No active deposits.' : 'Nothing matured or closed yet.'}</li>}
            </ul>
          )}
        </Card>
      )}
      <p className="muted small">Estimates compound every full period from the start date and pay simple interest (actual/365) for leftover days. Banks differ on day counts, rounding and tax deducted at source; enter the bank's maturity amount for an exact figure.</p>
      {edit && <DepositForm dep={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
      {sel && <DepositDetail x={sel} onClose={() => setOpenId(null)} onEdit={() => { setEdit(sel.dep); setOpenId(null) }} go={go} />}
    </>
  )
}

function DepositRow({ x, today, onOpen }: { x: DepositEntry; today: string; onOpen: () => void }) {
  const { dep, est } = x
  const total = Math.max(1, (new Date(dep.maturity_date).getTime() - new Date(dep.start_date).getTime()))
  const elapsed = Math.min(1, Math.max(0, (new Date(today).getTime() - new Date(dep.start_date).getTime()) / total))
  return (
    <li className="clickable" onClick={onOpen} tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') onOpen() }}>
      <span className="acc-icon" style={{ ['--c' as string]: 'var(--brand)' }}><Icon name="piggy" size={18} /></span>
      <div className="grow">
        <div>{dep.name}{dep.status === 'active' && dep.maturity_date <= today && <span className="pill warn">Matured</span>}{dep.status !== 'active' && <span className="pill ok">{dep.status === 'matured' ? 'Matured' : 'Closed early'}</span>}</div>
        <div className="sub">{[depositKindLabel(dep.kind), dep.institution, `${dep.annual_rate}%`, dep.status === 'active' ? `matures ${dayLabel(dep.maturity_date)}` : dep.closed_date ? `closed ${dayLabel(dep.closed_date)}` : ''].filter(Boolean).join(' · ')}</div>
        {dep.status === 'active' && <Bar fraction={elapsed} />}
      </div>
      <div className="right">
        <Money value={dep.status === 'active' ? x.balance : est.maturity} currency={dep.currency} />
        {dep.status === 'active' && <div className="muted small">→ <Money value={est.maturity} currency={dep.currency} />{est.fromBank ? '' : ' est.'}</div>}
      </div>
    </li>
  )
}

function MaturityCalendar({ list, onOpen }: { list: DepositEntry[]; onOpen: (id: string) => void }) {
  const byMonth = new Map<string, DepositEntry[]>()
  for (const x of [...list].sort((a, b) => a.dep.maturity_date.localeCompare(b.dep.maturity_date))) {
    const k = x.dep.maturity_date.slice(0, 7)
    byMonth.set(k, [...(byMonth.get(k) ?? []), x])
  }
  if (!byMonth.size) return <p className="muted">No active deposits.</p>
  return (
    <div className="calendar">
      {[...byMonth].map(([month, xs]) => {
        const sums = new Map<string, number>()
        for (const x of xs) sums.set(x.dep.currency, (sums.get(x.dep.currency) ?? 0) + x.est.maturity)
        return (
          <section key={month} className="cal-month">
            <header><strong>{new Date(month + '-01').toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</strong>
              <span className="muted small">{[...sums].map(([c, v]) => formatMoney(v, c)).join(' + ')} expected</span></header>
            <ul className="list">{xs.map((x) => (
              <li key={x.dep.id} className="clickable" onClick={() => onOpen(x.dep.id)}><span className="cal-day">{new Date(x.dep.maturity_date).getDate()}</span>
                <div className="grow"><div>{x.dep.name}</div><div className="sub">{x.dep.institution || depositKindLabel(x.dep.kind)}</div></div><Money value={x.est.maturity} currency={x.dep.currency} /></li>
            ))}</ul>
          </section>
        )
      })}
    </div>
  )
}

function exportDeposits(list: DepositEntry[]) {
  const rows = list.map((x) => {
    const d = currency(x.dep.currency).digits
    return [x.dep.name, depositKindLabel(x.dep.kind), x.dep.institution ?? '', x.dep.currency, { money: x.est.invested, digits: d }, Number(x.dep.annual_rate),
      { date: x.dep.start_date }, { date: x.dep.maturity_date }, tenureLabel(x.dep.start_date, x.dep.maturity_date), x.dep.payout === 'cumulative' ? 'At maturity' : x.dep.payout,
      { money: x.est.interest, digits: d }, { money: x.est.maturity, digits: d }, x.est.fromBank ? 'Bank figure' : 'Estimate', x.dep.status]
  })
  const bytes = workbook([{ name: 'Deposits', intro: ['Deposits and maturity schedule', `Generated ${isoDate()} · estimates assume the terms entered; bank figures may differ`],
    header: ['Name', 'Type', 'Institution', 'Currency', 'Invested', 'Rate %', 'Start', 'Maturity', 'Tenure', 'Interest paid', 'Total interest', 'Maturity amount', 'Basis', 'Status'], rows: rows as never }])
  download(`deposits-${isoDate()}.xlsx`, new Blob([bytes], { type: XLSX_TYPE }))
}

// --- Detail ------------------------------------------------------------------------

function DepositDetail({ x, onClose, onEdit, go }: { x: DepositEntry; onClose: () => void; onEdit: () => void; go: PageProps['go'] }) {
  const { dep, est } = x
  const can = useCanWrite()
  const accounts = useAccounts()
  const [action, setAction] = useState<'mature' | 'close' | 'interest' | 'instalment' | 'delete' | null>(null)
  const cur = dep.currency
  const today = isoDate()
  const accName = (id: string) => accounts.data?.find((a) => a.id === id)?.name ?? '—'
  const rdTotal = dep.kind === 'rd' ? Math.round(est.invested / dep.amount) : 0
  return (
    <Modal wide title={dep.name} onClose={onClose} footer={<>
      <button type="button" onClick={() => go('print', { kind: 'deposits' })}><Icon name="printer" size={16} /> Print / PDF</button>
      <span className="spacer" />
      {can && dep.status === 'active' && <>
        {dep.kind === 'rd' && x.rdPaid < rdTotal && <button type="button" onClick={() => setAction('instalment')}>Pay instalment</button>}
        <button type="button" onClick={() => setAction('interest')}>{dep.payout === 'cumulative' ? 'Interest credited' : 'Interest received'}</button>
        {dep.maturity_date > today && <button type="button" onClick={() => setAction('close')}>Close early</button>}
        <button type="button" className="primary" onClick={() => setAction('mature')}>{dep.maturity_date <= today ? 'Record maturity' : 'Matured early?'}</button>
      </>}
    </>}>
      <div className="kv-grid">
        <div><span className="muted small">Now in the deposit</span><strong className="stat-value"><Money value={x.balance} currency={cur} /></strong></div>
        <div><span className="muted small">{dep.status === 'active' ? 'Expected at maturity' : 'Maturity value'}</span><strong><Money value={est.maturity} currency={cur} /></strong><span className="muted small">{est.fromBank ? "Bank's figure" : 'Estimate'}</span></div>
        <div><span className="muted small">Matures</span><strong>{dayLabel(dep.maturity_date)}</strong><span className="muted small">{tenureLabel(dep.start_date, dep.maturity_date)}</span></div>
        <div><span className="muted small">Interest over the term</span><strong><Money value={est.interest} currency={cur} /></strong>{est.tax > 0 && <span className="muted small">≈ <Money value={est.tax} currency={cur} /> tax at {dep.tax_percent}%</span>}</div>
        {dep.status === 'active' && dep.payout === 'cumulative' && dep.kind !== 'other' && <div><span className="muted small">Estimated value today</span><strong><Money value={est.valueToday} currency={cur} /></strong><span className="muted small">Not what early closure pays</span></div>}
        {(x.interestCredited > 0 || x.payoutsReceived > 0) && <div><span className="muted small">Interest recorded</span><strong><Money value={x.interestCredited + x.payoutsReceived} currency={cur} /></strong></div>}
        {dep.kind === 'rd' && <div><span className="muted small">Instalments</span><strong>{x.rdPaid} of {rdTotal} paid</strong><span className="muted small"><Money value={dep.amount} currency={cur} /> each</span></div>}
      </div>
      {est.payouts.length > 0 && (
        <>
          <h3 className="section-title">Interest payouts</h3>
          <div className="table-wrap"><table className="table compact"><thead><tr><th>Date</th><th className="num">Amount</th><th>Status</th></tr></thead>
            <tbody>{est.payouts.map((p) => {
              const got = x.moves.some((m) => m.part === 'interest' && m.local_date.slice(0, 7) === p.date.slice(0, 7))
              return <tr key={p.date}><td>{dayLabel(p.date)}</td><td className="num"><Money value={p.amount} currency={cur} /></td><td>{got ? <span className="pos-text">Received</span> : p.date < today ? <span className="warn-text">Not recorded</span> : <span className="muted">Expected</span>}</td></tr>
            })}</tbody></table></div>
        </>
      )}
      <h3 className="section-title">Terms</h3>
      <dl className="terms">
        <dt>Type</dt><dd>{depositKindLabel(dep.kind)}{dep.institution ? ` at ${dep.institution}` : ''}</dd>
        <dt>{dep.kind === 'rd' ? 'Monthly instalment' : 'Principal'}</dt><dd><Money value={dep.amount} currency={cur} /></dd>
        <dt>Rate</dt><dd>{dep.annual_rate}% a year, {COMPOUNDINGS.find(([k]) => k === dep.compounding)?.[1].toLowerCase()}</dd>
        <dt>Interest</dt><dd>{PAYOUTS.find(([k]) => k === dep.payout)?.[1]}{dep.payout_account_id ? ` into ${accName(dep.payout_account_id)}` : ''}</dd>
        <dt>Started</dt><dd>{dayLabel(dep.start_date)}</dd>
        {dep.auto_renew && <><dt>Renewal</dt><dd>Renews automatically at maturity</dd></>}
        {dep.note && <><dt>Note</dt><dd>{dep.note}</dd></>}
      </dl>
      <h3 className="section-title">History</h3>
      {x.moves.length === 0 ? <p className="muted small">No money movements recorded for this deposit.</p> : (
        <ul className="list">{x.moves.map((m) => (
          <li key={m.id}><div className="grow"><div>{m.note ?? m.part}</div><div className="sub">{dayLabel(m.local_date)} · {m.type === 'transfer' ? `${accName(m.account_id)} → ${accName(m.to_account_id ?? '')}` : accName(m.account_id)}</div></div>
            <Money value={m.type === 'transfer' ? (m.to_account_id === dep.account_id ? m.amount : -m.amount) : m.type === 'income' ? m.amount : -m.amount} currency={cur} signed /></li>
        ))}</ul>
      )}
      {can && <div className="row mt"><button onClick={onEdit}><Icon name="edit" size={16} /> Edit</button><button className="danger" onClick={() => setAction('delete')}><Icon name="trash" size={16} /> Delete</button></div>}
      {(action === 'mature' || action === 'close') && <MatureForm x={x} early={action === 'close'} onClose={() => setAction(null)} />}
      {action === 'interest' && <InterestForm x={x} onClose={() => setAction(null)} />}
      {action === 'instalment' && <InstalmentForm x={x} onClose={() => setAction(null)} />}
      {action === 'delete' && <DeleteDeposit x={x} onClose={() => setAction(null)} onDone={onClose} />}
    </Modal>
  )
}

function useBankAccounts(cur: string) {
  const accounts = useAccounts()
  const balances = useBalances()
  return {
    list: (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === cur && a.type !== 'loan' && a.type !== 'deposit' && a.type !== 'credit_card'),
    bal: (id: string) => balances.data?.find((b) => b.account_id === id)?.balance,
  }
}

/** Maturity or early closure: the amount actually received lands in a bank account. */
function MatureForm({ x, early, onClose }: { x: DepositEntry; early: boolean; onClose: () => void }) {
  const { dep, est, balance } = x
  const write = useWrite()
  const cats = useCategories()
  const banks = useBankAccounts(dep.currency)
  const ids = useState(() => [uuidv7(), uuidv7(), uuidv7(), uuidv7()])[0]
  const cur = dep.currency
  const today = isoDate()
  const [received, setReceived] = useState(early ? '' : toPlain(est.maturity, cur))
  const [tax, setTax] = useState('')
  const [date, setDate] = useState(early || dep.maturity_date > today ? today : dep.maturity_date)
  const [into, setInto] = useState(dep.payout_account_id ?? banks.list[0]?.id ?? '')
  const [renew, setRenew] = useState(false)
  const [err, setErr] = useState('')
  const got = parseMoney(received, cur)
  const taxMinor = tax.trim() ? parseMoney(tax, cur) : 0
  // What the bank pays out is the deposit balance plus interest not yet credited, minus any penalty.
  const gross = got !== null && taxMinor !== null ? got + taxMinor : null
  const diff = gross !== null ? gross - balance : null

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (got === null || got <= 0) return setErr('Enter the amount that arrived in your account, as on the bank statement')
    if (taxMinor === null || taxMinor < 0) return setErr('Enter the tax deducted as an amount, or leave it empty')
    if (!into) return setErr(`Add a ${cur} bank account for the money to go to`)
    if (date > today) return setErr('Record the maturity on the day the money arrives, not before')
    if (date < dep.start_date) return setErr('That date is before the deposit started')
    if (diff !== null && diff < 0 && !early && -diff > balance * 0.05) return setErr(`That is ${formatMoney(-diff, cur)} less than what is in the deposit (${formatMoney(balance, cur)}). If it was closed early with a penalty, use “Close early” instead; otherwise check the amount.`)
    const ops: BatchOp[] = []
    const base = { payee: dep.institution || dep.name, date, deposit_id: dep.id }
    // 1. Interest not yet credited (or a penalty) adjusts the deposit to what the bank paid in total.
    if (diff && diff > 0) ops.push(createOp('transactions', ids[0], linkedTxData({ ...base, type: 'income', account_id: dep.account_id, amount: diff, category_id: interestIncomeCategory(cats.data ?? []), note: `${dep.name} · interest at ${early ? 'closure' : 'maturity'}`, part: 'interest' })))
    if (diff && diff < 0) ops.push(createOp('transactions', ids[0], linkedTxData({ ...base, type: 'expense', account_id: dep.account_id, amount: -diff, category_id: feeCategory(cats.data ?? []), note: `${dep.name} · early closure penalty`, part: 'fee' })))
    // 2. Tax deducted at source is a cost taken from the deposit.
    if (taxMinor && taxMinor > 0) ops.push(createOp('transactions', ids[1], linkedTxData({ ...base, type: 'expense', account_id: dep.account_id, amount: taxMinor, note: `${dep.name} · tax deducted`, part: 'fee' })))
    // 3. The money moves to the bank.
    if (!renew) ops.push(createOp('transactions', ids[2], linkedTxData({ ...base, type: 'transfer', account_id: dep.account_id, to_account_id: into, amount: got, note: `${dep.name} · ${early ? 'closed early' : 'matured'}`, part: 'principal' })))
    ops.push({ op: 'update', entity: 'deposits', id: dep.id, data: renew ? { start_date: date, maturity_date: addMonths(date, Math.max(1, Math.round((new Date(dep.maturity_date).getTime() - new Date(dep.start_date).getTime()) / (30.44 * 864e5)))), amount: got, maturity_amount: null }
      : { status: early ? 'closed' : 'matured', closed_date: date } })
    try {
      await write.batch.mutateAsync(ops)
      toast(renew ? `${dep.name} renewed with ${formatMoney(got, cur)}` : `${formatMoney(got, cur)} recorded in your account`)
      onClose()
    } catch (x2) { setErr(saveErrorText(x2)) }
  }
  return (
    <Modal title={early ? `Close ${dep.name} early` : `${dep.name} matured`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="matform" disabled={write.batch.isPending}>Record</button></>}>
      <form id="matform" onSubmit={submit}>
        <p className="muted small">In the deposit now: <Money value={balance} currency={cur} />. {early ? 'Banks usually pay a lower rate when a deposit closes early; enter what you actually received.' : `Expected: ${formatMoney(est.maturity, cur)}${est.fromBank ? '' : ' (estimate)'}.`}</p>
        <div className="two">
          <Field label="Amount received"><NumInput kind="decimal" value={received} onChange={(e) => setReceived(e.target.value)} autoFocus /></Field>
          <Field label="Tax deducted (TDS), if any"><NumInput kind="decimal" value={tax} onChange={(e) => setTax(e.target.value)} placeholder="0" /></Field>
        </div>
        <div className="two">
          <Field label="Into account"><Select value={into} onChange={(e) => setInto(e.target.value)} disabled={renew}>{banks.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
          <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={today} /></Field>
        </div>
        {!early && <Check checked={renew} onChange={setRenew}>The bank renewed it for the same term (the money stays in the deposit)</Check>}
        {diff !== null && diff !== 0 && <p className="small">{diff > 0 ? <>Records <Money value={diff} currency={cur} /> as interest income.</> : <>Records <Money value={-diff} currency={cur} /> as a penalty (a cost).</>}</p>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

function InterestForm({ x, onClose }: { x: DepositEntry; onClose: () => void }) {
  const { dep, est } = x
  const write = useWrite()
  const cats = useCategories()
  const banks = useBankAccounts(dep.currency)
  const id = useStableId()
  const cur = dep.currency
  const payout = dep.payout !== 'cumulative'
  const next = est.payouts.find((p) => !x.moves.some((m) => m.part === 'interest' && m.local_date.slice(0, 7) === p.date.slice(0, 7)))
  const [amount, setAmount] = useState(next ? toPlain(next.amount, cur) : '')
  const [date, setDate] = useState(next && next.date <= isoDate() ? next.date : isoDate())
  const [into, setInto] = useState(payout ? dep.payout_account_id ?? banks.list[0]?.id ?? '' : dep.account_id)
  const [err, setErr] = useState('')
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    const m = parseMoney(amount, cur)
    if (!m || m <= 0) return setErr('Enter the interest amount from your statement')
    if (date > isoDate()) return setErr('Record interest when it is credited, not in advance')
    try {
      await write.batch.mutateAsync([createOp('transactions', id, linkedTxData({ type: 'income', account_id: into, amount: m, category_id: interestIncomeCategory(cats.data ?? []), payee: dep.institution || dep.name, note: `${dep.name} · interest`, date, deposit_id: dep.id, part: 'interest' }))])
      toast('Interest recorded'); onClose()
    } catch (x2) { setErr(saveErrorText(x2)) }
  }
  return (
    <Modal title={`Interest · ${dep.name}`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="intform" disabled={write.batch.isPending}>Record</button></>}>
      <form id="intform" onSubmit={submit}>
        <p className="muted small">{payout ? 'Interest paid out to your account. It counts as income.' : 'Interest the bank added to the deposit. It counts as income and grows the deposit.'}</p>
        <div className="two">
          <Field label="Interest"><NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
          <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
        </div>
        {payout && <Field label="Paid into"><Select value={into} onChange={(e) => setInto(e.target.value)}>{banks.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

function InstalmentForm({ x, onClose }: { x: DepositEntry; onClose: () => void }) {
  const { dep } = x
  const write = useWrite()
  const banks = useBankAccounts(dep.currency)
  const id = useStableId()
  const due = addMonths(dep.start_date, x.rdPaid)
  const [date, setDate] = useState(due <= isoDate() ? due : isoDate())
  const [from, setFrom] = useState(banks.list[0]?.id ?? '')
  const [err, setErr] = useState('')
  const bal = from ? banks.bal(from) : undefined
  const submit = async () => {
    setErr('')
    if (write.batch.isPending) return
    if (!from) return setErr(`Add a ${dep.currency} account to pay from`)
    try {
      await write.batch.mutateAsync([createOp('transactions', id, linkedTxData({ type: 'transfer', account_id: from, to_account_id: dep.account_id, amount: dep.amount, payee: dep.institution || dep.name, note: `${dep.name} · instalment ${x.rdPaid + 1}`, date, deposit_id: dep.id, part: 'principal' }))])
      toast(`Instalment ${x.rdPaid + 1} recorded`); onClose()
    } catch (x2) { setErr(saveErrorText(x2)) }
  }
  return (
    <Modal title={`RD instalment ${x.rdPaid + 1}`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" onClick={submit} disabled={write.batch.isPending}>Record <Money value={dep.amount} currency={dep.currency} /></button></>}>
      <p className="muted small">Due {dayLabel(due)}. Moves the instalment from your account into the deposit; it is saving, not spending.</p>
      <div className="two">
        <Field label="From account" hint={bal !== undefined ? <>Balance <Money value={bal} currency={dep.currency} /></> : undefined}><Select value={from} onChange={(e) => setFrom(e.target.value)}>{banks.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
        <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
      </div>
      {bal !== undefined && bal < dep.amount && <p className="warn-text small"><Icon name="alert" size={14} /> The account has less than the instalment.</p>}
      {err && <p className="error" role="alert">{err}</p>}
    </Modal>
  )
}

function DeleteDeposit({ x, onClose, onDone }: { x: DepositEntry; onClose: () => void; onDone: () => void }) {
  const write = useWrite()
  const [everything, setEverything] = useState(false)
  return (
    <Confirm title={`Delete ${x.dep.name}?`} danger confirm="Delete" onClose={onClose} onConfirm={async () => {
      const ops: BatchOp[] = [{ op: 'delete', entity: 'deposits', id: x.dep.id }]
      if (everything) { for (const m of x.moves) ops.push({ op: 'delete', entity: 'transactions', id: m.id }); ops.push({ op: 'delete', entity: 'accounts', id: x.dep.account_id }) }
      await write.batch.mutateAsync(ops)
      toast('Deposit deleted', { label: 'Undo', run: () => write.batch.mutate(ops.map((o) => ({ op: 'restore' as const, entity: o.entity, id: o.id }))) })
      onDone()
    }} body={<>
      <p>If it matured or was closed, recording that keeps the history; deleting is for a deposit added by mistake.</p>
      <Check checked={everything} onChange={setEverything}>Also delete its deposit account and {x.moves.length} linked transactions (the money goes back to the accounts it came from)</Check>
      {!everything && x.balance !== 0 && <p className="warn-text small">The deposit account keeps its balance of {formatMoney(x.balance, x.dep.currency)} and stays in your net worth.</p>}
    </>} />
  )
}

// --- Create / edit ---------------------------------------------------------------------

function DepositForm({ dep, onClose }: { dep?: DepRec; onClose: () => void }) {
  const l = useLedger()
  const write = useWrite()
  const [cur, setCur] = useState(dep?.currency ?? l.base_currency)
  const banks = useBankAccounts(cur)
  const depId = useStableId(dep?.id)
  const accountId = useStableId(dep?.account_id)
  const fundId = useStableId()
  const today = isoDate()
  const [kind, setKind] = useState<Deposit['kind']>(dep?.kind ?? 'fd')
  const [name, setName] = useState(dep?.name ?? '')
  const [institution, setInstitution] = useState(dep?.institution ?? '')
  const [amount, setAmount] = useState(dep ? toPlain(dep.amount, dep.currency) : '')
  const [rate, setRate] = useState(dep?.annual_rate ?? '')
  const [start, setStart] = useState(dep?.start_date ?? today)
  const [termMode, setTermMode] = useState<'date' | 'months'>(dep ? 'date' : 'months')
  const [maturity, setMaturity] = useState(dep?.maturity_date ?? addMonths(today, 12))
  const [termMonths, setTermMonths] = useState('12')
  const [compounding, setCompounding] = useState(dep?.compounding ?? 'quarterly')
  const [payout, setPayout] = useState(dep?.payout ?? 'cumulative')
  const [payoutAccount, setPayoutAccount] = useState(dep?.payout_account_id ?? banks.list[0]?.id ?? '')
  const [bankMaturity, setBankMaturity] = useState(dep?.maturity_amount ? toPlain(dep.maturity_amount, dep.currency) : '')
  const [taxPct, setTaxPct] = useState(dep?.tax_percent ?? '')
  const [autoRenew, setAutoRenew] = useState(dep?.auto_renew ?? false)
  const [remind, setRemind] = useState(String(dep?.remind_days ?? 7))
  const [note, setNote] = useState(dep?.note ?? '')
  const [funded, setFunded] = useState<'account' | 'outside'>('account')
  const [fromAccount, setFromAccount] = useState(banks.list[0]?.id ?? '')
  const [err, setErr] = useState('')
  const end = termMode === 'months' ? (Number(termMonths) > 0 ? addMonths(start, Number(termMonths)) : start) : maturity
  const minor = parseMoney(amount, cur)
  const bankMinor = bankMaturity.trim() ? parseMoney(bankMaturity, cur) : null
  const preview = minor && minor > 0 && !rateError(rate) && end > start ? estimate({ kind, amount: minor, annual_rate: rate, start_date: start, maturity_date: end, compounding, payout: kind === 'rd' ? 'cumulative' : payout, maturity_amount: bankMinor, tax_percent: taxPct || null }, today) : null
  const fromBal = fromAccount ? banks.bal(fromAccount) : undefined

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (!name.trim()) return setErr('Give the deposit a name, like “SBI FD 2027”')
    if (!minor || minor <= 0) return setErr(kind === 'rd' ? 'Enter the monthly instalment' : 'Enter the amount deposited')
    const re = rateError(rate); if (re) return setErr(re)
    if (termMode === 'months' && (!Number.isInteger(Number(termMonths)) || Number(termMonths) < 1 || Number(termMonths) > 600)) return setErr('Enter the term as whole months, from 1 to 600')
    if (end <= start) return setErr('The maturity date must be after the start date')
    if (bankMaturity.trim() && (bankMinor === null || bankMinor <= 0)) return setErr("Enter the bank's maturity amount, or leave it empty to use the estimate")
    if (bankMinor && kind !== 'rd' && payout === 'cumulative' && bankMinor < minor) return setErr("The bank's maturity amount is less than the principal. Check the figure.")
    if (taxPct.trim() && (!/^\d+(\.\d+)?$/.test(taxPct.trim()) || Number(taxPct) > 50)) return setErr('Enter the tax rate as a number between 0 and 50, or leave it empty')
    const remindDays = Number(remind)
    if (!Number.isInteger(remindDays) || remindDays < 0 || remindDays > 60) return setErr('Reminder days must be 0 to 60')
    const data: Partial<Deposit> = {
      name: name.trim(), institution: institution.trim() || null, kind, currency: cur, amount: minor, annual_rate: rate.trim(), start_date: start, maturity_date: end,
      compounding, payout: kind === 'rd' ? 'cumulative' : payout, payout_account_id: payout !== 'cumulative' ? payoutAccount || null : null,
      maturity_amount: bankMinor, tax_percent: taxPct.trim() || null, auto_renew: autoRenew, remind_days: remindDays, note: note.trim() || null,
    }
    const ops: BatchOp[] = []
    if (dep) {
      ops.push({ op: 'update', entity: 'deposits', id: dep.id, data })
      ops.push({ op: 'update', entity: 'accounts', id: dep.account_id, data: { name: name.trim() } })
    } else {
      const fromBank = funded === 'account' && fromAccount && kind !== 'rd'
      // An RD already running starts with the instalments due so far.
      const rdSoFar = kind === 'rd' && funded === 'outside' ? (() => { let n = 0; while (addMonths(start, n) <= today && addMonths(start, n) < end) n++; return n })() : 0
      ops.push(createOp('accounts', accountId, { name: name.trim(), type: 'deposit', currency: cur, opening_balance: kind === 'rd' ? rdSoFar * minor : fromBank ? 0 : minor, include_in_net_worth: true, archived: false, sort_order: 0, note: institution.trim() || null, color: '#0F766E', created_at: Date.now() }))
      ops.push(createOp('deposits', depId, { ...data, account_id: accountId, status: 'active', closed_date: null, created_at: Date.now() }))
      if (fromBank) ops.push(createOp('transactions', fundId, linkedTxData({ type: 'transfer', account_id: fromAccount, to_account_id: accountId, amount: minor, payee: institution.trim() || name.trim(), note: `${name.trim()} · deposit opened`, date: start <= today ? start : today, deposit_id: depId, part: 'principal' })))
      if (kind === 'rd' && funded === 'account' && fromAccount && start <= today) ops.push(createOp('transactions', fundId, linkedTxData({ type: 'transfer', account_id: fromAccount, to_account_id: accountId, amount: minor, payee: institution.trim() || name.trim(), note: `${name.trim()} · instalment 1`, date: start, deposit_id: depId, part: 'principal' })))
    }
    try {
      await write.batch.mutateAsync(ops)
      toast(dep ? 'Deposit updated' : `${name.trim()} added${preview ? `; about ${formatMoney(preview.maturity, cur)} at maturity` : ''}`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }
  return (
    <Modal wide title={dep ? `Edit ${dep.name}` : 'Add a deposit'} onClose={onClose} footer={<><span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="depform" disabled={write.batch.isPending}>{write.batch.isPending ? 'Saving…' : 'Save'}</button></>}>
      <form id="depform" onSubmit={submit}>
        <Segmented label="Kind" value={kind} onChange={setKind} options={DEPOSIT_KINDS as [Deposit['kind'], string][]} />
        <div className="two">
          <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} placeholder="SBI FD, Post office RD…" autoFocus={!dep} maxLength={100} /></Field>
          <Field label="Bank or institution"><input value={institution} onChange={(e) => setInstitution(e.target.value)} maxLength={100} /></Field>
        </div>
        <div className="two">
          <Field label={kind === 'rd' ? 'Monthly instalment' : 'Amount deposited'}><NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0" /></Field>
          <Field label="Interest rate" hint={rate.trim() && rateError(rate) ? undefined : '% a year, as on the receipt'} error={rate.trim() ? rateError(rate) ?? undefined : undefined}><NumInput kind="decimal" value={rate} onChange={(e) => setRate(e.target.value)} placeholder="7.1" /></Field>
        </div>
        <div className="two">
          <Field label="Start date"><DateField value={start} onChange={(e) => setStart(e.target.value)} /></Field>
          <Field label="Currency" hint={dep ? 'Fixed once created' : undefined}><CurrencySelect value={cur} onChange={setCur} disabled={!!dep} /></Field>
        </div>
        <div className="two">
          <Field label="Term">
            <Segmented label="Term as" value={termMode} onChange={setTermMode} options={[['months', 'Months'], ['date', 'Maturity date']]} />
            {termMode === 'months' ? <NumInput kind="int" value={termMonths} onChange={(e) => setTermMonths(e.target.value)} aria-label="Months" /> : <DateField value={maturity} onChange={(e) => setMaturity(e.target.value)} aria-label="Maturity date" />}
          </Field>
          <Field label="Compounding"><Select value={compounding} onChange={(e) => setCompounding(e.target.value)}>{COMPOUNDINGS.map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></Field>
        </div>
        {kind !== 'rd' && (
          <div className="two">
            <Field label="Interest paid"><Select value={payout} onChange={(e) => setPayout(e.target.value)}>{PAYOUTS.map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></Field>
            {payout !== 'cumulative' && <Field label="Payouts go to"><Select value={payoutAccount} onChange={(e) => setPayoutAccount(e.target.value)}>{banks.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>}
          </div>
        )}
        {!dep && (
          <div className="subtle-box">
            <Segmented label="Funding" value={funded} onChange={setFunded} options={[['account', kind === 'rd' ? 'Instalments come from my account' : 'Paid from my account'], ['outside', 'Already open before LekhaMint']]} />
            {funded === 'account' ? (
              <Field label="From account" hint={fromBal !== undefined ? <>Balance <Money value={fromBal} currency={cur} /></> : undefined}><Select value={fromAccount} onChange={(e) => setFromAccount(e.target.value)}>{banks.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
            ) : <p className="muted small">The deposit starts with this amount; no account balance goes down.</p>}
            {funded === 'account' && kind !== 'rd' && fromBal !== undefined && minor && fromBal < minor && <p className="warn-text small"><Icon name="alert" size={14} /> The account has less than the deposit amount.</p>}
          </div>
        )}
        <div className="two">
          <Field label="Bank's maturity amount (optional)" hint="From the receipt; used instead of our estimate"><NumInput kind="decimal" value={bankMaturity} onChange={(e) => setBankMaturity(e.target.value)} placeholder={preview ? toPlain(preview.maturity, cur) : ''} /></Field>
          <Field label="Tax on interest (optional)" hint="% for the estimate, e.g. 10 for TDS"><NumInput kind="decimal" value={taxPct} onChange={(e) => setTaxPct(e.target.value)} placeholder="0" /></Field>
        </div>
        <div className="two">
          <Field label="Remind me before maturity"><div className="row nowrap"><NumInput kind="int" value={remind} onChange={(e) => setRemind(e.target.value)} style={{ maxWidth: 70 }} /><span className="muted">days</span></div></Field>
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Receipt number, nominee…" maxLength={2000} /></Field>
        </div>
        <Check checked={autoRenew} onChange={setAutoRenew}>Set to renew automatically at maturity</Check>
        {preview && (
          <div className="summary-strip">
            <div><span className="muted small">Invested</span><strong><Money value={preview.invested} currency={cur} /></strong></div>
            <div><span className="muted small">Interest</span><strong><Money value={preview.interest} currency={cur} /></strong></div>
            <div><span className="muted small">{payout === 'cumulative' || kind === 'rd' ? 'At maturity' : 'Principal back'}</span><strong><Money value={preview.maturity} currency={cur} /></strong></div>
            <div><span className="muted small">Matures</span><strong>{dayLabel(end)}</strong><span className="muted small">{tenureLabel(start, end)}</span></div>
          </div>
        )}
        {preview && !preview.fromBank && <p className="muted small">An estimate from the terms above. Your bank's figure may differ by a little.</p>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}
