import { Suspense, lazy, useCallback, useEffect, useMemo, useState } from 'react'
import { useIsFetching, useIsMutating, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, session, setUnauthorizedHandler, type Ledger } from './lib/api'
import { DEMO } from './lib/demo'
import { LedgerContext } from './lib/data'
import { defaultCurrency } from './lib/money'
import { CurrencySelect, ErrorBoundary, Field, HideAmounts, Kbd, Skeleton, Toaster, errorText, toast } from './components'
import { Icon } from './icons'
import { Loading } from './charts'
import { Select } from './controls'
import { Login, readResetToken } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { Transactions, TxForm } from './pages/Transactions'
const Accounts = lazy(() => import('./pages/Accounts').then((m) => ({ default: m.Accounts })))
const Budgets = lazy(() => import('./pages/Budgets').then((m) => ({ default: m.Budgets })))
const Reports = lazy(() => import('./pages/Reports').then((m) => ({ default: m.Reports })))
const Categories = lazy(() => import('./pages/Categories').then((m) => ({ default: m.Categories })))
import { Recurring, useAutoRecurring } from './pages/Recurring'
const Settings = lazy(() => import('./pages/Settings').then((m) => ({ default: m.Settings })))
const Tags = lazy(() => import('./pages/Tags').then((m) => ({ default: m.Tags })))
const Rules = lazy(() => import('./pages/Rules').then((m) => ({ default: m.Rules })))
const Import = lazy(() => import('./pages/Import').then((m) => ({ default: m.Import })))
const Debts = lazy(() => import('./pages/Debts').then((m) => ({ default: m.Debts })))
const Loans = lazy(() => import('./pages/Loans').then((m) => ({ default: m.Loans })))
const Deposits = lazy(() => import('./pages/Deposits').then((m) => ({ default: m.Deposits })))
const Goals = lazy(() => import('./pages/Goals').then((m) => ({ default: m.Goals })))
const YearTax = lazy(() => import('./pages/YearTax').then((m) => ({ default: m.YearTax })))
const Insights = lazy(() => import('./pages/Insights').then((m) => ({ default: m.Insights })))
// Bigger, less used pages load on first visit.
const Tools = lazy(() => import('./pages/Tools').then((m) => ({ default: m.Tools })))
const Statements = lazy(() => import('./pages/Statements').then((m) => ({ default: m.Statements })))
const Assistant = lazy(() => import('./pages/Assistant').then((m) => ({ default: m.Assistant })))
const Print = lazy(() => import('./pages/Print').then((m) => ({ default: m.Print })))
import { CommandPalette, type Command, type Resolved } from './pages/QuickAdd'
import { flushOutbox, outbox } from './lib/outbox'
import { useLiveUpdates } from './lib/live'
import { clearSettings, loadSettings, settingsLoaded, useSetting } from './lib/settings'

// [route, label, icon, nav section]
const ROUTES = [
  ['dashboard', 'Home', 'home', 'main'],
  ['transactions', 'Transactions', 'list', 'main'],
  ['accounts', 'Accounts', 'wallet', 'main'],
  ['budgets', 'Budgets', 'target', 'main'],
  ['reports', 'Reports', 'chart', 'main'],
  ['insights', 'Insights & forecast', 'forecast', 'main'],
  ['recurring', 'Bills & recurring', 'repeat', 'plan'],
  ['goals', 'Goals', 'sprout', 'plan'],
  ['loans', 'Loans & EMIs', 'bank', 'plan'],
  ['deposits', 'Deposits', 'piggy', 'plan'],
  ['debts', 'Lent & borrowed', 'hand', 'plan'],
  ['tools', 'Calculators & tools', 'calc', 'tools'],
  ['assistant', 'Assistant', 'chat', 'tools'],
  ['tax', 'Year & tax', 'percent', 'tools'],
  ['statements', 'Statements & export', 'file', 'tools'],
  ['import', 'Import statement', 'upload', 'tools'],
  ['categories', 'Categories', 'grid', 'organise'],
  ['tags', 'Tags', 'tag', 'organise'],
  ['rules', 'Auto-categorise', 'wand', 'organise'],
  ['settings', 'Settings', 'settings', 'organise'],
] as const
export type Route = (typeof ROUTES)[number][0] | (typeof HIDDEN_ROUTES)[number]
const SECTIONS: [string, string][] = [['main', ''], ['plan', 'Plan & commitments'], ['tools', 'Tools'], ['organise', 'Organise']]
/** Pages reachable by link but not listed in the navigation. */
const HIDDEN_ROUTES = ['print'] as const
const MOBILE_TABS: Route[] = ['dashboard', 'transactions', 'budgets']

export type Params = Record<string, string>

function readHash(): [Route, Params] {
  const [path, query = ''] = location.hash.slice(2).split('?')
  const route = (ROUTES.find(([r]) => r === path)?.[0] ?? HIDDEN_ROUTES.find((r) => r === path) ?? 'dashboard') as Route
  return [route, Object.fromEntries(new URLSearchParams(query))]
}

function useHashRoute(): [Route, Params, (r: Route, p?: Params) => void] {
  const [state, setState] = useState(readHash)
  useEffect(() => {
    const on = () => { setState(readHash()); window.scrollTo({ top: 0 }) }
    addEventListener('hashchange', on)
    return () => removeEventListener('hashchange', on)
  }, [])
  const go = useCallback((r: Route, p?: Params) => {
    const q = p && Object.keys(p).length ? '?' + new URLSearchParams(p) : ''
    location.hash = `/${r}${q}`
  }, [])
  return [state[0], state[1], go]
}

function useOnline() {
  const [on, setOn] = useState(() => navigator.onLine)
  useEffect(() => {
    const up = () => setOn(true), down = () => setOn(false)
    addEventListener('online', up); addEventListener('offline', down)
    return () => { removeEventListener('online', up); removeEventListener('offline', down) }
  }, [])
  return on
}


export function App() {
  // Unknown until the first /auth/me answers: the session lives in an HttpOnly
  // cookie, so the presence of a session cannot be checked synchronously.
  const [authed, setAuthed] = useState<boolean | null>(null)
  const qc = useQueryClient()
  useEffect(() => setUnauthorizedHandler(() => { qc.clear(); session.end(); setAuthed(false) }), [qc])
  useEffect(() => {
    if (DEMO) { setAuthed(false); return } // the demo shows its own login
    api.me().then(() => { session.start(); setAuthed(true) }).catch(() => setAuthed(false))
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  // Settings live on the server (the same in every browser); they load once per sign-in.
  const [settingsReady, setSettingsReady] = useState(settingsLoaded)
  useEffect(() => {
    if (!authed) return
    let live = true
    loadSettings().then(() => { if (live) setSettingsReady(true) })
    return () => { live = false }
  }, [authed])
  const [resetLink, setResetLink] = useState(readResetToken)
  const [theme, setTheme] = useSetting('theme', 'system')
  const [accent, setAccent] = useSetting('accent', 'teal')
  useEffect(() => {
    if (theme === 'system') document.documentElement.removeAttribute('data-theme')
    else document.documentElement.setAttribute('data-theme', theme)
  }, [theme])
  useEffect(() => {
    if (accent === 'teal') document.documentElement.removeAttribute('data-accent')
    else document.documentElement.setAttribute('data-accent', accent)
  }, [accent])

  if (authed === null || (authed && !settingsReady && !resetLink)) return <div className="splash"><img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" className="splash-logo" /><div className="splash-bar" /><span className="muted">Loading…</span></div>
  // A password reset link opens the reset form even in a browser that is still signed in.
  if (!authed || resetLink) return <><Login onDone={() => { if (resetLink) qc.clear(); setResetLink(''); setAuthed(true) }} /><Toaster /></>
  return <Signed theme={theme} setTheme={setTheme} accent={accent} setAccent={setAccent} onSignOut={() => { session.end(); qc.clear(); forgetChats(); outbox.clear(); clearSettings(); setSettingsReady(false); setAuthed(false) }} />
}

/** Saved assistant conversations hold financial details; they do not outlive the session. */
function forgetChats() {
  try { for (const k of Object.keys(localStorage)) if (k.startsWith('kosh.chats.')) localStorage.removeItem(k) } catch { /* ignore */ }
}

/**
 * Changes saved while offline: how many are waiting, sending them when the
 * connection is back (and every 30 seconds while any wait), and any the server refused.
 */
function OfflineQueue({ online }: { online: boolean }) {
  const qc = useQueryClient()
  const [, bump] = useState(0)
  useEffect(() => outbox.subscribe(() => bump((n) => n + 1)), [])
  const waiting = outbox.list().length
  const failed = outbox.failed()
  useEffect(() => {
    if (!online || waiting === 0) return
    const go = async () => {
      const sent = await flushOutbox()
      if (sent > 0) { qc.invalidateQueries(); toast(`Saved ${sent} ${sent === 1 ? 'change' : 'changes'} made while offline`) }
    }
    void go()
    const t = setInterval(go, 30_000)
    return () => clearInterval(t)
  }, [online, waiting, qc])
  return <>
    {!online && <div className="banner warn"><Icon name="alert" size={16} /> You are offline. You can keep adding and editing: changes are kept in this browser and saved when you are back online.{waiting > 0 && ` ${waiting} waiting.`}</div>}
    {online && waiting > 0 && <div className="banner"><Icon name="refresh" size={16} /> Saving {waiting} {waiting === 1 ? 'change' : 'changes'} made while offline…</div>}
    {failed.length > 0 && <div className="banner warn"><Icon name="alert" size={16} />
      <span className="grow">{failed.length} {failed.length === 1 ? 'change' : 'changes'} made offline could not be saved: {failed.slice(0, 3).map((f) => `${f.label} (${f.message})`).join('; ')}</span>
      <button className="link" onClick={() => outbox.clearFailed()}>Dismiss</button></div>}
  </>
}

/**
 * A thin bar under the top bar while anything loads or saves. It appears only
 * after a short delay, so quick requests do not flicker.
 */
function TopProgress() {
  const active = useIsFetching() + useIsMutating() > 0
  const [shown, setShown] = useState(false)
  useEffect(() => {
    if (!active) { setShown(false); return }
    const t = setTimeout(() => setShown(true), 180)
    return () => clearTimeout(t)
  }, [active])
  return <div className={`top-progress ${shown ? 'on' : ''}`} role="progressbar" aria-hidden={!shown} aria-label="Loading" />
}

function Signed({ theme, setTheme, accent, setAccent, onSignOut }: { theme: string; setTheme: (t: string) => void; accent: string; setAccent: (a: string) => void; onSignOut: () => void }) {
  const ledgers = useQuery({ queryKey: ['ledgers'], queryFn: api.ledgers })
  const [ledgerId, setLedgerId] = useSetting('ledger', '')
  const [hidden, setHidden] = useSetting('hide_amounts', false)
  const [creating, setCreating] = useState(false)
  const [route, params, go] = useHashRoute()

  if (ledgers.isPending) return <div className="splash"><img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" className="splash-logo" /><div className="splash-bar" /><span className="muted">Loading your books…</span></div>
  if (ledgers.isError) return <div className="splash"><p>{errorText(ledgers.error)}</p><button className="primary" onClick={() => ledgers.refetch()}>Retry</button></div>
  const list = ledgers.data
  const created = (l: Ledger) => { setLedgerId(l.id); setCreating(false); ledgers.refetch(); go('dashboard') }
  if (list.length === 0 || creating) return <CreateLedger first={list.length === 0} onCreated={created} onCancel={list.length ? () => setCreating(false) : undefined} />
  const ledger = list.find((l) => l.id === ledgerId) ?? list[0]
  const pick = (id: string) => { if (id === '__new') { setCreating(true); return } setLedgerId(id) }
  const toggleHidden = () => setHidden(!hidden)

  return (
    <LedgerContext.Provider value={ledger}>
      <HideAmounts.Provider value={hidden}>
        <Shell key={ledger.id} ledger={ledger} ledgers={list} pick={pick} route={route} params={params} go={go} hidden={hidden} toggleHidden={toggleHidden}
          theme={theme} setTheme={setTheme} accent={accent} setAccent={setAccent} onSignOut={onSignOut} />
        <Toaster />
      </HideAmounts.Provider>
    </LedgerContext.Provider>
  )
}

interface ShellProps {
  ledger: Ledger; ledgers: Ledger[]; pick: (id: string) => void; route: Route; params: Params; go: (r: Route, p?: Params) => void
  hidden: boolean; toggleHidden: () => void; theme: string; setTheme: (t: string) => void; accent: string; setAccent: (a: string) => void; onSignOut: () => void
}

function Shell({ ledger, ledgers, pick, route, params, go, hidden, toggleHidden, theme, setTheme, accent, setAccent, onSignOut }: ShellProps) {
  const [menu, setMenu] = useState(false)
  const [rail, setRail] = useSetting('nav_rail', false)
  const toggleRail = () => setRail(!rail)
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, staleTime: 5 * 60_000 })
  // The toggle shows the mode you would switch to; "system" follows the device until a choice is made.
  const dark = theme === 'dark' || theme === 'black' || (theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  const [palette, setPalette] = useState(false)
  const [adding, setAdding] = useState<{ draft: Resolved | null } | null>(null)
  const [help, setHelp] = useState(false)
  useAutoRecurring()
  useLiveUpdates(ledger.id, ledger.seq)
  const canWrite = ledger.role !== 'viewer'
  const online = useOnline()
  // The installed app's “Add transaction” shortcut opens #/…?add=1.
  useEffect(() => { if (params.add === '1' && canWrite) { setAdding({ draft: null }); go(route) } }, [params.add]) // eslint-disable-line

  // Shortcuts: Ctrl/⌘ K palette, N new transaction, / search, G then a letter to jump, ? help.
  useEffect(() => {
    let g = false, gTimer = 0
    const on = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement
      const typing = el.closest('input, textarea, select, [contenteditable="true"]') || document.querySelector('dialog[open]')
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalette((p) => !p); return }
      if (typing || e.ctrlKey || e.metaKey || e.altKey) return
      if (g) {
        g = false
        const to = ({ h: 'dashboard', t: 'transactions', b: 'budgets', a: 'accounts', r: 'reports', s: 'settings', c: 'categories' } as Record<string, Route>)[e.key]
        if (to) { e.preventDefault(); go(to) }
        return
      }
      if (e.key === 'n' && canWrite) { e.preventDefault(); setAdding({ draft: null }) }
      else if (e.key === '/') { e.preventDefault(); go('transactions', { focus: 'search' }) }
      else if (e.key === 'g') { g = true; clearTimeout(gTimer); gTimer = window.setTimeout(() => { g = false }, 1200) }
      else if (e.key === '?') { e.preventDefault(); setHelp(true) }
      else if (e.key === 'h' && e.shiftKey) toggleHidden()
    }
    addEventListener('keydown', on)
    return () => removeEventListener('keydown', on)
  }, [go, canWrite, toggleHidden])

  const commands: Command[] = useMemo(() => [
    ...(canWrite ? [{ id: 'add', label: 'New transaction', hint: 'N', icon: 'plus', run: () => { setPalette(false); setAdding({ draft: null }) } }] : []),
    ...ROUTES.map(([r, label, icon]) => ({ id: 'go-' + r, label: `Go to ${label}`, icon, run: () => { setPalette(false); go(r) } })),
    { id: 'uncat', label: 'Review uncategorised transactions', icon: 'inbox', keywords: 'triage categorise', run: () => { setPalette(false); go('transactions', { category: 'none', all: '1' }) } },
    { id: 'hide', label: hidden ? 'Show amounts' : 'Hide amounts', hint: 'Shift+H', icon: hidden ? 'eye' : 'eyeOff', keywords: 'privacy', run: () => { setPalette(false); toggleHidden() } },
    { id: 'theme', label: `Switch to ${dark ? 'light' : 'dark'} theme`, icon: dark ? 'sun' : 'moon', keywords: 'dark light appearance', run: () => { setPalette(false); setTheme(dark ? 'light' : 'dark') } },
    ...ledgers.filter((l) => l.id !== ledger.id).map((l) => ({ id: 'book-' + l.id, label: `Switch to book “${l.name}”`, icon: 'wallet', run: () => { setPalette(false); pick(l.id) } })),
    { id: 'keys', label: 'Keyboard shortcuts', hint: '?', icon: 'keyboard', run: () => { setPalette(false); setHelp(true) } },
    { id: 'out', label: 'Sign out', icon: 'logout', run: () => { api.logout().catch(() => {}); onSignOut() } },
  ], [canWrite, go, hidden, theme, dark, ledgers, ledger.id]) // eslint-disable-line

  const props: PageProps = { go, params, theme, setTheme, accent, setAccent, onSignOut, openAdd: (draft) => setAdding({ draft: draft ?? null }) }
  const Page = { dashboard: Dashboard, transactions: Transactions, budgets: Budgets, accounts: Accounts, reports: Reports, recurring: Recurring,
    categories: Categories, settings: Settings, tags: Tags, rules: Rules, import: Import, debts: Debts, loans: Loans, deposits: Deposits, goals: Goals, insights: Insights, tax: YearTax,
    tools: Tools, statements: Statements, assistant: Assistant, print: Print }[route]
  const label = ROUTES.find(([r]) => r === route)?.[1] ?? (route === 'print' ? 'Report' : '')
  const initial = (me.data?.user.name || me.data?.user.email || '?').trim()[0]?.toUpperCase()
  useEffect(() => { document.title = `${label} · LekhaMint` }, [label])

  if (route === 'print') return <ErrorBoundary resetKey={route}><Suspense fallback={<div className="splash"><Loading text="Preparing the report…" /></div>}><Print {...props} /></Suspense></ErrorBoundary>
  return (
    <div className={`shell ${rail ? 'collapsed' : ''}`}>
      <aside className={`side ${menu ? 'open' : ''}`} aria-label="Main">
        <div className="brand"><img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" /> <span>LekhaMint</span></div>
        <div className="ledger-pick-wrap">
          <Select className="ledger-pick" value={ledger.id} onChange={(e) => { setMenu(false); pick(e.target.value) }} aria-label="Book">
            {ledgers.map((l) => <option key={l.id} value={l.id}>{l.name}{l.role && l.role !== 'owner' ? ` (${l.role})` : ''}</option>)}
            <option value="__new">+ New book…</option>
          </Select>
        </div>
        <button className="search-btn" onClick={() => { setMenu(false); setPalette(true) }} title="Search or add"><Icon name="search" size={16} /><span>Search or add…</span><Kbd>Ctrl K</Kbd></button>
        <nav>
          {SECTIONS.map(([sec, title]) => (
            <div key={sec} className="nav-sec">
              {title && <div className="nav-title">{title}</div>}
              {ROUTES.filter((x) => x[3] === sec).map(([r, text, icon]) => (
                <a key={r} href={`#/${r}`} className={route === r ? 'active' : ''} onClick={() => setMenu(false)} aria-current={route === r ? 'page' : undefined} title={rail ? text : undefined}>
                  <Icon name={icon} size={19} /><span>{text}</span>
                </a>
              ))}
            </div>
          ))}
        </nav>
        <div className="side-foot">
          <button className="user-chip" onClick={() => { setMenu(false); go('settings') }} title="Account and settings">
            <span className="me-avatar" aria-hidden>{initial}</span>
            <span className="who"><strong>{me.data?.user.name || 'Your account'}</strong><span>{me.data?.user.email}</span></span>
          </button>
        </div>
      </aside>
      {menu && <div className="scrim" onClick={() => setMenu(false)} />}
      <div className="content">
        <header className="topbar">
          <button className="icon-btn tb-menu" onClick={() => setMenu(!menu)} aria-label="Menu"><Icon name="menu" /></button>
          <button className="icon-btn tb-collapse" onClick={toggleRail} aria-label={rail ? 'Expand sidebar' : 'Collapse sidebar'} title={rail ? 'Expand sidebar' : 'Collapse sidebar'}><Icon name="panel" /></button>
          <span className="topbar-title">{label}</span>
          <button className="tb-search" onClick={() => setPalette(true)}><Icon name="search" size={16} /><span>Search, or type “250 lunch” to add…</span><Kbd>Ctrl K</Kbd></button>
          <span className="spacer" />
          <button className="icon-btn tb-mobile" onClick={() => setPalette(true)} aria-label="Search"><Icon name="search" /></button>
          <button className="icon-btn" onClick={() => setTheme(dark ? 'light' : 'dark')} aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'} title={dark ? 'Light theme' : 'Dark theme'}><Icon name={dark ? 'sun' : 'moon'} /></button>
          <button className="icon-btn" onClick={toggleHidden} aria-label={hidden ? 'Show amounts' : 'Hide amounts'} title={`${hidden ? 'Show' : 'Hide'} amounts (Shift+H)`}><Icon name={hidden ? 'eye' : 'eyeOff'} /></button>
          {canWrite && <button className="primary tb-wide" onClick={() => setAdding({ draft: null })}><Icon name="plus" size={17} /> New</button>}
          <TopProgress />
        </header>
        <main className={`main ${route === 'assistant' ? 'main-chat' : ''}`} id="main">
          <OfflineQueue online={online} />
          {ledger.role === 'viewer' && <div className="banner"><Icon name="eye" size={16} /> You can view this shared book but not change it.</div>}
          <ErrorBoundary resetKey={route}><Suspense fallback={<div className="card pad"><Loading text={`Opening ${label.toLowerCase()}…`} /><Skeleton rows={3} /></div>}><Page {...props} /></Suspense></ErrorBoundary>
        </main>
      </div>
      <nav className="tabbar" aria-label="Quick navigation">
        {MOBILE_TABS.slice(0, 2).map((r) => <TabLink key={r} r={r} route={route} />)}
        {canWrite ? <button className="fab" onClick={() => setAdding({ draft: null })} aria-label="New transaction"><Icon name="plus" size={24} /></button> : <span />}
        {MOBILE_TABS.slice(2).map((r) => <TabLink key={r} r={r} route={route} />)}
        {/* Everything else (accounts, reports, plans, settings) is one tap away in the full menu. */}
        <button className={`tab-more ${menu || !MOBILE_TABS.includes(route) ? 'active' : ''}`} onClick={() => setMenu(true)} aria-label="More pages"><Icon name="grid" size={20} /><span>More</span></button>
      </nav>
      {palette && <CommandPalette commands={commands} onClose={() => setPalette(false)} onAddForm={(r) => setAdding({ draft: r })} onSearch={(q) => go('transactions', { q })} />}
      {adding && <TxForm draft={adding.draft ?? undefined} onClose={() => setAdding(null)} />}
      {help && <ShortcutHelp onClose={() => setHelp(false)} />}
    </div>
  )
}

function TabLink({ r, route }: { r: Route; route: Route }) {
  const [, label, icon] = ROUTES.find(([x]) => x === r)!
  return <a href={`#/${r}`} className={route === r ? 'active' : ''} aria-current={route === r ? 'page' : undefined}><Icon name={icon} size={20} /><span>{label}</span></a>
}

function ShortcutHelp({ onClose }: { onClose: () => void }) {
  const rows: [string, string][] = [['Ctrl/⌘ K', 'Search, jump, or type “250 lunch” to add'], ['N', 'New transaction'], ['/', 'Search transactions'],
    ['G then H, T, B, A, R, C, S', 'Go to Home, Transactions, Budgets, Accounts, Reports, Categories, Settings'], ['Shift H', 'Hide or show amounts'], ['?', 'This help']]
  return (
    <div className="scrim center" onClick={onClose}>
      <div className="card help" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Keyboard shortcuts">
        <header className="card-head"><h2>Keyboard shortcuts</h2><button className="icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button></header>
        <ul className="list">{rows.map(([k, v]) => <li key={k}><span className="grow">{v}</span><Kbd>{k}</Kbd></li>)}</ul>
      </div>
    </div>
  )
}

export interface PageProps {
  go: (r: Route, p?: Params) => void
  params: Params
  theme: string
  setTheme: (t: string) => void
  accent: string
  setAccent: (a: string) => void
  onSignOut: () => void
  /** Opens the transaction form, optionally prefilled from quick add. */
  openAdd: (draft?: Resolved | null) => void
}

function CreateLedger({ first, onCreated, onCancel }: { first: boolean; onCreated: (l: Ledger) => void; onCancel?: () => void }) {
  const [name, setName] = useState(first ? 'Personal' : '')
  const [cur, setCur] = useState(defaultCurrency())
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <div className="auth">
      <form className="auth-card" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true)
        try { onCreated(await api.createLedger(name.trim(), cur)) } catch (x) { setErr(errorText(x)) } finally { setBusy(false) }
      }}>
        <img className="auth-logo" src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" />
        <h1>{first ? 'Create your first book' : 'New book'}</h1>
        <p className="muted">A book holds accounts, categories and transactions{first ? ', for example Personal or Household' : ''}. We add common categories for you.</p>
        <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} required autoFocus placeholder="Household, Business…" /></Field>
        <Field label="Main currency"><CurrencySelect value={cur} onChange={setCur} /></Field>
        {err && <p className="error">{err}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Creating…' : 'Create'}</button>
        {onCancel && <button type="button" className="ghost wide" onClick={onCancel}>Cancel</button>}
        {first && <p className="muted small">Already use LekhaMint on Android? Connect the app to this server under More → Sync and upload your book; it will appear here.</p>}
      </form>
    </div>
  )
}
