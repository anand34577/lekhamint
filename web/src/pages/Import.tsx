import { useEffect, useMemo, useState } from 'react'
import { api, type BatchOp } from '../lib/api'
import { useQueryClient } from '@tanstack/react-query'
import { useAccounts, useCategorizationRules, useCategoryIndex, useLedger } from '../lib/data'
import {
  ROLE_LABELS, dateWindow, detectDateFormat, detectDelimiter, findDuplicates, fingerprints, guessRoles, matchCategory,
  parseCsv, toRows, type DateFormat, type Existing, type ParsedRow, type Role,
} from '../lib/csv'
import { applyRules } from '../lib/rules'
import { parseOfx, parseQif, statementKind, type Parsed } from '../lib/ofx'
import { ID_NAMESPACE, atTime, uuidv5 } from '../lib/util'
import { Card, Field, Money, errorText, toast } from '../components'
import { CategorySelect } from './Transactions'
import type { PageProps } from '../App'
import { FilePick, Select } from '../controls'

interface Planned { row: ParsedRow; include: boolean; duplicateOf?: string; categoryId: string; payee: string; tagIds: string[]; ruleName?: string }

/**
 * Import a bank or card statement (CSV). Columns are mapped once, every row is
 * previewed with likely duplicates unticked, and re-importing the same file is
 * harmless: each row's ID is derived from its content.
 */
export function Import({ go }: PageProps) {
  const l = useLedger()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const rules = useCategorizationRules()
  const qc = useQueryClient()
  const live = (accounts.data ?? []).filter((a) => !a.deleted && !a.archived)

  const [fileName, setFileName] = useState('')
  const [table, setTable] = useState<string[][] | null>(null)
  const [accountId, setAccountId] = useState('')
  const [hasHeader, setHasHeader] = useState(true)
  const [roles, setRoles] = useState<Role[]>([])
  const [dateFormat, setDateFormat] = useState<DateFormat>('DMY')
  const [decimalComma, setDecimalComma] = useState(false)
  const [positiveIsExpense, setPositiveIsExpense] = useState(false)
  const [plan, setPlan] = useState<Planned[] | null>(null)
  // OFX and QIF files need no column mapping: they are read straight into rows.
  const [direct, setDirect] = useState<(Parsed & { kind: 'ofx' | 'qif' }) | null>(null)
  const [busy, setBusy] = useState('')
  const [err, setErr] = useState('')
  useEffect(() => { if (!accountId && live[0]) setAccountId(live[0].id) }, [accountId, live])
  const account = live.find((a) => a.id === accountId)
  const code = account?.currency ?? l.base_currency

  const load = async (f: File) => {
    setErr(''); setPlan(null)
    if (f.size > 20 << 20) return setErr('That file is over 20 MB. Split it into smaller statements.')
    const text = await f.text()
    const kind = statementKind(f.name, text)
    if (kind !== 'csv') {
      const p = kind === 'ofx' ? parseOfx(text, code) : parseQif(text, code, navigator.language === 'en-US')
      if (p.rows.length === 0) return setErr(`No transactions found in this ${kind.toUpperCase()} file.`)
      setFileName(f.name); setTable(null); setDirect({ ...p, kind })
      return
    }
    setDirect(null)
    const t = parseCsv(text, detectDelimiter(text))
    if (t.length < 2) return setErr('No rows found. Is this a CSV file?')
    setFileName(f.name); setTable(t)
    const r = guessRoles(t[0])
    setRoles(r)
    const di = r.indexOf('date')
    setDateFormat(detectDateFormat(di >= 0 ? t.slice(1, 200).map((x) => x[di] ?? '') : [], navigator.language === 'en-US'))
    setPositiveIsExpense(account?.type === 'credit_card')
  }

  const width = table ? Math.max(...table.slice(0, 50).map((r) => r.length)) : 0
  const parsed = useMemo(() => direct ?? (table && roles.length
    ? toRows(table, { roles, hasHeader, dateFormat, currency: code, decimalComma, positiveIsExpense })
    : null), [direct, table, roles, hasHeader, dateFormat, code, decimalComma, positiveIsExpense])

  const preview = async () => {
    if (!parsed || !accountId) return
    setBusy('Checking for duplicates…'); setErr('')
    try {
      const win = dateWindow(parsed.rows)
      const existing: Existing[] = []
      if (win) {
        for (let cursor = '', more = true; more;) {
          const page = await api.transactions(l.id, { from: win[0], to: win[1], account_id: accountId, limit: '500', ...(cursor ? { cursor } : {}) })
          existing.push(...page.transactions.filter((t) => t.data.account_id === accountId).map((t) => ({ id: t.id, ...t.data })))
          cursor = page.next_cursor ?? ''; more = !!cursor
        }
      }
      const dup = findDuplicates(parsed.rows, accountId, existing)
      setPlan(parsed.rows.map((row, i) => {
        const out = applyRules(rules.data ?? [], { payee: row.description, note: row.note, type: row.type, accountId, amount: row.amount })
        const fromFile = row.category ? matchCategory(row.category, cats.all, row.type) : null
        return {
          row, include: !dup.has(i), duplicateOf: dup.get(i),
          categoryId: fromFile ?? (out.categoryId && cats.byId.has(out.categoryId) ? out.categoryId : ''),
          payee: out.payee ?? row.description, tagIds: out.tagIds, ruleName: out.matched[0] ? out.matched[0].name ?? out.matched[0].pattern : undefined,
        }
      }))
    } catch (x) { setErr(errorText(x)) } finally { setBusy('') }
  }

  const doImport = async () => {
    if (!plan || !accountId) return
    const chosen = plan.filter((p) => p.include)
    if (chosen.length === 0) return
    setBusy('Importing…'); setErr('')
    try {
      const fp = fingerprints(plan.map((p) => p.row))
      const ops: BatchOp[] = []
      for (let i = 0; i < plan.length; i++) {
        const p = plan[i]
        if (!p.include) continue
        // The statement's own time when it has one; a bare date is saved at midday (shown without a time).
        const at = p.row.time ? atTime(p.row.date, p.row.time) : new Date(p.row.date + 'T12:00:00').getTime()
        ops.push({
          op: 'create_if_missing', entity: 'transactions', id: await uuidv5(ID_NAMESPACE, `import:${accountId}:${fp[i]}`),
          data: {
            type: p.row.type, account_id: accountId, amount: p.row.amount, category_id: p.categoryId || null,
            payee: p.payee.slice(0, 200) || null, note: p.row.note.slice(0, 2000) || null, local_date: p.row.date, occurred_at: at,
            tz_offset_min: -new Date(at).getTimezoneOffset(), tag_ids: p.tagIds, status: 'cleared',
          },
        })
      }
      const res = await api.batch(l.id, ops, (n) => setBusy(`Importing… ${n} of ${ops.length}`))
      qc.invalidateQueries({ queryKey: [l.id] })
      const created = res.filter((r) => r.status === 'created').length
      const skipped = res.length - created
      toast(`Imported ${created} ${created === 1 ? 'transaction' : 'transactions'}${skipped ? ` · ${skipped} already imported` : ''}`)
      setPlan(null); setTable(null); setDirect(null); setFileName('')
      go('transactions')
    } catch (x) { setErr(errorText(x)) } finally { setBusy('') }
  }

  const sample = (table ?? []).slice(hasHeader ? 1 : 0, (hasHeader ? 1 : 0) + 3)
  const included = plan?.filter((p) => p.include) ?? []
  const totalOut = included.filter((p) => p.row.type === 'expense').reduce((a, p) => a + p.row.amount, 0)
  const totalIn = included.filter((p) => p.row.type === 'income').reduce((a, p) => a + p.row.amount, 0)

  return (
    <>
      <div className="page-head"><h1>Import statement</h1></div>
      <Card title="1. Choose a statement file">
        <p className="muted small" style={{ marginTop: 0 }}>Download a statement from your bank or card website as CSV, OFX (also called Money or QFX) or QIF. Exports from other money apps in these formats work too. The file is read here in your browser; only the rows you confirm are saved.</p>
        <div className="two">
          <div className="field"><span className="field-label">File</span><FilePick accept=".csv,text/csv,.txt,.ofx,.qfx,.qif" onPick={load} label="Choose a file or drop it here" hint="CSV, OFX, QFX or QIF" /></div>
          <Field label="Into account"><Select value={accountId} onChange={(e) => { setAccountId(e.target.value); setPlan(null) }}>{live.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}</Select></Field>
        </div>
        {fileName && table && <p className="small">{fileName}: {table.length - (hasHeader ? 1 : 0)} rows</p>}
      </Card>

      {direct && (
        <Card title={`2. Read from ${direct.kind.toUpperCase()}`} className="mt">
          <p className="small">{fileName}: {direct.rows.length} transactions{direct.errors.length > 0 && <>, <span style={{ color: 'var(--danger)' }}>{direct.errors.length} skipped</span> ({direct.errors.slice(0, 3).map((e) => `#${e.line}: ${e.message}`).join('; ')})</>}.
            {direct.kind === 'ofx' && ' Each transaction carries the bank’s own ID, so importing an overlapping statement later never adds it twice.'}</p>
          {direct.currency && account && direct.currency !== account.currency && <p className="callout warn small">This statement is in {direct.currency} but {account.name} is in {account.currency}. Pick a {direct.currency} account.</p>}
          <button className="primary" onClick={preview} disabled={!accountId || !!busy}>Preview</button>
        </Card>
      )}

      {table && (
        <Card title="2. Match the columns" className="mt">
          <label className="row"><input type="checkbox" style={{ width: 'auto' }} checked={hasHeader} onChange={(e) => setHasHeader(e.target.checked)} /> The first row has column names</label>
          <div style={{ overflowX: 'auto', marginTop: 8 }}>
            <table className="tx">
              <thead><tr>{Array.from({ length: width }, (_, i) => (
                <th key={i}>
                  <Select value={roles[i] ?? 'ignore'} onChange={(e) => { const r = [...roles]; r[i] = e.target.value as Role; setRoles(r); setPlan(null) }} aria-label={`Column ${i + 1}`}>
                    {(Object.keys(ROLE_LABELS) as Role[]).map((k) => <option key={k} value={k}>{ROLE_LABELS[k]}</option>)}
                  </Select>
                  {hasHeader && <div className="muted small" style={{ fontWeight: 400 }}>{table[0][i]}</div>}
                </th>
              ))}</tr></thead>
              <tbody>{sample.map((r, k) => <tr key={k}>{Array.from({ length: width }, (_, i) => <td key={i} className="small">{r[i]}</td>)}</tr>)}</tbody>
            </table>
          </div>
          <div className="two" style={{ marginTop: 12 }}>
            <Field label="Dates are written"><Select value={dateFormat} onChange={(e) => { setDateFormat(e.target.value as DateFormat); setPlan(null) }}>
              <option value="DMY">Day first (24/10/2026)</option><option value="MDY">Month first (10/24/2026)</option><option value="YMD">Year first (2026-10-24)</option></Select></Field>
            <div>
              <label className="row"><input type="checkbox" style={{ width: 'auto' }} checked={decimalComma} onChange={(e) => { setDecimalComma(e.target.checked); setPlan(null) }} /> Decimal comma (1.234,56)</label>
              {!roles.includes('debit') && !roles.includes('credit') && (
                <label className="row"><input type="checkbox" style={{ width: 'auto' }} checked={positiveIsExpense} onChange={(e) => { setPositiveIsExpense(e.target.checked); setPlan(null) }} /> Positive amounts are spending (common on card statements)</label>
              )}
            </div>
          </div>
          {parsed && (
            <p className="small">
              {parsed.rows.length} rows ready{parsed.errors.length > 0 && <> · <span style={{ color: 'var(--danger)' }}>{parsed.errors.length} skipped</span> ({parsed.errors.slice(0, 3).map((e) => `line ${e.line}: ${e.message}`).join('; ')}{parsed.errors.length > 3 ? '…' : ''})</>}
            </p>
          )}
          <button className="primary" onClick={preview} disabled={!parsed || parsed.rows.length === 0 || !accountId || !!busy}>Preview</button>
        </Card>
      )}

      {plan && (
        <Card title="3. Check and import" className="mt" action={<span className="muted small">{included.length} of {plan.length} selected</span>}>
          <div className="row" style={{ marginBottom: 8 }}>
            <span className="chip">Out <Money value={totalOut} currency={code} /></span>
            <span className="chip">In <Money value={totalIn} currency={code} /></span>
            {plan.some((p) => p.duplicateOf) && <span className="chip warn">{plan.filter((p) => p.duplicateOf).length} look like transactions you already have, and are unticked</span>}
          </div>
          <div style={{ overflowX: 'auto', maxHeight: 520 }}>
            <table className="tx">
              <thead><tr><th style={{ width: 28 }}><input type="checkbox" aria-label="Select all" checked={included.length === plan.length} onChange={(e) => setPlan(plan.map((p) => ({ ...p, include: e.target.checked })))} /></th>
                <th>Date</th><th>Description</th><th>Category</th><th className="amt">Amount</th></tr></thead>
              <tbody>
                {plan.map((p, i) => (
                  <tr key={i} className={p.duplicateOf ? 'muted-row' : ''}>
                    <td><input type="checkbox" aria-label="Include" checked={p.include} onChange={(e) => setPlan(plan.map((x, k) => (k === i ? { ...x, include: e.target.checked } : x)))} /></td>
                    <td className="small">{p.row.date}</td>
                    <td><div>{p.payee}</div><div className="muted small">{p.duplicateOf ? 'Possible duplicate' : p.ruleName ? `Rule “${p.ruleName}”` : p.payee !== p.row.description ? p.row.description : ''}</div></td>
                    <td><CategorySelect value={p.categoryId} onChange={(v) => setPlan(plan.map((x, k) => (k === i ? { ...x, categoryId: v } : x)))} cats={cats} kind={p.row.type === 'expense' ? 'expense' : undefined} /></td>
                    <td className="amt"><Money value={p.row.type === 'income' ? p.row.amount : -p.row.amount} currency={code} signed /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small">Importing the same file again later skips rows that are already in.</p>
          <button className="primary" onClick={doImport} disabled={!!busy || included.length === 0}>Import {included.length} {included.length === 1 ? 'transaction' : 'transactions'}</button>
        </Card>
      )}
      {busy && <p className="muted" role="status">{busy}</p>}
      {err && <p className="error" role="alert">{err}</p>}
    </>
  )
}
