// Natural-language entry ("450 swiggy dinner yesterday #work @hdfc") and the
// command palette (Ctrl/⌘ K). Both resolve the same draft against the ledger.

import { useEffect, useMemo, useRef, useState } from 'react'
import { api, type BatchOp, type Transaction } from '../lib/api'
import { useAccounts, useCanWrite, useCategorizationRules, useCategoryIndex, useLedger, useTagIndex, useWrite } from '../lib/data'
import { applyRules } from '../lib/rules'
import { bestMatch, categoryFromText, parseQuick, withoutWords, type QuickDraft } from '../lib/quickadd'
import { formatMoney, parseMoney } from '../lib/money'
import { dayLabel, tagColor, uuidv7 } from '../lib/util'
import { Kbd, errorText, toast } from '../components'
import { Icon } from '../icons'
import { getSetting, setSetting } from '../lib/settings'

/** The account used last in this book, so the next entry starts there (on every device). */
export function rememberAccount(lid: string, id: string) { if (getSetting(`last_account.${lid}`, '') !== id) setSetting(`last_account.${lid}`, id) }
export function lastAccount(lid: string): string | null { return getSetting<string | null>(`last_account.${lid}`, null) }

export interface Resolved {
  draft: QuickDraft
  accountId: string
  currency: string
  minor: number | null
  categoryId: string | null
  payee: string | null
  tagIds: string[]
  newTags: string[]
  /** One line describing what will be saved. */
  summary: string
}

/** Resolves quick-add text against accounts, categories, rules and tags. */
export function useQuickAdd() {
  const l = useLedger()
  const accounts = useAccounts()
  const cats = useCategoryIndex()
  const tags = useTagIndex()
  const rules = useCategorizationRules()
  const write = useWrite()
  const live = useMemo(() => (accounts.data ?? []).filter((a) => !a.deleted && !a.archived), [accounts.data])

  const resolve = (text: string): Resolved | null => {
    const draft = parseQuick(text)
    if (draft.amount === null) return null
    const acc = bestMatch(live, draft.account)
      ?? live.find((a) => a.id === lastAccount(l.id))
      ?? live.find((a) => ['cash', 'bank', 'wallet'].includes(a.type)) ?? live[0]
    if (!acc) return null
    const minor = parseMoney(String(draft.amount), acc.currency)
    const ruled = applyRules(rules.data ?? [], { payee: draft.text, note: null, type: draft.type, accountId: acc.id, amount: minor ?? 0 })
    const pool = cats.all.filter((c) => !c.archived && (draft.type === 'income' || c.kind === 'expense'))
    const fromText = categoryFromText(pool.filter((c) => c.kind === draft.type), draft.text) ?? categoryFromText(pool, draft.text)
    const categoryId = (ruled.categoryId && cats.byId.has(ruled.categoryId) ? ruled.categoryId : null) ?? fromText?.id ?? null
    const existing = draft.tags.map((n) => tags.all.find((t) => t.name.toLowerCase() === n.toLowerCase()))
    const newTags = draft.tags.filter((_, i) => !existing[i])
    const tagIds = [...new Set([...existing.filter(Boolean).map((t) => t!.id), ...ruled.tagIds.filter((t) => tags.byId.has(t))])]
    // "groceries big bazaar" → category Groceries, payee "Big bazaar".
    const rest = fromText && categoryId === fromText.id ? withoutWords(draft.text, fromText.name) : draft.text
    const payee = ruled.payee ?? (rest ? rest.replace(/^\p{L}/u, (c) => c.toUpperCase()) : null)
    const bits = [
      minor ? formatMoney(minor, acc.currency) : '',
      payee ?? '',
      categoryId ? cats.path(categoryId) : '',
      dayLabel(draft.date),
      acc.name,
      ...draft.tags.map((t) => `#${t}`),
    ].filter(Boolean)
    return { draft, accountId: acc.id, currency: acc.currency, minor, categoryId, payee, tagIds, newTags, summary: `${draft.type === 'income' ? 'Income' : 'Expense'} · ${bits.join(' · ')}` }
  }

  /** Saves the draft in one batch (new tags included); returns the transaction ID. */
  const save = async (r: Resolved): Promise<string> => {
    if (!r.minor || r.minor <= 0) throw new Error('Enter an amount')
    let categoryId = r.categoryId
    // Fall back to the category last used for this payee.
    if (!categoryId && r.payee) {
      try {
        const past = await api.transactions(l.id, { q: r.payee, type: r.draft.type, limit: '20' })
        categoryId = past.transactions.find((t) => t.data.category_id && t.data.payee?.toLowerCase() === r.payee!.toLowerCase())?.data.category_id ?? null
      } catch { /* offline: leave uncategorised */ }
    }
    const ops: BatchOp[] = []
    const tagIds = [...r.tagIds]
    for (const name of r.newTags) {
      const id = uuidv7()
      ops.push({ op: 'create', entity: 'tags', id, data: { name, color: tagColor(name), created_at: Date.now() } })
      tagIds.push(id)
    }
    const now = new Date()
    const at = new Date(r.draft.date + 'T12:00:00')
    at.setHours(now.getHours(), now.getMinutes())
    const id = uuidv7()
    const data: Partial<Transaction> & { created_at: number } = {
      type: r.draft.type, account_id: r.accountId, amount: r.minor, category_id: categoryId, payee: r.payee,
      local_date: r.draft.date, occurred_at: at.getTime(), tz_offset_min: -at.getTimezoneOffset(), tag_ids: tagIds, status: 'cleared', created_at: Date.now(),
    }
    ops.push({ op: 'create', entity: 'transactions', id, data })
    await write.batch.mutateAsync(ops)
    rememberAccount(l.id, r.accountId)
    toast(`Added ${formatMoney(r.minor, r.currency)}${r.payee ? ` · ${r.payee}` : ''}`, { label: 'Undo', run: () => write.batch.mutate([{ op: 'delete', entity: 'transactions', id }]) })
    return id
  }
  return { resolve, save, ready: !accounts.isPending, hasAccounts: live.length > 0 }
}

/** The dashboard's one-line entry. */
export function QuickAddBar({ onOpenForm }: { onOpenForm: (r: Resolved | null) => void }) {
  const qa = useQuickAdd()
  const can = useCanWrite()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const r = useMemo(() => (text.trim() ? qa.resolve(text) : null), [text, qa])
  if (!can) return null
  const submit = async () => {
    if (!r) { onOpenForm(null); return }
    setBusy(true); setErr('')
    try { await qa.save(r); setText('') } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
  }
  return (
    <div className="quick">
      <form className="quick-bar" onSubmit={(e) => { e.preventDefault(); submit() }}>
        <Icon name="sparkles" className="quick-spark" />
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Try “450 swiggy dinner yesterday #work”" aria-label="Quick add" disabled={!qa.hasAccounts && qa.ready} />
        <button type="button" className="icon-btn" title="Open the full form" aria-label="Open the full form" onClick={() => onOpenForm(r)}><Icon name="edit" /></button>
        <button className="primary" disabled={busy || (!!text.trim() && !r?.minor)}>{text.trim() ? 'Add' : 'New'}</button>
      </form>
      <div className="quick-hint" aria-live="polite">
        {err ? <span className="error">{err}</span>
          : !qa.hasAccounts && qa.ready ? 'Add an account first (Accounts → + Account).'
          : r ? <><Icon name="check" size={14} /> {r.summary}{!r.categoryId && ' · category from payee history'}</>
          : text.trim() ? 'Start with an amount, e.g. 120 coffee' : <>Amount, what for, and optionally a date, <code>#tag</code> or <code>@account</code>. Press <Kbd>Enter</Kbd>.</>}
      </div>
    </div>
  )
}

export interface Command { id: string; label: string; hint?: string; icon: string; run: () => void; keywords?: string }

/** Ctrl/⌘ K: jump anywhere, run actions, or type an expense to add it. */
export function CommandPalette({ commands, onClose, onAddForm, onSearch }: {
  commands: Command[]; onClose: () => void; onAddForm: (r: Resolved | null) => void; onSearch: (q: string) => void
}) {
  const qa = useQuickAdd()
  const can = useCanWrite()
  const [q, setQ] = useState('')
  const [sel, setSel] = useState(0)
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { ref.current?.showModal() }, [])
  const r = useMemo(() => (can && q.trim() ? qa.resolve(q) : null), [q, qa, can])
  const items: Command[] = useMemo(() => {
    const t = q.trim().toLowerCase()
    const out: Command[] = []
    if (r?.minor) {
      out.push({ id: 'qa', label: `Add ${r.summary}`, hint: 'Enter', icon: 'sparkles', run: async () => { try { await qa.save(r); onClose() } catch (x) { toast(errorText(x)) } } })
      out.push({ id: 'qa-edit', label: 'Review in the full form', hint: 'Shift+Enter', icon: 'edit', run: () => { onClose(); onAddForm(r) } })
    }
    out.push(...commands.filter((c) => !t || (c.label + ' ' + (c.keywords ?? '')).toLowerCase().includes(t)))
    if (t && !r?.minor) out.push({ id: 'search', label: `Search transactions for “${q.trim()}”`, icon: 'search', run: () => { onClose(); onSearch(q.trim()) } })
    return out
  }, [q, r, commands]) // eslint-disable-line
  useEffect(() => setSel(0), [q])
  return (
    <dialog ref={ref} className="palette" onClose={onClose} onCancel={(e) => { e.preventDefault(); onClose() }} aria-label="Command palette"
      onMouseDown={(e) => { if (e.target === ref.current) onClose() }}>
      <div className="palette-input">
        <Icon name="search" />
        <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={can ? 'Type a command, a page, or “250 lunch #work”…' : 'Type a command or a page…'}
          aria-label="Command" aria-controls="palette-list" aria-activedescendant={items[sel] ? `cmd-${items[sel].id}` : undefined}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setSel((s) => Math.min(items.length - 1, s + 1)) }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((s) => Math.max(0, s - 1)) }
            else if (e.key === 'Enter') {
              e.preventDefault()
              if (e.shiftKey && r?.minor) { onClose(); onAddForm(r); return }
              items[sel]?.run()
            }
          }} />
        <Kbd>Esc</Kbd>
      </div>
      <ul className="palette-list" id="palette-list" role="listbox">
        {items.map((c, i) => (
          <li key={c.id} id={`cmd-${c.id}`} role="option" aria-selected={i === sel} className={i === sel ? 'on' : ''} onMouseEnter={() => setSel(i)} onClick={() => c.run()}>
            <Icon name={c.icon} /><span className="grow">{c.label}</span>{c.hint && <span className="muted small">{c.hint}</span>}
          </li>
        ))}
        {items.length === 0 && <li className="muted">No matches</li>}
      </ul>
    </dialog>
  )
}
