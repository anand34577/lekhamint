import { useEffect, useState } from 'react'
import { api, session, type Meta } from '../lib/api'
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

export function Login({ onDone }: { onDone: () => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState(DEMO ? DEMO_EMAIL : '')
  const [password, setPassword] = useState(DEMO ? DEMO_PASSWORD : '')
  const [name, setName] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [meta, setMeta] = useState<Meta | null>(null)
  const sso = meta?.oidc?.enabled ? meta.oidc : null
  useEffect(() => { api.meta().then(setMeta).catch(() => setMeta(null)) }, [])

  // Finish a single sign-on round trip: trade the one-time code for a session.
  useEffect(() => {
    const { code, error } = readSsoHash()
    if (!code && !error) return
    history.replaceState(null, '', location.pathname + location.search + '#/dashboard') // keep the code out of the history
    if (error) { setErr(error); return }
    setBusy(true)
    api.oidcExchange(code).then((r) => { session.start(r.token); onDone() }).catch((x) => setErr(errorText(x))).finally(() => setBusy(false))
  }, []) // eslint-disable-line

  const passwordOff = sso?.only === true
  const signup = meta?.signup_allowed

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
        <form className="auth-card" onSubmit={async (e) => {
          e.preventDefault(); setErr(''); setBusy(true)
          try {
            const r = mode === 'login' ? await api.login(email.trim(), password) : await api.register(email.trim(), password, name.trim())
            session.start(r.token)
            onDone()
          } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
        }}>
          <img className="auth-logo" src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" />
          <h1>{mode === 'login' ? 'Welcome back' : 'Create your account'}</h1>
          <p className="muted">{mode === 'login' ? 'Sign in to your LekhaMint server.' : 'The first account becomes the administrator.'}</p>
          {DEMO && mode === 'login' && (
            <div className="demo-creds">
              <strong>Demo account</strong>
              <span>Email <code>{DEMO_EMAIL}</code></span>
              <span>Password <code>{DEMO_PASSWORD}</code></span>
            </div>
          )}
          {err && <div className="callout warn" role="alert"><Icon name="alert" size={16} /><div>{err}</div></div>}
          {sso && (
            <a className="button sso-btn" href={`/api/v1/auth/oidc/start`}>
              <Icon name="key" size={17} /> Continue with {sso.name || 'single sign-on'}
            </a>
          )}
          {sso && !passwordOff && <div className="divider">or use your email</div>}
          {!passwordOff && <>
            {mode === 'register' && <Field label="Your name"><input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" maxLength={100} placeholder="Asha Rao" /></Field>}
            <Field label="Email"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" autoFocus={!sso} placeholder="you@example.com" /></Field>
            <Field label="Password" hint={mode === 'register' ? 'At least 8 characters' : undefined}>
              <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} required minLength={mode === 'register' ? 8 : 1}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'} placeholder={mode === 'register' ? 'Choose a password' : 'Your password'} />
            </Field>
            <button className="primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? (DEMO ? 'Open the demo' : 'Sign in') : 'Create account'}</button>
            <p className="muted small center" style={{ marginTop: 14 }}>
              {mode === 'login' ? (
                <>New here? <button type="button" className="link" onClick={() => { setMode('register'); setErr('') }}>Create an account</button>
                  {signup === false && <><br />(Only the first user can sign up unless the admin enables sign-ups.)</>}</>
              ) : <>Have an account? <button type="button" className="link" onClick={() => { setMode('login'); setErr('') }}>Sign in</button></>}
            </p>
          </>}
          {passwordOff && busy && <p className="muted small center">Signing you in…</p>}
        </form>
      </section>
    </div>
  )
}
