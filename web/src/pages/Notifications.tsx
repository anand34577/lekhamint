import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import { Card, Field, Skeleton, errorText, toast } from '../components'
import { PasswordInput, Switch } from '../controls'
import { Icon } from '../icons'

/** Email and Gotify messages: security alerts, bills due and budget limits. Hidden when the server has neither set up. */
export function Notifications() {
  const qc = useQueryClient()
  const meta = useQuery({ queryKey: ['meta'], queryFn: api.meta, staleTime: 5 * 60_000 })
  const channels = meta.data?.notify
  const enabled = !!(channels?.email || channels?.gotify)
  const prefs = useQuery({ queryKey: ['notify-prefs'], queryFn: api.notifications, enabled })
  const [token, setToken] = useState('')
  const [busy, setBusy] = useState('')
  if (!channels || !enabled) return null
  const p = prefs.data

  const save = async (patch: Parameters<typeof api.saveNotifications>[0], ok = 'Saved') => {
    setBusy('save')
    try { qc.setQueryData(['notify-prefs'], await api.saveNotifications(patch)); toast(ok) } catch (x) { toast(errorText(x)) } finally { setBusy('') }
  }
  const test = async (channel: 'email' | 'gotify') => {
    setBusy(channel)
    try {
      await api.testNotification(channel, channel === 'gotify' ? token.trim() : undefined)
      toast(channel === 'email' ? 'Test email sent. Check your inbox.' : 'Test message sent to Gotify.')
    } catch (x) { toast(errorText(x)) } finally { setBusy('') }
  }

  return (
    <Card title={<><Icon name="bell" size={16} /> Notifications</>}>
      <p className="muted small">Get a message when someone signs in to your account, a bill is due in the next 3 days, or a budget reaches its warning level or limit. Reminders go out once a day.</p>
      {prefs.isPending || !p ? <Skeleton rows={3} /> : <>
        {channels.email && <>
          <div className="section-title" style={{ marginTop: 6 }}>Email</div>
          <Switch checked={p.notify_email} disabled={!!busy} onChange={(v) => save({ notify_email: v }, v ? 'Email notifications on' : 'Email notifications off')} hint="Sent to the address you sign in with">Send me email</Switch>
          {p.notify_email && <div className="row"><button type="button" className="sm" disabled={!!busy} onClick={() => test('email')}><Icon name="send" size={15} /> {busy === 'email' ? 'Sending…' : 'Send a test email'}</button></div>}
        </>}
        {channels.gotify && <>
          <div className="section-title">Gotify{channels.gotify_host ? ` · ${channels.gotify_host}` : ''}</div>
          <Switch checked={p.notify_gotify} disabled={!!busy || (!p.gotify_token_set && !token.trim())}
            onChange={(v) => save({ notify_gotify: v, ...(token.trim() ? { gotify_token: token.trim() } : {}) }, v ? 'Gotify notifications on' : 'Gotify notifications off')}
            hint={p.gotify_token_set ? 'Your application token is saved' : 'Add your application token below first'}>Push to Gotify</Switch>
          <Field label={p.gotify_token_set ? 'Replace application token' : 'Application token'} hint="Create an application in Gotify and paste its token here. It is stored on the server and never shown again.">
            <PasswordInput value={token} onChange={(e) => setToken(e.target.value)} placeholder={p.gotify_token_set ? '•••••••• (saved)' : 'Paste the token'} autoComplete="off" maxLength={200} />
          </Field>
          <div className="row">
            <button type="button" className="sm" disabled={!!busy || !token.trim()} onClick={async () => { await save({ gotify_token: token.trim() }, 'Token saved'); setToken('') }}>Save token</button>
            <button type="button" className="sm" disabled={!!busy || (!p.gotify_token_set && !token.trim())} onClick={() => test('gotify')}><Icon name="send" size={15} /> {busy === 'gotify' ? 'Sending…' : 'Send a test message'}</button>
            {p.gotify_token_set && <button type="button" className="sm danger" disabled={!!busy} onClick={() => save({ gotify_token: '', notify_gotify: false }, 'Token removed')}>Remove token</button>}
          </div>
        </>}
        <div className="section-title">Tell me about</div>
        <Switch checked={p.ev_security} disabled={!!busy} onChange={(v) => save({ ev_security: v })} hint="New sign-ins and password changes">Security alerts</Switch>
        <Switch checked={p.ev_due} disabled={!!busy} onChange={(v) => save({ ev_due: v })} hint="Bills and recurring payments due within 3 days">Bills due</Switch>
        <Switch checked={p.ev_budget} disabled={!!busy} onChange={(v) => save({ ev_budget: v })} hint="When a budget reaches its warning level or limit">Budget limits</Switch>
        <Switch checked={p.ev_digest ?? true} disabled={!!busy} onChange={(v) => save({ ev_digest: v })} hint="Every Monday: last week's spending against your usual week, biggest categories and what is due">Weekly summary</Switch>
      </>}
    </Card>
  )
}
