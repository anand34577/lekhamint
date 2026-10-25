import { TAX_SECTIONS } from '../lib/tax'
import { useState } from 'react'
import type { Category } from '../lib/api'
import { useCategoryIndex, useStableId, useWrite, type WithId } from '../lib/data'
import { Card, Dot, Field, Modal, errorText, toast } from '../components'
import type { PageProps } from '../App'
import { Select } from '../controls'

const PALETTE = ['#EF6C00', '#E53935', '#D81B60', '#8E24AA', '#5E35B1', '#3949AB', '#1E88E5', '#039BE5', '#00ACC1', '#00897B', '#2E7D32', '#7CB342', '#C0CA33', '#FFB300', '#8D6E63', '#546E7A']
const NATURES: [string, string][] = [['need', 'Need'], ['want', 'Want'], ['savings', 'Savings & debt']]

type Edit = { cat?: WithId<Category>; kind: string; parent: string | null }

export function Categories(_: PageProps) {
  const cats = useCategoryIndex()
  const [kind, setKind] = useState<'expense' | 'income'>('expense')
  const [edit, setEdit] = useState<Edit | null>(null)
  return (
    <>
      <div className="page-head">
        <h1>Categories</h1>
        <div className="seg"><button className={kind === 'expense' ? 'on' : ''} onClick={() => setKind('expense')}>Expense</button><button className={kind === 'income' ? 'on' : ''} onClick={() => setKind('income')}>Income</button></div>
        <button className="primary" onClick={() => setEdit({ kind, parent: null })}>+ Category</button>
      </div>
      <div className="grid">
        {cats.roots(kind).map((r) => (
          <Card key={r.id} title={<span className="row clickable" onClick={() => setEdit({ cat: r, kind, parent: null })}><Dot color={r.color ?? '#94A3B8'} />{r.name}</span>}
            action={<button className="link" onClick={() => setEdit({ kind, parent: r.id })}>+ Sub</button>}>
            {r.nature && <p className="muted small" style={{ marginTop: -8 }}>{NATURES.find(([k]) => k === r.nature)?.[1]}</p>}
            <div className="row">{cats.children(r.id).map((c) => <button key={c.id} className="chip" onClick={() => setEdit({ cat: c, kind, parent: r.id })}>{c.name}</button>)}</div>
          </Card>
        ))}
      </div>
      {edit && <CategoryForm {...edit} onClose={() => setEdit(null)} />}
    </>
  )
}

function CategoryForm({ cat, kind, parent, onClose }: Edit & { onClose: () => void }) {
  const cats = useCategoryIndex()
  const write = useWrite()
  const newId = useStableId()
  const parentCat = parent ? cats.byId.get(parent) : undefined
  const [name, setName] = useState(cat?.name ?? '')
  const [parentId, setParentId] = useState(parent ?? '')
  const [nature, setNature] = useState(cat?.nature ?? parentCat?.nature ?? '')
  const [color, setColor] = useState(cat?.color ?? parentCat?.color ?? PALETTE[6])
  const [tax, setTax] = useState(cat?.tax_section ?? '')
  const [err, setErr] = useState('')
  const hasKids = !!cat && cats.children(cat.id).length > 0
  return (
    <Modal title={cat ? 'Edit category' : 'New category'} onClose={onClose} footer={<>
      {cat && <button className="danger" onClick={async () => {
        const ids = [cat.id, ...cats.children(cat.id).map((c) => c.id)]
        await write.batch.mutateAsync(ids.map((id) => ({ op: 'delete' as const, entity: 'categories', id })))
        toast(ids.length > 1 ? `Deleted ${cat.name} and ${ids.length - 1} sub-categories` : 'Category deleted. Past transactions show as uncategorised.',
          { label: 'Undo', run: () => write.batch.mutate(ids.map((id) => ({ op: 'restore' as const, entity: 'categories', id }))) })
        onClose()
      }}>Delete</button>}
      <span className="spacer" /><button onClick={onClose}>Cancel</button><button className="primary" form="catform" disabled={write.save.isPending}>Save</button></>}>
      <form id="catform" onSubmit={async (e) => {
        e.preventDefault()
        try {
          await write.save.mutateAsync({ entity: 'categories', id: cat?.id, newId, data: {
            name: name.trim(), kind: kind as Category['kind'], parent_id: parentId || null, nature: kind === 'expense' ? nature || null : null, color, tax_section: tax || null,
            ...(cat ? {} : { sort_order: 999, icon: parentCat?.icon ?? 'dots' }),
          } satisfies Partial<Category> })
          onClose()
        } catch (x) { setErr(errorText(x)) }
      }}>
        <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} required autoFocus /></Field>
        {!hasKids && <Field label="Group"><Select value={parentId} onChange={(e) => setParentId(e.target.value)}><option value="">None (top level)</option>
          {cats.roots(kind).filter((r) => r.id !== cat?.id).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</Select></Field>}
        {kind === 'expense' && <Field label="Kind of spending" hint="Used for the needs / wants / savings (50/30/20) view">
          <Select value={nature} onChange={(e) => setNature(e.target.value)}><option value="">Not set</option>{NATURES.map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></Field>}
        <Field label="Counts for tax (optional)" hint="Adds this spending to the Year &amp; tax summary. A guide, not tax advice.">
          <Select value={tax} onChange={(e) => setTax(e.target.value)}><option value="">No</option>{TAX_SECTIONS.map((t) => <option key={t.code} value={t.code}>{t.label}</option>)}</Select></Field>
        <Field label="Colour"><div className="row">{PALETTE.map((c) => <button type="button" key={c} className={`swatch ${c === color ? 'on' : ''}`} aria-label={`Colour ${c}`} aria-pressed={c === color} onClick={() => setColor(c)} style={{ background: c }} />)}</div></Field>
        {err && <p className="error">{err}</p>}
      </form>
    </Modal>
  )
}
