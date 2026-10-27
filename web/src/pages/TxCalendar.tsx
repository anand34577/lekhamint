import { useContext } from 'react'
import type { TxRow } from '../lib/api'
import { compactMoney } from '../lib/money'
import { HideAmounts } from '../components'
import { isoDate, monthRange, parseDate, plural } from '../lib/util'
import { firstWeekday } from '../controls'

const WEEK = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

/**
 * A month as a calendar: each day shows money out and in (main currency), and
 * the darker the day, the more was spent. Picking a day lists its transactions.
 */
export function TxCalendar({ month, rows, currency, isMain, picked, onPick, spendOf }: {
  month: string; rows: TxRow[]; currency: string; isMain: (t: TxRow) => boolean
  picked: string; onPick: (day: string) => void
  /** Signed spending of a row for the totals (refund rules), or 0 to leave it out. */
  spendOf: (t: TxRow) => { out: number; in: number }
}) {
  const hidden = useContext(HideAmounts)
  const [from, to] = monthRange(month)
  const first = parseDate(from)
  const days = parseDate(to).getDate()
  // Weeks start on the reader's first weekday, as in the date picker (Sunday in the US, Monday in most places).
  const weekStart = firstWeekday()
  const lead = (first.getDay() - weekStart + 7) % 7
  const totals = new Map<string, { out: number; in: number; n: number }>()
  for (const t of rows) {
    if (!isMain(t)) continue
    const s = spendOf(t)
    const cur = totals.get(t.local_date) ?? { out: 0, in: 0, n: 0 }
    totals.set(t.local_date, { out: cur.out + s.out, in: cur.in + s.in, n: cur.n + 1 })
  }
  const max = Math.max(1, ...[...totals.values()].map((v) => v.out))
  // The local day: toISOString gives the UTC one, which is yesterday or tomorrow for some hours of the day.
  const today = isoDate()
  const cells: (string | null)[] = [...Array(lead).fill(null), ...Array.from({ length: days }, (_, i) => `${from.slice(0, 8)}${String(i + 1).padStart(2, '0')}`)]
  while (cells.length % 7) cells.push(null)
  const spent = [...totals.values()].reduce((a, v) => a + v.out, 0)
  const busiest = [...totals].sort((a, b) => b[1].out - a[1].out)[0]
  return (
    <div className="txcal">
      <div className="txcal-grid" role="grid" aria-label="Spending by day">
        {Array.from({ length: 7 }, (_, i) => WEEK[(weekStart + i) % 7]).map((d) => <div key={d} className="txcal-head" role="columnheader">{d}</div>)}
        {cells.map((d, i) => {
          if (!d) return <div key={'e' + i} className="txcal-cell empty" />
          const v = totals.get(d)
          const heat = v ? Math.min(1, v.out / max) : 0
          return (
            <button key={d} type="button" role="gridcell" className={`txcal-cell ${picked === d ? 'on' : ''} ${d === today ? 'today' : ''} ${v ? '' : 'quiet'}`}
              style={{ ['--heat' as string]: heat.toFixed(3) }} onClick={() => onPick(picked === d ? '' : d)}
              aria-label={`${d}: ${v ? plural(v.n, 'transaction') : 'nothing recorded'}`} aria-pressed={picked === d}>
              <span className="txcal-day">{Number(d.slice(8))}</span>
              {v && !hidden && v.out > 0 && <span className="txcal-out">−{compactMoney(v.out, currency)}</span>}
              {v && !hidden && v.in > 0 && <span className="txcal-in">+{compactMoney(v.in, currency)}</span>}
              {v && hidden && <span className="txcal-out">{v.n}×</span>}
            </button>
          )
        })}
      </div>
      <div className="txcal-foot muted small">
        <span><i className="txcal-key" style={{ ['--heat' as string]: '0.15' }} /> less</span>
        <span><i className="txcal-key" style={{ ['--heat' as string]: '1' }} /> more spent</span>
        {busiest && busiest[1].out > 0 && !hidden && <span className="spacer-left">Busiest day: {Number(busiest[0].slice(8))} ({Math.round((busiest[1].out / Math.max(1, spent)) * 100)}% of the month)</span>}
      </div>
    </div>
  )
}
