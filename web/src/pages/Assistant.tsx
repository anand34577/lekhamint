import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useLedger } from '../lib/data'
import { AiError, PROVIDERS, ask, destination, loadAi, saveAi, type AiSettings, type ServerAi, type Turn } from '../lib/ai'
import { Card, Check, Field, Modal, Segmented, toast } from '../components'
import { Icon } from '../icons'
import type { PageProps, Route } from '../App'

/**
 * Ask questions about your own money (docs/11-assistant.md). Answers come from
 * read-only lookups over your records; each answer lists what it looked at,
 * with links to the screens that show it. Nothing is sent until you agree.
 */

const SUGGESTIONS = [
  'How much did I spend this month, and on what?',
  'Why was my spending higher this month than last month?',
  'How much do I still owe across all my loans?',
  'What EMIs and bills are coming up in the next 30 days?',
  'Who owes me money, and how much?',
  'Which deposits mature in the next six months?',
  'How much did I spend on groceries over the last 12 months?',
  'How has my net worth changed this year?',
  'Which recurring expenses could I review to cut my monthly costs?',
]

export function useServerAi() {
  return useQuery({ queryKey: ['meta-ai'], queryFn: async () => ((await api.meta()) as { ai?: ServerAi }).ai ?? null, staleTime: 5 * 60_000 })
}

export function Assistant({ go }: PageProps) {
  const l = useLedger()
  const server = useServerAi()
  const [s, setS] = useState<AiSettings>(loadAi)
  const [turns, setTurns] = useState<Turn[]>([])
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [setup, setSetup] = useState(false)
  const [consent, setConsent] = useState<string | null>(null)
  const abort = useRef<AbortController | null>(null)
  const end = useRef<HTMLDivElement>(null)
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [turns, busy])
  const update = (patch: Partial<AiSettings>) => { const n = { ...s, ...patch }; setS(n); saveAi(n) }
  const dest = destination(s, server.data)
  const configured = s.mode === 'server' ? !!server.data?.enabled : s.mode === 'direct' && !!s.baseUrl

  const send = async (question: string, agreed = false) => {
    const text = question.trim()
    if (!text || busy) return
    if (!configured) { setSetup(true); return }
    if (!agreed && s.consentFor !== dest) { setConsent(text); return }
    setBusy(true); setQ('')
    abort.current = new AbortController()
    try {
      const t = await ask(s, { ledgerId: l.id, base: l.base_currency, share: s.shareDetails }, l.name, turns, text, abort.current.signal)
      setTurns((x) => [...x, t])
    } catch (e) {
      if ((e as Error).name === 'AbortError') { setTurns((x) => [...x, { question: text, answer: '', runs: [], mode: 'tools', error: 'Stopped.' }]) }
      else setTurns((x) => [...x, { question: text, answer: '', runs: [], mode: 'tools', error: e instanceof AiError ? e.message : `Something went wrong: ${(e as Error).message}` }])
    } finally { setBusy(false); abort.current = null }
  }

  return (
    <div className="assistant">
      <div className="page-head">
        <h1>Assistant</h1>
        <div className="row">
          {turns.length > 0 && <button onClick={() => setTurns([])}>New conversation</button>}
          <button onClick={() => setSetup(true)}><Icon name="settings" size={16} /> {configured ? (s.mode === 'server' ? 'Via server' : dest) : 'Set up'}</button>
        </div>
      </div>
      {!configured && (
        <Card className="mb onboarding">
          <h2><Icon name="sparkles" /> Ask about your money in plain words</h2>
          <p className="muted">Connect a language model: a cloud service such as OpenAI or OpenRouter, or a model on your own computer or home server (Ollama, LM Studio, vLLM). The assistant reads your records only through read-only lookups, shows what it used for every answer, and can never change anything.</p>
          <button className="primary" onClick={() => setSetup(true)}>Set up the assistant</button>
        </Card>
      )}
      <div className="chat" aria-live="polite">
        {turns.length === 0 && configured && (
          <div className="suggestions">
            <p className="muted small">Try asking:</p>
            {SUGGESTIONS.map((x) => <button key={x} className="chip-btn" onClick={() => send(x)}>{x}</button>)}
          </div>
        )}
        {turns.map((t, i) => <TurnView key={i} t={t} go={go} />)}
        {busy && <div className="msg assistant-msg"><div className="bubble"><span className="typing" aria-label="Thinking"><i /><i /><i /></span> Looking through your records…</div></div>}
        <div ref={end} />
      </div>
      <form className="ask-bar" onSubmit={(e) => { e.preventDefault(); send(q) }}>
        <textarea value={q} onChange={(e) => setQ(e.target.value)} rows={1} placeholder={configured ? 'Ask about your spending, loans, deposits, budgets…' : 'Set up the assistant to start'} aria-label="Question"
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(q) } }} />
        {busy ? <button type="button" onClick={() => abort.current?.abort()}>Stop</button> : <button className="primary" disabled={!q.trim()} aria-label="Send"><Icon name="send" size={18} /></button>}
      </form>
      <p className="muted small center">Answers are generated by a language model from your records. Check important figures on the linked screens. Calculations and balances come from LekhaMint, not the model.</p>
      {setup && <AiSetup s={s} server={server.data ?? null} onSave={(n) => { setS(n); saveAi(n); setSetup(false) }} onClose={() => setSetup(false)} />}
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

function TurnView({ t, go }: { t: Turn; go: PageProps['go'] }) {
  const [open, setOpen] = useState(false)
  const links = [...new Map(t.runs.filter((r) => r.link).map((r) => [r.link!.route + JSON.stringify(r.link!.params), r.link!])).values()]
  return (
    <>
      <div className="msg user-msg"><div className="bubble">{t.question}</div></div>
      <div className="msg assistant-msg">
        <div className="bubble">
          {t.error ? <p className="error">{t.error}</p> : <Markdown text={t.answer || '(No answer.)'} />}
          {t.mode === 'snapshot' && !t.error && <p className="muted small"><Icon name="info" size={12} /> This model can't look things up, so it answered from a summary of this month, last month, loans, deposits, debts and what is coming up.</p>}
          {(t.runs.length > 0 || links.length > 0) && (
            <div className="sources">
              {links.map((x) => <button key={x.route + JSON.stringify(x.params)} className="chip-btn" onClick={() => go(x.route as Route, x.params)}>Open {x.route === 'tools' ? 'net worth' : x.route}</button>)}
              {t.runs.length > 0 && <button className="link small" onClick={() => setOpen(!open)}>{open ? 'Hide' : 'Show'} the {t.runs.length} {t.runs.length === 1 ? 'lookup' : 'lookups'} behind this answer</button>}
            </div>
          )}
          {open && t.runs.map((r, i) => (
            <details key={i} className="lookup"><summary><code>{r.name}</code> {Object.keys(r.args).length ? <span className="muted small">{JSON.stringify(r.args)}</span> : ''}{r.error && <span className="error small"> · {r.error}</span>}</summary>
              <pre>{JSON.stringify(r.result, null, 2).slice(0, 8000)}</pre></details>
          ))}
        </div>
      </div>
    </>
  )
}

/** Renders the model's answer: paragraphs, bullet and numbered lists, **bold** and `code`. No HTML is interpreted. */
function Markdown({ text }: { text: string }) {
  const inline = (s: string): ReactNode[] => s.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((p, i) => p.startsWith('**') && p.endsWith('**') ? <strong key={i}>{p.slice(2, -2)}</strong> : p.startsWith('`') && p.endsWith('`') ? <code key={i}>{p.slice(1, -1)}</code> : <Fragment key={i}>{p}</Fragment>)
  const blocks: ReactNode[] = []
  let list: { ordered: boolean; items: string[] } | null = null
  const flush = () => { if (list) { const L = list.ordered ? 'ol' : 'ul'; blocks.push(<L key={blocks.length}>{list.items.map((x, i) => <li key={i}>{inline(x)}</li>)}</L>); list = null } }
  for (const raw of text.split('\n')) {
    const line = raw.trimEnd()
    const b = /^\s*[-*•]\s+(.*)$/.exec(line), n = /^\s*\d+[.)]\s+(.*)$/.exec(line), h = /^#{1,4}\s+(.*)$/.exec(line)
    if (b || n) { const ordered = !!n; if (list && list.ordered !== ordered) flush(); list ??= { ordered, items: [] }; list.items.push((b ?? n)![1]); continue }
    flush()
    if (h) blocks.push(<h4 key={blocks.length}>{inline(h[1])}</h4>)
    else if (line.trim()) blocks.push(<p key={blocks.length}>{inline(line)}</p>)
  }
  flush()
  return <div className="md">{blocks}</div>
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
      {s.mode !== 'off' && <button className="danger" onClick={() => { onSave({ ...s, mode: 'off' }); toast('Assistant turned off') }}>Turn off</button>}
      <span className="spacer" /><button onClick={onClose}>Cancel</button>
      <button className="primary" onClick={() => { if (mode === 'direct' && !baseUrl.trim()) { setTest({ ok: false, text: 'Enter the address of the model server' }); return } onSave(next()); toast('Assistant settings saved on this device') }}>Save</button>
    </>}>
      <Segmented label="Connection" value={mode} onChange={setMode} options={[['direct', 'Connect from this browser'], ['server', 'Through my LekhaMint server']]} />
      {mode === 'server' ? (
        <div className="subtle-box small">
          {server?.enabled ? <p>Your server forwards questions to <strong>{server.host}</strong>{server.model ? ` using ${server.model}` : ''}. Its key stays on the server.</p>
            : <p>Not set up on this server. The admin can set <code>LEKHAMINT_AI_BASE_URL</code>, <code>LEKHAMINT_AI_API_KEY</code> and <code>LEKHAMINT_AI_MODEL</code> (for example Ollama on the same machine: <code>http://localhost:11434/v1</code>) and restart.</p>}
          <p className="muted">Good for a model on your home server: no browser CORS settings needed, and the API key never reaches your devices.</p>
          <Field label="Model (optional)" hint="Leave empty to use the server's default"><input value={model} onChange={(e) => setModel(e.target.value)} placeholder={server?.model || 'default'} /></Field>
        </div>
      ) : <>
        <Field label="Provider"><select value={provider} onChange={(e) => { setProvider(e.target.value); const u = PROVIDERS.find((x) => x.id === e.target.value)?.url; if (u !== undefined && (u || e.target.value === 'custom')) setBaseUrl(u) }}>{PROVIDERS.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</select></Field>
        <p className="muted small">{p.note}</p>
        <div className="two">
          <Field label="Base URL" hint="Up to /v1, without /chat/completions"><input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://…/v1" spellCheck={false} /></Field>
          <Field label={p.key ? 'API key' : 'API key (if your server needs one)'} hint="Stored only in this browser"><input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} autoComplete="off" /></Field>
        </div>
        <Field label="Model" hint={models.length ? undefined : 'For example gpt-4o-mini, openai/gpt-4o-mini, llama3.1:8b or qwen2.5:7b'}>
          {models.length ? <select value={model} onChange={(e) => setModel(e.target.value)}>{models.map((x) => <option key={x}>{x}</option>)}</select> : <input value={model} onChange={(e) => setModel(e.target.value)} spellCheck={false} />}
        </Field>
      </>}
      <div className="row"><button onClick={check} disabled={testing}>{testing ? 'Testing…' : 'Test connection'}</button>{test && <span className={test.ok ? 'pos-text small' : 'error small'}>{test.text}</span>}</div>
      <h3 className="section-title">Privacy and capability</h3>
      <Check checked={share} onChange={setShare}>Include payee names, notes and people's names in what the model sees</Check>
      <p className="muted small">Off: the model sees amounts, dates, categories and accounts, but not who you paid or what notes say. Answers about specific shops or people will be less precise.</p>
      <Check checked={tools} onChange={setTools}>The model can call tools (look things up)</Check>
      <p className="muted small">Most recent models can (GPT-4o, Llama 3.1+, Qwen 2.5+, Mistral). Small or older models may not; untick this and the assistant sends a short summary instead, which answers fewer questions. LekhaMint also switches automatically when a model refuses tools.</p>
      <p className="muted small">On a phone, you can point the Android app at a model on your home server or one running on the phone itself (for example with an app that offers an OpenAI-compatible server on localhost).</p>
    </Modal>
  )
}
