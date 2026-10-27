import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type ExchangeRate, type Session } from '../lib/api'
import { setSetting } from '../lib/settings'
import { useAccounts, useCanWrite, useExchangeRates, useLedger, useStableId, useWrite, type WithId } from '../lib/data'
import { download, isoDate, plural, shortDate } from '../lib/util'
import { Card, Confirm, CurrencySelect, Field, Modal, errorText, toast } from '../components'
import { qrMatrix, qrPath } from '../lib/qr'
import { DEMO } from '../lib/demo'
import { Notifications } from './Notifications'
import { TwoStep } from './Security'
import { Spinner } from '../charts'
import { Icon } from '../icons'
import type { PageProps } from '../App'
import { Select, DateField, NumInput, PasswordInput } from '../controls'

export function Settings({ theme, setTheme, accent, setAccent, onSignOut, params }: PageProps) {
  const l = useLedger()
  const qc = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const [name, setName] = useState(l.name)
  const [cur, setCur] = useState(l.base_currency)
  const [pw, setPw] = useState({ current: '', next: '' })
  const [busy, setBusy] = useState('')
  const [deleting, setDeleting] = useState(false)
  const [deletingAccount, setDeletingAccount] = useState(false)
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
        <Card title={<><Icon name="palette" size={16} /> Appearance</>}>
          <div className="field"><span className="field-label">Theme</span>
            <div className="theme-cards" role="radiogroup" aria-label="Theme">
              {([['system', 'System', 'monitor'], ['light', 'Light', 'sun'], ['dark', 'Dark', 'moon'], ['black', 'Black', 'moon']] as const).map(([k, text, icon]) => (
                <button type="button" key={k} role="radio" aria-checked={theme === k} className={`theme-card ${theme === k ? 'on' : ''}`} onClick={() => setTheme(k)}>
                  <span className="theme-prev" style={PREVIEW[k]}><i /><b /></span>
                  <span className="row nowrap"><Icon name={icon} size={15} />{text}</span>
                </button>
              ))}
            </div>
          </div>
          <div className="field"><span className="field-label">Accent colour</span>
            <div className="accent-picker" role="radiogroup" aria-label="Accent colour">
              {ACCENTS.map(([k, text, color]) => (
                <button type="button" key={k} role="radio" aria-checked={accent === k} aria-label={text} title={text} className={`accent-swatch ${accent === k ? 'on' : ''}`}
                  style={{ background: color, color }} onClick={() => setAccent(k)}>{accent === k && <Icon name="check" size={16} className="on-accent" />}</button>
              ))}
            </div>
          </div>
          <p className="muted small">Black uses true black for OLED screens. Press <kbd className="kbd">?</kbd> anywhere for keyboard shortcuts.</p>
        </Card>
        <Notifications />
        <div ref={ratesRef}><Rates /></div>
        {owner && <Sharing />}
        <Card title={<><Icon name="download" size={16} /> Your data</>}>
          <p className="muted small">Exports use the same format as the Android app and server, so you can move data anywhere.</p>
          <div className="row">
            <button disabled={!!busy} onClick={() => run('export', async () => { download(`lekhamint-${l.name}-${isoDate()}.json`, await api.exportLedger(l.id)); return 'Export downloaded' })}>{busy === 'export' ? <Spinner size={15} /> : <Icon name="download" size={15} />} Export (JSON)</button>
            <button disabled={!!busy || !canWrite} onClick={() => file.current?.click()}>{busy === 'import' ? <Spinner size={15} /> : <Icon name="upload" size={15} />} Import (JSON)…</button>
            <input ref={file} type="file" accept=".json,application/json" hidden onChange={(e) => {
              const f = e.target.files?.[0]; e.target.value = ''
              if (f) run('import', async () => {
                const r = await api.importLedger(l.id, f)
                qc.invalidateQueries({ queryKey: [l.id] })
                return `Imported ${plural(r.accepted, 'record')}${r.skipped_newer_exists ? `, ${r.skipped_newer_exists} already newer here` : ''}`
              })
            }} />
          </div>
          <p className="muted small">Importing merges by last change: nothing is deleted, and newer versions win. A failed import changes nothing. For bank statements use <a href="#/import">Import statement</a>.</p>
          {(busy === 'export' || busy === 'import') && <div className="progress-line"><Spinner size={15} /> {busy === 'export' ? 'Preparing your export…' : 'Importing; this can take a minute for a large book…'}</div>}
        </Card>
        <TwoStep />
        <Devices />
        <Card title={<><Icon name="users" size={16} /> Your account</>}>
          <p>{me.data?.user.name && <strong>{me.data.user.name} · </strong>}{me.data?.user.email}</p>
          <form onSubmit={(e) => { e.preventDefault(); run('pw', async () => { await api.changePassword(pw.current, pw.next); setPw({ current: '', next: '' }); return 'Password changed. Other devices were signed out.' }) }}>
            <Field label="Current password"><PasswordInput autoComplete="current-password" value={pw.current} onChange={(e) => setPw({ ...pw, current: e.target.value })} required /></Field>
            <Field label="New password" hint="At least 8 characters"><PasswordInput autoComplete="new-password" minLength={8} value={pw.next} onChange={(e) => setPw({ ...pw, next: e.target.value })} required /></Field>
            <div className="form-actions">
              <button className="primary" disabled={!!busy || !pw.current || pw.next.length < 8}>{busy === 'pw' ? <Spinner size={15} /> : <Icon name="lock" size={15} />} Change password</button>
            </div>
          </form>
          <div className="account-actions">
            <button onClick={async () => { await api.logout().catch(() => {}); onSignOut() }}><Icon name="logout" size={16} /> Sign out</button>
            <span className="spacer" />
            <button className="danger" onClick={() => setDeletingAccount(true)}><Icon name="trash" size={15} /> Delete account…</button>
          </div>
        </Card>
      </div>
      {deleting && <DeleteBook onClose={() => setDeleting(false)} />}
      {deletingAccount && <DeleteAccount email={me.data?.user.email ?? ''} onClose={() => setDeletingAccount(false)} onDeleted={onSignOut} />}
    </>
  )
}

const ACCENTS: [string, string, string][] = [['teal', 'Teal', '#0f9488'], ['emerald', 'Emerald', '#12a15d'], ['sky', 'Sky', '#2f7de1'], ['indigo', 'Indigo', '#5b5ce0'],
  ['violet', 'Violet', '#9b51d6'], ['rose', 'Rose', '#e0436b'], ['amber', 'Amber', '#d98a00']]
const PREVIEW: Record<string, React.CSSProperties> = {
  system: { background: 'linear-gradient(110deg, #f3f5fa 50%, #0a0f1a 50%)' },
  light: { background: '#f3f5fa' }, dark: { background: '#0a0f1a' }, black: { background: '#000' },
}

function DeleteBook({ onClose }: { onClose: () => void }) {
  const l = useLedger()
  const qc = useQueryClient()
  const [typed, setTyped] = useState('')
  return (
    <Confirm title={`Delete “${l.name}” for everyone?`} danger confirm="Delete forever" onClose={onClose} disabled={typed.trim() !== l.name}
      onConfirm={async () => {
        await api.deleteLedger(l.id)
        setSetting('ledger', null)
        qc.invalidateQueries({ queryKey: ['ledgers'] })
        toast('Book deleted')
      }}
      body={<>
        <p>This permanently removes every account, transaction and budget in this book from the server, for every member. Phones keep their own copy until they sync. Export first if you might need it.</p>
        <Field label={`Type ${l.name} to confirm`}><input value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus /></Field>
      </>} />
  )
}

function DeleteAccount({ email, onClose, onDeleted }: { email: string; onClose: () => void; onDeleted: () => void }) {
  const [password, setPassword] = useState('')
  return (
    <Confirm title="Delete your account?" danger confirm="Delete account forever" onClose={onClose} disabled={!password}
      onConfirm={async () => {
        await api.deleteAccount(password)
        toast('Your account was deleted')
        onDeleted()
      }}
      body={<>
        <p>This permanently deletes <strong>{email}</strong>. Books that only you use are deleted with all their data. A book you share passes to the person who has been in it longest, so your family keeps it. Phones keep their own copy. Export first if you might need it.</p>
        <Field label="Password" hint="Signed in through single sign-on? Type your email address instead."><PasswordInput autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus /></Field>
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
  const [fetching, setFetching] = useState(false)
  const meta = useQuery({ queryKey: ['meta'], queryFn: api.meta, staleTime: 10 * 60_000 })
  const accounts = useAccounts()
  const qc = useQueryClient()
  const list = (rates.data ?? []).filter((r) => !r.deleted).sort((a, b) => b.effective_date.localeCompare(a.effective_date) || a.base.localeCompare(b.base))
  // Currencies the book uses besides its main one: the ones that need a rate.
  const foreign = [...new Set((accounts.data ?? []).filter((a) => !a.deleted && !a.archived && a.currency !== l.base_currency).map((a) => a.currency))].sort()
  const canFetch = can && meta.data?.features?.fx_fetch && foreign.length > 0
  const fetchNow = async () => {
    setFetching(true)
    try {
      // One request per currency, so each is saved as "1 USD = 83.2 INR", the way people read rates.
      const got: string[] = [], missing: string[] = []
      let date = '', source = ''
      for (const c of foreign) {
        try {
          const r = await api.fetchRates(l.id, c, [l.base_currency])
          date = r.date; source = r.source
          if (r.rates[l.base_currency]) got.push(`1 ${c} = ${r.rates[l.base_currency]} ${l.base_currency}`)
          else missing.push(c)
        } catch { missing.push(c) }
      }
      qc.invalidateQueries({ queryKey: [l.id] })
      toast(got.length ? `Saved ${got.join(', ')} (rates of ${date}, ${source})${missing.length ? `. Not available: ${missing.join(', ')}` : ''}` : `No rates found for ${missing.join(', ')}. Type them in instead.`)
    } finally { setFetching(false) }
  }
  return (
    <Card title={<><Icon name="coins" size={16} /> Exchange rates</>} action={can ? <button className="link" onClick={() => setEdit('new')}>+ Rate</button> : undefined}>
      <p className="muted small">LekhaMint converts other currencies into {l.base_currency} for totals using the newest rate on or before each period.
        {meta.data?.features?.fx_fetch ? ' Type rates in, or fetch the European Central Bank reference rates; nothing is fetched unless you press the button.' : ' Rates are typed in by you; nothing is fetched from the internet.'}</p>
      {canFetch && <div className="row" style={{ marginBottom: 8 }}>
        <button onClick={fetchNow} disabled={fetching}>{fetching ? <Spinner size={15} /> : <Icon name="refresh" size={15} />} Fetch today's rates for {foreign.join(', ')}</button>
      </div>}
      {list.length > 0 && foreign.some((c) => !list.some((r) => r.base === c || r.quote === c)) && (
        <p className="callout warn small"><Icon name="alert" size={14} /> No rate yet for {foreign.filter((c) => !list.some((r) => r.base === c || r.quote === c)).join(', ')}: those accounts are left out of combined totals.</p>
      )}
      {list.length === 0 ? <p className="muted">No rates yet.</p> : (
        <ul className="list">{list.slice(0, 12).map((r) => (
          <li key={r.id} className={can ? 'clickable' : ''} onClick={() => can && setEdit(r)}>
            <div className="grow"><div>1 {r.base} = {r.rate} {r.quote}</div><div className="sub">from {shortDate(r.effective_date)}{r.source === 'imported' ? ' · fetched' : ''}</div></div>
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
          <Field label="Rate"><NumInput kind="decimal" value={value} onChange={(e) => setValue(e.target.value)} placeholder="83.25" autoFocus /></Field>
          <Field label="From date"><DateField value={date} onChange={(e) => setDate(e.target.value)} /></Field>
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
        <Select value={role} onChange={(e) => setRole(e.target.value)} style={{ width: 'auto' }} aria-label="Role"><option value="editor">Can edit</option><option value="viewer">Can view</option></Select>
        <button disabled={busy}>Share</button>
      </form>
    </Card>
  )
}

function Devices() {
  const qc = useQueryClient()
  const [pairing, setPairing] = useState(false)
  const [removing, setRemoving] = useState<Session | 'all' | null>(null)
  const sessions = useQuery({ queryKey: ['sessions'], queryFn: api.sessions })
  const others = (sessions.data ?? []).filter((x) => !x.current)
  const ago = (ms: number) => {
    const d = Math.round((Date.now() - ms) / 864e5)
    return d <= 0 ? 'today' : d === 1 ? 'yesterday' : `${d} days ago`
  }
  return (
    <Card title={<><Icon name="device" size={16} /> Signed-in devices</>} action={DEMO ? undefined : <button className="link" onClick={() => setPairing(true)}><Icon name="plus" size={14} /> Connect a phone</button>}>
      {pairing && <ConnectPhone onClose={() => { setPairing(false); qc.invalidateQueries({ queryKey: ['sessions'] }) }} />}
      <ul className="list">
        {(sessions.data ?? []).sort((a, b) => Number(b.current) - Number(a.current) || b.last_used_at - a.last_used_at).map((s) => (
          <li key={s.id}>
            <span className="acc-icon" aria-hidden><Icon name={/browser/i.test(s.device_name) ? 'monitor' : 'phone'} size={18} /></span>
            <div className="grow"><div>{s.device_name || 'Unknown device'}{s.current && <span className="chip" style={{ marginLeft: 8 }}>this browser</span>}</div>
              <div className="sub">Last used {ago(s.last_used_at)} · signed in {new Date(s.created_at).toLocaleDateString()}</div></div>
            {!s.current && <button className="link danger" onClick={() => setRemoving(s)}>Remove</button>}
          </li>
        ))}
      </ul>
      {others.length > 1 && <button className="danger sm" onClick={() => setRemoving('all')}>Remove all other devices</button>}
      <p className="muted small">Lost or replaced a phone? Remove it here: it stops syncing at once and leaves your account. What was recorded on it stays on that device.</p>
      {removing && <Confirm danger title={removing === 'all' ? `Remove ${others.length} other devices?` : `Remove ${removing.device_name || 'this device'}?`}
        confirm="Remove"
        body={<p>{removing === 'all' ? 'Every device and browser except this one' : 'It'} stops syncing straight away and leaves your account. Data on {removing === 'all' ? 'them stays on them' : 'it stays on it'}, and changes not yet synced there upload if {removing === 'all' ? 'they are' : 'it is'} connected again.</p>}
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          const list = removing === 'all' ? others : [removing]
          for (const d of list) await api.revokeSession(d.id)
          qc.invalidateQueries({ queryKey: ['sessions'] })
          toast(list.length === 1 ? `Removed ${list[0].device_name || 'that device'}` : `Removed ${list.length} devices`)
        }} />}
    </Card>
  )
}

/**
 * Connects the phone app without typing a password on it: a one-time code shown as a QR code
 * (scanned in the app under Sync) and as text. It lasts 5 minutes and works once.
 */
function ConnectPhone({ onClose }: { onClose: () => void }) {
  const [code, setCode] = useState<{ code: string; expires_at: number } | null>(null)
  const [err, setErr] = useState('')
  const [now, setNow] = useState(Date.now())
  const [device, setDevice] = useState<string | null>(null)
  const server = location.origin
  const fresh = async () => {
    setErr(''); setDevice(null)
    try { setCode(await api.pairStart()) } catch (x) { setErr(errorText(x)) }
  }
  useEffect(() => { void fresh() }, [])
  const left = code ? Math.max(0, Math.round((code.expires_at - now) / 1000)) : 0
  const expired = !!code && left === 0 && !device
  // Tick the countdown, and ask every 2 seconds whether the phone has used the code.
  useEffect(() => {
    if (!code || device) return
    const tick = setInterval(() => setNow(Date.now()), 1000)
    const poll = setInterval(async () => {
      try { const s = await api.pairStatus(code.code); if (s.claimed) setDevice(s.device || 'Your phone') } catch { /* try again */ }
    }, 2000)
    return () => { clearInterval(tick); clearInterval(poll) }
  }, [code, device])
  const link = code ? `lekhamint://pair?server=${encodeURIComponent(server)}&code=${code.code}` : ''
  const matrix = useMemo(() => (link ? qrMatrix(link) : null), [link])
  const pretty = code ? `${code.code.slice(0, 5)}-${code.code.slice(5)}` : ''
  return (
    <Modal title="Connect a phone" onClose={onClose} footer={device ? <button className="primary" onClick={onClose}>Done</button> : <button onClick={onClose}>Close</button>}>
      {err && <p className="error">{err}</p>}
      {device ? (
        <div className="pair-done" role="status">
          <span className="pair-tick"><Icon name="check" size={26} /></span>
          <h3>{device} is connected</h3>
          <p className="muted">Choose the book to use on the phone, and it starts syncing. It also appears under Signed-in devices, where you can sign it out.</p>
        </div>
      ) : (
        <>
          <ol className="steps">
            <li>On your Android phone, open LekhaMint.</li>
            <li>Tap <strong>Sign in to server</strong> (or More → Sync), then <strong>Scan code</strong>.</li>
            <li>Point the camera at this code.</li>
          </ol>
          <div className="qr-wrap pair-qr">
            {matrix && !expired ? (
              <svg className="qr" viewBox={`0 0 ${matrix.length + 8} ${matrix.length + 8}`} role="img" aria-label="QR code that connects the phone app" shapeRendering="crispEdges">
                <rect width={matrix.length + 8} height={matrix.length + 8} fill="#fff" /><path d={qrPath(matrix)} fill="#000" />
              </svg>
            ) : <div className="qr qr-empty">{expired ? <button className="primary" onClick={fresh}>Show a new code</button> : <Spinner size={22} />}</div>}
            <div className="qr-side">
              <span className="muted small">No camera? Choose <strong>Enter a code</strong> in the app and type:</span>
              <span className="small">Server</span><code className="secret">{server}</code>
              <span className="small">Code</span><code className="secret pair-code">{pretty || '…'}</code>
              <span className={`small ${expired ? 'error' : 'muted'}`}>{expired ? 'This code has expired.' : code ? `Works once, for ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} more` : ''}</span>
            </div>
          </div>
          <p className="muted small">Anyone who scans this code while it is valid gets into your account, so show it only to your own phone.</p>
        </>
      )}
    </Modal>
  )
}
