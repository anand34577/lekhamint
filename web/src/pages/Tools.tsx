import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type BatchOp, type Category } from '../lib/api'
import { useAccounts, useBalances, useCanWrite, useCategories, useCategoryIndex, useLedger, useRules, useWrite } from '../lib/data'
import { emi, rateError, schedule } from '../lib/loans'
import { estimate, fdValue, rdValue, tenureLabel } from '../lib/deposits'
import { affordability, findRecurring, goalMonthly, goalMonths, payoff, splitBill, type PayoffDebt, type SplitMode } from '../lib/tools'
import { createOp, linkedTxData } from '../lib/links'
import { currency, formatMoney, parseMoney, toPlain } from '../lib/money'
import { addMonths, dayLabel, isoDate, monthRange, uuidv7 } from '../lib/util'
import { Bar, Card, Check, Field, LineChart, Money, Segmented, Skeleton, saveErrorText, toast } from '../components'
import { Icon } from '../icons'
import { useLoanStatuses } from './Loans'
import type { PageProps } from '../App'

/**
 * Everyday money calculators. Every tool states its inputs, assumptions and
 * limits; tools that can, start from the user's own records (average income
 * and spending, existing EMIs, card balances) instead of blank fields.
 */

const TOOLS = [
  ['emi', 'EMI calculator', 'calc', 'Instalment, total interest and a year-by-year breakdown; compare up to three options.'],
  ['afford', 'Can I afford this loan?', 'scale', 'What EMI and loan size fit your income, spending and existing EMIs.'],
  ['fd', 'FD & RD calculator', 'piggy', 'Maturity value and interest for fixed and recurring deposits; compare rates and terms.'],
  ['split', 'Bill splitter', 'split', 'Split equally, by shares, exact amounts, percentages or items, with tax and tip. Save your share and what others owe you.'],
  ['household', 'Household & budget planner', 'home', 'Monthly cost of running your home from your real spending, then turn it into budgets.'],
  ['goal', 'Savings goal', 'flag', 'How much to save each month to reach a goal, or when you will get there.'],
  ['emergency', 'Emergency fund', 'shield', 'How many months of essential spending your savings cover, and the gap.'],
  ['payoff', 'Debt payoff planner', 'target', 'Highest rate first or smallest first: months to debt-free and interest paid.'],
  ['subscriptions', 'Subscriptions & regular bills', 'repeat', 'Recurring payments found in your history and their yearly cost.'],
  ['networth', 'Net worth over time', 'chart', 'Month-end assets, debts and money owed, and what changed.'],
] as const
type ToolId = (typeof TOOLS)[number][0]

export function Tools({ params, go }: PageProps) {
  const tool = TOOLS.find(([id]) => id === params.tool)
  if (!tool) {
    return (
      <>
        <div className="page-head"><h1>Tools</h1></div>
        <div className="tool-grid">
          {TOOLS.map(([id, title, icon, body]) => (
            <button key={id} className="tool-card" onClick={() => go('tools', { tool: id })}>
              <span className="acc-icon" style={{ ['--c' as string]: 'var(--brand)' }}><Icon name={icon} size={20} /></span>
              <strong>{title}</strong><span className="muted small">{body}</span>
            </button>
          ))}
        </div>
        <p className="muted small">Calculators give estimates from the numbers you enter. They are planning aids, not financial advice or a lender's decision.</p>
      </>
    )
  }
  const [id, title] = tool
  const Body: Record<ToolId, () => JSX.Element> = { emi: EmiTool, afford: AffordTool, fd: DepositTool, split: SplitTool, household: HouseholdTool, goal: GoalTool, emergency: EmergencyTool, payoff: PayoffTool, subscriptions: SubscriptionsTool, networth: NetWorthTool }
  const B = Body[id]
  return (
    <>
      <div className="page-head"><button className="ghost" onClick={() => go('tools')} aria-label="All tools"><Icon name="left" size={16} /> <span className="show-sm-inline">{title}</span><span className="hide-sm">Tools</span></button><h1>{title}</h1></div>
      <B />
    </>
  )
}

// --- Shared bits -------------------------------------------------------------------

function useCur() { return useLedger().base_currency }

function MoneyField({ label, value, onChange, hint, placeholder }: { label: string; value: string; onChange: (v: string) => void; hint?: React.ReactNode; placeholder?: string }) {
  const code = useCur()
  const bad = value.trim() !== '' && parseMoney(value, code) === null
  return <Field label={`${label} (${currency(code).symbol.trim()})`} hint={hint} error={bad ? 'Enter a number like 25000 or 25,000.50' : undefined}><input inputMode="decimal" value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder ?? '0'} /></Field>
}

const m = (s: string, code: string) => (s.trim() ? parseMoney(s, code) ?? 0 : 0)
const num = (s: string) => (s.trim() && Number.isFinite(Number(s)) ? Number(s) : NaN)

function Assumptions({ children }: { children: React.ReactNode }) {
  return <details className="assumptions"><summary><Icon name="info" size={14} /> How this is calculated</summary><div className="small muted">{children}</div></details>
}

/** Average monthly income and spending over the last [n] full months, from real records. */
function useAverages(n = 3) {
  const l = useLedger()
  const [from] = monthRange(isoDate(), -n)
  const [, to] = monthRange(isoDate(), -1)
  return useQuery({
    queryKey: [l.id, 'tool-averages', from, to],
    queryFn: async () => {
      const s = (await api.summary(l.id, from, to)).find((c) => c.currency === l.base_currency)
      return { income: Math.round((s?.income ?? 0) / n), expense: Math.round((s?.expense ?? 0) / n), byCategory: s?.expense_by_category ?? [], months: n, from, to }
    },
  })
}

// --- EMI ---------------------------------------------------------------------------------

function EmiTool() {
  const code = useCur()
  const [opts, setOpts] = useState([{ amount: '2000000', rate: '8.5', years: '20' }])
  const set = (i: number, k: 'amount' | 'rate' | 'years', v: string) => setOpts(opts.map((o, j) => (j === i ? { ...o, [k]: v } : o)))
  const results = opts.map((o) => {
    const p = m(o.amount, code), months = Math.round(num(o.years) * 12)
    const err = !p || p <= 0 ? 'Enter the loan amount' : rateError(o.rate, { allowZero: true }) ?? (!(months >= 1 && months <= 600) ? 'Tenure must be from 1 month to 50 years' : null)
    if (err) return { err }
    const e = emi(p, o.rate || null, months)
    const s = schedule(p, o.rate || null, e, isoDate())
    const years: { year: number; principal: number; interest: number; balance: number }[] = []
    s.rows.forEach((r, i) => { const y = Math.floor(i / 12); years[y] ??= { year: y + 1, principal: 0, interest: 0, balance: 0 }; years[y].principal += r.principal; years[y].interest += r.interest; years[y].balance = r.closing })
    return { err: null, p, months, emi: e, s, years }
  })
  const first = results[0]
  return (
    <>
      <div className="compare-grid">
        {opts.map((o, i) => {
          const r = results[i]
          return (
            <Card key={i} title={opts.length > 1 ? `Option ${String.fromCharCode(65 + i)}` : 'Loan'} action={opts.length > 1 ? <button className="icon-btn" aria-label="Remove option" onClick={() => setOpts(opts.filter((_, j) => j !== i))}><Icon name="x" size={16} /></button> : undefined}>
              <MoneyField label="Loan amount" value={o.amount} onChange={(v) => set(i, 'amount', v)} />
              <div className="two">
                <Field label="Rate (% a year)" error={o.rate.trim() ? rateError(o.rate, { allowZero: true }) ?? undefined : undefined}><input inputMode="decimal" value={o.rate} onChange={(e) => set(i, 'rate', e.target.value)} /></Field>
                <Field label="Tenure (years)"><input inputMode="decimal" value={o.years} onChange={(e) => set(i, 'years', e.target.value)} /></Field>
              </div>
              {r.err ? <p className="muted">{r.err}</p> : <>
                <div className="stat-label">EMI</div><div className="stat-big"><Money value={r.emi!} currency={code} /></div>
                <ul className="nw-lines small">
                  <li><span>Total interest</span><Money value={r.s!.totalInterest} currency={code} /></li>
                  <li><span>Total repaid</span><Money value={r.s!.totalPayment} currency={code} /></li>
                  <li><span>Interest as share of payments</span><span>{((r.s!.totalInterest / Math.max(1, r.s!.totalPayment)) * 100).toFixed(1)}%</span></li>
                </ul>
                <Bar fraction={r.p! / Math.max(1, r.s!.totalPayment)} />
                <p className="muted small">Bar: principal share of everything you pay.</p>
              </>}
            </Card>
          )
        })}
      </div>
      {opts.length < 3 && <button className="mt" onClick={() => setOpts([...opts, { ...opts[opts.length - 1] }])}><Icon name="plus" size={16} /> Compare another option</button>}
      {first && !first.err && first.years && (
        <Card title="Year by year" className="mt">
          <LineChart labels={first.years.map((y) => `Year ${y.year}`)} currency={code} label="Balance owed at the end of each year"
            series={[{ name: 'Balance owed', color: 'var(--neg)', values: first.years.map((y) => y.balance) }]} />
          <div className="table-wrap"><table className="table compact"><thead><tr><th>Year</th><th className="num">Principal</th><th className="num">Interest</th><th className="num">Balance at end</th></tr></thead>
            <tbody>{first.years.map((y) => <tr key={y.year}><td>{y.year}</td><td className="num"><Money value={y.principal} currency={code} /></td><td className="num"><Money value={y.interest} currency={code} /></td><td className="num"><Money value={y.balance} currency={code} /></td></tr>)}</tbody></table></div>
        </Card>
      )}
      <Assumptions>EMI = P·r·(1+r)ⁿ ⁄ ((1+r)ⁿ − 1), with r the monthly rate (annual ÷ 12) and n the number of months, rounded to the nearest paisa/cent. Interest each month is charged on the balance still owed; the last instalment absorbs rounding. Lenders may use daily interest, different rounding, fees or insurance, so their EMI can differ slightly. A 0% rate divides the amount evenly.</Assumptions>
    </>
  )
}

// --- Affordability ----------------------------------------------------------------------

function AffordTool() {
  const code = useCur()
  const avg = useAverages()
  const { list } = useLoanStatuses()
  const existing = list.filter((x) => !x.loan.closed && x.loan.currency === code).reduce((a, x) => a + x.status.emi, 0)
  const [income, setIncome] = useState('')
  const [expenses, setExpenses] = useState('')
  const [emis, setEmis] = useState('')
  const [buffer, setBuffer] = useState('')
  const [ratio, setRatio] = useState('40')
  const [rate, setRate] = useState('9')
  const [years, setYears] = useState('15')
  const [target, setTarget] = useState('')
  const inc = income.trim() ? m(income, code) : avg.data?.income ?? 0
  // Average spending already includes EMI interest; existing EMIs are counted separately, so this is an upper estimate.
  const exp = expenses.trim() ? m(expenses, code) : avg.data?.expense ?? 0
  const ex = emis.trim() ? m(emis, code) : existing
  const buf = buffer.trim() ? m(buffer, code) : Math.round(inc * 0.1)
  const months = Math.round(num(years) * 12)
  const bad = rateError(rate, { allowZero: true }) ?? (!(num(ratio) > 0 && num(ratio) <= 80) ? 'The EMI share must be between 1 and 80%' : !(months >= 12 && months <= 480) ? 'Tenure must be 1 to 40 years' : null)
  const a = !bad && inc > 0 ? affordability({ income: inc, expenses: exp, existingEmi: ex, buffer: buf, ratio: num(ratio) / 100, rate: rate || null, months, target: target.trim() ? m(target, code) : undefined }) : null
  return (
    <div className="grid-2 tool-layout">
      <Card title="Your numbers">
        {avg.isPending ? <Skeleton rows={2} /> : <p className="muted small">Filled from your last {avg.data?.months} months ({dayLabel(avg.data!.from)} – {dayLabel(avg.data!.to)}). Type over any of them.</p>}
        <MoneyField label="Monthly take-home income" value={income} onChange={setIncome} placeholder={avg.data ? toPlain(avg.data.income, code) : ''} />
        <MoneyField label="Monthly spending (without EMIs)" value={expenses} onChange={setExpenses} placeholder={avg.data ? toPlain(avg.data.expense, code) : ''} hint="Your average spending may already include loan interest; lower it if so" />
        <MoneyField label="Existing EMIs" value={emis} onChange={setEmis} placeholder={toPlain(existing, code)} hint={list.length ? `From your ${list.filter((x) => !x.loan.closed).length} open loans` : undefined} />
        <MoneyField label="Monthly cushion to keep" value={buffer} onChange={setBuffer} placeholder={toPlain(Math.round(inc * 0.1), code)} hint="Default: 10% of income for surprises" />
        <div className="two">
          <Field label="Max share of income for all EMIs (%)" hint="Many lenders look at 40–50%; lower is safer"><input inputMode="decimal" value={ratio} onChange={(e) => setRatio(e.target.value)} /></Field>
          <Field label="New loan rate (%)"><input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} /></Field>
        </div>
        <div className="two">
          <Field label="Tenure (years)"><input inputMode="decimal" value={years} onChange={(e) => setYears(e.target.value)} /></Field>
          <MoneyField label="Loan you have in mind (optional)" value={target} onChange={setTarget} />
        </div>
        {bad && <p className="error">{bad}</p>}
      </Card>
      <Card title="What looks manageable">
        {!a ? <p className="muted">{inc <= 0 ? 'Enter your monthly income to begin.' : 'Fix the highlighted input.'}</p> : <>
          <div className="stat-label">Comfortable new EMI</div>
          <div className="stat-big"><Money value={a.maxEmi} currency={code} /></div>
          <ul className="nw-lines small">
            <li><span>Limit from the {ratio}% income share</span><Money value={a.maxEmiByRatio} currency={code} /></li>
            <li><span>Limit from what is left after spending and cushion</span><Money value={a.maxEmiBySurplus} currency={code} /></li>
          </ul>
          <div className="stat-label mt">Largest loan for that EMI</div>
          <div className="stat-value"><Money value={a.maxLoan} currency={code} /></div>
          <p className="muted small">At {rate || 0}% over {years} years.</p>
          {a.target && (
            <div className={`callout ${a.target.fits ? 'good' : 'warn'}`}><Icon name={a.target.fits ? 'check' : 'alert'} size={16} />
              <div>A loan of {formatMoney(m(target, code), code)} needs an EMI of <strong>{formatMoney(a.target.emi, code)}</strong>. All EMIs would take {(a.target.ratio * 100).toFixed(0)}% of income, leaving about {formatMoney(a.target.leftAfter, code)} a month after spending. {a.target.fits ? 'That is within both limits.' : 'That is above what looks comfortable: consider a smaller amount, a longer tenure, or paying off another loan first.'}</div>
            </div>
          )}
        </>}
        <Assumptions>The comfortable EMI is the lower of (income × share − existing EMIs) and (income − spending − existing EMIs − cushion). The loan size inverts the EMI formula at the given rate and tenure. This is not a loan approval: lenders also look at credit history, job stability, age and their own rules, and future income is not guaranteed.</Assumptions>
      </Card>
    </div>
  )
}

// --- FD / RD -----------------------------------------------------------------------------

function DepositTool() {
  const code = useCur()
  const [kind, setKind] = useState<'fd' | 'rd'>('fd')
  const [rows, setRows] = useState([{ amount: '100000', rate: '7.1', months: '12', comp: 'quarterly', payout: 'cumulative', tax: '' }])
  const set = (i: number, k: string, v: string) => setRows(rows.map((o, j) => (j === i ? { ...o, [k]: v } : o)))
  const start = isoDate()
  return (
    <>
      <Segmented label="Deposit type" value={kind} onChange={setKind} options={[['fd', 'Fixed deposit'], ['rd', 'Recurring deposit']]} />
      <div className="compare-grid">
        {rows.map((o, i) => {
          const p = m(o.amount, code), months = num(o.months)
          const err = !p || p <= 0 ? 'Enter the amount' : rateError(o.rate) ?? (!(Number.isInteger(months) && months >= 1 && months <= 600) ? 'Term must be 1 to 600 whole months' : null)
          const end = err ? start : addMonths(start, months)
          const e = err ? null : estimate({ kind, amount: p, annual_rate: o.rate, start_date: start, maturity_date: end, compounding: o.comp, payout: kind === 'rd' ? 'cumulative' : o.payout, tax_percent: o.tax || null }, start)
          const yieldPct = e && e.invested && kind === 'fd' ? (Math.pow(fdValue(10000000, o.rate, start, addMonths(start, 12), o.comp) / 10000000, 1) - 1) * 100 : null
          return (
            <Card key={i} title={rows.length > 1 ? `Option ${String.fromCharCode(65 + i)}` : kind === 'fd' ? 'Fixed deposit' : 'Recurring deposit'} action={rows.length > 1 ? <button className="icon-btn" aria-label="Remove option" onClick={() => setRows(rows.filter((_, j) => j !== i))}><Icon name="x" size={16} /></button> : undefined}>
              <MoneyField label={kind === 'rd' ? 'Monthly instalment' : 'Amount'} value={o.amount} onChange={(v) => set(i, 'amount', v)} />
              <div className="two">
                <Field label="Rate (% a year)"><input inputMode="decimal" value={o.rate} onChange={(e2) => set(i, 'rate', e2.target.value)} /></Field>
                <Field label="Term (months)"><input inputMode="numeric" value={o.months} onChange={(e2) => set(i, 'months', e2.target.value)} /></Field>
              </div>
              <div className="two">
                <Field label="Compounding"><select value={o.comp} onChange={(e2) => set(i, 'comp', e2.target.value)}><option value="quarterly">Quarterly</option><option value="monthly">Monthly</option><option value="half_yearly">Half-yearly</option><option value="yearly">Yearly</option><option value="simple">Simple</option></select></Field>
                {kind === 'fd' ? <Field label="Interest paid"><select value={o.payout} onChange={(e2) => set(i, 'payout', e2.target.value)}><option value="cumulative">At maturity</option><option value="monthly">Monthly</option><option value="quarterly">Quarterly</option><option value="yearly">Yearly</option></select></Field>
                  : <Field label="Tax on interest (%)"><input inputMode="decimal" value={o.tax} onChange={(e2) => set(i, 'tax', e2.target.value)} placeholder="0" /></Field>}
              </div>
              {err || !e ? <p className="muted">{err}</p> : <>
                <div className="stat-label">{kind === 'fd' && o.payout !== 'cumulative' ? 'Interest paid out' : 'At maturity'}</div>
                <div className="stat-big"><Money value={kind === 'fd' && o.payout !== 'cumulative' ? e.interest : e.maturity} currency={code} /></div>
                <ul className="nw-lines small">
                  <li><span>You put in</span><Money value={e.invested} currency={code} /></li>
                  <li><span>Interest earned</span><Money value={e.interest} currency={code} /></li>
                  {e.tax > 0 && <li><span>Estimated tax</span><Money value={-e.tax} currency={code} /></li>}
                  {e.payouts.length > 0 && <li><span>{e.payouts.length} payouts of about</span><Money value={e.payouts[0].amount} currency={code} /></li>}
                  {yieldPct !== null && o.payout === 'cumulative' && <li><span>Effective yearly yield</span><span>{yieldPct.toFixed(2)}%</span></li>}
                  <li><span>Matures</span><span>{dayLabel(end)} ({tenureLabel(start, end)})</span></li>
                </ul>
              </>}
            </Card>
          )
        })}
      </div>
      {rows.length < 3 && <button className="mt" onClick={() => setRows([...rows, { ...rows[rows.length - 1] }])}><Icon name="plus" size={16} /> Compare another option</button>}
      <Assumptions>FD: interest compounds every full period from today; leftover days earn simple interest on 365 days. Payout FDs pay principal × rate × period and return the principal at maturity. RD: each monthly instalment compounds for the whole time it stays invested (rdValue). Effective yield is the growth of one year at that compounding. Banks differ in day counts, rounding and tax deducted at source, and a deposit closed early usually earns a lower rate.</Assumptions>
      <p className="muted small">Check: ₹1,00,000 at 7% compounded quarterly for a year gives {formatMoney(fdValue(10000000, '7', '2026-01-01', '2027-01-01', 'quarterly'), 'INR')}; ₹5,000 a month for 12 months at 7% gives {formatMoney(rdValue(500000, '7', '2026-01-01', '2027-01-01', 'quarterly').value, 'INR')}.</p>
    </>
  )
}

// --- Bill splitter -------------------------------------------------------------------------

function SplitTool() {
  const code = useCur()
  const can = useCanWrite()
  const [total, setTotal] = useState('')
  const [extraPct, setExtraPct] = useState('')
  const [mode, setMode] = useState<SplitMode>('equal')
  const [people, setPeople] = useState([{ name: 'Me', paid: '', value: '1' }, { name: '', paid: '', value: '1' }])
  const [items, setItems] = useState([{ name: '', amount: '', people: [0, 1] }])
  const [saving, setSaving] = useState(false)
  const t = m(total, code)
  const extra = extraPct.trim() && Number.isFinite(Number(extraPct)) ? Math.round((t * Number(extraPct)) / 100) : 0
  const values = people.map((p) => (mode === 'exact' ? m(p.value, code) : Number(p.value) || 0))
  const ps = people.map((p, i) => ({ name: p.name.trim() || `Person ${i + 1}`, paid: m(p.paid, code) }))
  const r = t > 0 ? splitBill(t, ps, mode, values, extra, items.map((it) => ({ name: it.name, amount: m(it.amount, code), people: it.people }))) : null
  const setP = (i: number, k: 'name' | 'paid' | 'value', v: string) => setPeople(people.map((p, j) => (j === i ? { ...p, [k]: v } : p)))
  const iPaidAll = ps[0] && r && !r.error && ps[0].paid === t + extra
  return (
    <div className="grid-2 tool-layout">
      <Card title="The bill">
        <div className="two">
          <MoneyField label="Bill amount" value={total} onChange={setTotal} />
          <Field label="Tax / tip / service (%)" hint={extra ? `= ${formatMoney(extra, code)}, shared in proportion` : undefined}><input inputMode="decimal" value={extraPct} onChange={(e) => setExtraPct(e.target.value)} placeholder="0" /></Field>
        </div>
        <Segmented label="Split" value={mode} onChange={setMode} options={[['equal', 'Equally'], ['shares', 'By shares'], ['percent', 'By %'], ['exact', 'Exact amounts'], ['items', 'By item']]} />
        <h3 className="section-title">People</h3>
        {people.map((p, i) => (
          <div key={i} className={`split-person ${mode === 'shares' || mode === 'percent' || mode === 'exact' ? 'with-value' : ''}`}>
            <input value={p.name} onChange={(e) => setP(i, 'name', e.target.value)} placeholder={`Person ${i + 1}`} aria-label={`Name ${i + 1}`} />
            {(mode === 'shares' || mode === 'percent' || mode === 'exact') && <input inputMode="decimal" value={p.value} onChange={(e) => setP(i, 'value', e.target.value)} aria-label={mode === 'shares' ? 'Shares' : mode === 'percent' ? 'Percent' : 'Amount'} placeholder={mode === 'percent' ? '%' : mode === 'shares' ? 'shares' : 'amount'} />}
            <input inputMode="decimal" value={p.paid} onChange={(e) => setP(i, 'paid', e.target.value)} placeholder="paid" aria-label={`Amount ${p.name || `person ${i + 1}`} paid`} />
            <button className="icon-btn" aria-label="Remove person" disabled={people.length <= 1} onClick={() => setPeople(people.filter((_, j) => j !== i))}><Icon name="x" size={16} /></button>
          </div>
        ))}
        <button className="link" onClick={() => setPeople([...people, { name: '', paid: '', value: '1' }])}>+ Add person</button>
        {mode === 'items' && (
          <>
            <h3 className="section-title">Items</h3>
            {items.map((it, i) => (
              <div key={i} className="subtle-box">
                <div className="row nowrap"><input value={it.name} onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} placeholder="Item" />
                  <input inputMode="decimal" value={it.amount} onChange={(e) => setItems(items.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} placeholder="amount" style={{ maxWidth: 120 }} />
                  <button className="icon-btn" aria-label="Remove item" onClick={() => setItems(items.filter((_, j) => j !== i))}><Icon name="x" size={16} /></button></div>
                <div className="row">{ps.map((p, k) => <Check key={k} checked={it.people.includes(k)} onChange={(v) => setItems(items.map((x, j) => (j === i ? { ...x, people: v ? [...x.people, k].sort() : x.people.filter((q) => q !== k) } : x)))}>{p.name}</Check>)}</div>
              </div>
            ))}
            <button className="link" onClick={() => setItems([...items, { name: '', amount: '', people: ps.map((_, k) => k) }])}>+ Add item</button>
          </>
        )}
        <p className="muted small">Leave “paid” empty to see shares only. Fill in who paid to see who owes whom.</p>
      </Card>
      <Card title="Result">
        {!r ? <p className="muted">Enter the bill amount.</p> : r.error ? <p className="error">{r.error}</p> : <>
          <ul className="list">{ps.map((p, i) => (
            <li key={i}><div className="grow"><div>{p.name}</div>{p.paid > 0 && <div className="sub">paid {formatMoney(p.paid, code)}</div>}</div>
              <div className="right"><Money value={r.owes[i]} currency={code} />{r.net[i] !== 0 && <div className={`small ${r.net[i] > 0 ? 'pos-text' : 'neg-text'}`}>{r.net[i] > 0 ? 'gets back' : 'owes'} {formatMoney(Math.abs(r.net[i]), code)}</div>}</div></li>
          ))}</ul>
          <p className="small muted">Shares add up to exactly {formatMoney(t + extra, code)}; any leftover paisa/cent goes to the first people in the list.</p>
          {r.transfers.length > 0 && <>
            <h3 className="section-title">To settle up</h3>
            <ul className="plain-list">{r.transfers.map((x, i) => <li key={i}><strong>{ps[x.from].name}</strong> pays <strong>{ps[x.to].name}</strong> {formatMoney(x.amount, code)}</li>)}</ul>
          </>}
          {can && iPaidAll && ps.length > 1 && <button className="primary mt" onClick={() => setSaving(true)}>Save to LekhaMint…</button>}
          {can && ps[0] && r.net.some((v, i) => i > 0 && v !== 0) && !iPaidAll && <p className="muted small">To save this, put “Me” first and enter that you paid the whole bill.</p>}
        </>}
      </Card>
      {saving && r && <SaveSplit total={t + extra} names={ps.map((p) => p.name)} owes={r.owes} onClose={() => setSaving(false)} />}
    </div>
  )
}

/** You paid the whole bill: your share is an expense; each other share is money lent to that person. */
function SaveSplit({ total, names, owes, onClose }: { total: number; names: string[]; owes: number[]; onClose: () => void }) {
  const code = useCur()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const write = useWrite()
  const ids = useState(() => names.map(() => [uuidv7(), uuidv7()]))[0]
  const list = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency === code && a.type !== 'loan' && a.type !== 'deposit')
  const [acct, setAcct] = useState(list[0]?.id ?? '')
  const [cat, setCat] = useState('')
  const [what, setWhat] = useState('')
  const [date, setDate] = useState(isoDate())
  const [err, setErr] = useState('')
  const save = async () => {
    setErr('')
    if (!acct) return setErr(`Add a ${code} account first`)
    if (!what.trim()) return setErr('Say what the bill was for, e.g. “Dinner at Toit”')
    const ops: BatchOp[] = []
    if (owes[0] > 0) ops.push(createOp('transactions', ids[0][0], linkedTxData({ type: 'expense', account_id: acct, amount: owes[0], category_id: cat || null, payee: what.trim(), note: `My share of ${formatMoney(total, code)}`, date, part: null })))
    names.forEach((n, i) => {
      if (i === 0 || owes[i] <= 0) return
      ops.push(createOp('debts', ids[i][0], { person: n, direction: 'lent', amount: owes[i], currency: code, due_date: null, note: what.trim(), closed: false, created_at: Date.now() }))
      ops.push(createOp('transactions', ids[i][1], linkedTxData({ type: 'expense', account_id: acct, amount: owes[i], payee: n, note: `${what.trim()} · their share`, date, debt_id: ids[i][0], part: 'principal' })))
    })
    try { await write.batch.mutateAsync(ops); toast(`Saved: your share as spending and ${names.length - 1} ${names.length === 2 ? 'person owes' : 'people owe'} you`); onClose() } catch (x) { setErr(saveErrorText(x)) }
  }
  return (
    <div className="scrim center" onClick={onClose}>
      <div className="card pad save-split" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Save the split">
        <h2>Save the split</h2>
        <p className="muted small">Records the full {formatMoney(total, code)} leaving your account: {formatMoney(owes[0], code)} as your spending, and the rest as money lent to the others, so you can track their repayments.</p>
        <Field label="What was it?"><input value={what} onChange={(e) => setWhat(e.target.value)} autoFocus /></Field>
        <div className="two">
          <Field label="Paid from"><select value={acct} onChange={(e) => setAcct(e.target.value)}>{list.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></Field>
          <Field label="On"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} max={isoDate()} /></Field>
        </div>
        <Field label="Category for your share"><select value={cat} onChange={(e) => setCat(e.target.value)}><option value="">Uncategorised</option>{cats.roots('expense').flatMap((c) => [c, ...cats.children(c.id)]).map((c) => <option key={c.id} value={c.id}>{cats.path(c.id)}</option>)}</select></Field>
        {err && <p className="error">{err}</p>}
        <div className="row"><span className="spacer" /><button onClick={onClose}>Cancel</button><button className="primary" onClick={save} disabled={write.batch.isPending}>Save</button></div>
      </div>
    </div>
  )
}

// --- Household & budget planner ---------------------------------------------------------------

function HouseholdTool() {
  const code = useCur()
  const avg = useAverages(3)
  const cats = useCategoryIndex()
  const can = useCanWrite()
  const write = useWrite()
  const [edits, setEdits] = useState<Record<string, string>>({})
  const [oneOff, setOneOff] = useState([{ name: 'Insurance premiums', amount: '' }, { name: 'Festivals & gifts', amount: '' }, { name: 'School fees', amount: '' }])
  const [created, setCreated] = useState(false)
  const rows = useMemo(() => {
    const by = new Map<string, number>()
    for (const c of avg.data?.byCategory ?? []) { const root = cats.rootOf(c.category_id) ?? 'none'; by.set(root, (by.get(root) ?? 0) + c.amount) }
    return [...by].filter(([, v]) => v > 0).map(([id, v]) => ({ id, name: id === 'none' ? 'Uncategorised' : cats.name(id), nature: id === 'none' ? null : (cats.byId.get(id) as Category | undefined)?.nature ?? null, avg: Math.round(v / (avg.data?.months ?? 1)) })).sort((a, b) => b.avg - a.avg)
  }, [avg.data, cats])
  const plan = rows.map((r) => ({ ...r, plan: edits[r.id] !== undefined ? m(edits[r.id], code) : r.avg }))
  const yearly = oneOff.reduce((a, o) => a + m(o.amount, code), 0)
  const monthly = plan.reduce((a, r) => a + r.plan, 0) + Math.round(yearly / 12)
  const needs = plan.filter((r) => r.nature === 'need').reduce((a, r) => a + r.plan, 0)
  const income = avg.data?.income ?? 0
  const createBudgets = async () => {
    const start = monthRange(isoDate())[0]
    const ops: BatchOp[] = plan.filter((r) => r.id !== 'none' && r.plan > 0).map((r) => createOp('budgets', uuidv7(), { name: r.name, amount: r.plan, currency: code, period: 'monthly', start_date: start, end_date: null, category_ids: [r.id], account_ids: [], rollover: false, alert_percent: 80, archived: false, created_at: Date.now() }))
    try { await write.batch.mutateAsync(ops); setCreated(true); toast(`${ops.length} monthly budgets created; alerts at 80%`) } catch (x) { toast(saveErrorText(x)) }
  }
  if (avg.isPending) return <Card><Skeleton rows={6} /></Card>
  return (
    <div className="grid-2 tool-layout">
      <Card title={`Monthly plan (from your average of ${dayLabel(avg.data!.from)} – ${dayLabel(avg.data!.to)})`}>
        {rows.length === 0 ? <p className="muted">No spending recorded in the last three months yet. Record a few weeks of expenses, then come back.</p> : (
          <div className="table-wrap"><table className="table compact">
            <thead><tr><th>Category</th><th className="num">Average</th><th className="num">Plan</th></tr></thead>
            <tbody>{plan.map((r) => (
              <tr key={r.id}><td>{r.name}{r.nature && <span className="muted small"> · {r.nature}</span>}</td><td className="num"><Money value={r.avg} currency={code} /></td>
                <td className="num"><input className="num-input" inputMode="decimal" value={edits[r.id] ?? toPlain(r.avg, code)} onChange={(e) => setEdits({ ...edits, [r.id]: e.target.value })} aria-label={`Plan for ${r.name}`} /></td></tr>
            ))}</tbody>
          </table></div>
        )}
        <h3 className="section-title">Yearly or one-off costs</h3>
        {oneOff.map((o, i) => (
          <div key={i} className="row nowrap mb-s">
            <input value={o.name} onChange={(e) => setOneOff(oneOff.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} aria-label="Cost name" />
            <input inputMode="decimal" value={o.amount} onChange={(e) => setOneOff(oneOff.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)))} placeholder="per year" style={{ maxWidth: 130 }} aria-label={`${o.name} per year`} />
          </div>
        ))}
        <button className="link" onClick={() => setOneOff([...oneOff, { name: '', amount: '' }])}>+ Add a yearly cost</button>
      </Card>
      <Card title="Cost of running your home">
        <div className="stat-label">Per month</div><div className="stat-big"><Money value={monthly} currency={code} /></div>
        <ul className="nw-lines small">
          <li><span>Per year</span><Money value={monthly * 12} currency={code} /></li>
          {yearly > 0 && <li><span>Yearly costs set aside each month</span><Money value={Math.round(yearly / 12)} currency={code} /></li>}
          {needs > 0 && <li><span>Essentials (categories marked “need”)</span><Money value={needs} currency={code} /></li>}
          {income > 0 && <li><span>Average income</span><Money value={income} currency={code} /></li>}
          {income > 0 && <li className={income - monthly < 0 ? 'neg-text' : 'pos-text'}><span>{income - monthly < 0 ? 'Short each month' : 'Left each month'}</span><Money value={Math.abs(income - monthly)} currency={code} /></li>}
        </ul>
        {income > 0 && <Bar fraction={monthly / income} danger={monthly > income} />}
        {can && rows.length > 0 && !created && <button className="primary mt" onClick={createBudgets} disabled={write.batch.isPending}>Create monthly budgets from this plan</button>}
        {created && <p className="pos-text small"><Icon name="check" size={14} /> Budgets created. See them under Budgets.</p>}
        <Assumptions>Averages are your recorded spending per top-level category over the last three full months, net of refunds; transfers, loan principal and money lent are not spending. Yearly costs are divided by 12. Future spending will differ; review the plan every few months.</Assumptions>
      </Card>
    </div>
  )
}

// --- Goals --------------------------------------------------------------------------------

function GoalTool() {
  const code = useCur()
  const [target, setTarget] = useState('500000')
  const [saved, setSaved] = useState('')
  const [mode, setMode] = useState<'by' | 'monthly'>('by')
  const [date, setDate] = useState(addMonths(isoDate(), 24))
  const [monthly, setMonthly] = useState('')
  const [ret, setRet] = useState('6')
  const t = m(target, code), s = m(saved, code), r = num(ret)
  const months = Math.max(0, Math.round((new Date(date).getTime() - Date.now()) / (30.44 * 864e5)))
  const err = t <= 0 ? 'Enter the goal amount' : !(r >= 0 && r <= 30) ? 'Expected return must be 0 to 30%' : null
  return (
    <div className="grid-2 tool-layout">
      <Card title="Your goal">
        <MoneyField label="Goal amount" value={target} onChange={setTarget} />
        <MoneyField label="Already saved for it" value={saved} onChange={setSaved} />
        <Field label="Expected return (% a year)" hint="0 for a savings account; deposits 6–7%"><input inputMode="decimal" value={ret} onChange={(e) => setRet(e.target.value)} /></Field>
        <Segmented label="Plan by" value={mode} onChange={setMode} options={[['by', 'Reach it by a date'], ['monthly', 'Save a set amount']]} />
        {mode === 'by' ? <Field label="Target date"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} min={isoDate()} /></Field> : <MoneyField label="Saving each month" value={monthly} onChange={setMonthly} />}
      </Card>
      <Card title="Plan">
        {err ? <p className="muted">{err}</p> : mode === 'by' ? <>
          <div className="stat-label">Save each month</div><div className="stat-big"><Money value={goalMonthly(t, s, months, r)} currency={code} /></div>
          <p className="muted small">For {months} months until {dayLabel(date)}{s ? `, on top of the ${formatMoney(s, code)} you have` : ''}.</p>
        </> : (() => {
          const n = goalMonths(t, s, m(monthly, code), r)
          return n === null ? <p className="warn-text">At this rate the goal is not reached within 100 years. Save more each month or lower the goal.</p> : <>
            <div className="stat-label">You reach it in</div><div className="stat-big">{n} months</div><p className="muted small">Around {dayLabel(addMonths(isoDate(), n))}.</p></>
        })()}
        <Assumptions>Savings grow at the expected return compounded monthly, with each month's saving added at the end of the month. Returns are not guaranteed; a lower return means saving more.</Assumptions>
      </Card>
    </div>
  )
}

function EmergencyTool() {
  const code = useCur()
  const avg = useAverages(3)
  const cats = useCategories()
  const balances = useBalances()
  const [essential, setEssential] = useState('')
  const [months, setMonths] = useState('6')
  const [have, setHave] = useState('')
  const [by, setBy] = useState('12')
  const needIds = new Set((cats.data ?? []).filter((c) => c.nature === 'need').map((c) => c.id))
  const childOfNeed = (id: string | null) => { const c = (cats.data ?? []).find((x) => x.id === id); return !!c && (needIds.has(c.id) || (!!c.parent_id && needIds.has(c.parent_id) && c.nature !== 'want')) }
  const essAvg = Math.round((avg.data?.byCategory ?? []).filter((c) => childOfNeed(c.category_id)).reduce((a, c) => a + c.amount, 0) / (avg.data?.months ?? 1))
  const liquid = (balances.data ?? []).filter((b) => !b.archived && b.currency === code && ['cash', 'bank', 'savings', 'wallet'].includes(b.type)).reduce((a, b) => a + Math.max(0, b.balance), 0)
  const ess = essential.trim() ? m(essential, code) : essAvg
  const target = ess * (Number(months) || 0)
  const got = have.trim() ? m(have, code) : liquid
  const gap = Math.max(0, target - got)
  return (
    <div className="grid-2 tool-layout">
      <Card title="Your numbers">
        <MoneyField label="Essential spending a month" value={essential} onChange={setEssential} placeholder={toPlain(essAvg, code)} hint="Filled from categories marked “need” (rent, groceries, bills, health…)" />
        <Field label="Months to cover" hint="3 is a minimum; 6–12 if your income is irregular or you support others"><input inputMode="numeric" value={months} onChange={(e) => setMonths(e.target.value)} /></Field>
        <MoneyField label="Savings you can reach quickly" value={have} onChange={setHave} placeholder={toPlain(liquid, code)} hint="Cash, bank, savings and wallets (not FDs or investments)" />
        <Field label="Build the gap over (months)"><input inputMode="numeric" value={by} onChange={(e) => setBy(e.target.value)} /></Field>
      </Card>
      <Card title="Emergency fund">
        <div className="stat-label">Target</div><div className="stat-big"><Money value={target} currency={code} /></div>
        <Bar fraction={target ? got / target : 0} />
        <p className="small">{got >= target && target > 0 ? <span className="pos-text">You have it covered: about {(got / Math.max(1, ess)).toFixed(1)} months of essentials.</span>
          : <>You have <Money value={got} currency={code} />, about {(got / Math.max(1, ess)).toFixed(1)} months. Gap: <strong><Money value={gap} currency={code} /></strong>{Number(by) > 0 && gap > 0 ? <>, or <Money value={Math.ceil(gap / Number(by))} currency={code} /> a month for {by} months</> : ''}.</>}</p>
        <Assumptions>Target = essential monthly spending × months. Only money you can withdraw within a day counts; deposits and investments are left out because breaking them can cost money or take time.</Assumptions>
      </Card>
    </div>
  )
}

// --- Debt payoff ----------------------------------------------------------------------------

function PayoffTool() {
  const code = useCur()
  const { list } = useLoanStatuses()
  const accounts = useAccounts()
  const balances = useBalances()
  const initial = useMemo(() => {
    const out: { name: string; balance: string; rate: string; minimum: string }[] = []
    for (const x of list) if (!x.loan.closed && x.loan.currency === code && x.status.outstanding > 0) out.push({ name: x.loan.name, balance: toPlain(x.status.outstanding, code), rate: x.loan.annual_rate ?? '0', minimum: toPlain(x.status.emi, code) })
    for (const a of accounts.data ?? []) {
      if (a.deleted || a.archived || a.type !== 'credit_card' || a.currency !== code) continue
      const owed = -(balances.data?.find((b) => b.account_id === a.id)?.balance ?? 0)
      if (owed > 0) out.push({ name: a.name, balance: toPlain(owed, code), rate: '36', minimum: toPlain(Math.max(Math.round(owed * 0.05), 0), code) })
    }
    return out
  }, [list, accounts.data, balances.data, code])
  const [rows, setRows] = useState<typeof initial | null>(null)
  const ds = rows ?? initial
  const [extra, setExtra] = useState('')
  const debts: PayoffDebt[] = ds.map((d) => ({ name: d.name || 'Debt', balance: m(d.balance, code), rate: d.rate || null, minimum: m(d.minimum, code) }))
  const minSum = debts.reduce((a, d) => a + d.minimum, 0)
  const budget = minSum + m(extra, code)
  const av = debts.length ? payoff(debts, budget, 'avalanche') : null
  const sn = debts.length ? payoff(debts, budget, 'snowball') : null
  const base = debts.length ? payoff(debts, minSum, 'avalanche') : null
  const set = (i: number, k: string, v: string) => setRows(ds.map((d, j) => (j === i ? { ...d, [k]: v } : d)))
  return (
    <>
      <Card title="Your debts">
        <p className="muted small">Filled from your open loans and credit card balances. Card rates are a guess (36% a year): check your statement.</p>
        <div className="table-wrap"><table className="table compact">
          <thead><tr><th>Debt</th><th className="num">Balance</th><th className="num">Rate %</th><th className="num">Minimum / EMI</th><th /></tr></thead>
          <tbody>{ds.map((d, i) => (
            <tr key={i}><td><input value={d.name} onChange={(e) => set(i, 'name', e.target.value)} aria-label="Debt name" /></td>
              <td><input className="num-input" inputMode="decimal" value={d.balance} onChange={(e) => set(i, 'balance', e.target.value)} aria-label="Balance" /></td>
              <td><input className="num-input" inputMode="decimal" value={d.rate} onChange={(e) => set(i, 'rate', e.target.value)} aria-label="Rate" /></td>
              <td><input className="num-input" inputMode="decimal" value={d.minimum} onChange={(e) => set(i, 'minimum', e.target.value)} aria-label="Minimum payment" /></td>
              <td><button className="icon-btn" aria-label="Remove" onClick={() => setRows(ds.filter((_, j) => j !== i))}><Icon name="x" size={16} /></button></td></tr>
          ))}</tbody>
        </table></div>
        <button className="link" onClick={() => setRows([...ds, { name: '', balance: '', rate: '', minimum: '' }])}>+ Add a debt</button>
        <div className="two mt"><MoneyField label="Extra you can pay each month" value={extra} onChange={setExtra} hint={`On top of the minimums (${formatMoney(minSum, code)})`} /></div>
      </Card>
      {av && sn && base && (
        <div className="compare-grid mt">
          {[['Highest rate first (avalanche)', av, 'Pays the least interest'], ['Smallest balance first (snowball)', sn, 'Quick wins keep you going'], ['Minimums only', base, 'For comparison']].map(([t, r, note]) => {
            const x = r as ReturnType<typeof payoff>
            return (
              <Card key={t as string} title={t as string}>
                {x.never ? <p className="warn-text">{budget < minSum ? 'The budget is below the minimum payments.' : 'Not repaid within 100 years at this budget: the minimums barely cover interest.'}</p> : <>
                  <div className="stat-label">Debt-free in</div><div className="stat-big">{Math.floor(x.months / 12) ? `${Math.floor(x.months / 12)} y ` : ''}{x.months % 12} m</div>
                  <ul className="nw-lines small"><li><span>Interest paid</span><Money value={x.totalInterest} currency={code} /></li><li><span>Order</span><span>{x.order.join(' → ')}</span></li></ul>
                </>}
                <p className="muted small">{note as string}</p>
              </Card>
            )
          })}
        </div>
      )}
      <Assumptions>Each month interest is added (balance × rate ÷ 12), every debt gets its minimum, and all the rest goes to the first debt in priority order. When a debt is paid off its minimum rolls over to the next. Real cards charge daily interest and minimums change with the balance, so treat results as a guide.</Assumptions>
    </>
  )
}

// --- Subscriptions ---------------------------------------------------------------------------

function SubscriptionsTool() {
  const l = useLedger()
  const rules = useRules()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const [from] = monthRange(isoDate(), -6)
  const rows = useQuery({
    queryKey: [l.id, 'tool-recurring', from],
    queryFn: async () => {
      const out: { payee: string | null; amount: number; local_date: string; currency: string; type: string }[] = []
      const cur = new Map((accounts.data ?? []).map((a) => [a.id, a.currency]))
      for (let cursor = '', more = true, pages = 0; more && pages < 20; pages++) {
        const p = await api.transactions(l.id, { from, type: 'expense', limit: '500', ...(cursor ? { cursor } : {}) })
        for (const r of p.transactions) if (!r.data.debt_id && !r.data.loan_id && !r.data.is_adjustment) out.push({ payee: r.data.payee, amount: r.data.amount, local_date: r.data.local_date, currency: cur.get(r.data.account_id) ?? l.base_currency, type: r.data.type })
        cursor = p.next_cursor ?? ''; more = !!cursor
      }
      return findRecurring(out)
    },
    enabled: !!accounts.data,
  })
  const ruleList = (rules.data ?? []).filter((r) => !r.deleted && !r.paused && r.type === 'expense')
  const perMonth = (r: typeof ruleList[number]) => ({ daily: 30.44, weekly: 30.44 / 7, monthly: 1, yearly: 1 / 12 }[r.frequency] ?? 1) / Math.max(1, r.every) * r.amount
  const known = new Set(ruleList.map((r) => (r.payee ?? r.name ?? '').toLowerCase()))
  const found = (rows.data ?? []).filter((g) => !known.has(g.payee.toLowerCase()))
  const code = l.base_currency
  const total = ruleList.reduce((a, r) => a + perMonth(r), 0) + found.filter((f) => f.currency === code).reduce((a, f) => a + f.monthly, 0)
  return (
    <>
      <Card className="mb"><div className="stat-label">Regular payments, per month</div><div className="stat-big"><Money value={Math.round(total)} currency={code} /></div>
        <div className="muted small">About {formatMoney(Math.round(total * 12), code)} a year. Cancelling a ₹500 subscription saves ₹6,000 a year.</div></Card>
      <div className="grid-2">
        <Card title={`Set up as recurring (${ruleList.length})`}>
          {ruleList.length === 0 ? <p className="muted">No recurring expenses set up yet.</p> : <ul className="list">{ruleList.sort((a, b) => perMonth(b) - perMonth(a)).map((r) => (
            <li key={r.id}><div className="grow"><div>{r.name || r.payee || cats.name(r.category_id)}</div><div className="sub">{r.frequency}{r.every > 1 ? ` × ${r.every}` : ''} · {cats.name(r.category_id)}</div></div>
              <div className="right"><Money value={Math.round(perMonth(r))} currency={accounts.data?.find((a) => a.id === r.account_id)?.currency ?? code} /><div className="muted small">a month</div></div></li>
          ))}</ul>}
        </Card>
        <Card title="Found in your history">
          {rows.isPending ? <Skeleton rows={4} /> : found.length === 0 ? <p className="muted">No other repeating payments found in the last six months.</p> : <ul className="list">{found.map((g) => (
            <li key={g.payee + g.currency}><div className="grow"><div>{g.payee}</div><div className="sub">{g.count} payments in {g.months} months · last {dayLabel(g.last)}</div></div>
              <div className="right"><Money value={g.monthly} currency={g.currency} /><div className="muted small">a month</div></div></li>
          ))}</ul>}
          <p className="muted small">A payee charged in at least 3 different months with similar amounts. Some may be regular shopping rather than subscriptions.</p>
        </Card>
      </div>
    </>
  )
}

// --- Net worth ----------------------------------------------------------------------------------

function NetWorthTool() {
  const l = useLedger()
  const [months, setMonths] = useState<'6' | '12' | '24' | '60'>('12')
  const q = useQuery({ queryKey: [l.id, 'networth', months], queryFn: () => api.netWorth(l.id, Number(months)) })
  const code = l.base_currency
  const pts = (q.data ?? []).map((p) => ({ date: p.date, p: p.positions.find((x) => x.currency === code) }))
  const labels = pts.map((x) => new Date(x.date).toLocaleDateString(undefined, { month: 'short', year: '2-digit' }))
  const v = (f: (p: NonNullable<typeof pts[number]['p']>) => number) => pts.map((x) => (x.p ? f(x.p) : 0))
  const last = pts[pts.length - 1]?.p, first = pts[0]?.p
  const change = last && first ? last.net_worth - first.net_worth : 0
  const otherCur = new Set((q.data ?? []).flatMap((p) => p.positions.map((x) => x.currency)).filter((c) => c !== code))
  return (
    <>
      <Segmented label="Period" value={months} onChange={setMonths} options={[['6', '6 months'], ['12', '1 year'], ['24', '2 years'], ['60', '5 years']]} />
      {q.isPending ? <Card><Skeleton rows={5} /></Card> : !last ? <Card><p className="muted">No accounts in {code} yet.</p></Card> : <>
        <div className="grid stats mb">
          <Card className="stat-card"><div className="stat-label">Net worth now</div><div className="stat-big"><Money value={last.net_worth} currency={code} /></div></Card>
          <Card className="stat-card"><div className="stat-label">Change since {labels[0]}</div><div className={`stat-big ${change >= 0 ? 'pos-text' : 'neg-text'}`}><Money value={change} currency={code} signed /></div></Card>
          <Card className="stat-card"><div className="stat-label">Owed (cards, loans, people)</div><div className="stat-big"><Money value={last.liabilities + last.payable} currency={code} /></div></Card>
        </div>
        <Card title="Month by month">
          <LineChart labels={labels} currency={code} label="Net worth, assets and debts at each month end" series={[
            { name: 'Net worth', color: 'var(--brand)', values: v((p) => p.net_worth) },
            { name: 'Assets', color: 'var(--pos)', values: v((p) => p.cash + p.investments + p.receivable), dashed: true },
            { name: 'Debts', color: 'var(--neg)', values: v((p) => p.liabilities + p.payable), dashed: true },
          ]} />
          <div className="table-wrap"><table className="table compact">
            <thead><tr><th>Month end</th><th className="num">Cash & bank</th><th className="num">Investments</th><th className="num">Owed to you</th><th className="num">Cards & loans</th><th className="num">You owe</th><th className="num">Net worth</th></tr></thead>
            <tbody>{[...pts].reverse().map((x) => x.p && <tr key={x.date}><td>{dayLabel(x.date)}</td><td className="num"><Money value={x.p.cash} currency={code} /></td><td className="num"><Money value={x.p.investments} currency={code} /></td><td className="num"><Money value={x.p.receivable} currency={code} /></td><td className="num"><Money value={-x.p.liabilities} currency={code} /></td><td className="num"><Money value={-x.p.payable} currency={code} /></td><td className="num"><strong><Money value={x.p.net_worth} currency={code} /></strong></td></tr>)}</tbody>
          </table></div>
        </Card>
        {otherCur.size > 0 && <p className="muted small">Also held in {[...otherCur].join(', ')}; those are tracked separately and not converted here.</p>}
      </>}
      <Assumptions>Each month end uses account balances on that day (planned items excluded), plus open personal debts that existed by then. Credit limits are not counted. Investments and deposits are shown at the money recorded in them (principal plus interest credited), not market value or accrued interest. Archived accounts and those marked “not in net worth” are left out.</Assumptions>
    </>
  )
}

