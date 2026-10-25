// Form parts shared by several pages: tag input and split editor.

import { useState } from 'react'
import { useCategoryIndex, useTagIndex, useWrite } from '../lib/data'
import { evaluate } from '../lib/finance'
import { formatMoney, parseMoney, toPlain } from '../lib/money'
import { tagColor, uuidv7 } from '../lib/util'
import { errorText, toast } from '../components'
import { CategorySelect } from './Transactions'
import { NumInput } from '../controls'

/** Tags as removable chips plus a search box; Enter on a new name creates the tag. */
export function TagInput({ value, onChange }: { value: string[]; onChange: (ids: string[]) => void }) {
  const tags = useTagIndex()
  const write = useWrite()
  const [q, setQ] = useState('')
  const clean = q.trim().replace(/^#/, '').trim()
  const matches = tags.active.filter((t) => !value.includes(t.id) && (!clean || t.name.toLowerCase().includes(clean.toLowerCase()))).slice(0, 8)
  const exact = tags.all.find((t) => t.name.toLowerCase() === clean.toLowerCase())
  const create = async () => {
    if (!clean) return
    if (exact) { if (!value.includes(exact.id)) onChange([...value, exact.id]); setQ(''); return }
    try {
      const rec = await write.save.mutateAsync({ entity: 'tags', data: { name: clean, color: tagColor(clean) } })
      onChange([...value, rec.id]); setQ('')
    } catch (x) { toast(errorText(x)) }
  }
  return (
    <div className="tag-input">
      <div className="row" style={{ gap: 6 }}>
        {value.map((id) => (
          <span key={id} className="chip tag">#{tags.name(id) ?? 'deleted'}
            <button type="button" className="chip-x" aria-label={`Remove tag ${tags.name(id) ?? ''}`} onClick={() => onChange(value.filter((v) => v !== id))}>×</button>
          </span>
        ))}
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={value.length ? 'Add another' : 'Add a tag, e.g. goa-trip'}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); create() } }} style={{ flex: 1, minWidth: 140 }} aria-label="Add tag" />
      </div>
      {clean && (
        <div className="row" style={{ gap: 6, marginTop: 6 }}>
          {matches.map((t) => <button type="button" key={t.id} className="chip-btn" onClick={() => { onChange([...value, t.id]); setQ('') }}>#{t.name}</button>)}
          {!exact && <button type="button" className="chip-btn" onClick={create}>Create #{clean}</button>}
        </div>
      )}
    </div>
  )
}

export interface SplitDraft { id: string; category_id: string; expr: string }

export function splitMinor(s: SplitDraft, code: string): number | null {
  const v = evaluate(s.expr)
  return v === null ? null : parseMoney(String(v), code)
}

/** Lines of a split transaction; shows what is left to assign. */
export function SplitEditor({ lines, onChange, total, code, kind }: { lines: SplitDraft[]; onChange: (l: SplitDraft[]) => void; total: number | null; code: string; kind?: string }) {
  const cats = useCategoryIndex()
  const assigned = lines.reduce((a, l) => a + (splitMinor(l, code) ?? 0), 0)
  const left = total === null ? null : total - assigned
  const set = (i: number, patch: Partial<SplitDraft>) => onChange(lines.map((l, k) => (k === i ? { ...l, ...patch } : l)))
  return (
    <div className="split-editor">
      {lines.map((l, i) => (
        <div key={l.id} className="split-line">
          <CategorySelect value={l.category_id} onChange={(v) => set(i, { category_id: v })} cats={cats} kind={kind} all="Choose category" />
          <NumInput kind="expr" value={l.expr} onChange={(e) => set(i, { expr: e.target.value })} placeholder="0" aria-label={`Amount for line ${i + 1}`} />
          <button type="button" className="icon-btn" aria-label="Remove line" disabled={lines.length <= 2} onClick={() => onChange(lines.filter((_, k) => k !== i))}>✕</button>
        </div>
      ))}
      <div className="row">
        <button type="button" className="link" onClick={() => onChange([...lines, { id: uuidv7(), category_id: '', expr: left !== null && left > 0 ? toPlain(left, code) : '' }])}>+ Add line</button>
        <span className="spacer" />
        {left === null ? <span className="muted small">Enter the total first</span>
          : left === 0 ? <span className="small" style={{ color: 'var(--accent)' }}>All assigned</span>
          : <span className="small" style={{ color: 'var(--danger)' }}>{left > 0 ? `${formatMoney(left, code)} left` : `${formatMoney(-left, code)} over`}</span>}
      </div>
    </div>
  )
}
