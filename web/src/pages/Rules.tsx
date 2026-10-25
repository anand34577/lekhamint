import { useMemo, useState } from 'react'
import { api, type BatchOp, type Rule } from '../lib/api'
import { useAccounts, useCategorizationRules, useCategoryIndex, useLedger, useTagIndex, useStableId, useWrite, type WithId } from '../lib/data'
import { describeRule, ruleMatches, type RuleRec } from '../lib/rules'
import { parseMoney, toPlain } from '../lib/money'
import { Card, Empty, Field, Modal, errorText, toast } from '../components'
import { CategorySelect } from './Transactions'
import { TagInput } from './parts'
import type { PageProps } from '../App'
import { Select, NumInput } from '../controls'

/** Rules fill in the category, payee and tags for new and imported transactions. */
export function Rules(_: PageProps) {
  const rules = useCategorizationRules()
  const cats = useCategoryIndex()
  const tags = useTagIndex()
  const write = useWrite()
  const [edit, setEdit] = useState<WithId<Rule> | 'new' | null>(null)
  const all = useMemo(() => [...(rules.data ?? []).filter((r) => !r.deleted)]
    .sort((a, b) => a.sort_order - b.sort_order || a.created_at - b.created_at || (a.id < b.id ? -1 : 1)), [rules.data])

  const move = (i: number, d: number) => {
    const list = [...all]
    const [r] = list.splice(i, 1)
    list.splice(i + d, 0, r)
    const ops: BatchOp[] = list.flatMap((x, k) => (x.sort_order === k ? [] : [{ op: 'update' as const, entity: 'rules', id: x.id, data: { sort_order: k } }]))
    if (ops.length) write.batch.mutate(ops)
  }

  return (
    <>
      <div className="page-head"><h1>Auto-categorise</h1><button className="primary" onClick={() => setEdit('new')}>+ Rule</button></div>
      {all.length === 0 && !rules.isPending ? (
        <Empty title="No rules yet" body="Example: payee contains “swiggy” → Food · Delivery. LekhaMint also remembers the last category for each payee on Android; rules work everywhere, including imports."
          action={<button className="primary" onClick={() => setEdit('new')}>Create a rule</button>} />
      ) : (
        <Card>
          <p className="muted small" style={{ marginTop: 0 }}>Rules run top to bottom. The first rule that sets a category wins; tags from every matching rule are added.</p>
          <ul className="list">
            {all.map((r, i) => (
              <li key={r.id}>
                <div className="grow clickable" onClick={() => setEdit(r)}>
                  <div>{r.name ?? r.pattern} {!r.enabled && <span className="chip">off</span>}</div>
                  <div className="sub">{describeRule(r, cats.path, tags.name)}</div>
                </div>
                <button className="icon-btn" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>↑</button>
                <button className="icon-btn" aria-label="Move down" disabled={i === all.length - 1} onClick={() => move(i, 1)}>↓</button>
                <label className="row" title={r.enabled ? 'Turn off' : 'Turn on'}>
                  <input type="checkbox" style={{ width: 'auto' }} checked={r.enabled} aria-label="Enabled"
                    onChange={(e) => write.save.mutate({ entity: 'rules', id: r.id, data: { enabled: e.target.checked } })} />
                </label>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {edit && <RuleForm rule={edit === 'new' ? undefined : edit} nextOrder={all.length} onClose={() => setEdit(null)} />}
    </>
  )
}

function RuleForm({ rule, nextOrder, onClose }: { rule?: WithId<Rule>; nextOrder: number; onClose: () => void }) {
  const l = useLedger()
  const cats = useCategoryIndex()
  const accounts = useAccounts()
  const write = useWrite()
  const newId = useStableId()
  const [pattern, setPattern] = useState(rule?.pattern ?? '')
  const [field, setField] = useState<Rule['match_field']>(rule?.match_field ?? 'payee')
  const [how, setHow] = useState<Rule['match_type']>(rule?.match_type ?? 'contains')
  const [txType, setTxType] = useState<Rule['tx_type']>(rule ? rule.tx_type : 'expense')
  const [accountId, setAccountId] = useState(rule?.account_id ?? '')
  const code = (accounts.data ?? []).find((a) => a.id === accountId)?.currency ?? l.base_currency
  const [min, setMin] = useState(rule?.min_amount != null ? toPlain(rule.min_amount, code) : '')
  const [max, setMax] = useState(rule?.max_amount != null ? toPlain(rule.max_amount, code) : '')
  const [categoryId, setCategoryId] = useState(rule?.set_category_id ?? '')
  const [setPayee, setSetPayee] = useState(rule?.set_payee ?? '')
  const [tagIds, setTagIds] = useState<string[]>(rule?.add_tag_ids ?? [])
  const [name, setName] = useState(rule?.name ?? '')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  const minMinor = min.trim() ? parseMoney(min, code) : null
  const maxMinor = max.trim() ? parseMoney(max, code) : null
  const build = (): Omit<Rule, 'created_at'> => ({
    name: name.trim() || null, pattern: pattern.trim(), match_field: field, match_type: how, tx_type: txType, account_id: accountId || null,
    min_amount: minMinor, max_amount: maxMinor, set_category_id: categoryId || null, set_payee: setPayee.trim() || null,
    add_tag_ids: tagIds, enabled: rule?.enabled ?? true, sort_order: rule?.sort_order ?? nextOrder,
  })
  const validate = () => {
    if (!pattern.trim()) return 'Enter the text to look for'
    if ((min.trim() && minMinor === null) || (max.trim() && maxMinor === null)) return 'Check the amounts'
    if (minMinor !== null && maxMinor !== null && maxMinor < minMinor) return 'Max must not be below min'
    if (!categoryId && !setPayee.trim() && tagIds.length === 0) return 'Pick a category, a payee name or tags'
    return ''
  }

  const save = async (applyPast: boolean) => {
    const v = validate()
    if (v) return setErr(v)
    setBusy(true); setErr('')
    try {
      const rec = await write.save.mutateAsync({ entity: 'rules', id: rule?.id, newId, data: build() })
      if (applyPast) {
        const r = { ...build(), id: rec.id, created_at: 0, enabled: true } as RuleRec
        const ops: BatchOp[] = []
        for (let cursor = '', more = true; more;) {
          const page = await api.transactions(l.id, { limit: '500', ...(cursor ? { cursor } : {}) })
          for (const t of page.transactions) {
            const d = t.data
            if (d.type === 'transfer' || d.is_adjustment || !ruleMatches(r, { payee: d.payee, note: d.note, type: d.type, accountId: d.account_id, amount: d.amount })) continue
            const next = { category_id: r.set_category_id ?? d.category_id, payee: r.set_payee ?? d.payee, tag_ids: [...new Set([...d.tag_ids, ...r.add_tag_ids])] }
            if (next.category_id !== d.category_id || next.payee !== d.payee || next.tag_ids.length !== d.tag_ids.length) ops.push({ op: 'update', entity: 'transactions', id: t.id, data: next })
          }
          cursor = page.next_cursor ?? ''; more = !!cursor
        }
        if (ops.length) await write.batch.mutateAsync(ops)
        toast(`Rule saved and applied to ${ops.length} past ${ops.length === 1 ? 'transaction' : 'transactions'}`)
      }
      onClose()
    } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
  }

  return (
    <Modal wide title={rule ? 'Edit rule' : 'New rule'} onClose={onClose} footer={<>
      {rule && <button className="danger" onClick={async () => { await write.remove.mutateAsync({ entity: 'rules', id: rule.id }); toast('Rule deleted', { label: 'Undo', run: () => write.restore.mutate({ entity: 'rules', id: rule.id }) }); onClose() }}>Delete</button>}
      <span className="spacer" />
      <button onClick={onClose}>Cancel</button>
      <button onClick={() => save(true)} disabled={busy} title="Also update past transactions this rule matches">Save and apply to past</button>
      <button className="primary" onClick={() => save(false)} disabled={busy}>Save</button></>}>
      <h3 style={{ marginTop: 0 }}>When</h3>
      <div className="two">
        <Field label="Look at"><Select value={field} onChange={(e) => setField(e.target.value as Rule['match_field'])}><option value="payee">Payee</option><option value="note">Note</option><option value="any">Payee or note</option></Select></Field>
        <Field label="Match"><Select value={how} onChange={(e) => setHow(e.target.value as Rule['match_type'])}><option value="contains">contains</option><option value="starts_with">starts with</option><option value="equals">is exactly</option></Select></Field>
      </div>
      <Field label="Text" hint="Not case-sensitive. Example: swiggy"><input value={pattern} onChange={(e) => setPattern(e.target.value)} autoFocus /></Field>
      <div className="two">
        <Field label="Only for"><Select value={txType ?? ''} onChange={(e) => setTxType((e.target.value || null) as Rule['tx_type'])}><option value="expense">Expenses</option><option value="income">Income</option><option value="">Both</option></Select></Field>
        <Field label="Account"><Select value={accountId} onChange={(e) => setAccountId(e.target.value)}><option value="">Any account</option>{(accounts.data ?? []).filter((a) => !a.deleted).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</Select></Field>
      </div>
      <div className="two">
        <Field label="Min amount (optional)"><NumInput kind="decimal" value={min} onChange={(e) => setMin(e.target.value)} /></Field>
        <Field label="Max amount (optional)"><NumInput kind="decimal" value={max} onChange={(e) => setMax(e.target.value)} /></Field>
      </div>
      <h3>Then</h3>
      <div className="two">
        <Field label="Set category"><CategorySelect value={categoryId} onChange={setCategoryId} cats={cats} all="Don't change" kind={txType ?? undefined} /></Field>
        <Field label="Rename payee to" hint="Cleans names like “SWIGGY*ORDER 8812”"><input value={setPayee} onChange={(e) => setSetPayee(e.target.value)} /></Field>
      </div>
      <Field label="Add tags"><TagInput value={tagIds} onChange={setTagIds} /></Field>
      <Field label="Rule name (optional)"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
      {err && <p className="error" role="alert">{err}</p>}
    </Modal>
  )
}

