import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type ExchangeRate } from '../lib/api'
import { useCanWrite, useExchangeRates, useLedger, useStableId, useWrite, type WithId } from '../lib/data'
import { download, isoDate, shortDate } from '../lib/util'
import { Card, Confirm, CurrencySelect, Field, Modal, Segmented, errorText, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps } from '../App'

export function Settings({ theme, setTheme, onSignOut, params }: PageProps) {
  const l = useLedger()
  const qc = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const [name, setName] = useState(l.name)
  const [cur, setCur] = useState(l.base_currency)
  const [pw, setPw] = useState({ current: '', next: '' })
  const [busy, setBusy] = useState('')
  const [deleting, setDeleting] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const ratesRef = useRef<HTMLDivElement>(null)
  useEffect(() => { if (params.section === 'rates') ratesRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [params.section])
  const owner = l.role === 'owner'
  const canWrite = useCanWrite()

  const run = async (label: string, fn: () => Promise<string>) => {
    setBusy(label)
    try { toast(await fn()) } catch (x) { toast(errorText(x)) } finally { setBusy('') }
  }

  return (
    <>
      <div className="page-head"><h1>Settings</h1></div>
      <div className="settings-grid">
        <Card title={<><Icon name="wallet" size={16} /> Book</>}>
          <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} disabled={!owner} /></Field>
          <Field label="Main currency" hint="Used for totals; accounts keep their own currency"><CurrencySelect value={cur} onChange={setCur} disabled={!owner} /></Field>
          <div className="row">
            <button onClick={() => run('save', async () => { await api.updateLedger(l.id, { name: name.trim(), base_currency: cur }); qc.invalidateQueries({ queryKey: ['ledgers'] }); return 'Saved' })}
              disabled={!!busy || !owner || !name.trim() || (name.trim() === l.name && cur === l.base_currency)}>Save</button>
            {owner && <button className="danger" onClick={() => setDeleting(true)}>Delete book…</button>}
          </div>
          {!owner && <p className="muted small">Only the owner can change these.</p>}
        </Card>
        <Card title={<><Icon name="eye" size={16} /> Appearance</>}>
          <Field label="Theme"><Segmented label="Theme" value={theme} onChange={setTheme} options={[['system', 'System'], ['light', 'Light'], ['dark', 'Dark'], ['black', 'Black']]} /></Field>
          <p className="muted small">Black uses true black for OLED screens. Press <kbd className="kbd">?</kbd> anywhere for keyboard shortcuts.</p>
        </Card>
        <div ref={ratesRef}><Rates /></div>
        {owner && <Sharing />}
        <Card title={<><Icon name="download" size={16} /> Your data</>}>
          <p className="muted small">Exports use the same format as the Android app and server, so you can move data anywhere.</p>
          <div className="row">
            <button disabled={!!busy} onClick={() => run('export', async () => { download(`lekhamint-${l.name}-${isoDate()}.json`, await api.exportLedger(l.id)); return 'Export downloaded' })}>Export (JSON)</button>
            <button disabled={!!busy || !canWrite} onClick={() => file.current?.click()}>Import (JSON)…</button>
            <input ref={file} type="file" accept=".json,application/json" hidden onChange={(e) => {
              const f = e.target.files?.[0]; e.target.value = ''
              if (f) run('import', async () => {
                const r = await api.importLedger(l.id, f)
                qc.invalidateQueries({ queryKey: [l.id] })
                return `Imported ${r.accepted} records${r.skipped_newer_exists ? `, ${r.skipped_newer_exists} already newer here` : ''}`
              })
            }} />
          </div>
          <p className="muted small">Importing merges by last change: nothing is deleted, and newer versions win. A failed import changes nothing. For bank statements use <a href="#/import">Import statement</a>.</p>
          {busy && <p className="muted">Working…</p>}
        </Card>
        <Devices />
        <Card title={<><Icon name="users" size={16} /> Your account</>}>
          <p>{me.data?.user.name && <strong>{me.data.user.name} · </strong>}{me.data?.user.email}</p>
          <form onSubmit={(e) => { e.preventDefault(); run('pw', async () => { await api.changePassword(pw.current, pw.next); setPw({ current: '', next: '' }); return 'Password changed. Other devices were signed out.' }) }}>
            <Field label="Current password"><input type="password" autoComplete="current-password" value={pw.current} onChange={(e) => setPw({ ...pw, current: e.target.value })} required /></Field>
            <Field label="New password" hint="At least 8 characters"><input type="password" autoComplete="new-password" minLength={8} value={pw.next} onChange={(e) => setPw({ ...pw, next: e.target.value })} required /></Field>
            <button disabled={!!busy}>Change password</button>
          </form>
          <p><button onClick={async () => { await api.logout().catch(() => {}); onSignOut() }}><Icon name="logout" size={16} /> Sign out</button></p>
        </Card>
      </div>
      {deleting && <DeleteBook onClose={() => setDeleting(false)} />}
    </>
  )
}

function DeleteBook({ onClose }: { onClose: () => void }) {
  const l = useLedger()
  const qc = useQueryClient()
  const [typed, setTyped] = useState('')
  return (
    <Confirm title={`Delete “${l.name}” for everyone?`} danger confirm="Delete forever" onClose={onClose} disabled={typed.trim() !== l.name}
      onConfirm={async () => {
        await api.deleteLedger(l.id)
        try { localStorage.removeItem('kosh.ledger') } catch { /* ignore */ }
        qc.invalidateQueries({ queryKey: ['ledgers'] })
        toast('Book deleted')
      }}
      body={<>
        <p>This permanently removes every account, transaction and budget in this book from the server, for every member. Phones keep their own copy until they sync. Export first if you might need it.</p>
        <Field label={`Type ${l.name} to confirm`}><input value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus /></Field>
      </>} />
  )
}

/** Manual exchange rates: used to convert totals across currencies (newest rate on or before the date). */
function Rates() {
  const l = useLedger()
  const rates = useExchangeRates()
  const write = useWrite()
  const can = useCanWrite()
  const [edit, setEdit] = useState<WithId<ExchangeRate> | 'new' | null>(null)
  const list = (rates.data ?? []).filter((r) => !r.deleted).sort((a, b) => b.effective_date.localeCompare(a.effective_date) || a.base.localeCompare(b.base))
  return (
    <Card title={<><Icon name="coins" size={16} /> Exchange rates</>} action={can ? <button className="link" onClick={() => setEdit('new')}>+ Rate</button> : undefined}>
      <p className="muted small">LekhaMint converts other currencies into {l.base_currency} for totals using the newest rate on or before each period. Rates are typed in by you; nothing is fetched from the internet.</p>
      {list.length === 0 ? <p className="muted">No rates yet.</p> : (
        <ul className="list">{list.slice(0, 12).map((r) => (
          <li key={r.id} className={can ? 'clickable' : ''} onClick={() => can && setEdit(r)}>
            <div className="grow"><div>1 {r.base} = {r.rate} {r.quote}</div><div className="sub">from {shortDate(r.effective_date)}</div></div>
            {can && <button className="icon-btn" aria-label="Delete rate" onClick={async (e) => { e.stopPropagation(); await write.remove.mutateAsync({ entity: 'exchange_rates', id: r.id }); toast('Rate deleted', { label: 'Undo', run: () => write.restore.mutate({ entity: 'exchange_rates', id: r.id }) }) }}><Icon name="trash" size={16} /></button>}
          </li>
        ))}</ul>
      )}
      {edit && <RateForm rate={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
    </Card>
  )
}

function RateForm({ rate, onClose }: { rate?: WithId<ExchangeRate>; onClose: () => void }) {
  const l = useLedger()
  const write = useWrite()
  const newId = useStableId()
  const [base, setBase] = useState(rate?.base ?? (l.base_currency === 'USD' ? 'EUR' : 'USD'))
  const [quote, setQuote] = useState(rate?.quote ?? l.base_currency)
  const [value, setValue] = useState(rate?.rate ?? '')
  const [date, setDate] = useState(rate?.effective_date ?? isoDate())
  const [err, setErr] = useState('')
  return (
    <Modal title={rate ? 'Edit rate' : 'New exchange rate'} onClose={onClose} footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="rateform" disabled={write.save.isPending}>Save</button></>}>
      <form id="rateform" onSubmit={async (e) => {
        e.preventDefault()
        const v = value.trim().replace(/,/g, '')
        if (base === quote) return setErr('Pick two different currencies')
        if (!/^\d+(\.\d+)?$/.test(v) || Number(v) <= 0) return setErr('Enter a positive number, e.g. 83.25')
        try {
          await write.save.mutateAsync({ entity: 'exchange_rates', id: rate?.id, newId, data: { base, quote, rate: v, effective_date: date, source: 'manual' } })
          toast('Rate saved'); onClose()
        } catch (x) { setErr(errorText(x)) }
      }}>
        <div className="two">
          <Field label="1 unit of"><CurrencySelect value={base} onChange={setBase} /></Field>
          <Field label="equals this many"><CurrencySelect value={quote} onChange={setQuote} /></Field>
        </div>
        <div className="two">
          <Field label="Rate"><input inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} placeholder="83.25" autoFocus /></Field>
          <Field label="From date"><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        </div>
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

/** Share the book: editors can change it, viewers can only look. */
function Sharing() {
  const l = useLedger()
  const qc = useQueryClient()
  const members = useQuery({ queryKey: [l.id, 'members'], queryFn: () => api.members(l.id) })
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('editor')
  const [busy, setBusy] = useState(false)
  const add = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true)
    try {
      await api.addMember(l.id, email.trim(), role)
      setEmail(''); qc.invalidateQueries({ queryKey: [l.id, 'members'] })
      toast('Shared. They will see this book the next time they sign in.')
    } catch (x) { toast(errorText(x)) } finally { setBusy(false) }
  }
  return (
    <Card title={<><Icon name="users" size={16} /> Sharing</>}>
      <p className="muted small">Share this book with a partner or family member who has an account on this server.</p>
      <ul className="list">
        {(members.data ?? []).map((m) => (
          <li key={m.user_id}>
            <div className="grow"><div>{m.name || m.email}{m.user_id === me.data?.user.id && ' (you)'}</div><div className="sub">{m.email} · {m.role}</div></div>
            {m.role !== 'owner' && <button className="link" onClick={async () => {
              try { await api.removeMember(l.id, m.user_id); qc.invalidateQueries({ queryKey: [l.id, 'members'] }); toast('Access removed') } catch (x) { toast(errorText(x)) }
            }}>Remove</button>}
          </li>
        ))}
      </ul>
      <form className="row" onSubmit={add} style={{ marginTop: 8 }}>
        <input type="email" required placeholder="Their email" value={email} onChange={(e) => setEmail(e.target.value)} style={{ flex: 1, minWidth: 160 }} aria-label="Email" />
        <select value={role} onChange={(e) => setRole(e.target.value)} style={{ width: 'auto' }} aria-label="Role"><option value="editor">Can edit</option><option value="viewer">Can view</option></select>
        <button disabled={busy}>Share</button>
      </form>
    </Card>
  )
}

function Devices() {
  const qc = useQueryClient()
  const sessions = useQuery({ queryKey: ['sessions'], queryFn: api.sessions })
  const ago = (ms: number) => {
    const d = Math.round((Date.now() - ms) / 864e5)
    return d <= 0 ? 'today' : d === 1 ? 'yesterday' : `${d} days ago`
  }
  return (
    <Card title={<><Icon name="device" size={16} /> Signed-in devices</>}>
      <ul className="list">
        {(sessions.data ?? []).sort((a, b) => b.last_used_at - a.last_used_at).map((s) => (
          <li key={s.id}>
            <div className="grow"><div>{s.device_name || 'Unknown device'}{s.current && <span className="chip" style={{ marginLeft: 8 }}>this browser</span>}</div>
              <div className="sub">Last used {ago(s.last_used_at)} · signed in {new Date(s.created_at).toLocaleDateString()}</div></div>
            {!s.current && <button className="link" onClick={async () => {
              try { await api.revokeSession(s.id); qc.invalidateQueries({ queryKey: ['sessions'] }); toast('Signed out that device') } catch (x) { toast(errorText(x)) }
            }}>Sign out</button>}
          </li>
        ))}
      </ul>
      <p className="muted small">Lost a phone? Sign it out here; its local data stays on the phone, but it can no longer sync.</p>
    </Card>
  )
}
