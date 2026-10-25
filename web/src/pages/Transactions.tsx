import { useEffect, useMemo, useRef, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { api, type BatchOp, type Transaction, type TxRow } from '../lib/api'
import { isLiability, useAccounts, useBalances, useCanWrite, useCategorizationRules, useCategoryIndex, useLedger, useSplitIndex, useSplits, useStableId, useTagIndex, useWrite } from '../lib/data'
import { evaluate } from '../lib/finance'
import { applyRules, type RuleOutcome } from '../lib/rules'
import { currency, formatMoney, parseMoney, toPlain } from '../lib/money'
import { addDays, csvEscape, dayLabel, download, isoDate, monthLabel, monthRange, uuidv7 } from '../lib/util'
import { Card, Check, Empty, Field, Modal, Money, Segmented, Skeleton, errorText, saveErrorText, toast } from '../components'
import { CatIcon, Icon } from '../icons'
import { SplitEditor, TagInput, splitMinor, type SplitDraft } from './parts'
import { rememberAccount, type Resolved } from './QuickAdd'
import type { PageProps } from '../App'
import { Select, DateField, NumInput } from '../controls'

export function Transactions({ params, go }: PageProps) {
  const l = useLedger()
  const cats = useCategoryIndex()
  const tags = useTagIndex()
  const splits = useSplitIndex()
  const accounts = useAccounts()
  const write = useWrite()
  const canWrite = useCanWrite()
  const [month, setMonth] = useState(params.month ?? isoDate())
  const [allTime, setAllTime] = useState(params.all === '1')
  const [type, setType] = useState(params.type ?? '')
  const [account, setAccount] = useState(params.account ?? '')
  const [category, setCategory] = useState(params.category ?? '')
  const [tag, setTag] = useState(params.tag ?? '')
  const [q, setQ] = useState(params.q ?? '')
  const [debouncedQ, setDebouncedQ] = useState(q.trim())
  useEffect(() => { const h = setTimeout(() => setDebouncedQ(q.trim()), 300); return () => clearTimeout(h) }, [q])
  // A link with new filters (from Accounts, Tags, the palette…) replaces the current ones.
  useEffect(() => {
    setAccount(params.account ?? ''); setCategory(params.category ?? ''); setTag(params.tag ?? ''); setType(params.type ?? '')
    setQ(params.q ?? ''); setDebouncedQ((params.q ?? '').trim()); setAllTime(params.all === '1')
    if (params.month) setMonth(params.month)
  }, [params])
  const searchRef = useRef<HTMLInputElement>(null)
  useEffect(() => { if (params.focus === 'search') searchRef.current?.focus() }, [params])
  const [trash, setTrash] = useState(false)
  const [editing, setEditing] = useState<TxRow | 'new' | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkCat, setBulkCat] = useState('')
  const [bulkTag, setBulkTag] = useState('')

  const [from, to] = monthRange(month)
  const ranged = !debouncedQ && !allTime
  const query: Record<string, string> = {
    limit: '200', ...(ranged ? { from, to } : {}), ...(type && { type }), ...(account && { account_id: account }),
    ...(category && { category_id: category }), ...(tag && { tag_id: tag }), ...(debouncedQ && { q: debouncedQ }), ...(trash && { deleted: 'true' }),
  }
  const list = useInfiniteQuery({
    queryKey: [l.id, 'transactions', query],
    queryFn: ({ pageParam }) => api.transactions(l.id, { ...query, ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  })
  const rows: TxRow[] = useMemo(() => (list.data?.pages ?? []).flatMap((p) => p.transactions.map((r) => ({ ...r.data, id: r.id, hlc: r.hlc, deleted: r.deleted }))), [list.data])
  const accById = useMemo(() => new Map((accounts.data ?? []).map((a) => [a.id, a])), [accounts.data])
  const cur = (id: string | null) => (id && accById.get(id)?.currency) || l.base_currency
  const accName = (id: string | null) => (id && accById.get(id)?.name) || '?'
  // Selection only ever holds rows that are on screen.
  useEffect(() => { setSelected((s) => (s.size ? new Set([...s].filter((id) => rows.some((r) => r.id === id))) : s)) }, [rows])

  // Same rules as the reports: refunds reduce spending, an expense in an income category reduces income,
  // and money lent, borrowed or repaid is neither.
  const totals = useMemo(() => {
    let inc = 0, exp = 0
    for (const t of rows) {
      if (t.type === 'transfer' || t.debt_id || t.is_adjustment || t.excluded_from_reports || t.status === 'planned' || cur(t.account_id) !== l.base_currency) continue
      const kind = t.category_id ? cats.byId.get(t.category_id)?.kind : undefined
      if (t.type === 'expense') { if (kind === 'income') inc -= t.amount; else exp += t.amount }
      else if (kind === 'expense') exp -= t.amount
      else inc += t.amount
    }
    return { inc, exp }
  }, [rows, cats, accById, l.base_currency]) // eslint-disable-line

  const ops = (op: BatchOp['op'], ids: string[], data?: (id: string) => object): BatchOp[] =>
    ids.map((id) => ({ op, entity: 'transactions', id, ...(data ? { data: data(id) } : {}) }))
  const run = async (o: BatchOp[], done: string) => {
    try { await write.batch.mutateAsync(o); setSelected(new Set()); toast(done) } catch (x) { toast(errorText(x)) }
  }
  const del = async (ids: string[]) => {
    try {
      await write.batch.mutateAsync(ops('delete', ids))
      setSelected(new Set())
      toast(`${ids.length === 1 ? 'Transaction' : `${ids.length} transactions`} moved to Trash`, { label: 'Undo', run: () => write.batch.mutate(ops('restore', ids)) })
    } catch (x) { toast(errorText(x)) }
  }
  const recategorize = () => {
    const ids = [...selected].filter((id) => rows.find((r) => r.id === id)?.type !== 'transfer')
    const before = new Map(ids.map((id) => [id, rows.find((r) => r.id === id)?.category_id ?? null]))
    if (bulkCat) run(ops('update', ids, () => ({ category_id: bulkCat })), `${ids.length} moved to ${cats.name(bulkCat)}`).then(() => {
      setBulkCat('')
      toast(`${ids.length} moved to ${cats.name(bulkCat)}`, { label: 'Undo', run: () => write.batch.mutate(ops('update', ids, (id) => ({ category_id: before.get(id) }))) })
    })
  }
  const addTag = () => {
    if (!bulkTag) return
    const ids = [...selected]
    run(ops('update', ids, (id) => ({ tag_ids: [...new Set([...(rows.find((r) => r.id === id)?.tag_ids ?? []), bulkTag])] })), `Tagged ${ids.length} with #${tags.name(bulkTag)}`)
      .then(() => setBulkTag(''))
  }
  const categoryLabel = (t: TxRow) => {
    const parts = !t.category_id ? splits.get(t.id) : undefined
    return parts ? 'Split · ' + [...new Set(parts.map((s) => cats.name(s.category_id)))].join(', ') : cats.path(t.category_id)
  }
  const title = (t: TxRow) => (t.type === 'transfer' ? `${accName(t.account_id)} → ${accName(t.to_account_id)}` : t.payee || categoryLabel(t))
  const exportCsv = () => {
    const head = ['date', 'type', 'amount', 'currency', 'account', 'to_account', 'category', 'payee', 'note', 'tags', 'status']
    const lines = rows.map((t) => [t.local_date, t.type, toPlain(t.type === 'income' ? t.amount : -t.amount, cur(t.account_id)), cur(t.account_id),
      accName(t.account_id), t.to_account_id ? accName(t.to_account_id) : '', t.type === 'transfer' ? '' : categoryLabel(t),
      t.payee ?? '', t.note ?? '', t.tag_ids.map((id) => tags.name(id)).filter(Boolean).join(' '), t.status].map(csvEscape).join(','))
    download(`lekhamint-transactions-${ranged ? from.slice(0, 7) : isoDate()}.csv`, new Blob(['﻿' + [head.join(','), ...lines].join('\r\n')], { type: 'text/csv' }))
  }
  const clearFilters = () => { setType(''); setAccount(''); setCategory(''); setTag(''); setQ(''); go('transactions') }

  let lastDay = ''
  return (
    <>
      <div className="page-head">
        <h1>{trash ? 'Trash' : category === 'none' ? 'Needs a category' : 'Transactions'}</h1>
        <button onClick={exportCsv} disabled={rows.length === 0} title="Export what is shown as CSV"><Icon name="download" size={16} /> CSV</button>
        <button onClick={() => { setTrash(!trash); setSelected(new Set()) }}>{trash ? <><Icon name="left" size={16} /> Back</> : <><Icon name="trash" size={16} /> Trash</>}</button>
        {!trash && canWrite && <button className="primary" onClick={() => setEditing('new')}><Icon name="plus" size={16} /> Add</button>}
      </div>
      <div className="filters">
        <div className="search-field"><Icon name="search" size={16} /><input ref={searchRef} type="search" placeholder="Search payee or note" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search" /></div>
        {!debouncedQ && (
          <div className="row month-nav">
            <Segmented label="Range" value={allTime ? 'all' : 'month'} onChange={(v) => setAllTime(v === 'all')} options={[['month', 'Month'], ['all', 'All time']]} />
            {!allTime && <>
              <button className="icon-btn" onClick={() => setMonth(monthRange(month, -1)[0])} aria-label="Previous month"><Icon name="left" /></button>
              <strong className="month-label">{monthLabel(month)}</strong>
              <button className="icon-btn" onClick={() => setMonth(monthRange(month, 1)[0])} aria-label="Next month"><Icon name="right" /></button>
            </>}
          </div>
        )}
        <Select value={type} onChange={(e) => setType(e.target.value)} aria-label="Type"><option value="">All types</option><option value="expense">Expenses</option><option value="income">Income</option><option value="transfer">Transfers</option></Select>
        <Select value={account} onChange={(e) => setAccount(e.target.value)} aria-label="Account"><option value="">All accounts</option>{(accounts.data ?? []).filter((a) => !a.deleted).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select>
        <CategorySelect value={category} onChange={setCategory} cats={cats} all="All categories" uncategorised />
        {tags.all.length > 0 && (
          <Select value={tag} onChange={(e) => setTag(e.target.value)} aria-label="Tag"><option value="">All tags</option>{tags.all.map((t) => <option key={t.id} value={t.id}>#{t.name}</option>)}</Select>
        )}
        {(type || account || category || tag || q) && <button className="link" onClick={clearFilters}>Clear filters</button>}
      </div>
      {!trash && ranged && (
        <div className="row totals">
          <span className="chip">Income <Money value={totals.inc} currency={l.base_currency} /></span>
          <span className="chip">Spent <Money value={totals.exp} currency={l.base_currency} /></span>
          <span className="chip">Net <Money value={totals.inc - totals.exp} currency={l.base_currency} signed /></span>
        </div>
      )}
      {category === 'none' && !trash && rows.length > 0 && <p className="muted small">Tick rows and use “Move to category” to sort several at once. A rule under Auto-categorise does it for you next time.</p>}
      {selected.size > 0 && canWrite && (
        <div className="bulkbar row">
          <strong>{selected.size} selected</strong>
          {trash ? <button onClick={() => run(ops('restore', [...selected]), 'Restored')}>Restore</button> : <>
            <CategorySelect value={bulkCat} onChange={setBulkCat} cats={cats} all="Move to category…" />
            <button onClick={recategorize} disabled={!bulkCat || write.batch.isPending}>Apply</button>
            {tags.active.length > 0 && <>
              <Select value={bulkTag} onChange={(e) => setBulkTag(e.target.value)} aria-label="Add tag" style={{ width: 'auto' }}><option value="">Add tag…</option>{tags.active.map((t) => <option key={t.id} value={t.id}>#{t.name}</option>)}</Select>
              <button onClick={addTag} disabled={!bulkTag || write.batch.isPending}>Tag</button>
            </>}
            <button className="danger" onClick={() => del([...selected])} disabled={write.batch.isPending}><Icon name="trash" size={16} /> Delete</button>
          </>}
          <span className="spacer" />
          <button className="link" onClick={() => setSelected(new Set())}>Clear</button>
        </div>
      )}
      <Card className="flush">
        {list.isPending ? <div className="pad"><Skeleton rows={6} /></div> : list.isError ? <p className="error pad">{errorText(list.error)} <button className="link" onClick={() => list.refetch()}>Retry</button></p> : rows.length === 0 ? (
          <Empty icon={trash ? 'trash' : category === 'none' ? 'check' : 'inbox'}
            title={trash ? 'Trash is empty' : category === 'none' ? 'All sorted' : 'No transactions'}
            body={debouncedQ ? `Nothing matches “${debouncedQ}”.` : trash ? 'Deleted transactions appear here.' : category === 'none' ? 'Every transaction has a category.' : 'Nothing recorded in this period.'}
            action={!trash && canWrite && category !== 'none' && !debouncedQ ? <button className="primary" onClick={() => setEditing('new')}>Add a transaction</button> : undefined} />
        ) : (
          <div className="table-wrap">
            <table className="tx">
              <thead><tr>
                {canWrite && <th className="sel"><input type="checkbox" aria-label="Select all" checked={selected.size === rows.length} onChange={(e) => setSelected(e.target.checked ? new Set(rows.map((r) => r.id)) : new Set())} /></th>}
                <th>Description</th><th className="hide-sm">Category</th><th className="hide-sm">Account</th><th className="amt">Amount</th></tr></thead>
              <tbody>
                {rows.flatMap((t) => {
                  const out = []
                  if (t.local_date !== lastDay) { lastDay = t.local_date; out.push(<tr className="day" key={'d' + t.local_date}><td colSpan={canWrite ? 5 : 4}>{dayLabel(t.local_date)}</td></tr>) }
                  const transfer = t.type === 'transfer'
                  const isSplit = !transfer && !t.category_id && splits.has(t.id)
                  const tagText = t.tag_ids.map((id) => tags.name(id)).filter(Boolean).map((n) => `#${n}`).join(' ')
                  out.push(
                    <tr key={t.id} className={`clickable ${selected.has(t.id) ? 'selected' : ''} ${t.status === 'planned' ? 'muted-row' : ''}`} onClick={() => !trash && setEditing(t)}
                      tabIndex={trash ? undefined : 0} onKeyDown={(e) => { if (e.key === 'Enter' && !trash) setEditing(t) }}>
                      {canWrite && <td className="sel" onClick={(e) => e.stopPropagation()}><input type="checkbox" aria-label="Select" checked={selected.has(t.id)} onChange={(e) => { const s = new Set(selected); if (e.target.checked) s.add(t.id); else s.delete(t.id); setSelected(s) }} /></td>}
                      <td>
                        <div className="tx-title">
                          <span className="cat-slot">{transfer ? <CatIcon name="transfer" color="var(--transfer)" size={30} /> : isSplit ? <CatIcon name="split" color="#7C3AED" size={30} /> : <CatIcon name={cats.icon(t.category_id)} color={cats.color(t.category_id)} size={30} />}</span>
                          {transfer && <Icon name="transfer" size={14} className="muted hide-sm" />}{title(t)}
                        </div>
                        <div className="muted small tx-sub">
                          <span className="show-sm">{transfer ? 'Transfer' : categoryLabel(t)} · {accName(t.account_id)}{t.note || tagText ? ' · ' : ''}</span>
                          {t.note}{t.note && tagText ? ' · ' : ''}{tagText}
                        </div>
                      </td>
                      <td className="hide-sm">{transfer ? <span className="muted">Transfer</span> : <span className="row nowrap">{categoryLabel(t)}</span>}</td>
                      <td className="hide-sm">{accName(t.account_id)}{t.status !== 'cleared' && <span className="chip" style={{ marginLeft: 6 }}>{t.status}</span>}</td>
                      <td className="amt"><Money value={t.type === 'income' ? t.amount : -t.amount} currency={cur(t.account_id)} signed={!transfer} /></td>
                    </tr>,
                  )
                  return out
                })}
              </tbody>
            </table>
            {list.hasNextPage && <p className="center pad"><button onClick={() => list.fetchNextPage()} disabled={list.isFetchingNextPage}>{list.isFetchingNextPage ? 'Loading…' : 'Load more'}</button></p>}
          </div>
        )}
      </Card>
      {editing && <TxForm tx={editing === 'new' ? undefined : editing} presetAccount={account || undefined} onClose={() => setEditing(null)} onDelete={canWrite ? (id) => del([id]) : undefined} />}
    </>
  )
}

export function CategorySelect({ value, onChange, cats, all, kind, uncategorised }: {
  value: string; onChange: (v: string) => void; cats: ReturnType<typeof useCategoryIndex>; all?: string; kind?: string; uncategorised?: boolean
}) {
  const kinds = kind ? [kind] : ['expense', 'income']
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)} aria-label="Category">
      <option value="">{all ?? 'Uncategorised'}</option>
      {uncategorised && <option value="none">Needs a category</option>}
      {kinds.map((k) => (
        <optgroup key={k} label={k === 'expense' ? 'Expenses' : 'Income'}>
          {cats.roots(k).flatMap((r) => [<option key={r.id} value={r.id}>{r.name}</option>, ...cats.children(r.id).map((c) => <option key={c.id} value={c.id}>{'   '}{c.name}</option>)])}
        </optgroup>
      ))}
    </Select>
  )
}

/**
 * Add or edit a transaction. Amount accepts arithmetic like "450+800".
 * For new entries, rules suggest the category, payee name and tags.
 */
type TxFormProps = { tx?: TxRow; onClose: () => void; onDelete?: (id: string) => void; presetAccount?: string; draft?: Resolved; payCard?: { cardId: string; minor: number } }

export function TxForm(props: TxFormProps) {
  // The form starts from loaded data: an edit opened before splits arrive must not drop them on save.
  const accounts = useAccounts()
  const splits = useSplits()
  const [key, setKey] = useState(0)
  const [copy, setCopy] = useState<TxRow | undefined>()
  if (accounts.isPending || splits.isPending) return <Modal title={props.tx ? 'Edit transaction' : 'New transaction'} onClose={props.onClose}><Skeleton rows={5} height={36} /></Modal>
  if (!(accounts.data ?? []).some((a) => !a.deleted && !a.archived)) {
    return <Modal title="New transaction" onClose={props.onClose}><Empty icon="wallet" title="Add an account first" body="Transactions belong to an account: cash, a bank account, a card or a wallet." action={<a className="button primary" href="#/accounts" onClick={props.onClose}>Go to Accounts</a>} /></Modal>
  }
  // "Save and add another" remounts a fresh form; "Duplicate" remounts a new form copied from this one.
  return <TxFormInner key={key} {...props} tx={copy ? undefined : props.tx} copyOf={copy}
    onAnother={() => { setCopy(undefined); setKey((k) => k + 1) }} onDuplicate={(t) => { setCopy(t); setKey((k) => k + 1) }} />
}

function TxFormInner({ tx, onClose, onDelete, presetAccount, draft, payCard, copyOf, onAnother, onDuplicate }: TxFormProps & { copyOf?: TxRow; onAnother: () => void; onDuplicate: (t: TxRow) => void }) {
  const l = useLedger()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const tagIndex = useTagIndex()
  const splitIndex = useSplitIndex()
  const rules = useCategorizationRules()
  const write = useWrite()
  const canWrite = useCanWrite()
  const balances = useBalances()
  const newId = useStableId()
  // Warnings the user has seen for exactly this entry; saving again confirms them.
  const [warn, setWarn] = useState<{ key: string; lines: string[] } | null>(null)
  const src = tx ?? copyOf
  const live = useMemo(() => (accounts.data ?? []).filter((a) => !a.deleted && !a.archived), [accounts.data])
  const lastUsed = (() => { try { return localStorage.getItem(`kosh.lastAccount.${l.id}`) } catch { return null } })()
  const [type, setType] = useState<Transaction['type']>(src?.type ?? (payCard ? 'transfer' : draft?.draft.type) ?? 'expense')
  const cardCur = payCard ? (accounts.data ?? []).find((a) => a.id === payCard.cardId)?.currency : undefined
  const payer = payCard ? live.find((a) => a.id === lastUsed && a.id !== payCard.cardId && !isLiability(a.type) && a.currency === cardCur) ?? live.find((a) => a.id !== payCard.cardId && !isLiability(a.type) && a.currency === cardCur) : undefined
  const [accountId, setAccountId] = useState(src?.account_id ?? payer?.id ?? draft?.accountId ?? presetAccount ?? (live.find((a) => a.id === lastUsed) ?? live.find((a) => ['cash', 'bank', 'wallet', 'credit_card', 'savings'].includes(a.type)) ?? live[0])?.id ?? '')
  const acc = (accounts.data ?? []).find((a) => a.id === accountId)
  const code = acc?.currency ?? l.base_currency
  const [amount, setAmount] = useState(src ? toPlain(src.amount, code) : payCard ? toPlain(payCard.minor, code) : draft?.minor ? toPlain(draft.minor, code) : '')
  const [toAccountId, setToAccountId] = useState(src?.to_account_id ?? payCard?.cardId ?? '')
  useEffect(() => { if (type === 'transfer' && (!toAccountId || toAccountId === accountId)) setToAccountId(live.find((a) => a.id !== accountId)?.id ?? '') }, [type, accountId, toAccountId, live])
  const toAcc = (accounts.data ?? []).find((a) => a.id === toAccountId)
  const [toAmount, setToAmount] = useState(src?.to_amount ? toPlain(src.to_amount, toAcc?.currency ?? code) : '')
  const [categoryId, setCategoryId] = useState(src?.category_id ?? draft?.categoryId ?? '')
  const [catTouched, setCatTouched] = useState(!!src || !!draft?.categoryId)
  // Time of day, "HH:MM". Empty means "now" for a new entry; an edit keeps the time it was saved with.
  const [time, setTime] = useState(() => {
    if (!tx) return ''
    const d = new Date(tx.occurred_at)
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  })
  const [date, setDate] = useState(copyOf ? isoDate() : src?.local_date ?? draft?.draft.date ?? isoDate())
  const [payee, setPayee] = useState(src?.payee ?? draft?.payee ?? '')
  const [note, setNote] = useState(src?.note ?? '')
  const [status, setStatus] = useState(src?.status ?? 'cleared')
  const [excluded, setExcluded] = useState(src?.excluded_from_reports ?? false)
  const [tagIds, setTagIds] = useState<string[]>(src?.tag_ids ?? draft?.tagIds ?? [])
  const existingSplits = tx ? splitIndex.get(tx.id) ?? [] : []
  const copiedSplits = copyOf ? splitIndex.get(copyOf.id) ?? [] : []
  const [split, setSplit] = useState<SplitDraft[] | null>(() => {
    const from = existingSplits.length ? existingSplits : copiedSplits
    return from.length ? from.map((s) => ({ id: tx ? s.id : uuidv7(), category_id: s.category_id, expr: toPlain(s.amount, code) })) : null
  })
  const [err, setErr] = useState('')
  const [hint, setHint] = useState<RuleOutcome | null>(null)
  const readOnly = !canWrite

  const value = evaluate(amount)
  const minor = value === null ? null : parseMoney(String(value), code)
  const cross = type === 'transfer' && toAcc && toAcc.currency !== code

  // Rules run as you type the payee (new entries only, so edits are never overridden).
  useEffect(() => {
    if (tx || type === 'transfer') { setHint(null); return }
    const h = setTimeout(() => {
      const out = applyRules(rules.data ?? [], { payee, note, type, accountId, amount: minor ?? 0 })
      setHint(out.matched.length ? out : null)
      if (!catTouched && !split && out.categoryId && cats.byId.has(out.categoryId)) setCategoryId(out.categoryId)
      if (out.tagIds.length) setTagIds((cur) => [...new Set([...cur, ...out.tagIds.filter((t) => tagIndex.byId.has(t))])])
    }, 250)
    return () => clearTimeout(h)
  }, [payee, note, type, accountId, minor, rules.data]) // eslint-disable-line

  /** Things worth a second look before saving: overdrawing an account, passing a card limit, a likely duplicate. */
  const checks = async (): Promise<string[]> => {
    const out: string[] = []
    if (!minor || status === 'planned' || !acc) return out
    if (type !== 'income') {
      const bal = balances.data?.find((b) => b.account_id === accountId)?.balance
      if (bal !== undefined) {
        // Editing: the old amount is already in the balance.
        const back = tx && tx.account_id === accountId && tx.status !== 'planned' && tx.type !== 'income' ? tx.amount : 0
        const after = bal + back - minor
        if (isLiability(acc.type)) {
          if (acc.credit_limit && -after > acc.credit_limit) out.push(`This takes ${acc.name} past its credit limit of ${formatMoney(acc.credit_limit, code)}: ${formatMoney(-after, code)} would be owed.`)
        } else if (after < 0 && bal + back >= 0) {
          out.push(`${acc.name} has ${formatMoney(bal + back, code)}, so this would take it below zero, to ${formatMoney(after, code)}. Check the amount or the account.`)
        }
      }
    }
    if (type === 'transfer' && toAcc && isLiability(toAcc.type)) {
      const owedNow = -(balances.data?.find((b) => b.account_id === toAccountId)?.balance ?? 0)
      const received = cross ? parseMoney(toAmount, toAcc.currency) ?? 0 : minor
      // Editing: the old payment is already counted in the balance.
      const back = tx && tx.to_account_id === toAccountId && tx.status !== 'planned' ? (tx.to_amount ?? tx.amount) : 0
      const extra = received - (owedNow + back)
      if (owedNow + back >= 0 && extra > 0) out.push(`${toAcc.name} shows ${formatMoney(Math.max(0, owedNow + back), toAcc.currency)} owed, so this pays ${formatMoney(extra, toAcc.currency)} more than that. The card will be in credit by that amount (fine for an advance payment or a refund due).`)
    }
    if (!tx && type !== 'transfer') {
      try {
        const same = await api.transactions(l.id, { from: date, to: date, account_id: accountId, type, min_amount: String(minor), max_amount: String(minor), limit: '5' })
        const hit = same.transactions.find((r) => r.data.amount === minor && !r.deleted)
        if (hit) {
          const t = new Date(hit.data.occurred_at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
          out.push(`${formatMoney(minor, code)}${hit.data.payee ? ` to ${hit.data.payee}` : ''} is already recorded on ${acc.name} for ${dayLabel(date).toLowerCase()} (added ${t}). If this is a second payment, save anyway.`)
        }
      } catch { /* offline: skip the check, the save itself will report the problem */ }
    }
    return out
  }

  const save = async (another: boolean) => {
    setErr('')
    if (write.batch.isPending) return
    if (!minor || minor <= 0) return setErr(amount.trim() && value === null ? 'That amount is not a number or sum we understand. Use digits like 450 or 450+80.' : 'Enter an amount more than zero')
    if (!accountId) return setErr('Choose an account')
    if (type === 'transfer' && (!toAccountId || toAccountId === accountId)) return setErr('Choose a different destination account')
    const toMinor = type === 'transfer' ? (cross ? parseMoney(toAmount, toAcc!.currency) : minor) : null
    if (type === 'transfer' && !toMinor) return setErr(`Enter the amount received in ${toAcc?.currency}`)
    if (type === 'transfer' && cross && toMinor && toMinor <= 0) return setErr('The amount received must be more than zero')
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return setErr('Pick a date')
    const useSplit = split && type !== 'transfer'
    if (useSplit) {
      if (split.length < 2) return setErr('A split needs at least two lines')
      if (split.some((s) => !s.category_id)) return setErr('Choose a category for every line')
      if (split.some((s) => !splitMinor(s, code) || splitMinor(s, code)! <= 0)) return setErr('Enter an amount for every line')
      const left = minor - split.reduce((a, s) => a + (splitMinor(s, code) ?? 0), 0)
      if (left !== 0) return setErr(left > 0 ? `${formatMoney(left, code)} still to assign` : `Lines add up to ${formatMoney(-left, code)} more than the total`)
    }
    // Warn once; the same entry saved again goes through.
    const warnKey = [type, accountId, toAccountId, minor, date, status].join('|')
    if (warn?.key !== warnKey) {
      const lines = await checks()
      if (lines.length) { setWarn({ key: warnKey, lines }); return }
    }
    const occurred = (() => {
      const d = new Date(date + 'T12:00:00')
      const m = /^(\d{1,2}):(\d{2})$/.exec(time)
      if (m) d.setHours(Math.min(23, +m[1]), Math.min(59, +m[2]), 0, 0)
      else { const n = new Date(); d.setHours(n.getHours(), n.getMinutes(), 0, 0) }
      return d.getTime()
    })()
    const finalPayee = (!tx && hint?.payee) || payee.trim()
    const data: Partial<Transaction> = {
      type, account_id: accountId, amount: minor, to_account_id: type === 'transfer' ? toAccountId : null, to_amount: toMinor,
      category_id: type === 'transfer' || useSplit ? null : categoryId || null, payee: finalPayee || null, note: note.trim() || null,
      local_date: date, occurred_at: occurred, tz_offset_min: -new Date(occurred).getTimezoneOffset(), status, excluded_from_reports: excluded,
      tag_ids: tagIds.filter((t) => tagIndex.byId.has(t)),
    }
    try {
      const id = tx?.id ?? newId
      // create_if_missing with an ID kept for the life of the form: pressing Save twice or retrying records it once.
      const batch: BatchOp[] = [tx ? { op: 'update', entity: 'transactions', id, data } : { op: 'create_if_missing', entity: 'transactions', id, data: { ...data, created_at: Date.now() } }]
      const keep = new Set((useSplit ? split : []).map((s) => s.id))
      for (const s of existingSplits) if (!keep.has(s.id)) batch.push({ op: 'delete', entity: 'transaction_splits', id: s.id })
      if (useSplit) split.forEach((s, i) => {
        const d = { transaction_id: id, category_id: s.category_id, amount: splitMinor(s, code)!, sort_order: i }
        batch.push(existingSplits.some((x) => x.id === s.id) ? { op: 'update', entity: 'transaction_splits', id: s.id, data: d } : { op: 'create_if_missing', entity: 'transaction_splits', id: s.id, data: d })
      })
      await write.batch.mutateAsync(batch)
      rememberAccount(l.id, accountId)
      if (tx) toast('Saved')
      else toast(`Added ${formatMoney(minor, code)}`, { label: 'Undo', run: () => write.batch.mutate([{ op: 'delete', entity: 'transactions', id }]) })
      if (another) onAnother(); else onClose()
    } catch (x) { setErr(saveErrorText(x)) }
  }

  const startSplit = () => setSplit([
    { id: uuidv7(), category_id: categoryId, expr: minor ? toPlain(minor, code) : '' },
    { id: uuidv7(), category_id: '', expr: '' },
  ])

  return (
    <Modal title={readOnly ? 'Transaction' : tx ? 'Edit transaction' : copyOf ? 'Copy of transaction' : 'New transaction'} onClose={onClose} footer={readOnly ? <button type="button" onClick={onClose}>Close</button> : <>
      {tx && onDelete && <button type="button" className="danger" onClick={() => { onDelete(tx.id); onClose() }}><Icon name="trash" size={16} /> Delete</button>}
      {tx && <button type="button" onClick={() => onDuplicate(tx)} title="Start a new transaction from this one"><Icon name="copy" size={16} /> Duplicate</button>}
      <span className="spacer" />
      {!tx && <button type="button" onClick={() => save(true)} disabled={write.batch.isPending} title="Save and start another">Save + new</button>}
      <button className="primary" form="txform" disabled={write.batch.isPending}>{write.batch.isPending ? 'Saving…' : warn ? 'Save anyway' : 'Save'}</button>
    </>}>
      <form id="txform" onSubmit={(e) => { e.preventDefault(); save(false) }}>
        <fieldset disabled={readOnly} className="plain">
          <Segmented label="Type" value={type} onChange={(t) => { if (t !== type) { setCategoryId(''); setCatTouched(false) } setType(t) }}
            options={[['expense', 'Expense'], ['income', 'Income'], ['transfer', 'Transfer']]} />
          <Field label={`Amount (${currency(code).symbol.trim()})`} hint={value !== null && /[+\-*/×÷−]/.test(amount.slice(1)) && minor ? `= ${formatMoney(minor, code)}` : 'You can type sums like 450+800'}>
            <NumInput className={`amount-input ${type}`} kind="expr" value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus={!readOnly} placeholder="0" aria-label="Amount" />
          </Field>
          <div className="two">
            <Field label={type === 'transfer' ? 'From account' : 'Account'}>
              <Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
                {acc && !live.includes(acc) && <option value={acc.id}>{acc.name} (archived)</option>}
                {live.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
              </Select>
            </Field>
            {type === 'transfer' ? (
              <Field label="To account"><Select value={toAccountId} onChange={(e) => setToAccountId(e.target.value)}>{live.filter((a) => a.id !== accountId).map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}</Select></Field>
            ) : split ? (
              <Field label="Category"><button type="button" onClick={() => setSplit(null)}>Don't split</button></Field>
            ) : (
              <Field label="Category" hint={type === 'income' && cats.byId.get(categoryId)?.kind === 'expense' ? 'Counts as a refund of that spending' : undefined}>
                <div className="row nowrap">
                  <CategorySelect value={categoryId} onChange={(v) => { setCategoryId(v); setCatTouched(true) }} cats={cats} kind={type === 'expense' ? 'expense' : undefined} />
                  <button type="button" onClick={startSplit} title="Split across categories">Split</button>
                </div>
              </Field>
            )}
          </div>
          {split && type !== 'transfer' && <SplitEditor lines={split} onChange={setSplit} total={minor} code={code} kind={type === 'expense' ? 'expense' : undefined} />}
          {cross && <Field label={`Amount received (${toAcc!.currency})`}><NumInput kind="decimal" value={toAmount} onChange={(e) => setToAmount(e.target.value)} /></Field>}
          <div className="two">
            <Field label="Date"><DateField value={date} onChange={(e) => setDate(e.target.value)} required /></Field>
            <Field label="Time (optional)" hint={time ? undefined : 'Defaults to now'}>
              <input type="time" value={time} onChange={(e) => setTime(e.target.value)} />
            </Field>
          </div>
          {type !== 'transfer' && (
            <Field label={type === 'income' ? 'From (payer)' : 'Paid to (payee)'}
              hint={hint ? `Rule “${hint.matched[0].name ?? hint.matched[0].pattern}”: ${[hint.categoryId && cats.path(hint.categoryId), hint.payee && `saved as “${hint.payee}”`].filter(Boolean).join(', ') || 'tags added'}` : undefined}>
              <input value={payee} onChange={(e) => setPayee(e.target.value)} />
            </Field>
          )}
          {!readOnly && (
            <div className="row date-chips">
              <button type="button" className={`chip-btn ${date === isoDate() ? 'on' : ''}`} onClick={() => setDate(isoDate())}>Today</button>
              <button type="button" className={`chip-btn ${date === addDays(isoDate(), -1) ? 'on' : ''}`} onClick={() => setDate(addDays(isoDate(), -1))}>Yesterday</button>
              <button type="button" className={`chip-btn ${date === addDays(isoDate(), -2) ? 'on' : ''}`} onClick={() => setDate(addDays(isoDate(), -2))}>{dayLabel(addDays(isoDate(), -2))}</button>
            </div>
          )}
          <Field label="Note"><textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          <Field label="Tags"><TagInput value={tagIds} onChange={setTagIds} /></Field>
          <div className="two">
            <Field label="Status" hint={status === 'planned' ? "Planned items don't affect balances yet" : status === 'pending' ? 'Counts now; mark cleared when it shows on your statement' : undefined}>
              <Select value={status} onChange={(e) => setStatus(e.target.value)}><option value="cleared">Cleared</option><option value="pending">Pending</option><option value="planned">Planned</option></Select>
            </Field>
            {type !== 'transfer' && <div className="field-pad"><Check checked={excluded} onChange={setExcluded}>Exclude from reports</Check></div>}
          </div>
          {tx?.recurring_rule_id && <p className="muted small"><Icon name="repeat" size={14} /> Recorded from a recurring rule.</p>}
          {warn && warn.key === [type, accountId, toAccountId, minor, date, status].join('|') && (
            <div className="callout warn" role="alert">
              <Icon name="alert" size={16} />
              <div>{warn.lines.map((t) => <p key={t}>{t}</p>)}<p className="small muted">Press Save again to keep it as it is.</p></div>
            </div>
          )}
          {tx?.loan_id && <p className="muted small"><Icon name="bank" size={14} /> Part of a loan repayment ({tx.part ?? 'payment'}). Edit or delete it from Loans to keep the schedule right.</p>}
          {tx?.deposit_id && <p className="muted small"><Icon name="piggy" size={14} /> Linked to a deposit ({tx.part ?? 'movement'}).</p>}
          {tx?.debt_id && <p className="muted small"><Icon name="hand" size={14} /> Linked to money lent or borrowed. It moves the balance but is not counted as spending or income{tx.part === 'interest' ? ', except this interest' : ''}.</p>}
          {err && <p className="error" role="alert">{err}</p>}
        </fieldset>
      </form>
    </Modal>
  )
}
