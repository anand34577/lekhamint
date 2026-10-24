import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type Tag } from '../lib/api'
import { useLedger, useTagIndex, useStableId, useWrite, type WithId } from '../lib/data'
import { isoDate } from '../lib/util'
import { Card, Dot, Empty, Field, Modal, Money, errorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'

const PALETTE = ['#0F766E', '#2563EB', '#7C3AED', '#DB2777', '#EA580C', '#CA8A04', '#16A34A', '#0891B2', '#475569']

/** Tags group spending across categories: a trip, a wedding, a client project. */
export function Tags({ go }: PageProps) {
  const l = useLedger()
  const tags = useTagIndex()
  const [edit, setEdit] = useState<WithId<Tag> | 'new' | null>(null)
  const year = isoDate().slice(0, 4)
  const [from, to] = [`${Number(year) - 1}-01-01`, `${year}-12-31`]
  const totals = useQuery({ queryKey: [l.id, 'tag-totals', from, to], queryFn: () => api.tagTotals(l.id, from, to) })
  const byTag = useMemo(() => {
    const m = new Map<string, { expense: number; count: number; currency: string }[]>()
    for (const t of totals.data ?? []) m.set(t.tag_id, [...(m.get(t.tag_id) ?? []), { expense: t.expense, count: t.count, currency: t.currency }])
    return m
  }, [totals.data])

  return (
    <>
      <div className="page-head"><h1>Tags</h1><button className="primary" onClick={() => setEdit('new')}>+ Tag</button></div>
      {tags.all.length === 0 ? (
        <Empty title="No tags yet" body="Tags answer “what was it for?” across categories, like #goa-trip or #wedding. Add them on any transaction or here."
          action={<button className="primary" onClick={() => setEdit('new')}>Create a tag</button>} />
      ) : (
        <Card>
          <p className="muted small" style={{ marginTop: 0 }}>Spending since 1 January {Number(year) - 1}. A transaction with two tags counts under both.</p>
          <ul className="list">
            {tags.all.map((t) => (
              <li key={t.id} className="clickable" onClick={() => setEdit(t)}>
                <Dot color={t.color ?? '#94A3B8'} />
                <div className="grow"><div>#{t.name}{t.archived && <span className="chip" style={{ marginLeft: 8 }}>hidden</span>}</div>
                  <div className="sub">{(byTag.get(t.id) ?? []).reduce((a, x) => a + x.count, 0)} transactions</div></div>
                <div style={{ textAlign: 'right' }}>{(byTag.get(t.id) ?? []).map((x) => <div key={x.currency}><Money value={x.expense} currency={x.currency} /></div>)}</div>
                <button className="icon-btn" aria-label={`Transactions tagged ${t.name}`} title="See transactions" onClick={(e) => { e.stopPropagation(); go('transactions', { tag: t.id, all: '1' }) }}><Icon name="right" /></button>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {edit && <TagForm tag={edit === 'new' ? undefined : edit} existing={tags.all} onClose={() => setEdit(null)} />}
    </>
  )
}

function TagForm({ tag, existing, onClose }: { tag?: WithId<Tag>; existing: WithId<Tag>[]; onClose: () => void }) {
  const write = useWrite()
  const newId = useStableId()
  const [name, setName] = useState(tag?.name ?? '')
  const [color, setColor] = useState(tag?.color ?? PALETTE[Math.floor(Math.random() * PALETTE.length)])
  const [archived, setArchived] = useState(tag?.archived ?? false)
  const [err, setErr] = useState('')
  const clean = name.trim().replace(/^#/, '').trim()
  const clash = existing.some((t) => t.id !== tag?.id && t.name.toLowerCase() === clean.toLowerCase())
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!clean || clash) return
    try { await write.save.mutateAsync({ entity: 'tags', id: tag?.id, newId, data: { name: clean, color, archived } }); onClose() } catch (x) { setErr(errorText(x)) }
  }
  return (
    <Modal title={tag ? 'Edit tag' : 'New tag'} onClose={onClose} footer={<>
      {tag && <button className="danger" onClick={async () => {
        await write.remove.mutateAsync({ entity: 'tags', id: tag.id })
        toast(`#${tag.name} deleted`, { label: 'Undo', run: () => write.restore.mutate({ entity: 'tags', id: tag.id }) }); onClose()
      }}>Delete</button>}
      <span className="spacer" /><button onClick={onClose}>Cancel</button><button className="primary" form="tagform" disabled={!clean || clash || write.save.isPending}>Save</button></>}>
      <form id="tagform" onSubmit={submit}>
        <Field label="Name" error={clash ? 'A tag with this name already exists' : undefined}><input value={name} onChange={(e) => setName(e.target.value)} autoFocus placeholder="goa-trip" /></Field>
        <Field label="Colour">
          <div className="row">{PALETTE.map((c) => <button type="button" key={c} className={`swatch ${c === color ? 'on' : ''}`} style={{ background: c }} aria-label={`Colour ${c}`} onClick={() => setColor(c)} />)}</div>
        </Field>
        <label className="row"><input type="checkbox" style={{ width: 'auto' }} checked={archived} onChange={(e) => setArchived(e.target.checked)} /> Hide from pickers (keeps it on past transactions)</label>
        {err && <p className="error">{err}</p>}
      </form>
    </Modal>
  )
}
