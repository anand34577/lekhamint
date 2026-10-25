import { Suspense, lazy, useCallback, useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, session, setUnauthorizedHandler, type Ledger } from './lib/api'
import { LedgerContext } from './lib/data'
import { defaultCurrency } from './lib/money'
import { CurrencySelect, ErrorBoundary, Field, HideAmounts, Kbd, Skeleton, Toaster, errorText } from './components'
import { Icon } from './icons'
import { Select } from './controls'
import { Login } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { Transactions, TxForm } from './pages/Transactions'
import { Accounts } from './pages/Accounts'
import { Budgets } from './pages/Budgets'
import { Reports } from './pages/Reports'
import { Categories } from './pages/Categories'
import { Recurring, useAutoRecurring } from './pages/Recurring'
import { Settings } from './pages/Settings'
import { Tags } from './pages/Tags'
import { Rules } from './pages/Rules'
import { Import } from './pages/Import'
import { Debts } from './pages/Debts'
import { Loans } from './pages/Loans'
import { Deposits } from './pages/Deposits'
// Bigger, less used pages load on first visit.
const Tools = lazy(() => import('./pages/Tools').then((m) => ({ default: m.Tools })))
const Statements = lazy(() => import('./pages/Statements').then((m) => ({ default: m.Statements })))
const Assistant = lazy(() => import('./pages/Assistant').then((m) => ({ default: m.Assistant })))
const Print = lazy(() => import('./pages/Print').then((m) => ({ default: m.Print })))
import { CommandPalette, type Command, type Resolved } from './pages/QuickAdd'

// [route, label, icon, nav section]
const ROUTES = [
  ['dashboard', 'Home', 'home', 'main'],
  ['transactions', 'Transactions', 'list', 'main'],
  ['accounts', 'Accounts', 'wallet', 'main'],
  ['budgets', 'Budgets', 'target', 'main'],
  ['reports', 'Reports', 'chart', 'main'],
  ['recurring', 'Bills & recurring', 'repeat', 'plan'],
  ['loans', 'Loans & EMIs', 'bank', 'plan'],
  ['deposits', 'Deposits', 'piggy', 'plan'],
  ['debts', 'Lent & borrowed', 'hand', 'plan'],
  ['tools', 'Calculators & tools', 'calc', 'tools'],
  ['assistant', 'Assistant', 'chat', 'tools'],
  ['statements', 'Statements & export', 'file', 'tools'],
  ['import', 'Import statement', 'upload', 'tools'],
  ['categories', 'Categories', 'grid', 'organise'],
  ['tags', 'Tags', 'tag', 'organise'],
  ['rules', 'Auto-categorise', 'wand', 'organise'],
  ['settings', 'Settings', 'settings', 'organise'],
] as const
export type Route = (typeof ROUTES)[number][0] | (typeof HIDDEN_ROUTES)[number]
const SECTIONS: [string, string][] = [['main', ''], ['plan', 'Commitments'], ['tools', 'Tools'], ['organise', 'Organise']]
/** Pages reachable by link but not listed in the navigation. */
const HIDDEN_ROUTES = ['print'] as const
const MOBILE_TABS: Route[] = ['dashboard', 'transactions', 'budgets', 'reports']

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

function pref(key: string, fallback: string) { try { return localStorage.getItem(key) ?? fallback } catch { return fallback } }
function setPref(key: string, v: string) { try { localStorage.setItem(key, v) } catch { /* ignore */ } }

export function App() {
  const [authed, setAuthed] = useState(() => !!session.token)
  const qc = useQueryClient()
  useEffect(() => setUnauthorizedHandler(() => { qc.clear(); setAuthed(false) }), [qc])
  const [theme, setTheme] = useState(() => pref('kosh.theme', 'system'))
  const [accent, setAccent] = useState(() => pref('kosh.accent', 'teal'))
  useEffect(() => {
    if (theme === 'system') document.documentElement.removeAttribute('data-theme')
    else document.documentElement.setAttribute('data-theme', theme)
    setPref('kosh.theme', theme)
  }, [theme])
  useEffect(() => {
    if (accent === 'teal') document.documentElement.removeAttribute('data-accent')
    else document.documentElement.setAttribute('data-accent', accent)
    setPref('kosh.accent', accent)
  }, [accent])

  if (!authed) return <><Login onDone={() => setAuthed(true)} /><Toaster /></>
  return <Signed theme={theme} setTheme={setTheme} accent={accent} setAccent={setAccent} onSignOut={() => { session.token = null; qc.clear(); setAuthed(false) }} />
}

function Signed({ theme, setTheme, accent, setAccent, onSignOut }: { theme: string; setTheme: (t: string) => void; accent: string; setAccent: (a: string) => void; onSignOut: () => void }) {
  const ledgers = useQuery({ queryKey: ['ledgers'], queryFn: api.ledgers })
  const [ledgerId, setLedgerId] = useState(() => pref('kosh.ledger', ''))
  const [hidden, setHidden] = useState(() => pref('kosh.hide', '0') === '1')
  const [creating, setCreating] = useState(false)
  const [route, params, go] = useHashRoute()

  if (ledgers.isPending) return <div className="splash"><img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" className="splash-logo" /><span className="muted">Loading your books…</span></div>
  if (ledgers.isError) return <div className="splash"><p>{errorText(ledgers.error)}</p><button className="primary" onClick={() => ledgers.refetch()}>Retry</button></div>
  const list = ledgers.data
  const created = (l: Ledger) => { setPref('kosh.ledger', l.id); setLedgerId(l.id); setCreating(false); ledgers.refetch(); go('dashboard') }
  if (list.length === 0 || creating) return <CreateLedger first={list.length === 0} onCreated={created} onCancel={list.length ? () => setCreating(false) : undefined} />
  const ledger = list.find((l) => l.id === ledgerId) ?? list[0]
  const pick = (id: string) => { if (id === '__new') { setCreating(true); return } setPref('kosh.ledger', id); setLedgerId(id) }
  const toggleHidden = () => { const v = !hidden; setHidden(v); setPref('kosh.hide', v ? '1' : '0') }

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
  const [rail, setRail] = useState(() => pref('kosh.rail', '0') === '1')
  const toggleRail = () => { const v = !rail; setRail(v); setPref('kosh.rail', v ? '1' : '0') }
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, staleTime: 5 * 60_000 })
  // The toggle shows the mode you would switch to; "system" follows the device until a choice is made.
  const dark = theme === 'dark' || theme === 'black' || (theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  const [palette, setPalette] = useState(false)
  const [adding, setAdding] = useState<{ draft: Resolved | null } | null>(null)
  const [help, setHelp] = useState(false)
  useAutoRecurring()
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
    categories: Categories, settings: Settings, tags: Tags, rules: Rules, import: Import, debts: Debts, loans: Loans, deposits: Deposits,
    tools: Tools, statements: Statements, assistant: Assistant, print: Print }[route]
  const label = ROUTES.find(([r]) => r === route)?.[1] ?? (route === 'print' ? 'Report' : '')
  const initial = (me.data?.user.name || me.data?.user.email || '?').trim()[0]?.toUpperCase()
  useEffect(() => { document.title = `${label} · LekhaMint` }, [label])

  if (route === 'print') return <ErrorBoundary resetKey={route}><Suspense fallback={<div className="splash"><span className="muted">Preparing the report…</span></div>}><Print {...props} /></Suspense></ErrorBoundary>
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
        </header>
        <main className="main" id="main">
          {!online && <div className="banner warn"><Icon name="alert" size={16} /> You are offline. LekhaMint will reconnect by itself; changes need a connection to save.</div>}
          {ledger.role === 'viewer' && <div className="banner"><Icon name="eye" size={16} /> You can view this shared book but not change it.</div>}
          <ErrorBoundary resetKey={route}><Suspense fallback={<div className="card pad"><Skeleton rows={4} /></div>}><Page {...props} /></Suspense></ErrorBoundary>
        </main>
      </div>
      <nav className="tabbar" aria-label="Quick navigation">
        {MOBILE_TABS.slice(0, 2).map((r) => <TabLink key={r} r={r} route={route} />)}
        {canWrite ? <button className="fab" onClick={() => setAdding({ draft: null })} aria-label="New transaction"><Icon name="plus" size={24} /></button> : <span />}
        {MOBILE_TABS.slice(2).map((r) => <TabLink key={r} r={r} route={route} />)}
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
