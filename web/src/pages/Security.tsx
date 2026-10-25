import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { qrMatrix, qrPath } from '../lib/qr'
import { download } from '../lib/util'
import { Card, Field, Modal, errorText, toast } from '../components'
import { PasswordInput } from '../controls'
import { Spinner } from '../charts'
import { Icon } from '../icons'

/**
 * Two-step sign-in: after the password, signing in also needs the 6-digit code
 * an authenticator app shows. Recovery codes stand in when the phone is lost.
 */
export function TwoStep() {
  const qc = useQueryClient()
  const st = useQuery({ queryKey: ['two-step'], queryFn: api.twoStep })
  const [dialog, setDialog] = useState<'setup' | 'off' | 'codes' | null>(null)
  const [codes, setCodes] = useState<string[] | null>(null)
  const done = () => { setDialog(null); qc.invalidateQueries({ queryKey: ['two-step'] }); qc.invalidateQueries({ queryKey: ['me'] }) }
  const on = st.data?.enabled
  return (
    <Card title={<><Icon name="shield" size={16} /> Two-step sign-in</>}>
      {st.isPending ? <p className="muted"><Spinner size={14} /> Checking…</p> : on ? <>
        <p className="row nowrap"><span className="chip good"><Icon name="check" size={13} /> On</span>
          <span className="muted small">Signing in needs your password and a code from your authenticator app.</span></p>
        <p className="muted small">{st.data!.recovery_codes_left} of 10 recovery codes left.{st.data!.recovery_codes_left <= 3 && ' Make new ones soon.'}</p>
        <div className="row">
          <button onClick={() => setDialog('codes')}><Icon name="key" size={15} /> New recovery codes</button>
          <button className="danger" onClick={() => setDialog('off')}>Turn off…</button>
        </div>
      </> : <>
        <p className="muted small">Protect your money records even if someone learns your password: signing in will also need the 6-digit code from an
          authenticator app on your phone (Google Authenticator, Microsoft Authenticator, Aegis, 1Password and others work).</p>
        <button className="primary" onClick={() => setDialog('setup')}><Icon name="shield" size={15} /> Turn on two-step sign-in</button>
      </>}
      {dialog === 'setup' && <Setup onClose={() => setDialog(null)} onDone={(c) => { setCodes(c); done() }} />}
      {dialog === 'off' && <TurnOff onClose={() => setDialog(null)} onDone={() => { toast('Two-step sign-in is off'); done() }} />}
      {dialog === 'codes' && <NewCodes onClose={() => setDialog(null)} onDone={(c) => { setCodes(c); done() }} />}
      {codes && <RecoveryCodes codes={codes} onClose={() => setCodes(null)} />}
    </Card>
  )
}

function Setup({ onClose, onDone }: { onClose: () => void; onDone: (codes: string[]) => void }) {
  const setup = useQuery({ queryKey: ['two-step-setup'], queryFn: api.twoStepSetup, staleTime: Infinity, gcTime: 0, retry: false })
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const qr = useMemo(() => (setup.data ? qrPath(qrMatrix(setup.data.otpauth_url)) : ''), [setup.data])
  const size = setup.data ? qrMatrix(setup.data.otpauth_url).length + 8 : 0
  const secret = setup.data?.secret.replace(/(.{4})/g, '$1 ').trim()
  return (
    <Modal title="Turn on two-step sign-in" onClose={onClose}
      footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="tsform" disabled={busy || code.replace(/\D/g, '').length !== 6}>{busy ? <Spinner size={15} /> : null} Turn on</button></>}>
      {setup.isPending && <p className="muted"><Spinner size={14} /> Preparing…</p>}
      {setup.isError && <p className="error">{errorText(setup.error)}</p>}
      {setup.data && <form id="tsform" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setErr('')
        try { const r = await api.twoStepEnable(code); onDone(r.recovery_codes) } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
      }}>
        <ol className="steps">
          <li><strong>Add LekhaMint to your authenticator app.</strong> Scan this code with the app, or type the key below.
            <div className="qr-wrap">
              <svg className="qr" viewBox={`0 0 ${size} ${size}`} role="img" aria-label="QR code for your authenticator app" shapeRendering="crispEdges">
                <rect width={size} height={size} fill="#fff" /><path d={qr} fill="#000" />
              </svg>
              <div className="qr-side">
                <span className="muted small">Key (time-based)</span>
                <code className="secret">{secret}</code>
                <button type="button" className="link small" onClick={() => { navigator.clipboard?.writeText(setup.data!.secret); toast('Key copied') }}>Copy key</button>
                <a className="link small only-phone" href={setup.data.otpauth_url}>Open in authenticator app</a>
              </div>
            </div>
          </li>
          <li><strong>Type the 6-digit code the app shows</strong> to check it works.
            <Field label="Code"><input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={7} placeholder="123456" className="otp-input" autoFocus /></Field>
          </li>
        </ol>
        {err && <p className="error" role="alert">{err}</p>}
        <p className="muted small">Your other devices will be signed out; sign in on them again with a code.</p>
      </form>}
    </Modal>
  )
}

function TurnOff({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  return (
    <Modal title="Turn off two-step sign-in?" onClose={onClose}
      footer={<><button type="button" onClick={onClose}>Cancel</button><button className="danger" form="tsoff" disabled={busy || !password || !code.trim()}>Turn off</button></>}>
      <form id="tsoff" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setErr('')
        try { await api.twoStepDisable(password, code.trim()); onDone() } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
      }}>
        <p className="muted small">Your account will be protected by your password alone.</p>
        <Field label="Password" hint="Signed in through single sign-on? Type your email instead."><PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" autoFocus /></Field>
        <Field label="Code from your app, or a recovery code"><input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" maxLength={20} className="otp-input" /></Field>
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

function NewCodes({ onClose, onDone }: { onClose: () => void; onDone: (codes: string[]) => void }) {
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  return (
    <Modal title="Make new recovery codes" onClose={onClose}
      footer={<><button type="button" onClick={onClose}>Cancel</button><button className="primary" form="tscodes" disabled={busy || !code.trim()}>Make new codes</button></>}>
      <form id="tscodes" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setErr('')
        try { onDone((await api.twoStepNewCodes(code.trim())).recovery_codes) } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
      }}>
        <p className="muted small">Your old recovery codes stop working.</p>
        <Field label="Code from your app"><input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={20} className="otp-input" autoFocus /></Field>
        {err && <p className="error" role="alert">{err}</p>}
      </form>
    </Modal>
  )
}

/** Shown once: the codes are not stored readable anywhere. */
function RecoveryCodes({ codes, onClose }: { codes: string[]; onClose: () => void }) {
  const [saved, setSaved] = useState(false)
  const text = `LekhaMint recovery codes (each works once)\n${location.host}\n\n${codes.join('\n')}\n`
  return (
    <Modal title="Save your recovery codes" onClose={() => saved && onClose()}
      footer={<button className="primary" disabled={!saved} onClick={onClose}>Done</button>}>
      <p>If you lose your phone, sign in with one of these codes instead of the 6-digit code. Each works once. Keep them somewhere safe, like a password manager or a printed page.
        <strong> This is the only time they are shown.</strong></p>
      <ul className="recovery-codes">{codes.map((c) => <li key={c}><code>{c}</code></li>)}</ul>
      <div className="row">
        <button onClick={() => { navigator.clipboard?.writeText(text); toast('Codes copied'); setSaved(true) }}><Icon name="copy" size={15} /> Copy</button>
        <button onClick={() => { download('lekhamint-recovery-codes.txt', new Blob([text], { type: 'text/plain' })); setSaved(true) }}><Icon name="download" size={15} /> Download</button>
      </div>
      <label className="check" style={{ marginTop: 12 }}><input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} /><span>I have saved these codes</span></label>
    </Modal>
  )
}
