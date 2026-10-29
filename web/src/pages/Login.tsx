import { useEffect, useState } from 'react'
import { ApiError, api, session, type Meta } from '../lib/api'
import { DEMO, DEMO_EMAIL, DEMO_PASSWORD } from '../lib/demo'
import { Field, errorText } from '../components'
import { PasswordInput } from '../controls'
import { Icon } from '../icons'

/** The identity provider sends the browser back to #/sso?code=… or #/login?sso_error=…. */
function readSsoHash(): { code: string; error: string } {
  const [path, query = ''] = location.hash.slice(2).split('?')
  const q = new URLSearchParams(query)
  if (path === 'sso') return { code: q.get('code') ?? '', error: q.get('code') ? '' : 'The sign-in link was not valid. Try again.' }
  if (path === 'login' && q.get('sso_error')) return { code: '', error: q.get('sso_error') ?? '' }
  return { code: '', error: '' }
}

/** A password reset email links to #/reset?token=…. */
export function readResetToken(): string {
  const [path, query = ''] = location.hash.slice(2).split('?')
  return path === 'reset' ? new URLSearchParams(query).get('token') ?? '' : ''
}

type Mode = 'login' | 'register' | 'otp' | 'forgot' | 'sent' | 'reset' | 'done'

export function Login({ onDone }: { onDone: () => void }) {
  const [resetToken] = useState(readResetToken)
  const [mode, setModeRaw] = useState<Mode>(resetToken ? 'reset' : 'login')
  const [otp, setOtp] = useState('')
  const [notice, setNotice] = useState('')
  const setMode = (m: Mode) => { setModeRaw(m); setErr(''); setNotice('') }
  const [email, setEmail] = useState(DEMO ? DEMO_EMAIL : '')
  const [password, setPassword] = useState(DEMO ? DEMO_PASSWORD : '')
  const [name, setName] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [meta, setMeta] = useState<Meta | null>(null)
  const sso = meta?.oidc?.enabled ? meta.oidc : null
  // A single sign-on code waiting for the two-step code (accounts with two-step sign-in on).
  const [ssoCode, setSsoCode] = useState('')
  useEffect(() => { api.meta().then(setMeta).catch(() => setMeta(null)) }, [])

  // Finish a single sign-on round trip: trade the one-time code for a session.
  useEffect(() => {
    const { code, error } = readSsoHash()
    if (!code && !error) return
    history.replaceState(null, '', location.pathname + location.search + '#/dashboard') // keep the code out of the history
    if (error) { setErr(error); return }
    setBusy(true)
    api.oidcExchange(code).then((r) => { session.start(r.token); onDone() }).catch((x) => {
      if (x instanceof ApiError && x.code === 'otp_required') { setSsoCode(code); setModeRaw('otp'); setOtp(''); return }
      setErr(errorText(x))
    }).finally(() => setBusy(false))
  }, []) // eslint-disable-line

  const passwordOff = sso?.only === true
  const signup = meta?.signup_allowed
  const canReset = meta?.features?.password_reset === true

  async function submit() {
    setErr(''); setBusy(true)
    try {
      if (mode === 'forgot') {
        await api.forgotPassword(email.trim())
        setMode('sent')
        return
      }
      if (mode === 'reset') {
        await api.resetPassword(resetToken, password)
        history.replaceState(null, '', location.pathname + location.search + '#/login') // the link works once
        setPassword(''); setMode('done')
        return
      }
      const r = mode === 'otp' && ssoCode ? await api.oidcExchange(ssoCode, otp.trim())
        : mode === 'register' ? await api.register(email.trim(), password, name.trim())
        : await api.login(email.trim(), password, mode === 'otp' ? otp.trim() : undefined)
      session.start(r.token)
      onDone()
    } catch (x) {
      if (x instanceof ApiError && x.code === 'otp_required') { setModeRaw('otp'); setOtp(''); return }
      setErr(errorText(x))
    } finally { setBusy(false) }
  }

  const title = { login: 'Welcome back', register: 'Create your account', otp: 'Two-step sign-in', forgot: 'Forgot your password?', sent: 'Check your email', reset: 'Choose a new password', done: 'Password changed' }[mode]
  const lead = {
    login: 'Sign in to your LekhaMint server.', register: 'The first account becomes the administrator.',
    otp: 'Open your authenticator app and type the 6-digit code for LekhaMint. Lost your phone? Type one of your recovery codes instead.',
    forgot: 'Type the email you sign in with. We will send a link to choose a new password.',
    sent: `If ${email.trim() || 'that address'} has an account here, a link is on its way. It works for one hour. Check your spam folder if it does not arrive.`,
    reset: 'Pick a password of at least 8 characters. Every device will be signed out, so sign in again afterwards.',
    done: 'Your new password is saved. Sign in with it now.',
  }[mode]

  return (
    <div className="auth">
      <section className="auth-hero" aria-hidden="true">
        <div className="brand"><img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" /><span>LekhaMint</span></div>
        <div>
          <h2>Your money, clear and in your hands.</h2>
          <p>Track spending, plan budgets, and see loans, deposits and net worth in one place, on a server you control.</p>
          <ul className="auth-points">
            <li><Icon name="shield" /><div><b>Private by design</b><span>Your data stays on your own server. No ads, no tracking.</span></div></li>
            <li><Icon name="chart" /><div><b>Everything in one view</b><span>Accounts, budgets, EMIs, deposits and statements together.</span></div></li>
            <li><Icon name="device" /><div><b>Web and Android, in sync</b><span>Work offline on your phone; it syncs when you are back.</span></div></li>
          </ul>
        </div>
        <span className="small" style={{ opacity: 0.6 }}>Open source and self-hosted.</span>
      </section>
      <section className="auth-panel">
        <form className="auth-card" onSubmit={(e) => { e.preventDefault(); void submit() }}>
          <img className="auth-logo" src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" />
          <h1>{title}</h1>
          <p className="muted">{lead}</p>
          {DEMO && mode === 'login' && (
            <div className="demo-creds">
              <strong>Demo account</strong>
              <span>Email <code>{DEMO_EMAIL}</code></span>
              <span>Password <code>{DEMO_PASSWORD}</code></span>
            </div>
          )}
          {err && <div className="callout warn" role="alert"><Icon name="alert" size={16} /><div>{err}</div></div>}
          {notice && <div className="callout" role="status"><Icon name="check" size={16} /><div>{notice}</div></div>}
          {mode === 'otp' && <>
            <Field label="Code from your app">
              <input value={otp} onChange={(e) => setOtp(e.target.value)} autoFocus required inputMode="text" autoComplete="one-time-code" maxLength={20}
                placeholder="123 456" className="otp-input" />
            </Field>
            <button className="primary" disabled={busy || otp.trim().length < 6}>{busy ? 'Checking…' : 'Sign in'}</button>
            <p className="muted small center" style={{ marginTop: 14 }}><button type="button" className="link" onClick={() => { setSsoCode(''); setMode('login') }}>Back</button></p>
          </>}
          {(mode === 'forgot' || mode === 'sent') && <>
            {mode === 'forgot' && <>
              <Field label="Email"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" autoFocus placeholder="you@example.com" /></Field>
              <button className="primary" disabled={busy}>{busy ? 'Sending…' : 'Send reset link'}</button>
            </>}
            <p className="muted small center" style={{ marginTop: 14 }}><button type="button" className="link" onClick={() => setMode('login')}>Back to sign in</button></p>
          </>}
          {mode === 'reset' && <>
            <Field label="New password" hint="At least 8 characters">
              <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} autoComplete="new-password" autoFocus placeholder="Choose a password" />
            </Field>
            <button className="primary" disabled={busy || password.length < 8}>{busy ? 'Saving…' : 'Save new password'}</button>
          </>}
          {mode === 'done' && <button type="button" className="primary" onClick={() => setMode('login')}>Sign in</button>}
          {(mode === 'login' || mode === 'register') && sso && (
            <a className="button sso-btn" href={`/api/v1/auth/oidc/start`}>
              <Icon name="key" size={17} /> Continue with {sso.name || 'single sign-on'}
            </a>
          )}
          {(mode === 'login' || mode === 'register') && sso && !passwordOff && <div className="divider">or use your email</div>}
          {(mode === 'login' || mode === 'register') && !passwordOff && <>
            {mode === 'register' && <Field label="Your name"><input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" maxLength={100} placeholder="Asha Rao" /></Field>}
            <Field label="Email"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" autoFocus={!sso} placeholder="you@example.com" /></Field>
            <Field label="Password" hint={mode === 'register' ? 'At least 8 characters' : undefined}>
              <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} required minLength={mode === 'register' ? 8 : 1}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'} placeholder={mode === 'register' ? 'Choose a password' : 'Your password'} />
            </Field>
            {mode === 'login' && !DEMO && (
              <p className="forgot-row"><button type="button" className="link small" onClick={() => {
                if (canReset) setMode('forgot')
                else setNotice('This server cannot send reset emails. Ask the person who runs it to reset your password (lekhamint user passwd your@email).')
              }}>Forgot password?</button></p>
            )}
            <button className="primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? (DEMO ? 'Open the demo' : 'Sign in') : 'Create account'}</button>
            <p className="muted small center" style={{ marginTop: 14 }}>
              {mode === 'login' ? (
                signup === false
                  ? <>New here? Ask the person who runs this server to add you.</>
                  : <>New here? <button type="button" className="link" onClick={() => { setMode('register'); setErr('') }}>Create an account</button></>
              ) : <>Have an account? <button type="button" className="link" onClick={() => { setMode('login'); setErr('') }}>Sign in</button></>}
            </p>
          </>}
          {passwordOff && busy && <p className="muted small center">Signing you in…</p>}
        </form>
      </section>
    </div>
  )
}
