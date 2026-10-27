import { quickAnswer } from '../lib/quickanswer'
import { Fragment, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useLedger } from '../lib/data'
import { AiError, PROVIDERS, ask, destination, hydrateKey, loadAi, persistAiKey, saveAi, toolLabel, type AiSettings, type ServerAi, type ToolRun, type Turn } from '../lib/ai'
import { Bar, Check, Field, LineChart, Modal, toast } from '../components'
import { Columns, RankBars, SERIES, Spinner, StackBar } from '../charts'
import { Icon } from '../icons'
import type { PageProps, Route } from '../App'
import { Select, PasswordInput } from '../controls'
import { formatMoney } from '../lib/money'
import { parseDate, uuidv7 } from '../lib/util'
import { getSetting, useSetting } from '../lib/settings'

/**
 * Ask questions about your own money (docs/11-assistant.md). Answers come from
 * read-only lookups over your records; each answer lists what it looked at,
 * draws the figures it used, and links to the screens that show them. Nothing
 * is sent until you agree.
 *
 * It works like a chat app: conversations are kept (on this device) and listed
 * at the side, the answer streams in as the model writes it, each lookup shows
 * as a step while it runs, and the last question can be edited or asked again.
 */

const SUGGESTIONS: [string, string][] = [
  ['How much did I spend this month, and on what?', 'pie'],
  ['Why was my spending higher this month than last month?', 'trendUp'],
  ['How much do I still owe across all my loans?', 'bank'],
  ['What EMIs and bills are coming up in the next 30 days?', 'clock'],
  ['How has my net worth changed this year?', 'chart'],
  ['Which recurring expenses could I review to cut my monthly costs?', 'repeat'],
]

/** Questions answered without any AI model (lib/quickanswer.ts). */
const QUICK: [string, string][] = [
  ['How much did I spend this month?', 'pie'],
  ['What is coming up?', 'clock'],
  ['What is my net worth?', 'chart'],
  ['How are my budgets doing?', 'target'],
  ['Who owes me money?', 'hand'],
  ['How are my goals doing?', 'sprout'],
]

/** Follow-up questions offered under an answer, by the lookups it used. */
const FOLLOW_UPS: Record<string, string[]> = {
  spending_summary: ['Which category grew the most compared with the period before?', 'What were my biggest single expenses in this period?'],
  monthly_trend: ['Which month did I spend the most, and on what?', 'What is my average monthly spending over this time?'],
  loans: ['Which loan should I think about prepaying first, and why?', 'How much interest will I pay on my loans in total?'],
  upcoming: ['Do my account balances cover everything due in the next 30 days?'],
  budgets: ['Which budgets am I likely to go over this month?'],
  deposits: ['How much interest will my deposits earn in total?'],
  people_debts: ['Which money owed to me is overdue?'],
  net_worth_history: ['What caused the biggest change in my net worth?'],
  accounts: ['Where is most of my money kept?'],
  overview: ['How does this month compare with last month?'],
}

export function useServerAi() {
  return useQuery({ queryKey: ['meta-ai'], queryFn: async () => ((await api.meta()) as { ai?: ServerAi }).ai ?? null, staleTime: 5 * 60_000 })
}

// --- Conversations (kept in this browser, per book; cleared on sign-out) ---------------------

interface Chat { id: string; title: string; updated: number; turns: Turn[] }
const chatsKey = (lid: string) => `kosh.chats.${lid}`
const MAX_CHATS = 30
const MAX_TURNS = 40

function loadChats(lid: string): Chat[] {
  let chats: Chat[] = []
  try { chats = JSON.parse(localStorage.getItem(chatsKey(lid)) ?? '[]') as Chat[] } catch { /* blocked or corrupt: start empty */ }
  // One-time move of the single conversation older builds kept for this tab.
  try {
    const old = sessionStorage.getItem(`kosh.assistant.${lid}`)
    if (old) {
      const turns = JSON.parse(old) as Turn[]
      if (turns.length) chats.unshift({ id: uuidv7(), title: titleOf(turns[0].question), updated: Date.now(), turns })
      sessionStorage.removeItem(`kosh.assistant.${lid}`)
    }
  } catch { /* ignore */ }
  return chats
}

function saveChats(lid: string, chats: Chat[]) {
  let keep = [...chats].sort((a, b) => b.updated - a.updated).slice(0, MAX_CHATS).map((c) => ({ ...c, turns: c.turns.slice(-MAX_TURNS) }))
  // When storage is full, drop the oldest conversations until it fits.
  while (keep.length) {
    try { localStorage.setItem(chatsKey(lid), JSON.stringify(keep)); return } catch { keep = keep.slice(0, -1) }
  }
  try { localStorage.removeItem(chatsKey(lid)) } catch { /* ignore */ }
}

/** Forgets every saved conversation in this browser (on sign-out). */
export function clearChats() {
  try { for (const k of Object.keys(localStorage)) if (k.startsWith('kosh.chats.')) localStorage.removeItem(k) } catch { /* ignore */ }
}

const titleOf = (q: string) => (q.length > 60 ? q.slice(0, 57).trimEnd() + '…' : q)

function chatGroup(t: number): string {
  const d = new Date(t), now = new Date()
  const days = Math.floor((new Date(now.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 864e5)
  return days <= 0 ? 'Today' : days === 1 ? 'Yesterday' : days < 7 ? 'This week' : 'Earlier'
}

// --- Page ----------------------------------------------------------------------------------


export function Assistant({ go }: PageProps) {
  const l = useLedger()
  const server = useServerAi()
  const [s, setS] = useState<AiSettings>(loadAi)
  const [chats, setChats] = useState<Chat[]>(() => loadChats(l.id))
  const [chatId, setChatId] = useState<string | null>(() => chats[0]?.id ?? null)
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [setup, setSetup] = useState(false)
  const [consent, setConsent] = useState<string | null>(null)
  /** Replacing the question at this index (and dropping the answers after it). */
  const [editing, setEditing] = useState<number | null>(null)
  /** The question being answered right now: its lookups so far and the answer as it streams. */
  const [pending, setPending] = useState<{ q: string; runs: string[]; text: string } | null>(null)
  const [listOpen, setListOpen] = useSetting('assistant_chat_list', true)
  const [away, setAway] = useState(false)
  const abort = useRef<AbortController | null>(null)
  const partial = useRef('')
  const scrollBox = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLTextAreaElement>(null)
  const atBottom = useRef(true)

  const chat = chats.find((c) => c.id === chatId) ?? null
  const turns = chat?.turns ?? []

  // Nobody has chosen yet and the admin has set up an assistant on this server: use it, no setup
  // needed. Not saved, so if the admin later removes it the page simply asks again. "Turn off" is kept.
  useEffect(() => {
    if (server.data?.enabled && s.mode === 'off' && !('mode' in getSetting<Record<string, unknown>>('assistant', {}))) setS({ ...s, mode: 'server' })
  }, [server.data?.enabled]) // eslint-disable-line react-hooks/exhaustive-deps
  // A key saved on the server (its durable home) is loaded into memory once.
  useEffect(() => { if (s.mode === 'direct' && !s.apiKey) hydrateKey().then((k) => { if (k) setS(loadAi()) }) }, []) // eslint-disable-line
  // Ready to type on a computer; on a phone the keyboard would cover the page.
  useEffect(() => { if (matchMedia('(pointer: fine)').matches) input.current?.focus() }, [chatId])

  const update = (patch: Partial<AiSettings>) => { const n = { ...s, ...patch }; setS(n); saveAi(n) }
  const dest = destination(s, server.data)
  const configured = s.mode === 'server' ? !!server.data?.enabled : s.mode === 'direct' && !!s.baseUrl

  // Keep pinned to the newest message unless the reader scrolled up.
  useLayoutEffect(() => {
    const el = scrollBox.current
    if (el && atBottom.current) el.scrollTop = el.scrollHeight
  }, [turns, pending])
  const onScroll = () => {
    const el = scrollBox.current
    if (!el) return
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    setAway(!atBottom.current)
  }
  const toBottom = () => { const el = scrollBox.current; if (el) { atBottom.current = true; el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' }) } }

  // The question box grows with what is typed, up to a limit.
  useLayoutEffect(() => {
    const t = input.current
    if (!t) return
    t.style.height = 'auto'
    t.style.height = `${Math.min(t.scrollHeight, 200)}px`
  }, [q])

  const store = (fn: (list: Chat[]) => Chat[]) => setChats((x) => { const n = fn(x); saveChats(l.id, n); return n })
  /** Adds a finished turn to conversation [id] (creating it), keeping turns before [cut]. */
  const record = (id: string, t: Turn, cut: number | null) => store((list) => {
    const c = list.find((x) => x.id === id)
    if (!c) return [{ id, title: titleOf(t.question), updated: Date.now(), turns: [t] }, ...list]
    const before = cut === null ? c.turns : c.turns.slice(0, cut)
    return list.map((x) => (x.id === id ? { ...x, updated: Date.now(), title: before.length ? x.title : titleOf(t.question), turns: [...before, t] } : x))
  })
  const newChat = () => { if (busy) return; setChatId(null); setEditing(null); setQ(''); if (innerWidth < 1100) setListOpen(false) }
  const openChat = (id: string) => { if (busy) return; setChatId(id); setEditing(null); atBottom.current = true; if (innerWidth < 1100) setListOpen(false) }
  const removeChat = (id: string) => {
    const gone = chats.find((c) => c.id === id)
    store((list) => list.filter((c) => c.id !== id))
    if (chatId === id) setChatId(null)
    if (gone) toast('Conversation deleted', { label: 'Undo', run: () => store((list) => [gone, ...list]) })
  }
  const toggleList = () => setListOpen(!listOpen)

  const send = async (question: string, agreed = false, cut: number | null = editing) => {
    const text = question.trim()
    if (!text || busy) return
    if (!configured) { void answerQuickly(text, cut); return }
    if (!agreed && s.consentFor !== dest) { setConsent(text); return }
    const id = chatId ?? uuidv7()
    const history = cut === null ? turns : turns.slice(0, cut)
    if (!chatId) setChatId(id)
    setBusy(true); setQ(''); setEditing(null); atBottom.current = true
    // While editing, the turns after the edited one are hidden until the new answer arrives.
    if (cut !== null) store((list) => list.map((c) => (c.id === id ? { ...c, turns: c.turns.slice(0, cut) } : c)))
    setPending({ q: text, runs: [], text: '' })
    partial.current = ''
    abort.current = new AbortController()
    try {
      const t = await ask(s, { ledgerId: l.id, base: l.base_currency, share: s.shareDetails }, l.name, history, text, abort.current.signal,
        (r) => setPending((p) => (p ? { ...p, runs: [...p.runs, r.name] } : p)),
        (txt) => { partial.current = txt; setPending((p) => (p ? { ...p, text: txt } : p)) })
      record(id, { ...t, at: Date.now() }, null)
    } catch (e) {
      const base = { question: text, runs: [], mode: 'tools' as const, at: Date.now() }
      if ((e as Error).name === 'AbortError') record(id, { ...base, answer: partial.current, error: partial.current ? 'Stopped before the answer was finished.' : 'Stopped before finishing. Ask again to retry.' }, null)
      else record(id, { ...base, answer: '', error: e instanceof AiError ? e.message : `Something went wrong: ${(e as Error).message}` }, null)
    } finally { setBusy(false); setPending(null); abort.current = null }
  }
  /** Without a model: answer from a single look-up, or explain what a model would add. */
  const answerQuickly = async (text: string, cut: number | null) => {
    const id = chatId ?? uuidv7()
    if (!chatId) setChatId(id)
    setBusy(true); setQ(''); setEditing(null); atBottom.current = true
    setPending({ q: text, runs: [], text: '' })
    try {
      const t = await quickAnswer({ ledgerId: l.id, base: l.base_currency, share: true }, text)
      record(id, t ?? { question: text, runs: [], mode: 'quick', at: Date.now(),
        answer: 'I can answer questions like the ones below without an AI model. For anything else (comparisons, “why”, advice on cutting costs), connect a model under **Settings**: a free one on your own computer works.' }, cut)
    } catch (e) {
      record(id, { question: text, answer: '', runs: [], mode: 'quick', at: Date.now(), error: (e as Error).message }, cut)
    } finally { setBusy(false); setPending(null) }
  }
  const retryLast = () => { const last = turns[turns.length - 1]; if (last) send(last.question, s.consentFor === dest, turns.length - 1) }
  const edit = (i: number) => { setEditing(i); setQ(turns[i].question); requestAnimationFrame(() => input.current?.focus()) }

  const groups = new Map<string, Chat[]>()
  for (const c of [...chats].sort((a, b) => b.updated - a.updated)) groups.set(chatGroup(c.updated), [...(groups.get(chatGroup(c.updated)) ?? []), c])
  const last = turns[turns.length - 1]
  const followUps = !busy && last && !last.error ? [...new Set(last.runs.flatMap((r) => FOLLOW_UPS[r.name] ?? []))].filter((x) => !turns.some((t) => t.question === x)).slice(0, 3) : []
  const empty = turns.length === 0 && !pending

  return (
    <div className={`assistant ${listOpen ? 'list-open' : ''}`}>
      <aside className="chat-list" aria-label="Conversations">
        <div className="chat-list-head">
          <button className="primary chat-new" onClick={newChat} disabled={busy}><Icon name="plus" size={16} /> New chat</button>
          <button className="icon-btn chat-list-close" onClick={toggleList} aria-label="Hide conversations"><Icon name="x" size={18} /></button>
        </div>
        <div className="chat-list-scroll">
          {chats.length === 0 && <p className="muted small chat-list-empty">Your conversations will appear here. They are kept on this device only.</p>}
          {[...groups].map(([g, list]) => (
            <div key={g} className="chat-group">
              <div className="chat-group-title">{g}</div>
              {list.map((c) => (
                <div key={c.id} className={`chat-item ${c.id === chatId ? 'on' : ''}`}>
                  <button className="chat-item-open" onClick={() => openChat(c.id)} title={c.title} disabled={busy && c.id !== chatId}>
                    <Icon name="chat" size={15} /><span className="ellipsis">{c.title}</span>
                  </button>
                  <button className="icon-btn chat-item-del" onClick={() => removeChat(c.id)} aria-label={`Delete “${c.title}”`} title="Delete" disabled={busy && c.id === chatId}><Icon name="trash" size={15} /></button>
                </div>
              ))}
            </div>
          ))}
        </div>
      </aside>
      {listOpen && <div className="chat-list-scrim" onClick={toggleList} />}

      <section className="chat-main">
        <header className="chat-head">
          <button className="icon-btn" onClick={toggleList} aria-label={listOpen ? 'Hide conversations' : 'Show conversations'} title="Conversations"><Icon name="panel" size={19} /></button>
          <div className="chat-title">
            <strong className="ellipsis">{chat?.title ?? 'New conversation'}</strong>
            <span className={`status-dot ${configured ? 'ok' : 'off'}`}>{configured ? (s.mode === 'server' ? `Via your server · ${server.data?.model || server.data?.host || 'model'}` : `${s.model || 'Model'} · ${dest}`) : 'Not set up'}</span>
          </div>
          <span className="spacer" />
          {turns.length > 0 && <button className="sm chat-head-new" onClick={newChat} disabled={busy}><Icon name="plus" size={15} /> <span className="hide-sm">New chat</span></button>}
          <button className="sm" onClick={() => setSetup(true)} aria-label="Assistant settings"><Icon name="settings" size={15} /> <span className="hide-sm">Settings</span></button>
        </header>

        <div className="chat-scroll" ref={scrollBox} onScroll={onScroll}>
          <div className="chat-column" aria-live="polite">
            {empty && (configured ? (
              <div className="chat-welcome">
                <span className="ai-avatar big" aria-hidden><Icon name="sparkles" size={26} /></span>
                <h3>What would you like to know about your money?</h3>
                <p className="muted">Ask in plain words. Answers come from your own records through read-only lookups, with charts and links to check every figure.</p>
                <div className="suggestion-grid">
                  {SUGGESTIONS.map(([text, icon]) => (
                    <button key={text} className="suggestion" onClick={() => send(text)}>
                      <span className="suggestion-icon"><Icon name={icon} size={16} /></span>
                      <span>{text}</span>
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              <div className="chat-welcome">
                <span className="ai-avatar big" aria-hidden><Icon name="sparkles" size={26} /></span>
                <h3>Quick answers, no setup needed</h3>
                <p className="muted">Tap a question, or type one like “How much did I spend on Swiggy this month?”. These are answered straight from your records, with a chart.</p>
                <div className="suggestion-grid">
                  {QUICK.map(([text, icon]) => (
                    <button key={text} className="suggestion" onClick={() => send(text)}>
                      <span className="suggestion-icon"><Icon name={icon} size={16} /></span>
                      <span>{text}</span>
                    </button>
                  ))}
                </div>
                <div className="subtle-box small" style={{ marginTop: 16 }}>
                  <strong>Want answers to any question?</strong> Connect an AI model: a free one on your own computer (Ollama), or a cloud service with a key. It reads your records only through read-only look-ups and can never change anything.
                  <div style={{ marginTop: 8 }}><button className="primary" onClick={() => setSetup(true)}><Icon name="plug" size={16} /> Connect a model</button></div>
                </div>
              </div>
            ))}

            {turns.map((t, i) => (
              <TurnView key={`${chatId}-${i}`} t={t} go={go} last={i === turns.length - 1} busy={busy}
                onRetry={() => (i === turns.length - 1 ? retryLast() : send(t.question, s.consentFor === dest, i))} onEdit={() => edit(i)} />
            ))}

            {pending && (
              <>
                <div className="msg user-msg"><div className="bubble">{pending.q}</div></div>
                <div className="msg assistant-msg">
                  <span className="ai-avatar" aria-hidden><Icon name="sparkles" size={14} /></span>
                  <div className="msg-body">
                    {!pending.text && (
                      <ol className="think-steps" aria-label="Progress">
                        <li className={pending.runs.length ? 'done' : 'active'}>{pending.runs.length ? <Icon name="check" size={14} /> : <Spinner size={14} />} {pending.runs.length ? 'Understood the question' : 'Reading your question…'}</li>
                        {pending.runs.map((name, i) => <li key={i} className="done"><Icon name="check" size={14} /> Looked up {toolLabel(name).toLowerCase()}</li>)}
                        {pending.runs.length > 0 && <li className="active"><Spinner size={14} /> Working out the answer…</li>}
                      </ol>
                    )}
                    {pending.text && <>
                      {pending.runs.length > 0 && <div className="used-line muted small"><Icon name="check" size={13} /> Used {pending.runs.map(toolLabel).join(', ').toLowerCase()}</div>}
                      <Markdown text={pending.text} streaming />
                    </>}
                  </div>
                </div>
              </>
            )}

            {followUps.length > 0 && (
              <div className="follow-ups" aria-label="Suggested follow-up questions">
                {followUps.map((f) => <button key={f} className="chip-btn follow-up" onClick={() => send(f)}><Icon name="sparkles" size={13} /> {f}</button>)}
              </div>
            )}
          </div>
        </div>

        {away && <button className="to-bottom" onClick={toBottom} aria-label="Scroll to the newest message"><Icon name="down" size={16} /></button>}

        <form className="composer" onSubmit={(e) => { e.preventDefault(); send(q) }}>
          {editing !== null && (
            <div className="composer-edit small"><Icon name="edit" size={14} /> Editing your question. The answer after it will be replaced.
              <button type="button" className="link small" onClick={() => { setEditing(null); setQ('') }}>Cancel</button></div>
          )}
          <div className="composer-box">
            <textarea ref={input} value={q} onChange={(e) => setQ(e.target.value)} rows={1} placeholder={configured ? (innerWidth < 640 ? 'Ask about your money…' : 'Ask about your spending, loans, deposits, budgets…') : 'Ask a quick question, like “spent on fuel this month”'}
              aria-label="Question" enterKeyHint="send"
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(q) } if (e.key === 'Escape' && editing !== null) { setEditing(null); setQ('') } }} />
            {busy
              ? <button type="button" className="composer-send stop" onClick={() => abort.current?.abort()} aria-label="Stop" title="Stop"><Icon name="stop" size={16} /></button>
              : <button type="submit" className="composer-send" disabled={!q.trim()} aria-label="Send" title="Send (Enter)"><Icon name="send" size={18} /></button>}
          </div>
          <p className="composer-note muted"><span className="hide-sm"><kbd className="kbd">Enter</kbd> to send · <kbd className="kbd">Shift</kbd>+<kbd className="kbd">Enter</kbd> for a new line · </span>{configured ? 'Answers come from your records through a language model; balances and totals are calculated by LekhaMint. Check important figures on the linked screens.' : 'Quick answers come straight from your records; no AI model is used.'}</p>
        </form>
      </section>

      {setup && <AiSetup s={s} server={server.data ?? null} onSave={(n) => { setS(n); saveAi(n); persistAiKey(n).catch(() => toast('The key could not be saved on the server; it works in this tab', { label: 'Retry', run: () => persistAiKey(n).catch(() => {}) })); setSetup(false) }} onClose={() => setSetup(false)} />}
      {consent !== null && (
        <Modal title="Share your records with the model?" onClose={() => setConsent(null)} footer={<>
          <button onClick={() => setConsent(null)}>Not now</button>
          <button className="primary" onClick={() => { update({ consentFor: dest }); const qq = consent; setConsent(null); send(qq, true) }}>Agree and ask</button>
        </>}>
          <p>To answer, the assistant sends your question and the records it looks up (totals, balances, loans, deposits{s.shareDetails ? ', and payee names and notes of matching transactions' : ''}) to <strong>{dest}</strong>.</p>
          {s.mode === 'direct' && /api\.openai|openrouter|anthropic|googleapis|groq|together|mistral/.test(s.baseUrl) && <p className="warn-text small">This is a cloud service. Its own privacy terms apply to what it receives.</p>}
          <p className="muted small">The assistant cannot change your data. You can turn off sharing payee names and notes in its settings, and switch to a model on your own computer at any time. We will ask again if you change where it sends data.</p>
        </Modal>
      )}
    </div>
  )
}

function TurnView({ t, go, last, busy, onRetry, onEdit }: { t: Turn; go: PageProps['go']; last: boolean; busy: boolean; onRetry: () => void; onEdit: () => void }) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const links = [...new Map(t.runs.filter((r) => r.link).map((r) => [r.link!.route + JSON.stringify(r.link!.params), r.link!])).values()]
  const copy = () => {
    navigator.clipboard?.writeText(t.answer).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500) }).catch(() => { })
  }
  return (
    <>
      <div className="msg user-msg">
        {!busy && <button className="icon-btn msg-edit" onClick={onEdit} aria-label="Edit this question" title="Edit"><Icon name="edit" size={14} /></button>}
        <div className="bubble">{t.question}</div>
      </div>
      <div className="msg assistant-msg">
        <span className="ai-avatar" aria-hidden><Icon name="sparkles" size={14} /></span>
        <div className="msg-body">
          {t.runs.length > 0 && (
            <button className="used-line muted small" onClick={() => setOpen(!open)} aria-expanded={open}>
              <Icon name="check" size={13} /> Used {t.runs.length} {t.runs.length === 1 ? 'lookup' : 'lookups'}: {[...new Set(t.runs.map((r) => toolLabel(r.name)))].join(', ').toLowerCase()}
              <Icon name={open ? 'chevronUp' : 'chevronDown'} size={13} />
            </button>
          )}
          {open && t.runs.map((r, i) => (
            <details key={i} className="lookup">
              <summary>
                <code>{toolLabel(r.name)}</code>
                {Object.keys(r.args).length ? <span className="muted small">{Object.entries(r.args).map(([k, v]) => `${k}=${String(v)}`).slice(0, 4).join(', ')}</span> : ''}
                {r.error ? <span className="error small"> · {r.error}</span> : <span className="pos-text small"> · ok</span>}
              </summary>
              <pre>{JSON.stringify(r.result, null, 2).slice(0, 8000)}</pre>
            </details>
          ))}
          {t.answer && <Markdown text={t.answer} />}
          {t.error ? (
            <div className="turn-error" role="alert">
              <Icon name="alert" size={16} />
              <p>{t.error}</p>
              {!busy && <button className="sm" onClick={onRetry}><Icon name="refresh" size={14} /> Try again</button>}
            </div>
          ) : (
            <>
              {!t.answer && <p className="muted">(No answer.)</p>}
              <AnswerVisuals runs={t.runs} go={go} />
              {t.mode === 'snapshot' && <p className="muted small"><Icon name="info" size={12} /> Answered from a summary (this model can't look things up)</p>}
              {t.mode === 'quick' && <p className="muted small"><Icon name="info" size={12} /> Quick answer straight from your records; no AI model was used</p>}
            </>
          )}
          <div className="msg-actions">
            {t.answer && <button className="ghost sm" onClick={copy} title="Copy the answer"><Icon name={copied ? 'check' : 'copy'} size={14} /> {copied ? 'Copied' : 'Copy'}</button>}
            {last && !busy && !t.error && <button className="ghost sm" onClick={onRetry} title="Ask the same question again"><Icon name="refresh" size={14} /> Regenerate</button>}
            {links.map((x) => <button key={x.route + JSON.stringify(x.params)} className="ghost sm" onClick={() => go(x.route as Route, x.params)}><Icon name="external" size={14} /> Open {x.route === 'tools' ? 'net worth' : x.route}</button>)}
            {t.at && <span className="muted small msg-time">{new Date(t.at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</span>}
          </div>
        </div>
      </div>
    </>
  )
}

// --- Charts drawn from what the lookups returned -----------------------------------------------

type M = { minor_units?: number } | undefined
const mu = (x: unknown) => (x as M)?.minor_units ?? 0
const shortDate = (d: string) => new Date(d + 'T00:00:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
const monthLabel = (ym: string) => new Date(ym + '-01T00:00:00').toLocaleDateString(undefined, { month: 'short', year: '2-digit' })

/**
 * The figures behind an answer as charts: category spending, monthly trends,
 * loans, budgets and so on. They are drawn from the lookup results (calculated
 * by LekhaMint), not from the model's text, so they are exact.
 */
function AnswerVisuals({ runs, go }: { runs: ToolRun[]; go: PageProps['go'] }) {
  const code = useLedger().base_currency
  const latest = new Map<string, ToolRun>()
  for (const r of runs) if (!r.error && r.result) latest.set(r.name, r)
  const out: ReactNode[] = []
  for (const r of latest.values()) {
    const v = visualFor(r, code)
    if (v) out.push(
      <figure key={r.name} className="answer-viz">
        <figcaption className="answer-viz-head">
          <span><Icon name="chart" size={14} /> {v.title}</span>
          {r.link && <button className="link small" onClick={() => go(r.link!.route as Route, r.link!.params)}>Open</button>}
        </figcaption>
        {v.body}
      </figure>,
    )
    if (out.length >= 3) break
  }
  return out.length ? <div className="answer-visuals">{out}</div> : null
}

function visualFor(r: ToolRun, code: string): { title: string; body: ReactNode } | null {
  const res = r.result as Record<string, unknown> & unknown[]
  switch (r.name) {
    case 'spending_summary': {
      const cur = (res.current as { currency: string; spending: M; spending_by_category: { category: string; total: M }[] }[] | undefined)?.find((x) => x.currency === code)
      const prev = (res.previous as typeof cur[] | undefined)?.find((x) => x?.currency === code)
      if (!cur || !cur.spending_by_category.length) return null
      const before = new Map((prev?.spending_by_category ?? []).map((x) => [x.category, mu(x.total)]))
      const p = res.period as { from: string; to: string }
      return {
        title: `Spending by category, ${shortDate(p.from)} – ${shortDate(p.to)}`,
        body: <RankBars currency={code} label="Spending by category" rows={cur.spending_by_category.slice(0, 7).map((x, i) => {
          const now = mu(x.total), was = before.get(x.category) ?? 0
          const change = was > 0 ? Math.round(((now - was) / was) * 100) : null
          return { key: x.category, name: x.category, value: now, color: SERIES[0], sub: change === null ? (i === 0 ? 'largest' : 'new') : change === 0 ? 'same as before' : `${change > 0 ? '▲' : '▼'} ${Math.abs(change)}% vs before` }
        })} />,
      }
    }
    case 'monthly_trend': {
      if (res.category) {
        const months = (res.months as { month: string; spent: M }[]) ?? []
        if (!months.length) return null
        return { title: `${res.category as string}, month by month`, body: <Columns currency={code} label={`Spending on ${res.category}`} labels={months.map((x) => monthLabel(x.month))} series={[{ name: res.category as string, color: SERIES[0], values: months.map((x) => mu(x.spent)) }]} height={150} /> }
      }
      const months = ((res.months as { month: string; currency: string; income: M; spending: M }[]) ?? []).filter((x) => x.currency === code)
      if (months.length < 2) return null
      return { title: 'Income and spending, month by month', body: <LineChart currency={code} label="Income and spending per month" height={140} labels={months.map((x) => monthLabel(x.month))}
        series={[{ name: 'Income', color: 'var(--pos)', values: months.map((x) => mu(x.income)) }, { name: 'Spending', color: 'var(--neg)', values: months.map((x) => mu(x.spending)) }]} /> }
    }
    case 'net_worth_history': {
      const pts = (res as unknown as { date: string; positions: { currency: string; net_worth: M; assets: M; debts: M }[] }[]).map((p) => ({ date: p.date, p: p.positions.find((x) => x.currency === code) })).filter((x) => x.p)
      if (pts.length < 2) return null
      return { title: 'Net worth at each month end', body: <LineChart currency={code} label="Net worth over time" height={140} labels={pts.map((x) => parseDate(x.date).toLocaleDateString(undefined, { month: 'short', year: '2-digit' }))}
        series={[{ name: 'Net worth', color: SERIES[0], values: pts.map((x) => mu(x.p!.net_worth)) }]} /> }
    }
    case 'overview': {
      const p = (res.positions as { currency: string; cash_and_bank: M; investments_and_deposits: M; owed_to_user: M; owed_on_cards_and_loans: M; user_owes_people: M }[] | undefined)?.find((x) => x.currency === code)
      if (!p) return null
      return { title: 'What you have and what you owe', body: <>
        <StackBar currency={code} label="What you have" parts={[{ name: 'Cash & bank', value: mu(p.cash_and_bank), color: SERIES[0] }, { name: 'Investments & deposits', value: mu(p.investments_and_deposits), color: SERIES[2] }, { name: 'Owed to you', value: mu(p.owed_to_user), color: SERIES[6] }]} />
        {mu(p.owed_on_cards_and_loans) + mu(p.user_owes_people) > 0 && <StackBar currency={code} label="What you owe" parts={[{ name: 'Cards & loans', value: mu(p.owed_on_cards_and_loans), color: SERIES[1] }, { name: 'You owe people', value: mu(p.user_owes_people), color: SERIES[4] }]} />}
      </> }
    }
    case 'loans': {
      const list = (res as unknown as { name: string; closed: boolean; outstanding: M; emi: M; instalments_left: number }[]).filter((x) => !x.closed && mu(x.outstanding) > 0)
      if (!list.length) return null
      return { title: 'Still owed on each loan', body: <RankBars currency={code} label="Outstanding by loan" rows={list.sort((a, b) => mu(b.outstanding) - mu(a.outstanding)).map((x) => ({ key: x.name, name: x.name, value: mu(x.outstanding), color: SERIES[1], sub: `EMI ${formatMoney(mu(x.emi), code)}${x.instalments_left ? ` · ${x.instalments_left} left` : ''}` }))} /> }
    }
    case 'deposits': {
      const list = (res as unknown as { name: string; status: string; at_maturity: M; matures: string }[]).filter((x) => x.status === 'active')
      if (!list.length) return null
      return { title: 'Deposits at maturity', body: <RankBars currency={code} label="Maturity value by deposit" rows={list.map((x) => ({ key: x.name, name: x.name, value: mu(x.at_maturity), color: SERIES[2], sub: `matures ${shortDate(x.matures)}` }))} /> }
    }
    case 'people_debts': {
      const list = res as unknown as { person: string; owes_user?: M; user_owes?: M; overdue: boolean }[]
      if (!list.length) return null
      return { title: 'Lent and borrowed, by person', body: <RankBars currency={code} label="Money owed by person" rows={list.map((x) => ({ key: x.person, name: x.person, value: mu(x.owes_user ?? x.user_owes), color: x.owes_user ? SERIES[2] : SERIES[1], sub: `${x.owes_user ? 'owes you' : 'you owe'}${x.overdue ? ' · overdue' : ''}` }))} /> }
    }
    case 'accounts': {
      const list = (res as unknown as { name: string; currency: string; balance: M }[]).filter((x) => x.currency === code && mu(x.balance) !== 0)
      if (!list.length) return null
      return { title: 'Balances by account', body: <RankBars currency={code} label="Balance by account" rows={list.sort((a, b) => Math.abs(mu(b.balance)) - Math.abs(mu(a.balance))).slice(0, 8).map((x) => ({ key: x.name, name: x.name, value: mu(x.balance), color: mu(x.balance) < 0 ? SERIES[1] : SERIES[0], sub: mu(x.balance) < 0 ? 'owed' : undefined }))} /> }
    }
    case 'budgets': {
      const list = res as unknown as { budget: string; limit: M; spent: M; days_left: number }[]
      if (!list.length) return null
      return { title: 'Budgets this period', body: (
        <ul className="viz-budgets">{list.map((b) => {
          const f = mu(b.limit) ? mu(b.spent) / mu(b.limit) : 0
          return <li key={b.budget}><div className="row between small"><span className="ellipsis">{b.budget}</span><span className="muted">{formatMoney(mu(b.spent), code)} of {formatMoney(mu(b.limit), code)} · {Math.round(f * 100)}%</span></div><Bar fraction={f} danger={f > 1} color={f > 0.85 && f <= 1 ? 'var(--warn)' : undefined} /></li>
        })}</ul>
      ) }
    }
    case 'upcoming': {
      const items = ((res.items as { date: string; what: string; amount?: M; kind: string; overdue?: boolean }[]) ?? []).slice(0, 8)
      if (!items.length) return null
      return { title: 'Coming up', body: (
        <ul className="viz-upcoming">{items.map((x, i) => (
          <li key={i} className={x.overdue ? 'overdue' : ''}><span className="viz-date">{shortDate(x.date)}</span><span className="grow ellipsis">{x.what}<span className="muted small"> · {x.overdue ? 'overdue' : x.kind}</span></span>{x.amount && <strong className="money">{formatMoney(mu(x.amount), code)}</strong>}</li>
        ))}</ul>
      ) }
    }
    default: return null
  }
}

/** Renders the model's answer: paragraphs, bullet and numbered lists, headings, tables (| a | b |), code blocks, **bold**, *italics* and `code`. No HTML is interpreted. */
function Markdown({ text, streaming }: { text: string; streaming?: boolean }) {
  const inline = (s: string): ReactNode[] => s.split(/(\*\*[^*]+\*\*|`[^`]+`|\*[^*\s][^*]*\*)/g).map((p, i) =>
    p.startsWith('**') && p.endsWith('**') && p.length > 4 ? <strong key={i}>{p.slice(2, -2)}</strong>
      : p.startsWith('`') && p.endsWith('`') && p.length > 2 ? <code key={i}>{p.slice(1, -1)}</code>
        : p.startsWith('*') && p.endsWith('*') && p.length > 2 ? <em key={i}>{p.slice(1, -1)}</em>
          : <Fragment key={i}>{p}</Fragment>)
  const blocks: ReactNode[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  let table: string[][] | null = null
  let code: string[] | null = null
  const flushList = () => { if (list) { const L = list.ordered ? 'ol' : 'ul'; blocks.push(<L key={blocks.length}>{list.items.map((x, i) => <li key={i}>{inline(x)}</li>)}</L>); list = null } }
  const flushTable = () => {
    if (table && table.length) {
      const [head, ...rows] = table
      const numeric = head.map((_, ci) => rows.length > 0 && rows.every((r) => /^[-−+]?\s*[^\d\s]{0,3}\s?[\d,.]+\s*%?[A-Za-z]{0,3}$/.test((r[ci] ?? '').replace(/\*\*/g, '').trim()) || !(r[ci] ?? '').trim()))
      blocks.push(
        <div key={blocks.length} className="md-table-wrap">
          <table className="md-table">
            <thead><tr>{head.map((c, i) => <th key={i} className={numeric[i] ? 'num' : ''}>{inline(c)}</th>)}</tr></thead>
            <tbody>{rows.map((r, ri) => <tr key={ri}>{r.map((c, ci) => <td key={ci} className={numeric[ci] ? 'num' : ''}>{inline(c)}</td>)}</tr>)}</tbody>
          </table>
        </div>,
      )
    }
    table = null
  }
  const cells = (line: string) => line.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map((c) => c.trim())
  for (const raw of text.split('\n')) {
    const line = raw.trimEnd()
    if (line.trim().startsWith('```')) {
      if (code) { blocks.push(<pre key={blocks.length} className="md-code">{code.join('\n')}</pre>); code = null } else { flushList(); flushTable(); code = [] }
      continue
    }
    if (code) { code.push(raw); continue }
    const isTableRow = line.trim().startsWith('|') && line.includes('|', 1)
    if (isTableRow) {
      if (!/^\s*\|?[\s:|-]+\|?\s*$/.test(line)) { flushList(); table ??= []; table.push(cells(line)) }
      continue // rule rows (|---|---|) just separate the header
    }
    flushTable()
    const b = /^\s*[-*•]\s+(.*)$/.exec(line), n = /^\s*\d+[.)]\s+(.*)$/.exec(line), h = /^#{1,4}\s+(.*)$/.exec(line)
    if (b || n) { const ordered = !!n; if (list && list.ordered !== ordered) flushList(); list ??= { ordered, items: [] }; list.items.push((b ?? n)![1]); continue }
    flushList()
    if (h) blocks.push(<h4 key={blocks.length}>{inline(h[1])}</h4>)
    else if (/^\s*(---|\*\*\*)\s*$/.test(line)) blocks.push(<hr key={blocks.length} />)
    else if (line.trim()) blocks.push(<p key={blocks.length}>{inline(line)}</p>)
  }
  if (code) blocks.push(<pre key={blocks.length} className="md-code">{(code as string[]).join('\n')}</pre>)
  flushList()
  flushTable()
  return <div className={`md ${streaming ? 'streaming' : ''}`}>{blocks}</div>
}

function AiSetup({ s, server, onSave, onClose }: { s: AiSettings; server: ServerAi | null; onSave: (s: AiSettings) => void; onClose: () => void }) {
  const [mode, setMode] = useState<AiSettings['mode']>(s.mode === 'off' ? (server?.enabled ? 'server' : 'direct') : s.mode)
  const guess = PROVIDERS.find((p) => p.url && s.baseUrl.startsWith(p.url))?.id ?? (s.baseUrl ? 'custom' : 'ollama')
  const [provider, setProvider] = useState(guess)
  const [baseUrl, setBaseUrl] = useState(s.baseUrl || PROVIDERS.find((p) => p.id === guess)?.url || '')
  const [apiKey, setApiKey] = useState(s.apiKey)
  const [model, setModel] = useState(s.model)
  const [tools, setTools] = useState(s.tools)
  const [share, setShare] = useState(s.shareDetails)
  const [models, setModels] = useState<string[]>([])
  const [test, setTest] = useState<{ ok: boolean; text: string } | null>(null)
  const [testing, setTesting] = useState(false)
  const p = PROVIDERS.find((x) => x.id === provider)!
  const next = (): AiSettings => ({ ...s, mode, baseUrl: baseUrl.trim(), apiKey: apiKey.trim(), model: model.trim(), tools, shareDetails: share })
  const check = async () => {
    setTesting(true); setTest(null)
    try {
      if (mode === 'server') {
        if (!server?.enabled) throw new Error('The server has no assistant configured (LEKHAMINT_AI_BASE_URL is not set).')
        setTest({ ok: true, text: `The server is set up${server.model ? ` with ${server.model}` : ''}.` })
      } else {
        let url: URL
        try { url = new URL(baseUrl.trim()) } catch { throw new Error('Enter a full address, starting with http:// or https://') }
        if (location.protocol === 'https:' && url.protocol === 'http:' && !/^(localhost|127\.0\.0\.1)$/.test(url.hostname)) throw new Error('This page is served over HTTPS, so the browser will block a plain http:// model address. Use https://, run the model on this computer (localhost), or use the server option.')
        const res = await fetch(baseUrl.trim().replace(/\/+$/, '') + '/models', { headers: apiKey.trim() ? { Authorization: `Bearer ${apiKey.trim()}` } : {} })
        if (res.status === 401 || res.status === 403) throw new Error('Connected, but the API key was refused.')
        if (!res.ok) throw new Error(`Connected, but the server answered ${res.status}. Check the address ends with /v1.`)
        const j = await res.json().catch(() => ({}))
        const ids = ((j.data ?? j.models ?? []) as { id?: string; name?: string }[]).map((x) => x.id ?? x.name ?? '').filter(Boolean).sort()
        setModels(ids)
        if (!model && ids[0]) setModel(ids[0])
        setTest({ ok: true, text: ids.length ? `Connected. ${ids.length} models available.` : 'Connected.' })
      }
    } catch (e) {
      setTest({ ok: false, text: (e as Error).message.includes('Failed to fetch') ? "Couldn't connect. Check the address and that the server is running and allows this page (CORS)." : (e as Error).message })
    } finally { setTesting(false) }
  }
  return (
    <Modal wide title="Assistant settings" onClose={onClose} footer={<>
      {s.mode !== 'off' && <button className="danger" onClick={() => { onSave({ ...s, mode: 'off', apiKey: '' }); toast('Assistant turned off') }}>Turn off</button>}
      <span className="spacer" /><button onClick={onClose}>Cancel</button>
      <button className="primary" onClick={() => { if (mode === 'direct' && !baseUrl.trim()) { setTest({ ok: false, text: 'Enter the address of the model server' }); return } onSave(next()); toast('Assistant settings saved') }}>Save</button>
    </>}>
      <div className="choice-cards">
        <button className={`choice ${mode === 'direct' ? 'on' : ''}`} onClick={() => setMode('direct')}>
          <strong><Icon name="device" size={16} /> Connect from this browser</strong>
          <span>Works with cloud keys (OpenAI, OpenRouter) and with a model on this computer. The key is stored on your LekhaMint server, not in the browser.</span>
        </button>
        <button className={`choice ${mode === 'server' ? 'on' : ''}`} onClick={() => setMode('server')} disabled={!server?.enabled && !s.mode}>
          <strong><Icon name="server" size={16} /> Through my LekhaMint server</strong>
          <span>{server?.enabled ? <>Forwards to <strong>{server.host}</strong>{server.model ? ` using ${server.model}` : ''}; its key never leaves the server.</> : 'Not set up on this server (the admin sets LEKHAMINT_AI_BASE_URL).'}</span>
        </button>
      </div>
      {mode === 'server' ? (
        <div className="subtle-box small">
          {server?.enabled
            ? <p>Your server forwards questions to <strong>{server.host}</strong>{server.model ? ` using ${server.model}` : ''}. Its key stays on the server.</p>
              : <p>Not set up on this server. The admin can set <code>LEKHAMINT_AI_BASE_URL</code>, <code>LEKHAMINT_AI_API_KEY</code> and <code>LEKHAMINT_AI_MODEL</code> (for example Ollama on the same machine: <code>http://localhost:11434/v1</code>) and restart.</p>}
          <Field label="Model (optional)" hint="Leave empty to use the server's default"><input value={model} onChange={(e) => setModel(e.target.value)} placeholder={server?.model || 'default'} /></Field>
        </div>
      ) : <>
        <Field label="Provider"><Select value={provider} onChange={(e) => { setProvider(e.target.value); const u = PROVIDERS.find((x) => x.id === e.target.value)?.url; if (u !== undefined && (u || e.target.value === 'custom')) setBaseUrl(u) }}>{PROVIDERS.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select></Field>
        <p className="muted small">{p.note}</p>
        <div className="two">
          <Field label="Base URL" hint="Up to /v1, without /chat/completions"><input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://…/v1" spellCheck={false} /></Field>
          <Field label={p.key ? 'API key' : 'API key (if your server needs one)'} hint="Saved on your LekhaMint server; never in browser storage"><PasswordInput value={apiKey} onChange={(e) => setApiKey(e.target.value)} autoComplete="off" /></Field>
        </div>
        <Field label="Model" hint={models.length ? undefined : 'For example gpt-4o-mini, openai/gpt-4o-mini, llama3.1:8b or qwen2.5:7b'}>
          {models.length ? <Select value={model} onChange={(e) => setModel(e.target.value)}>{models.map((x) => <option key={x}>{x}</option>)}</Select> : <input value={model} onChange={(e) => setModel(e.target.value)} spellCheck={false} />}
        </Field>
      </>}
      <div className="row"><button onClick={check} disabled={testing}>{testing ? <><Spinner size={14} /> Testing…</> : 'Test connection'}</button>{test && <span className={test.ok ? 'pos-text small' : 'error small'}>{test.text}</span>}</div>
      <h3 className="section-title">Privacy and capability</h3>
      <Check checked={share} onChange={setShare}>Include payee names, notes and people's names in what the model sees</Check>
      <p className="muted small">Off: the model sees amounts, dates, categories and accounts, but not who you paid or what notes say. Answers about specific shops or people will be less precise.</p>
      <Check checked={tools} onChange={setTools}>The model can call tools (look things up)</Check>
      <p className="muted small">Most recent models can (GPT-4o, Llama 3.1+, Qwen 2.5+, Mistral). Small or older models may not; untick this and the assistant sends a short summary instead, which answers fewer questions. LekhaMint also switches automatically when a model refuses tools.</p>
      <p className="muted small">Conversations are kept in this browser only, for this book, and are removed when you sign out. Delete any of them from the list.</p>
      <p className="muted small">On a phone, you can point the Android app at a model on your home server or one running on the phone itself (for example with an app that offers an OpenAI-compatible server on localhost).</p>
    </Modal>
  )
}
