import { useEffect, useMemo, useState } from 'react'
import type { BatchOp, Loan, Transaction } from '../lib/api'
import { useAccounts, useBalances, useCanWrite, useCategories, useLedger, useLinkedMoves, useLoans, useStableId, useWrite, type WithId } from '../lib/data'
import { LOAN_KINDS, emi, loanKindLabel, loanStatus, monthlyInterest, prepaymentOptions, rateError, schedule, splitPayment, type LoanStatus, type ScheduleRow } from '../lib/loans'
import { createOp, feeCategory, interestExpenseCategory, linkedTxData } from '../lib/links'
import { currency, formatMoney, parseMoney, toPlain } from '../lib/money'
import { addMonths, dayLabel, download, isoDate, monthRange, plural, uuidv7 } from '../lib/util'
import { workbook, XLSX_TYPE } from '../lib/xlsx'
import { Card, Check, Confirm, CurrencySelect, Empty, Field, Modal, Money, Segmented, Skeleton, saveErrorText, toast } from '../components'
import { SERIES, StackBar } from '../charts'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, DateField, NumInput } from '../controls'

/**
 * Loans & EMIs (docs/02, "Loans and deposits"). The amount owed lives in a
 * liability account; an EMI is a principal transfer into it plus an interest
 * expense, so balances, net worth and spending reports are right without any
 * special cases. Schedules are projected from the balance actually owed.
 */

type LoanRec = WithId<Loan>
type Move = Transaction & { id: string }
type Moves = Map<string, Move[]>
export type LoanEntry = { loan: LoanRec; moves: Move[]; status: LoanStatus }

export function useLoanStatuses() {
  const loans = useLoans()
  const moves = useLinkedMoves('loan_id')
  const balances = useBalances()
  const today = isoDate()
  const list = useMemo((): LoanEntry[] => (loans.data ?? []).filter((x) => !x.deleted).map((loan) => {
    const bal = balances.data?.find((b) => b.account_id === loan.account_id)?.balance ?? 0
    const ms = moves.data?.get(loan.id) ?? []
    return { loan, moves: ms, status: loanStatus(loan, ms, bal, today, loan.account_id) }
  }), [loans.data, moves.data, balances.data, today])
  return { list, pending: loans.isPending || moves.isPending || balances.isPending, moves: moves.data ?? new Map() as Moves }
}

export function Loans({ params, go }: PageProps) {
  const { list, pending } = useLoanStatuses()
  const can = useCanWrite()
  const today = isoDate()
  const [edit, setEdit] = useState<LoanRec | 'new' | null>(null)
  const [openId, setOpenId] = useState<string | null>(params.id ?? null)
  // The EMI calculator links here with ?new=1&principal=…&rate=…&months=… to start a loan from its numbers.
  const [preset, setPreset] = useState<LoanPreset | undefined>()
  useEffect(() => {
    if (params.new === '1' && can) { setPreset({ principal: Number(params.principal) || 0, rate: params.rate ?? '', months: Number(params.months) || 0 }); setEdit('new'); go('loans') }
  }, [params.new]) // eslint-disable-line react-hooks/exhaustive-deps
  const [showClosed, setShowClosed] = useState(false)
  const active = list.filter((x) => !x.loan.closed)
  const shown = list.filter((x) => showClosed || !x.loan.closed)
    .sort((a, b) => Number(a.loan.closed) - Number(b.loan.closed) || (a.status.nextDue ?? '9').localeCompare(b.status.nextDue ?? '9'))
  const sel = openId ? list.find((x) => x.loan.id === openId || x.loan.account_id === openId) : undefined
  const [, mTo] = monthRange(today)
  const byCur = (f: (x: LoanEntry) => number) => {
    const m = new Map<string, number>()
    for (const x of active) m.set(x.loan.currency, (m.get(x.loan.currency) ?? 0) + f(x))
    return [...m].filter(([, v]) => v !== 0)
  }
  const owed = byCur((x) => x.status.outstanding)
  const dueThisMonth = byCur((x) => x.status.projection.rows.filter((r) => r.date <= mTo).reduce((a, r) => a + r.payment, 0))
  const year = today.slice(0, 4)
  const interestYear = byCur((x) => x.moves.filter((m) => m.part === 'interest' && m.local_date.startsWith(year)).reduce((a, m) => a + m.amount, 0))
  const overdue = active.filter((x) => x.status.overdue > 0)

  return (
    <>
      <div className="page-head"><h1>Loans & EMIs</h1>
        <div className="row">
          <button onClick={() => go('tools', { tool: 'emi' })}><Icon name="calc" size={16} /> EMI calculator</button>
          {can && <button className="primary" onClick={() => setEdit('new')}><Icon name="plus" size={16} /> Add loan</button>}
        </div>
      </div>
      {list.length > 0 && (
        <div className="grid stats" style={{ marginBottom: 16 }}>
          <Card className="stat-card"><div className="stat-label">Still owed</div>{owed.length ? owed.map(([c, v]) => <div key={c} className="stat-big"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">Nothing</div>}
            <div className="muted small">Principal outstanding on {active.length} {active.length === 1 ? 'loan' : 'loans'}</div></Card>
          <Card className="stat-card"><div className="stat-label">EMIs due by {dayLabel(mTo)}</div>{dueThisMonth.length ? dueThisMonth.map(([c, v]) => <div key={c} className="stat-value"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">None left this month</div>}
            <div className="muted small">Including anything overdue</div></Card>
          <Card className="stat-card"><div className="stat-label">Interest paid in {year}</div>{interestYear.length ? interestYear.map(([c, v]) => <div key={c} className="stat-value"><Money value={v} currency={c} /></div>) : <div className="stat-value muted">—</div>}
            <div className="muted small">From the payments you recorded</div></Card>
        </div>
      )}
      {overdue.length > 0 && (
        <div className="insights">
          {overdue.map((x) => (
            <button key={x.loan.id} className="insight warn" onClick={() => setOpenId(x.loan.id)}>
              <Icon name="alert" /><div><strong>{x.loan.name}: {x.status.overdue === 1 ? 'an EMI is' : `${x.status.overdue} EMIs are`} past due</strong>
                <span>Due {dayLabel(x.status.nextDue!)}. Record the payment if you have paid, so the schedule stays right.</span></div><Icon name="right" />
            </button>
          ))}
        </div>
      )}
      {pending ? <Card><Skeleton rows={4} /></Card> : list.length === 0 ? (
        <Card><Empty icon="bank" title="No loans recorded" body="Add a home, car, personal or any other loan, new or already running. LekhaMint keeps the EMI schedule, splits each payment into principal and interest, and reminds you before it is due."
          action={can ? <button className="primary" onClick={() => setEdit('new')}>Add a loan</button> : undefined} /></Card>
      ) : (
        <div className="stack">
          {shown.map(({ loan, status }) => <LoanCard key={loan.id} loan={loan} s={status} today={today} onOpen={() => setOpenId(loan.id)} />)}
          {list.some((x) => x.loan.closed) && <Check checked={showClosed} onChange={setShowClosed}>Show closed loans</Check>}
        </div>
      )}
      <p className="muted small">Schedules assume interest is charged monthly on the reducing balance. Your lender may round or count days differently; enter the EMI and each payment's interest as your statement shows and LekhaMint follows them.</p>
      {edit && <LoanForm loan={edit === 'new' ? undefined : edit} preset={edit === 'new' ? preset : undefined} onClose={() => { setEdit(null); setPreset(undefined) }} />}
      {sel && <LoanDetail x={sel} onClose={() => setOpenId(null)} onEdit={() => { setEdit(sel.loan); setOpenId(null) }} go={go} />}
    </>
  )
}

function LoanCard({ loan, s, today, onOpen }: { loan: LoanRec; s: LoanStatus; today: string; onOpen: () => void }) {
  const due = s.nextDue
  const soon = !!due && due >= today && due <= isoDate(new Date(Date.now() + Math.max(loan.remind_days, 3) * 864e5))
  return (
    <Card className={`loan-card clickable ${loan.closed ? 'muted-row' : ''}`}>
      <div className="loan-head" onClick={onOpen} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') onOpen() }}>
        <span className="acc-icon" style={{ ['--c' as string]: 'var(--neg)' }}><Icon name="bank" size={18} /></span>
        <div className="grow">
          <div className="loan-title">{loan.name}{loan.closed && <span className="pill ok">Closed</span>}{!loan.closed && s.overdue > 0 && <span className="pill danger">Overdue</span>}{!loan.closed && !s.overdue && soon && <span className="pill warn">Due soon</span>}</div>
          <div className="sub muted small">{[loanKindLabel(loan.kind), loan.lender, loan.annual_rate ? `${loan.annual_rate}% ${loan.rate_type}` : 'Interest-free'].filter(Boolean).join(' · ')}</div>
        </div>
        <div className="right">
          <Money value={s.outstanding} currency={loan.currency} className="stat-value" />
          <div className="muted small">{loan.closed ? 'closed' : 'owed'}</div>
        </div>
      </div>
      {!loan.closed && (
        <>
          <StackBar inline currency={loan.currency} label={`${loan.name}: repaid, interest paid, still owed and interest still to come`} parts={[
            { name: 'Principal repaid', value: s.principalPaid, color: SERIES[0] },
            { name: 'Interest paid', value: s.interestPaid, color: SERIES[1] },
            { name: 'Still owed', value: s.outstanding, color: 'var(--line-strong)' },
            { name: 'Interest to come (est.)', value: s.projectedInterest, color: SERIES[3] },
          ]} />
          <div className="loan-facts small">
            <span><span className="muted">EMI</span> <Money value={s.emi} currency={loan.currency} /></span>
            {due && <span className={s.overdue ? 'error' : ''}><span className="muted">Next</span> {dayLabel(due)}</span>}
            <span><span className="muted">Paid</span> {s.paidInstalments} of {s.paidInstalments + s.remainingInstalments}</span>
            {s.endDate && <span><span className="muted">Ends</span> {new Date(s.endDate).toLocaleDateString(undefined, { month: 'short', year: 'numeric' })}</span>}
            {s.overpaid > 0 && <span className="warn-text">Overpaid by <Money value={s.overpaid} currency={loan.currency} /></span>}
          </div>
        </>
      )}
    </Card>
  )
}

// --- Detail ------------------------------------------------------------------

function LoanDetail({ x, onClose, onEdit, go }: { x: LoanEntry; onClose: () => void; onEdit: () => void; go: PageProps['go'] }) {
  const { loan, status: s, moves } = x
  const can = useCanWrite()
  const accounts = useAccounts()
  const [tab, setTab] = useState<'schedule' | 'payments' | 'terms'>('schedule')
  const [action, setAction] = useState<'pay' | 'prepay' | 'close' | 'delete' | null>(null)
  const accName = (id: string) => accounts.data?.find((a) => a.id === id)?.name ?? '—'
  const payments = useMemo(() => groupPayments(moves, loan.account_id), [moves, loan.account_id])
  const cur = loan.currency
  const exportSchedule = () => {
    const d = currency(cur).digits
    const past = payments.map((p, i) => [loan.paid_before + i + 1, { date: p.date }, 'Paid', { money: p.principal + p.interest + p.fee, digits: d }, { money: p.principal, digits: d }, { money: p.interest, digits: d }, { money: p.fee, digits: d }, null])
    const future = s.projection.rows.map((r) => [r.n, { date: r.date }, r.date < isoDate() ? 'Overdue' : 'Projected', { money: r.payment, digits: d }, { money: r.principal, digits: d }, { money: r.interest, digits: d }, null, { money: r.closing, digits: d }])
    const bytes = workbook([{ name: 'Schedule', intro: [`${loan.name} · ${loanKindLabel(loan.kind)}${loan.lender ? ` · ${loan.lender}` : ''}`, `Principal ${formatMoney(loan.principal, cur)} · ${loan.annual_rate ? `${loan.annual_rate}% a year` : 'interest-free'} · ${loan.tenure_months} months · EMI ${formatMoney(s.emi, cur)}`,
      `Outstanding ${formatMoney(s.outstanding, cur)} on ${isoDate()} · amounts in ${cur}`, 'Projected rows assume monthly interest on the reducing balance; your lender may differ slightly.'],
      header: ['#', 'Date', 'Status', 'Payment', 'Principal', 'Interest', 'Fees', 'Balance after'], rows: [...past, ...future] as never }])
    download(`${loan.name.replace(/[^\w-]+/g, '-')}-schedule.xlsx`, new Blob([bytes], { type: XLSX_TYPE }))
  }
  return (
    <Modal wide title={loan.name} onClose={onClose} footer={<>
      <button type="button" onClick={exportSchedule}><Icon name="table" size={16} /> Excel</button>
      <button type="button" onClick={() => go('print', { kind: 'loan', id: loan.id })}><Icon name="printer" size={16} /> Print / PDF</button>
      <span className="spacer" />
      {can && !loan.closed && s.outstanding > 0 && <button type="button" onClick={() => setAction('prepay')}>Prepay</button>}
      {can && !loan.closed && <button type="button" onClick={() => setAction('close')}>{s.outstanding > 0 ? 'Foreclose' : 'Mark closed'}</button>}
      {can && !loan.closed && s.outstanding > 0 && <button type="button" className="primary" onClick={() => setAction('pay')}>Pay EMI</button>}
    </>}>
      <div className="kv-grid">
        <div><span className="muted small">Outstanding</span><strong className="stat-value"><Money value={s.outstanding} currency={cur} /></strong></div>
        <div><span className="muted small">EMI</span><strong><Money value={s.emi} currency={cur} /></strong></div>
        <div><span className="muted small">Next due</span><strong className={s.overdue ? 'error' : ''}>{s.nextDue ? dayLabel(s.nextDue) : '—'}</strong>{s.nextDue && <span className="muted small">≈ <Money value={s.nextInterest} currency={cur} /> interest + <Money value={s.nextPrincipal} currency={cur} /> principal</span>}</div>
        <div><span className="muted small">Instalments</span><strong>{s.paidInstalments} paid · {s.remainingInstalments} left</strong></div>
        <div><span className="muted small">Paid so far</span><strong><Money value={s.principalPaid + s.prepaid + s.interestPaid + s.feesPaid} currency={cur} /></strong>
          <span className="muted small"><Money value={s.principalPaid + s.prepaid} currency={cur} /> principal · <Money value={s.interestPaid} currency={cur} /> interest{s.feesPaid ? <> · <Money value={s.feesPaid} currency={cur} /> fees</> : ''}</span></div>
        <div><span className="muted small">Interest still to pay</span><strong><Money value={s.projectedInterest} currency={cur} /></strong><span className="muted small">{s.endDate ? `Last EMI ${dayLabel(s.endDate)}` : ''}</span></div>
      </div>
      {s.projection.neverRepaid && <div className="callout warn"><Icon name="alert" size={16} /><div>The EMI of {formatMoney(s.emi, cur)} does not cover the monthly interest of {formatMoney(monthlyInterest(s.outstanding, loan.annual_rate), cur)}, so this loan would never be repaid. Check the EMI and rate under Terms.</div></div>}
      {s.overpaid > 0 && <div className="callout warn"><Icon name="alert" size={16} /><div>More has been paid than was owed: the loan account shows {formatMoney(s.overpaid, cur)} in credit. Check the payments for a duplicate, or record the refund from the lender.</div></div>}
      <Segmented label="Section" value={tab} onChange={setTab} options={[['schedule', 'Schedule'], ['payments', `Payments (${payments.length})`], ['terms', 'Terms']]} />
      {tab === 'schedule' && <ScheduleTable loan={loan} paid={payments} rows={s.projection.rows} truncated={s.projection.truncated} />}
      {tab === 'payments' && <PaymentsList loan={loan} payments={payments} accName={accName} />}
      {tab === 'terms' && (
        <div>
          <dl className="terms">
            <dt>Type</dt><dd>{loanKindLabel(loan.kind)}</dd>
            {loan.lender && <><dt>Lender</dt><dd>{loan.lender}</dd></>}
            <dt>Borrowed</dt><dd><Money value={loan.principal} currency={cur} /> on {dayLabel(loan.start_date)}</dd>
            <dt>Interest</dt><dd>{loan.annual_rate ? `${loan.annual_rate}% a year, ${loan.rate_type}` : 'Interest-free'}</dd>
            <dt>Tenure</dt><dd>{loan.tenure_months} months from {dayLabel(loan.first_due_date)}</dd>
            <dt>EMI</dt><dd><Money value={s.emi} currency={cur} />{loan.emi_amount ? ' (as per lender)' : ' (calculated)'}</dd>
            {loan.paid_before > 0 && <><dt>Paid before tracking</dt><dd>{loan.paid_before} instalments</dd></>}
            <dt>Paid from</dt><dd>{loan.pay_from_account_id ? accName(loan.pay_from_account_id) : 'Chosen each time'}</dd>
            {loan.processing_fee ? <><dt>Processing fee</dt><dd><Money value={loan.processing_fee} currency={cur} /></dd></> : null}
            <dt>Reminder</dt><dd>{loan.remind_days ? `${plural(loan.remind_days, 'day')} before each EMI` : 'Off'}</dd>
            {loan.closed && <><dt>Closed</dt><dd>{loan.closed_date ? dayLabel(loan.closed_date) : 'Yes'}</dd></>}
            {loan.note && <><dt>Note</dt><dd>{loan.note}</dd></>}
          </dl>
          {can && <div className="row"><button onClick={onEdit}><Icon name="edit" size={16} /> Edit terms or rate</button><button className="danger" onClick={() => setAction('delete')}><Icon name="trash" size={16} /> Delete loan</button></div>}
        </div>
      )}
      {action === 'pay' && <PayEmi loan={loan} s={s} onClose={() => setAction(null)} />}
      {action === 'prepay' && <Prepay loan={loan} s={s} onClose={() => setAction(null)} />}
      {action === 'close' && <CloseLoan loan={loan} s={s} onClose={() => setAction(null)} />}
      {action === 'delete' && <DeleteLoan loan={loan} moves={moves} onClose={() => setAction(null)} onDone={onClose} />}
    </Modal>
  )
}

export interface PaymentGroup { date: string; ids: string[]; principal: number; prepaid: number; interest: number; fee: number; accountId: string }

/** Payments on the same day form one instalment: principal transfer + interest + any fee. Disbursal is left out. */
export function groupPayments(moves: { id: string; type: string; amount: number; to_amount?: number | null; to_account_id?: string | null; account_id: string; part?: string | null; local_date: string }[], loanAccount: string): PaymentGroup[] {
  const by = new Map<string, PaymentGroup>()
  for (const m of moves) {
    if (!m.part) continue // disbursal and other linked movements
    if (m.type === 'transfer' && m.to_account_id !== loanAccount) continue
    const g = by.get(m.local_date) ?? { date: m.local_date, ids: [], principal: 0, prepaid: 0, interest: 0, fee: 0, accountId: m.account_id }
    g.ids.push(m.id)
    const amt = m.type === 'transfer' ? (m.to_amount ?? m.amount) : m.amount
    if (m.part === 'principal') g.principal += amt
    else if (m.part === 'prepayment') g.prepaid += amt
    else if (m.part === 'interest') g.interest += amt
    else if (m.part === 'fee') g.fee += amt
    by.set(m.local_date, g)
  }
  return [...by.values()].sort((a, b) => a.date.localeCompare(b.date))
}

function ScheduleTable({ loan, paid, rows, truncated }: { loan: LoanRec; paid: PaymentGroup[]; rows: ScheduleRow[]; truncated: boolean }) {
  const [all, setAll] = useState(false)
  const today = isoDate()
  const cur = loan.currency
  const emiPaid = paid.filter((p) => p.principal || p.interest)
  const future = all ? rows : rows.slice(0, 24)
  return (
    <>
      <div className="table-wrap">
        <table className="table compact">
          <thead><tr><th>#</th><th>Date</th><th className="num">Payment</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Balance after</th></tr></thead>
          <tbody>
            {emiPaid.map((p, i) => (
              <tr key={p.date} className="paid-row"><td>{loan.paid_before + i + 1}</td><td><Icon name="check" size={14} className="pos-text" /> {dayLabel(p.date)}</td>
                <td className="num"><Money value={p.principal + p.interest + p.fee} currency={cur} /></td><td className="num"><Money value={p.principal} currency={cur} /></td><td className="num"><Money value={p.interest} currency={cur} /></td><td className="num muted">paid</td></tr>
            ))}
            {future.map((r) => (
              <tr key={r.n} className={r.date < today ? 'overdue-row' : ''}><td>{r.n}</td><td>{r.date < today ? <span className="error">{dayLabel(r.date)} · overdue</span> : dayLabel(r.date)}</td>
                <td className="num"><Money value={r.payment} currency={cur} /></td><td className="num"><Money value={r.principal} currency={cur} /></td><td className="num"><Money value={r.interest} currency={cur} /></td><td className="num"><Money value={r.closing} currency={cur} /></td></tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > 24 && !all && <p className="center"><button className="link" onClick={() => setAll(true)}>Show all {rows.length} remaining instalments</button></p>}
      {truncated && <p className="muted small">The schedule is cut at 600 instalments.</p>}
      {rows.length === 0 && !loan.closed && <p className="muted">Nothing left to pay.</p>}
    </>
  )
}

function PaymentsList({ loan, payments, accName }: { loan: LoanRec; payments: PaymentGroup[]; accName: (id: string) => string }) {
  const write = useWrite()
  const can = useCanWrite()
  const [del, setDel] = useState<PaymentGroup | null>(null)
  if (payments.length === 0) return <p className="muted">No payments recorded yet{loan.paid_before ? ` (${loan.paid_before} were paid before you started tracking)` : ''}.</p>
  return (
    <>
      <ul className="list">
        {[...payments].reverse().map((p) => (
          <li key={p.date}>
            <div className="grow"><div>{p.prepaid ? 'Prepayment' : p.principal || p.interest ? 'EMI' : 'Charges'} · {dayLabel(p.date)}</div>
              <div className="sub">{[p.principal && `principal ${formatMoney(p.principal, loan.currency)}`, p.prepaid && `prepaid ${formatMoney(p.prepaid, loan.currency)}`, p.interest && `interest ${formatMoney(p.interest, loan.currency)}`, p.fee && `fees ${formatMoney(p.fee, loan.currency)}`].filter(Boolean).join(' · ')} · from {accName(p.accountId)}</div></div>
            <Money value={-(p.principal + p.prepaid + p.interest + p.fee)} currency={loan.currency} signed />
            {can && <button className="icon-btn" aria-label="Delete this payment" title="Delete this payment" onClick={() => setDel(p)}><Icon name="trash" size={16} /></button>}
          </li>
        ))}
      </ul>
      {del && <Confirm title="Delete this payment?" danger confirm="Delete payment" onClose={() => setDel(null)} onConfirm={async () => {
        const ops: BatchOp[] = del.ids.map((id) => ({ op: 'delete', entity: 'transactions', id }))
        if (loan.closed) ops.push({ op: 'update', entity: 'loans', id: loan.id, data: { closed: false, closed_date: null } })
        await write.batch.mutateAsync(ops)
        toast('Payment deleted; the balance owed went back up', { label: 'Undo', run: () => write.batch.mutate(del.ids.map((id) => ({ op: 'restore' as const, entity: 'transactions', id }))) })
      }} body={<p>The {dayLabel(del.date)} payment ({del.ids.length} {del.ids.length === 1 ? 'transaction' : 'transactions'}) is removed, so the amount owed and the account it was paid from go back to how they were. Use this to correct a payment recorded twice or by mistake.</p>} />}
    </>
  )
}

// --- Payment forms ---------------------------------------------------------------

function usePayAccounts(cur: string) {
  const accounts = useAccounts()
  const balances = useBalances()
  const list = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === cur && a.type !== 'loan' && a.type !== 'deposit')
  return { list, bal: (id: string) => balances.data?.find((b) => b.account_id === id)?.balance }
}

function PayEmi({ loan, s, onClose }: { loan: LoanRec; s: LoanStatus; onClose: () => void }) {
  const write = useWrite()
  const cats = useCategories()
  const pay = usePayAccounts(loan.currency)
  const cur = loan.currency
  const ids = useState(() => [uuidv7(), uuidv7(), uuidv7()])[0]
  const next = s.projection.rows[0]
  const today = isoDate()
  const [amount, setAmount] = useState(toPlain(next?.payment ?? s.emi, cur))
  const [date, setDate] = useState(next && next.date <= today ? (s.overdue > 1 ? today : next.date) : today)
  const [accountId, setAccountId] = useState(loan.pay_from_account_id && pay.list.some((a) => a.id === loan.pay_from_account_id) ? loan.pay_from_account_id : pay.list[0]?.id ?? '')
  const minor = parseMoney(amount, cur)
  const auto = minor && minor > 0 ? splitPayment(minor, s.outstanding, loan.annual_rate) : { interest: 0, principal: 0 }
  const [interestText, setInterestText] = useState('')
  const [fee, setFee] = useState('')
  const [err, setErr] = useState('')
  const interest = interestText.trim() ? parseMoney(interestText, cur) : auto.interest
  const principal = minor && interest !== null ? minor - interest : 0
  const feeMinor = fee.trim() ? parseMoney(fee, cur) : 0
  const bal = accountId ? pay.bal(accountId) : undefined
  const total = (minor ?? 0) + (feeMinor ?? 0)
  const closes = principal === s.outstanding && principal > 0

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (!minor || minor <= 0) return setErr('Enter the amount paid, more than zero')
    if (interest === null || interest < 0) return setErr('Enter the interest part as an amount, or leave it empty to use the calculated figure')
    if (interest > minor) return setErr(`The interest (${formatMoney(interest, cur)}) cannot be more than the payment (${formatMoney(minor, cur)}).`)
    if (principal > s.outstanding) return setErr(`That repays ${formatMoney(principal, cur)} of principal, but only ${formatMoney(s.outstanding, cur)} is owed. Pay ${formatMoney(s.outstanding + interest, cur)} to close the loan, or use Foreclose.`)
    if (feeMinor === null || feeMinor < 0) return setErr('Enter the late fee or charge as an amount, or leave it empty')
    if (!accountId) return setErr(`Add a ${cur} bank or cash account to pay from`)
    if (date > today) return setErr('That date is in the future. Record a payment when it is made; upcoming EMIs are already on the schedule.')
    if (date < loan.start_date) return setErr(`The loan started on ${dayLabel(loan.start_date)}, so a payment cannot be earlier.`)
    const ops: BatchOp[] = []
    const base = { account_id: accountId, payee: loan.lender || loan.name, date, loan_id: loan.id }
    if (principal > 0) ops.push(createOp('transactions', ids[0], linkedTxData({ ...base, type: 'transfer', to_account_id: loan.account_id, amount: principal, note: `${loan.name} EMI · principal`, part: 'principal' })))
    if (interest > 0) ops.push(createOp('transactions', ids[1], linkedTxData({ ...base, type: 'expense', amount: interest, category_id: interestExpenseCategory(cats.data ?? [], loan.kind, loan.interest_category_id), note: `${loan.name} EMI · interest`, part: 'interest' })))
    if (feeMinor && feeMinor > 0) ops.push(createOp('transactions', ids[2], linkedTxData({ ...base, type: 'expense', amount: feeMinor, category_id: feeCategory(cats.data ?? []), note: `${loan.name} · charges`, part: 'fee' })))
    if (closes) ops.push({ op: 'update', entity: 'loans', id: loan.id, data: { closed: true, closed_date: date } })
    try {
      await write.batch.mutateAsync(ops)
      toast(closes ? `${loan.name} is fully repaid. Congratulations!` : `EMI recorded: ${formatMoney(principal, cur)} principal, ${formatMoney(interest, cur)} interest`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }
  return (
    <Modal title={`Pay EMI · ${loan.name}`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="emiform" disabled={write.batch.isPending}>{write.batch.isPending ? 'Saving…' : 'Record payment'}</button></>}>
      <form id="emiform" onSubmit={submit}>
        {next && <p className="muted small">Instalment {next.n}{next.date < today ? `, due ${dayLabel(next.date)} (overdue)` : `, due ${dayLabel(next.date)}`}. Owed now: <Money value={s.outstanding} currency={cur} /></p>}
        <div className="two">
          <Field label="Amount paid"><NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
          <Field label="Paid on"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={today} /></Field>
        </div>
        <div className="two">
          <Field label="From account" hint={bal !== undefined ? <>Balance <Money value={bal} currency={cur} /></> : undefined}>
            <Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{pay.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>
          </Field>
          <Field label="Interest part" hint={`Calculated ${formatMoney(auto.interest, cur)}; type the figure from your statement if it differs`}>
            <NumInput kind="decimal" value={interestText} onChange={(e) => setInterestText(e.target.value)} placeholder={toPlain(auto.interest, cur)} />
          </Field>
        </div>
        <Field label="Late fee or other charges (optional)"><NumInput kind="decimal" value={fee} onChange={(e) => setFee(e.target.value)} placeholder="0" /></Field>
        {minor && minor > 0 && interest !== null && interest <= minor && (
          <div className="subtle-box small">
            <strong>Split</strong>: <Money value={principal} currency={cur} /> principal (reduces what you owe) + <Money value={interest} currency={cur} /> interest (a cost){feeMinor ? <> + <Money value={feeMinor} currency={cur} /> charges</> : ''}.
            {minor < (next?.payment ?? s.emi) && <div className="warn-text">This is less than the EMI of {formatMoney(next?.payment ?? s.emi, cur)}. It is recorded as a part payment; the rest of the instalment stays due.</div>}
            {closes && <div className="pos-text">This pays off the loan; it will be marked closed.</div>}
          </div>
        )}
        {bal !== undefined && minor && bal - total < 0 && <p className="warn-text small"><Icon name="alert" size={14} /> This takes the account below zero ({formatMoney(bal - total, cur)}). Check the account, or record it anyway if it is right.</p>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

function Prepay({ loan, s, onClose }: { loan: LoanRec; s: LoanStatus; onClose: () => void }) {
  const write = useWrite()
  const cats = useCategories()
  const pay = usePayAccounts(loan.currency)
  const cur = loan.currency
  const ids = useState(() => [uuidv7(), uuidv7()])[0]
  const [amount, setAmount] = useState('')
  const [charge, setCharge] = useState('')
  const [date, setDate] = useState(isoDate())
  const [mode, setMode] = useState<'tenure' | 'emi'>('tenure')
  const [accountId, setAccountId] = useState(loan.pay_from_account_id ?? pay.list[0]?.id ?? '')
  const [err, setErr] = useState('')
  const minor = parseMoney(amount, cur)
  const chargeMinor = charge.trim() ? parseMoney(charge, cur) : 0
  const opts = minor && minor > 0 && minor < s.outstanding ? prepaymentOptions(s.outstanding, loan.annual_rate, s.emi, s.remainingInstalments, minor, loan.first_due_date, s.paidInstalments + 1) : null
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (!minor || minor <= 0) return setErr('Enter the amount you are prepaying')
    if (minor >= s.outstanding) return setErr(`That is all of the ${formatMoney(s.outstanding, cur)} owed. Use Foreclose to close the loan instead.`)
    if (chargeMinor === null || chargeMinor < 0) return setErr('Enter the prepayment charge as an amount, or leave it empty')
    if (!accountId) return setErr(`Add a ${cur} account to pay from`)
    if (date > isoDate()) return setErr('Record the prepayment on the day it is made, not in the future')
    const ops: BatchOp[] = [createOp('transactions', ids[0], linkedTxData({ type: 'transfer', account_id: accountId, to_account_id: loan.account_id, amount: minor, payee: loan.lender || loan.name, note: `${loan.name} · prepayment`, date, loan_id: loan.id, part: 'prepayment' }))]
    if (chargeMinor && chargeMinor > 0) ops.push(createOp('transactions', ids[1], linkedTxData({ type: 'expense', account_id: accountId, amount: chargeMinor, category_id: feeCategory(cats.data ?? []), payee: loan.lender || loan.name, note: `${loan.name} · prepayment charge`, date, loan_id: loan.id, part: 'fee' })))
    if (mode === 'emi' && opts) ops.push({ op: 'update', entity: 'loans', id: loan.id, data: { emi_amount: opts.keepTenure.emi } })
    try {
      await write.batch.mutateAsync(ops)
      toast(mode === 'emi' && opts ? `Prepaid. New EMI ${formatMoney(opts.keepTenure.emi, cur)}` : `Prepaid. About ${opts?.keepEmi.months ?? ''} instalments left`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }
  return (
    <Modal title={`Prepay · ${loan.name}`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="prepayform" disabled={write.batch.isPending}>Record prepayment</button></>}>
      <form id="prepayform" onSubmit={submit}>
        <p className="muted small">Extra principal paid outside the EMI. Owed now: <Money value={s.outstanding} currency={cur} /></p>
        <div className="two">
          <Field label="Prepayment"><NumInput kind="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus /></Field>
          <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
        </div>
        <div className="two">
          <Field label="From account"><Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{pay.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
          <Field label="Prepayment charge (optional)"><NumInput kind="decimal" value={charge} onChange={(e) => setCharge(e.target.value)} placeholder="0" /></Field>
        </div>
        {opts && (
          <div className="choice-cards">
            <button type="button" className={`choice ${mode === 'tenure' ? 'on' : ''}`} onClick={() => setMode('tenure')} aria-pressed={mode === 'tenure'}>
              <strong>Keep the EMI, finish sooner</strong>
              <span>{opts.keepEmi.months} instalments left instead of {s.remainingInstalments}</span>
              <span className="pos-text">Saves about {formatMoney(opts.keepEmi.interestSaved, cur)} interest</span>
            </button>
            <button type="button" className={`choice ${mode === 'emi' ? 'on' : ''}`} onClick={() => setMode('emi')} aria-pressed={mode === 'emi'}>
              <strong>Keep the end date, lower the EMI</strong>
              <span>EMI {formatMoney(opts.keepTenure.emi, cur)} instead of {formatMoney(s.emi, cur)}</span>
              <span className="pos-text">Saves about {formatMoney(opts.keepTenure.interestSaved, cur)} interest</span>
            </button>
          </div>
        )}
        <p className="muted small">Tell your lender which option you chose; they decide how a prepayment is applied. Estimates assume monthly interest on the reducing balance.</p>
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

function CloseLoan({ loan, s, onClose }: { loan: LoanRec; s: LoanStatus; onClose: () => void }) {
  const write = useWrite()
  const cats = useCategories()
  const pay = usePayAccounts(loan.currency)
  const cur = loan.currency
  const ids = useState(() => [uuidv7(), uuidv7(), uuidv7()])[0]
  const [date, setDate] = useState(isoDate())
  const [accountId, setAccountId] = useState(loan.pay_from_account_id ?? pay.list[0]?.id ?? '')
  const [interest, setInterest] = useState(toPlain(monthlyInterest(s.outstanding, loan.annual_rate), cur))
  const [charge, setCharge] = useState('')
  const [err, setErr] = useState('')
  const owed = s.outstanding
  const submit = async () => {
    setErr('')
    if (write.batch.isPending) return
    const i = interest.trim() ? parseMoney(interest, cur) : 0
    const c = charge.trim() ? parseMoney(charge, cur) : 0
    if (i === null || i < 0 || c === null || c < 0) return setErr('Interest and charges must be amounts (or empty)')
    if (owed > 0 && !accountId) return setErr(`Add a ${cur} account to pay from`)
    const ops: BatchOp[] = []
    const base = { account_id: accountId, payee: loan.lender || loan.name, date, loan_id: loan.id }
    if (owed > 0) ops.push(createOp('transactions', ids[0], linkedTxData({ ...base, type: 'transfer', to_account_id: loan.account_id, amount: owed, note: `${loan.name} · foreclosure`, part: 'prepayment' })))
    if (owed > 0 && i > 0) ops.push(createOp('transactions', ids[1], linkedTxData({ ...base, type: 'expense', amount: i, category_id: interestExpenseCategory(cats.data ?? [], loan.kind, loan.interest_category_id), note: `${loan.name} · interest to closure`, part: 'interest' })))
    if (owed > 0 && c > 0) ops.push(createOp('transactions', ids[2], linkedTxData({ ...base, type: 'expense', amount: c, category_id: feeCategory(cats.data ?? []), note: `${loan.name} · foreclosure charge`, part: 'fee' })))
    ops.push({ op: 'update', entity: 'loans', id: loan.id, data: { closed: true, closed_date: date } })
    try { await write.batch.mutateAsync(ops); toast(`${loan.name} closed`); onClose() } catch (x) { setErr(saveErrorText(x)) }
  }
  return (
    <Modal title={owed > 0 ? `Foreclose ${loan.name}` : `Close ${loan.name}`} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" onClick={submit} disabled={write.batch.isPending}>{owed > 0 ? 'Pay off and close' : 'Mark closed'}</button></>}>
      {owed > 0 ? <>
        <p>Pays the remaining principal of <strong><Money value={owed} currency={cur} /></strong> and closes the loan. Use the figures from your lender's foreclosure letter.</p>
        <div className="two">
          <Field label="Interest to the closing date" hint="Calculated for one month; edit to match the letter"><NumInput kind="decimal" value={interest} onChange={(e) => setInterest(e.target.value)} /></Field>
          <Field label="Foreclosure charge"><NumInput kind="decimal" value={charge} onChange={(e) => setCharge(e.target.value)} placeholder="0" /></Field>
        </div>
        <div className="two">
          <Field label="From account"><Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{pay.list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
          <Field label="On"><DateField value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
        </div>
      </> : <p>Nothing is owed on this loan, so closing it just moves it to the closed list. Its history stays.</p>}
      {err && <p className="error" role="alert">{err}</p>}
    </Modal>
  )
}

function DeleteLoan({ loan, moves, onClose, onDone }: { loan: LoanRec; moves: { id: string }[]; onClose: () => void; onDone: () => void }) {
  const write = useWrite()
  const [everything, setEverything] = useState(false)
  return (
    <Confirm title={`Delete ${loan.name}?`} danger confirm="Delete" onClose={onClose} onConfirm={async () => {
      const ops: BatchOp[] = [{ op: 'delete', entity: 'loans', id: loan.id }]
      if (everything) { for (const m of moves) ops.push({ op: 'delete', entity: 'transactions', id: m.id }); ops.push({ op: 'delete', entity: 'accounts', id: loan.account_id }) }
      await write.batch.mutateAsync(ops)
      toast(everything ? 'Loan, its account and payments moved to the Trash' : 'Loan deleted; its account and payments were kept', { label: 'Undo', run: () => write.batch.mutate(ops.map((o) => ({ op: 'restore' as const, entity: o.entity, id: o.id }))) })
      onDone()
    }} body={<>
      <p>The loan's schedule and reminders are removed. Closing it instead keeps the full history.</p>
      <Check checked={everything} onChange={setEverything}>Also delete its loan account and all {moves.length} linked payments (balances of the accounts you paid from go back up)</Check>
      {!everything && <p className="muted small">Kept: the loan account (still showing what is owed) and every payment, as ordinary transactions.</p>}
    </>} />
  )
}

// --- Create / edit -----------------------------------------------------------------

interface LoanPreset { principal: number; rate: string; months: number }

function LoanForm({ loan, preset, onClose }: { loan?: LoanRec; preset?: LoanPreset; onClose: () => void }) {
  const l = useLedger()
  const write = useWrite()
  const cats = useCategories()
  const accounts = useAccounts()
  const balances = useBalances()
  const linked = useLinkedMoves('loan_id')
  const loanId = useStableId(loan?.id)
  const accountId = useStableId(loan?.account_id)
  const disburseId = useStableId()
  const feeId = useStableId()
  const today = isoDate()
  const [name, setName] = useState(loan?.name ?? '')
  const [lender, setLender] = useState(loan?.lender ?? '')
  const [kind, setKind] = useState(loan?.kind ?? 'home')
  const [cur, setCur] = useState(loan?.currency ?? l.base_currency)
  const [principal, setPrincipal] = useState(loan ? toPlain(loan.principal, loan.currency) : preset?.principal ? toPlain(preset.principal, l.base_currency) : '')
  const [rateKind, setRateKind] = useState<'fixed' | 'floating' | 'free'>(loan ? (loan.annual_rate ? loan.rate_type : 'free') : 'fixed')
  const [rate, setRate] = useState(loan?.annual_rate ?? preset?.rate ?? '')
  const [years, setYears] = useState(loan ? String(Math.floor(loan.tenure_months / 12)) : preset?.months ? String(Math.floor(preset.months / 12)) : '')
  const [months, setMonths] = useState(loan ? String(loan.tenure_months % 12) : preset?.months ? String(preset.months % 12) : '0')
  const [start, setStart] = useState(loan?.start_date ?? today)
  const [firstDue, setFirstDue] = useState(loan?.first_due_date ?? addMonths(today, 1))
  const [firstTouched, setFirstTouched] = useState(!!loan)
  const [customEmi, setCustomEmi] = useState(loan?.emi_amount ? toPlain(loan.emi_amount, loan.currency) : '')
  const [useCustom, setUseCustom] = useState(!!loan?.emi_amount)
  const payList = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === cur && a.type !== 'loan' && a.type !== 'deposit')
  const [payFrom, setPayFrom] = useState(loan?.pay_from_account_id ?? payList[0]?.id ?? '')
  const [running, setRunning] = useState(false)
  const [paidBefore, setPaidBefore] = useState('')
  const [outstandingText, setOutstandingText] = useState('')
  const [received, setReceived] = useState<'account' | 'direct'>('account')
  const [intoAccount, setIntoAccount] = useState(payList[0]?.id ?? '')
  const [fee, setFee] = useState(loan?.processing_fee ? toPlain(loan.processing_fee, loan.currency) : '')
  const [remind, setRemind] = useState(String(loan?.remind_days ?? 3))
  const [note, setNote] = useState(loan?.note ?? '')
  const [rateEffect, setRateEffect] = useState<'tenure' | 'emi'>('tenure')
  const [err, setErr] = useState('')

  const p = parseMoney(principal, cur)
  const tenure = (Number(years) || 0) * 12 + (Number(months) || 0)
  const r = rateKind === 'free' ? null : rate.trim()
  const calc = p && tenure > 0 && (rateKind === 'free' || !rateError(rate)) ? emi(p, r, tenure) : 0
  const custom = useCustom && customEmi.trim() ? parseMoney(customEmi, cur) : null
  const theEmi = custom ?? calc
  // Due dates before today: how many EMIs a running loan has probably paid.
  const dueSoFar = (() => { let n = 0; while (n < tenure && addMonths(firstDue, n) <= today) n++; return n })()
  const paidN = running ? (paidBefore.trim() ? Number(paidBefore) : dueSoFar) : 0
  const projected = p && theEmi ? schedule(p, r, theEmi, firstDue) : null
  const projectedLeft = projected ? (paidN === 0 ? p! : projected.rows[Math.min(paidN, projected.rows.length) - 1]?.closing ?? 0) : 0
  const owedNow = running && outstandingText.trim() ? parseMoney(outstandingText, cur) : running ? projectedLeft : p
  const rateChanged = loan && (loan.annual_rate ?? '') !== (r ?? '')
  const loanBal = loan ? -(balances.data?.find((b) => b.account_id === loan.account_id)?.balance ?? 0) : 0

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    if (write.batch.isPending) return
    if (!name.trim()) return setErr('Give the loan a name, like “Home loan” or “Car loan”')
    if (!p || p <= 0) return setErr('Enter the amount borrowed (the sanctioned or disbursed principal)')
    if (rateKind !== 'free') { const e2 = rateError(rate); if (e2) return setErr(e2) }
    if (!Number.isInteger(Number(years)) || !Number.isInteger(Number(months)) || Number(months) > 11 || Number(months) < 0) return setErr('Enter the tenure as whole years and 0 to 11 months')
    if (tenure < 1 || tenure > 600) return setErr('The tenure must be between 1 month and 50 years')
    if (firstDue < start) return setErr('The first EMI cannot be before the loan starts')
    if (useCustom && (custom === null || custom <= 0)) return setErr('Enter the EMI from your loan documents, or untick “My lender’s EMI is different”')
    if (theEmi && r && theEmi <= monthlyInterest(p, r)) return setErr(`An EMI of ${formatMoney(theEmi, cur)} does not cover the first month's interest (${formatMoney(monthlyInterest(p, r), cur)}), so the loan would never be repaid. Check the EMI, rate and amount.`)
    if (running && (!Number.isInteger(paidN) || paidN < 0 || paidN > tenure)) return setErr(`Instalments already paid must be a whole number from 0 to ${tenure}`)
    if (running && (owedNow === null || owedNow < 0)) return setErr('Enter what you still owe as an amount')
    if (running && owedNow! > p) return setErr('What you still owe is more than you borrowed. Check both amounts; if the loan was topped up, enter the new total as the amount borrowed.')
    const feeMinor = fee.trim() ? parseMoney(fee, cur) : null
    if (fee.trim() && (feeMinor === null || feeMinor < 0)) return setErr('Enter the processing fee as an amount, or leave it empty')
    const remindDays = Number(remind)
    if (!Number.isInteger(remindDays) || remindDays < 0 || remindDays > 30) return setErr('Reminder days must be 0 to 30')

    let emiToSave: number | null = useCustom ? custom : null
    // A new rate on a running loan: keep the EMI (tenure changes) or keep the end date (EMI changes).
    if (loan && rateChanged) {
      const now = loanStatus(loan, linked.data?.get(loan.id) ?? [], -loanBal, today, loan.account_id)
      const left = Math.max(1, loan.tenure_months - now.paidInstalments)
      emiToSave = rateEffect === 'emi' ? emi(loanBal, r, left) : (custom ?? now.emi)
    }
    const data: Partial<Loan> = {
      name: name.trim(), lender: lender.trim() || null, kind, currency: cur, principal: p, annual_rate: r, rate_type: rateKind === 'floating' ? 'floating' : 'fixed',
      start_date: start, first_due_date: firstDue, tenure_months: tenure, emi_amount: emiToSave, pay_from_account_id: payFrom || null,
      interest_category_id: loan?.interest_category_id ?? interestExpenseCategory(cats.data ?? [], kind) ?? null,
      processing_fee: feeMinor, remind_days: remindDays, note: note.trim() || null,
      ...(loan ? {} : { paid_before: paidN, closed: false, closed_date: null, account_id: accountId }),
    }
    const ops: BatchOp[] = []
    if (loan) {
      ops.push({ op: 'update', entity: 'loans', id: loan.id, data })
      ops.push({ op: 'update', entity: 'accounts', id: loan.account_id, data: { name: name.trim() } })
    } else {
      const direct = running || received === 'direct'
      ops.push(createOp('accounts', accountId, { name: name.trim(), type: 'loan', currency: cur, opening_balance: direct ? -(running ? owedNow! : p) : 0, include_in_net_worth: true, archived: false, sort_order: 0, note: lender.trim() || null, color: '#C2410C', created_at: Date.now() }))
      ops.push(createOp('loans', loanId, { ...data, created_at: Date.now() }))
      if (!direct && intoAccount) ops.push(createOp('transactions', disburseId, linkedTxData({ type: 'transfer', account_id: accountId, to_account_id: intoAccount, amount: p, payee: lender.trim() || name.trim(), note: `${name.trim()} · loan disbursed`, date: start, loan_id: loanId, part: null })))
      if (!running && feeMinor && feeMinor > 0 && payFrom) ops.push(createOp('transactions', feeId, linkedTxData({ type: 'expense', account_id: payFrom, amount: feeMinor, category_id: feeCategory(cats.data ?? []), payee: lender.trim() || name.trim(), note: `${name.trim()} · processing fee`, date: start, loan_id: loanId, part: 'fee' })))
    }
    try {
      await write.batch.mutateAsync(ops)
      toast(loan ? 'Loan updated' : `${name.trim()} added. EMI ${formatMoney(theEmi, cur)}`)
      onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }

  return (
    <Modal wide title={loan ? `Edit ${loan.name}` : 'Add a loan'} onClose={onClose} footer={<><span className="spacer" /><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="loanform" disabled={write.batch.isPending}>{write.batch.isPending ? 'Saving…' : 'Save'}</button></>}>
      <form id="loanform" onSubmit={submit}>
        <div className="two">
          <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} placeholder="Home loan, Car loan…" autoFocus={!loan} maxLength={100} /></Field>
          <Field label="Lender"><input value={lender} onChange={(e) => setLender(e.target.value)} placeholder="Bank or company" maxLength={100} /></Field>
        </div>
        <div className="two">
          <Field label="Type"><Select value={kind} onChange={(e) => setKind(e.target.value)}>{LOAN_KINDS.map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></Field>
          <Field label="Currency" hint={loan ? 'Fixed once created' : undefined}><CurrencySelect value={cur} onChange={setCur} disabled={!!loan} /></Field>
        </div>
        <div className="two">
          <Field label="Amount borrowed"><NumInput kind="decimal" value={principal} onChange={(e) => setPrincipal(e.target.value)} placeholder="0" /></Field>
          <Field label="Interest">
            <div className="row nowrap">
              <Select value={rateKind} onChange={(e) => setRateKind(e.target.value as typeof rateKind)} aria-label="Rate type"><option value="fixed">Fixed rate</option><option value="floating">Floating rate</option><option value="free">Interest-free</option></Select>
              {rateKind !== 'free' && <NumInput kind="decimal" value={rate} onChange={(e) => setRate(e.target.value)} placeholder="8.5" aria-label="Annual rate in percent" style={{ maxWidth: 110 }} />}
              {rateKind !== 'free' && <span className="muted">% a year</span>}
            </div>
          </Field>
        </div>
        {rateKind !== 'free' && rate.trim() && rateError(rate) && <p className="field-error">{rateError(rate)}</p>}
        <div className="two">
          <Field label="Tenure">
            <div className="row nowrap"><NumInput kind="int" value={years} onChange={(e) => setYears(e.target.value)} placeholder="20" aria-label="Years" style={{ maxWidth: 80 }} /><span className="muted">years</span>
              <NumInput kind="int" value={months} onChange={(e) => setMonths(e.target.value)} aria-label="Months" style={{ maxWidth: 70 }} /><span className="muted">months</span></div>
          </Field>
          <Field label="EMI" hint={calc ? `Calculated: ${formatMoney(calc, cur)} a month` : 'Fill in the amount, rate and tenure'}>
            <Check checked={useCustom} onChange={setUseCustom}>My lender's EMI is different</Check>
            {useCustom && <NumInput kind="decimal" value={customEmi} onChange={(e) => setCustomEmi(e.target.value)} placeholder={calc ? toPlain(calc, cur) : ''} />}
          </Field>
        </div>
        <div className="two">
          <Field label="Loan started (disbursed) on"><DateField value={start} onChange={(e) => { setStart(e.target.value); if (!firstTouched && e.target.value) setFirstDue(addMonths(e.target.value, 1)) }} /></Field>
          <Field label="First EMI date" hint="Later EMIs fall on the same day each month (the last day in shorter months)"><DateField value={firstDue} onChange={(e) => { setFirstDue(e.target.value); setFirstTouched(true) }} /></Field>
        </div>
        {loan && rateChanged && (
          <div className="subtle-box">
            <strong className="small">The rate changed. What should change with it?</strong>
            <Segmented label="Effect of the new rate" value={rateEffect} onChange={setRateEffect} options={[['tenure', 'Keep the EMI (tenure changes)'], ['emi', 'Keep the end date (EMI changes)']]} />
            <p className="muted small">Check your lender's letter. Applies to the {formatMoney(loanBal, cur)} still owed.</p>
          </div>
        )}
        {!loan && (
          <div className="subtle-box">
            <Check checked={running} onChange={setRunning}>This loan is already running; I've paid some EMIs before</Check>
            {running ? (
              <div className="two" style={{ marginTop: 8 }}>
                <Field label="EMIs already paid" hint={`${dueSoFar} were due by today`}><NumInput kind="int" value={paidBefore} onChange={(e) => setPaidBefore(e.target.value)} placeholder={String(dueSoFar)} /></Field>
                <Field label="Still owed today" hint={projected ? `About ${formatMoney(projectedLeft, cur)} by our schedule; use your statement's figure` : undefined}>
                  <NumInput kind="decimal" value={outstandingText} onChange={(e) => setOutstandingText(e.target.value)} placeholder={projectedLeft ? toPlain(projectedLeft, cur) : ''} />
                </Field>
              </div>
            ) : (
              <div style={{ marginTop: 8 }}>
                <Segmented label="Where the money went" value={received} onChange={setReceived} options={[['account', 'Into my account'], ['direct', 'Paid directly (builder, dealer, college)']]} />
                {received === 'account' && <Field label="Received in"><Select value={intoAccount} onChange={(e) => setIntoAccount(e.target.value)}>{payList.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>}
                {received === 'direct' && <p className="muted small">No account balance goes up; the loan account starts owing the full amount.</p>}
              </div>
            )}
          </div>
        )}
        <div className="two">
          <Field label="EMIs are paid from"><Select value={payFrom} onChange={(e) => setPayFrom(e.target.value)}><option value="">Choose each time</option>{payList.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
          <Field label="Remind me before each EMI"><div className="row nowrap"><NumInput kind="int" value={remind} onChange={(e) => setRemind(e.target.value)} style={{ maxWidth: 70 }} /><span className="muted">days (0 = off)</span></div></Field>
        </div>
        <div className="two">
          {!running && <Field label="Processing fee (optional)" hint={loan ? undefined : 'Recorded as a bank charge from the account above'}><NumInput kind="decimal" value={fee} onChange={(e) => setFee(e.target.value)} placeholder="0" /></Field>}
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Loan number, branch…" maxLength={2000} /></Field>
        </div>
        {projected && !loan && theEmi > 0 && (
          <div className="summary-strip">
            <div><span className="muted small">EMI</span><strong><Money value={theEmi} currency={cur} /></strong></div>
            <div><span className="muted small">Total interest</span><strong><Money value={projected.totalInterest} currency={cur} /></strong></div>
            <div><span className="muted small">Total repaid</span><strong><Money value={projected.totalPayment} currency={cur} /></strong></div>
            <div><span className="muted small">Last EMI</span><strong>{projected.rows.length ? new Date(projected.rows[projected.rows.length - 1].date).toLocaleDateString(undefined, { month: 'short', year: 'numeric' }) : '—'}</strong></div>
          </div>
        )}
        {projected?.neverRepaid && <p className="error">This EMI does not cover the interest, so the loan would never be repaid.</p>}
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}
