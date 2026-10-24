import { useEffect, useState } from 'react'
import { api, session } from '../lib/api'
import { DEMO, DEMO_EMAIL, DEMO_PASSWORD } from '../lib/demo'
import { Field, errorText } from '../components'

export function Login({ onDone }: { onDone: () => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState(DEMO ? DEMO_EMAIL : '')
  const [password, setPassword] = useState(DEMO ? DEMO_PASSWORD : '')
  const [name, setName] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [signup, setSignup] = useState<boolean | null>(null)
  useEffect(() => { api.meta().then((m) => setSignup(m.signup_allowed)).catch(() => setSignup(null)) }, [])

  return (
    <div className="auth">
      <form className="auth-card" onSubmit={async (e) => {
        e.preventDefault(); setErr(''); setBusy(true)
        try {
          const r = mode === 'login' ? await api.login(email.trim(), password) : await api.register(email.trim(), password, name.trim())
          session.token = r.token
          onDone()
        } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
      }}>
        <img className="auth-logo" src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" />
        <h1>{mode === 'login' ? 'Sign in to LekhaMint' : 'Create your account'}</h1>
        <p className="muted">Your self-hosted money manager. Your data stays on this server.</p>
        {DEMO && mode === 'login' && (
          <div className="demo-creds">
            <strong>Demo account</strong>
            <span>Email <code>{DEMO_EMAIL}</code></span>
            <span>Password <code>{DEMO_PASSWORD}</code></span>
          </div>
        )}
        {mode === 'register' && <Field label="Your name"><input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" /></Field>}
        <Field label="Email"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" autoFocus /></Field>
        <Field label="Password" hint={mode === 'register' ? 'At least 8 characters' : undefined}>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={mode === 'register' ? 8 : 1}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
        </Field>
        {err && <p className="error" role="alert">{err}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? (DEMO ? 'Open the demo' : 'Sign in') : 'Create account'}</button>
        <p className="muted small" style={{ textAlign: 'center' }}>
          {mode === 'login' ? (
            <>New here? <button type="button" className="link" onClick={() => setMode('register')}>Create an account</button>
              {signup === false && <><br />(Only the first user can sign up unless the admin enables sign-ups.)</>}</>
          ) : <>Have an account? <button type="button" className="link" onClick={() => setMode('login')}>Sign in</button></>}
        </p>
      </form>
    </div>
  )
}
