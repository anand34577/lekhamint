import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Account } from '../lib/api'
import { allTransactions } from '../lib/reports'
import { useAccounts, useBalances, useCanWrite, useCategoryIndex, useLedger, useRules, useWrite, type WithId } from '../lib/data'
import { CADENCE_LABEL, CADENCE_RULE, detectSubscriptions, payeeKey, type Subscription } from '../lib/subscriptions'
import { dailySpend, forecast, type FlowEvent } from '../lib/forecast'
import { addDays, isoDate, shortDate } from '../lib/util'
import { useObligations } from './obligations'
import { Card, Empty, LineChart, Money, Segmented, Skeleton, errorText, toast } from '../components'
import { Explain, Loading, RankBars, SERIES } from '../charts'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { useSetting } from '../lib/settings'

const CASH_TYPES = new Set(['cash', 'bank', 'wallet'])

/** Looking ahead: a cash-flow forecast, and subscriptions found in past spending. */
export function Insights({ go }: PageProps) {
  const l = useLedger()
  const today = isoDate()
  // A year and a bit of spending is enough to see yearly payments repeat.
  const spending = useQuery({
    queryKey: [l.id, 'transactions', 'insights', today],
    queryFn: () => allTransactions(l.id, { from: addDays(today, -400), to: today, type: 'expense' }),
    staleTime: 5 * 60_000,
  })
  return (
    <>
      <div className="page-head"><h1>Insights & forecast</h1></div>
      <p className="muted page-lead">What is coming: how your cash may move over the next weeks, and the payments that repeat on their own. Everything is worked out on your own data; nothing is sent anywhere.</p>
      <ForecastCard spending={spending.data} loading={spending.isPending} go={go} />
      {spending.isError ? <Card><p className="error">{errorText(spending.error)}</p></Card> : <Subscriptions spending={spending.data} loading={spending.isPending} />}
    </>
  )
}

type Tx = Awaited<ReturnType<typeof allTransactions>>[number]

function ForecastCard({ spending, loading, go }: { spending?: Tx[]; loading: boolean; go: PageProps['go'] }) {
  const l = useLedger()
  const accounts = useAccounts()
  const balances = useBalances()
  const [days, setDays] = useState<'30' | '60' | '90'>('60')
  const horizon = Number(days)
  const ob = useObligations(horizon)
  const today = isoDate()
  const cashAccts = useMemo(() => (accounts.data ?? []).filter((a) => !a.deleted && !a.archived && CASH_TYPES.has(a.type) && a.currency === l.base_currency), [accounts.data, l.base_currency])
  const result = useMemo(() => {
    if (!spending || !balances.data) return null
    const ids = new Set(cashAccts.map((a) => a.id))
    const start = (balances.data ?? []).filter((b) => ids.has(b.account_id)).reduce((s, b) => s + b.balance, 0)
    // Everyday spending: the last 90 days of expenses in the main currency that no bill, EMI or deposit already covers.
    const from = addDays(today, -89)
    const cur = (id: string) => accounts.data?.find((a) => a.id === id)?.currency
    const totals = new Map<string, number>()
    for (const t of spending) {
      if (t.local_date < from || t.recurring_rule_id || t.loan_id || t.deposit_id || t.debt_id || t.is_adjustment || t.excluded_from_reports || t.status === 'planned') continue
      if (cur(t.account_id) !== l.base_currency) continue
      totals.set(t.local_date, (totals.get(t.local_date) ?? 0) + t.amount)
    }
    const firstSeen = spending.reduce((m, t) => (t.local_date < m ? t.local_date : m), today)
    const historyDays = Math.max(14, Math.min(90, -Math.round((new Date(firstSeen).getTime() - new Date(today).getTime()) / 864e5)))
    const spend = dailySpend(totals, addDays(today, -historyDays + 1), historyDays)
    const events: FlowEvent[] = ob.items
      .filter((o) => o.amount && o.currency === l.base_currency && (!o.accountId || ids.has(o.accountId)) && daysBetweenSafe(o.date, today) <= 7)
      .map((o) => ({ date: o.date, amount: o.direction === 'in' ? o.amount! : -o.amount!, title: o.title }))
    return { f: forecast(start, events, spend, today, horizon), start, spend, events }
  }, [spending, balances.data, ob.items, cashAccts, horizon, today, accounts.data, l.base_currency])

  if (loading || ob.pending || !result) return <Card title={<><Icon name="forecast" size={16} /> Cash forecast</>} className="mb"><Loading text="Working out the forecast…" /></Card>
  if (!cashAccts.length) return <Card title={<><Icon name="forecast" size={16} /> Cash forecast</>} className="mb"><Empty icon="wallet" title="No cash or bank accounts" body={`The forecast follows cash, bank and wallet accounts in ${l.base_currency}. Add one to see where your money is heading.`} action={<button onClick={() => go('accounts')}>Go to Accounts</button>} /></Card>
  const { f, start, spend, events } = result
  const step = Math.max(1, Math.round(horizon / 30))
  const pts = f.points.filter((_, i) => i % step === 0 || i === f.points.length - 1)
  const end = f.points[f.points.length - 1]
  const upcoming = [...events].sort((a, b) => a.date.localeCompare(b.date)).slice(0, 8)
  return (
    <Card title={<><Icon name="forecast" size={16} /> Cash forecast</>} className="mb">
      <div className="mb-s"><Segmented label="Look ahead" value={days} onChange={setDays} options={[['30', '30 days'], ['60', '60 days'], ['90', '90 days']]} /></div>
      <div className="forecast-head">
        <div><div className="stat-label">Now</div><div className="stat-mid"><Money value={start} currency={l.base_currency} /></div></div>
        <div><div className="stat-label">Likely in {horizon} days</div><div className="stat-mid"><Money value={end.expected} currency={l.base_currency} /></div>
          <div className="muted small">between <Money value={end.low} currency={l.base_currency} /> and <Money value={end.high} currency={l.base_currency} /></div></div>
        <div><div className="stat-label">Lowest point</div><div className={`stat-mid ${f.lowest.expected < 0 ? 'neg' : ''}`}><Money value={f.lowest.expected} currency={l.base_currency} /></div>
          <div className="muted small">around {shortDate(f.lowest.date)}</div></div>
      </div>
      {f.shortBy && <div className="callout warn"><Icon name="alert" size={16} /><div>Money in cash and bank accounts could run short around <strong>{shortDate(f.shortBy)}</strong> if spending stays at its usual level. Move money in, or hold back on spending before then.</div></div>}
      <LineChart height={190} fit currency={l.base_currency} label={`Cash and bank balance over the next ${horizon} days`}
        labels={pts.map((p) => shortDate(p.date))} series={[
          { name: 'Likely', color: SERIES[0], values: pts.map((p) => p.expected) },
          { name: 'Cautious', color: SERIES[1], values: pts.map((p) => p.low), dashed: true },
          { name: 'Hopeful', color: SERIES[2], values: pts.map((p) => p.high), dashed: true },
        ]} />
      <Explain title="How this is worked out" steps={[
        { title: `Start from today's cash, bank and wallet balances (${cashAccts.length} ${cashAccts.length === 1 ? 'account' : 'accounts'})`, value: <Money value={start} currency={l.base_currency} /> },
        { title: 'Add income and take off bills, EMIs and instalments on their dates', body: `${events.length} known ${events.length === 1 ? 'item' : 'items'} from Bills & recurring, Loans and Deposits.`, value: <Money value={f.knownIn - f.knownOut} currency={l.base_currency} signed /> },
        { title: 'Take off everyday spending at your usual pace', body: `About ${formatPerDay(spend.mean, l.base_currency)} a day, the average of the last ${spend.days} days (spending that is not a recorded bill or EMI, card spends included).`, value: <Money value={-f.everyday} currency={l.base_currency} signed /> },
        { title: 'Show a range, not one number', body: 'Spending varies day to day, so the dashed lines show where 8 in 10 months would land. The range widens the further ahead you look.' },
      ]} />
      {upcoming.length > 0 && <>
        <h3 className="sub-head">Known items in this period</h3>
        <ul className="list compact">{upcoming.map((e, i) => (
          <li key={i}><span className="muted small date-col">{shortDate(e.date)}</span><span className="grow">{e.title}</span><Money value={e.amount} currency={l.base_currency} signed /></li>
        ))}</ul>
      </>}
    </Card>
  )
}

const daysBetweenSafe = (date: string, today: string) => (date < today ? Math.round((new Date(today).getTime() - new Date(date).getTime()) / 864e5) : 0)
const formatPerDay = (minor: number, cur: string) => new Intl.NumberFormat(undefined, { style: 'currency', currency: cur, maximumFractionDigits: 0 }).format(minor / 100)

function Subscriptions({ spending, loading }: { spending?: Tx[]; loading: boolean }) {
  const l = useLedger()
  const rules = useRules()
  const cats = useCategoryIndex()
  const accounts = useAccounts()
  const write = useWrite()
  const can = useCanWrite()
  const [hidden, setHidden] = useSetting<string[]>(`subscriptions_hidden.${l.id}`, [])
  const today = isoDate()
  const subs = useMemo(() => {
    if (!spending) return []
    const ruled = new Set((rules.data ?? []).filter((r) => !r.deleted && r.payee).map((r) => payeeKey(r.payee!)))
    const accCur = (id: string) => accounts.data?.find((a) => a.id === id)?.currency
    return detectSubscriptions(spending.filter((t) => accCur(t.account_id) === l.base_currency && !t.loan_id && !t.deposit_id && !t.debt_id), today, ruled)
  }, [spending, rules.data, accounts.data, today, l.base_currency])
  const shown = subs.filter((s) => !hidden.includes(s.key))
  const active = shown.filter((s) => !s.stale)
  const yearly = active.reduce((a, s) => a + s.yearly, 0)
  const hide = (k: string) => setHidden([...hidden, k])
  const track = async (s: Subscription) => {
    const [frequency, every] = CADENCE_RULE[s.cadence]
    const acc = accounts.data?.find((a) => a.id === s.accountId) as WithId<Account> | undefined
    try {
      await write.save.mutateAsync({ entity: 'recurring_rules', data: {
        name: s.payee, type: 'expense', account_id: acc && !acc.deleted ? s.accountId : (accounts.data ?? []).find((a) => !a.deleted && !a.archived)?.id, amount: s.amount,
        category_id: s.categoryId, payee: s.payee, frequency, every, start_date: s.nextDate, next_date: s.nextDate, mode: 'remind', paused: false, tag_ids: [],
      } })
      toast(`${s.payee} is now in Bills & recurring. You will be reminded before it is due.`)
    } catch (x) { toast(errorText(x)) }
  }
  return (
    <Card title={<><Icon name="repeat" size={16} /> Subscriptions and repeat payments</>} className="mb">
      {loading ? <Skeleton rows={4} /> : shown.length === 0 ? (
        <Empty icon="repeat" title="No repeat payments found" body="Payments to the same payee at a steady interval and a similar amount show up here, so you can track them, spot price rises and cancel what you do not use. Ones already in Bills & recurring are left out." />
      ) : <>
        {active.length > 0 && <div className="subs-head">
          <div><div className="stat-label">These cost you about</div><div className="stat-big"><Money value={yearly} currency={l.base_currency} /> <span className="muted small">a year</span></div>
            <div className="muted small">that is <Money value={Math.round(yearly / 12)} currency={l.base_currency} /> a month across {active.length} {active.length === 1 ? 'payment' : 'payments'}</div></div>
          <RankBars currency={l.base_currency} label="Yearly cost of each repeat payment" rows={active.slice(0, 6).map((s, i) => ({ key: s.key, name: s.payee, value: s.yearly, color: SERIES[i % 8] }))} />
        </div>}
        <ul className="list subs-list">{shown.map((s) => (
          <li key={s.key} className={s.stale ? 'muted-row' : ''}>
            <div className="grow">
              <div><strong>{s.payee}</strong> <span className="chip">{CADENCE_LABEL[s.cadence]}</span>
                {s.priceChange && <span className={`chip ${s.priceChange.to > s.priceChange.from ? 'warn' : 'good'}`}><Icon name={s.priceChange.to > s.priceChange.from ? 'trendUp' : 'trendDown'} size={13} /> was <Money value={s.priceChange.from} currency={l.base_currency} /></span>}
                {s.stale && <span className="chip">stopped?</span>}</div>
              <div className="sub">{s.count} payments · last {shortDate(s.lastDate)}{s.categoryId ? ` · ${cats.path(s.categoryId)}` : ''}{s.stale ? ' · none since; maybe cancelled' : ` · next about ${shortDate(s.nextDate)}`}</div>
            </div>
            <div className="acc-balance"><Money value={s.amount} currency={l.base_currency} /><div className="muted small"><Money value={s.yearly} currency={l.base_currency} /> a year</div></div>
            <div className="subs-actions">
              {can && !s.stale && <button className="link" onClick={() => track(s)} title="Add to Bills & recurring with a reminder">Track</button>}
              <button className="link" onClick={() => hide(s.key)} title="Hide this from the list">Not one</button>
            </div>
          </li>
        ))}</ul>
        <p className="muted small">Found from the last 13 months: at least 3 payments to the same payee at a steady interval and a similar amount. A <Icon name="trendUp" size={12} /> marks a price rise since earlier payments.</p>
      </>}
    </Card>
  )
}
