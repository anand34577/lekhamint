// The assistant (docs/11-assistant.md). It talks to any OpenAI-compatible
// chat endpoint: a cloud service, a home server (Ollama, vLLM, LM Studio) or
// the LekhaMint server's proxy. The model never sees the database: it calls
// read-only tools that this file runs against the user's own records, and
// every figure in a tool result is calculated here, not by the model.

import { api, type Debt, type Deposit, type Goal, type Loan, type RecurringRule } from './api'
import { DEMO } from './demo'
import { estimate } from './deposits'
import { loanStatus } from './loans'
import { currency, formatMoney } from './money'
import { dueDates } from './recurrence'
import { allTransactions, presets } from './reports'
import { addDays, isoDate, monthRange } from './util'
import { people, type Move } from './debts'
import { STATUS_TEXT, goalProgress } from './goals'
import { getSetting, setSetting } from './settings'

// --- Settings (per browser; the API key never syncs) ---------------------------------------

export type AiMode = 'off' | 'direct' | 'server'
export interface AiSettings {
  mode: AiMode
  baseUrl: string
  apiKey: string
  model: string
  /** Payee names and notes are included in tool results only when true. */
  shareDetails: boolean
  /** The user agreed to send data to this endpoint. */
  consentFor: string | null
  /** Off for models that cannot call tools; a summary is sent instead. */
  tools: boolean
}

export const PROVIDERS: { id: string; name: string; url: string; key: boolean; note: string }[] = [
  { id: 'openai', name: 'OpenAI', url: 'https://api.openai.com/v1', key: true, note: 'Cloud. Your questions and the records the assistant looks up are sent to OpenAI.' },
  { id: 'openrouter', name: 'OpenRouter', url: 'https://openrouter.ai/api/v1', key: true, note: 'Cloud gateway to many models. Data goes to OpenRouter and the model provider you pick.' },
  { id: 'groq', name: 'Groq (free tier)', url: 'https://api.groq.com/openai/v1', key: true, note: 'Cloud, fast, with a free tier: sign in at console.groq.com, create an API key and paste it here. Try the model llama-3.3-70b-versatile. Questions and looked-up records go to Groq.' },
  { id: 'gemini', name: 'Google Gemini (free tier)', url: 'https://generativelanguage.googleapis.com/v1beta/openai', key: true, note: 'Cloud, with a free tier: create a key at aistudio.google.com and paste it here. Try gemini-2.0-flash. Questions and looked-up records go to Google.' },
  { id: 'ollama', name: 'Ollama (this computer or home server)', url: 'http://localhost:11434/v1', key: false, note: 'Free and private: runs on your own computer. 1) Install Ollama from ollama.com. 2) In a terminal run: ollama pull llama3.1. 3) Allow this site: set OLLAMA_ORIGINS to this page’s address (or *) and restart Ollama. Then press Test connection.' },
  { id: 'lmstudio', name: 'LM Studio', url: 'http://localhost:1234/v1', key: false, note: 'Local server in LM Studio. Enable CORS in its server settings.' },
  { id: 'vllm', name: 'vLLM / llama.cpp server', url: 'http://localhost:8000/v1', key: false, note: 'Any OpenAI-compatible server you run yourself.' },
  { id: 'custom', name: 'Other OpenAI-compatible', url: '', key: true, note: 'Enter the base URL that ends before /chat/completions.' },
]

const DEFAULTS: AiSettings = { mode: 'off', baseUrl: '', apiKey: '', model: '', shareDetails: true, consentFor: null, tools: true }

// The settings live with the person's other settings on the server (lib/settings), so they are
// the same in every browser. The API key is never among them: it lives in memory for this tab,
// and its durable copy is the server's own key store, which returns it only to its owner.
let memKey = ''
let serverKey: string | null = null

function readStored(): AiSettings {
  return { ...DEFAULTS, ...getSetting<Partial<AiSettings>>('assistant', {}) }
}
function writeStored(s: AiSettings) {
  const { apiKey: _drop, ...rest } = s
  setSetting('assistant', rest)
}

/** Settings for this tab. The key comes from memory; call [hydrateKey] to load
 *  the server copy once (used when the assistant connects from the browser). */
export function loadAi(): AiSettings {
  const s = readStored()
  if (s.apiKey) { // one-time move out of localStorage, from older builds
    memKey = s.apiKey
    writeStored({ ...s, apiKey: '' })
    if (!DEMO) api.saveAiKey(memKey).catch(() => { /* offline: kept in memory */ })
  }
  s.apiKey = memKey
  return s
}

export function saveAi(s: AiSettings) { memKey = s.apiKey; writeStored(s) }

/** Saves or clears the key on the server (its durable home). */
export function persistAiKey(s: AiSettings): Promise<unknown> {
  if (DEMO) return Promise.resolve()
  if (s.apiKey) { serverKey = s.apiKey; return api.saveAiKey(s.apiKey) }
  if (serverKey) { serverKey = ''; return api.clearAiKey() }
  return Promise.resolve()
}

/** Loads the key saved on this server into memory, once. */
export async function hydrateKey(): Promise<string> {
  if (serverKey === null && !DEMO) {
    try { serverKey = (await api.aiKey()).key } catch { serverKey = '' }
  }
  if (!memKey && serverKey) memKey = serverKey
  return memKey
}

/** Where data goes, in words, for the consent prompt. */
export function destination(s: AiSettings, serverInfo?: ServerAi | null): string {
  if (s.mode === 'server') return serverInfo?.host ? `your LekhaMint server, which forwards to ${serverInfo.host}` : 'your LekhaMint server'
  try { return new URL(s.baseUrl).host } catch { return s.baseUrl || 'the address you entered' }
}

export interface ServerAi { enabled: boolean; model: string; host: string }

// --- Chat transport ------------------------------------------------------------------------

export interface ChatMessage { role: 'system' | 'user' | 'assistant' | 'tool'; content: string | null; tool_calls?: ToolCall[]; tool_call_id?: string; name?: string }
export interface ToolCall { id: string; type: 'function'; function: { name: string; arguments: string } }

export class AiError extends Error {
  constructor(message: string, public kind: 'config' | 'network' | 'auth' | 'tools' | 'provider' | 'cors') { super(message) }
}

/** One chat completion. With [onText] the answer is streamed and [onText] gets the text so far. */
async function complete(s: AiSettings, messages: ChatMessage[], withTools: boolean, signal?: AbortSignal, onText?: (text: string) => void): Promise<ChatMessage> {
  try {
    return await completeOnce(s, messages, withTools, signal, onText)
  } catch (e) {
    // A few servers refuse streaming (or streaming with tools); ask again for one response.
    if (onText && e instanceof AiError && e.kind === 'provider' && /stream/i.test(e.message)) return completeOnce(s, messages, withTools, signal)
    throw e
  }
}

async function completeOnce(s: AiSettings, messages: ChatMessage[], withTools: boolean, signal?: AbortSignal, onText?: (text: string) => void): Promise<ChatMessage> {
  const body: Record<string, unknown> = { model: s.model || undefined, messages, temperature: 0.2 }
  if (withTools) { body.tools = TOOL_SPECS; body.tool_choice = 'auto' }
  if (onText) body.stream = true
  let res: Response
  try {
    if (s.mode === 'server') {
      // The session cookie authenticates this call; the X-Kosh-Client header is
      // the CSRF marker the server requires on cookie-authenticated writes.
      res = await fetch('/api/v1/ai/chat/completions', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Kosh-Client': 'web' }, body: JSON.stringify(body), signal })
    } else {
      if (!s.baseUrl) throw new AiError('Choose a provider and enter its address in the assistant settings.', 'config')
      res = await fetch(s.baseUrl.replace(/\/+$/, '') + '/chat/completions', {
        method: 'POST', signal,
        headers: { 'Content-Type': 'application/json', ...(s.apiKey ? { Authorization: `Bearer ${s.apiKey}` } : {}), 'X-Title': 'LekhaMint' },
        body: JSON.stringify(body),
      })
    }
  } catch (e) {
    if (e instanceof AiError || (e as Error).name === 'AbortError') throw e
    throw new AiError(s.mode === 'direct' && /localhost|127\.0\.0\.1|192\.168|\.local/.test(s.baseUrl)
      ? "Couldn't reach the model. Check that the server is running, and that it allows requests from this page (CORS: OLLAMA_ORIGINS for Ollama, “Enable CORS” in LM Studio). Or choose “Through my LekhaMint server”."
      : "Couldn't reach the model. Check the address and your connection. Some providers block requests from browsers; “Through my LekhaMint server” avoids that.", 'cors')
  }
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`
    try { const j = await res.json(); msg = j.error?.message ?? j.message ?? msg } catch { /* not JSON */ }
    if (res.status === 401 || res.status === 403) throw new AiError(`The provider refused the request (${msg}). Check the API key.`, 'auth')
    if (res.status === 404) throw new AiError(`Not found (${msg}). Check the base URL and the model name.`, 'config')
    if (res.status === 503 && s.mode === 'server') throw new AiError('The assistant is not set up on this LekhaMint server. Ask the admin to set LEKHAMINT_AI_BASE_URL, or connect directly.', 'config')
    if (withTools && res.status === 400 && /tool|function/i.test(msg)) throw new AiError(msg, 'tools')
    throw new AiError(`The model returned an error: ${msg}`, 'provider')
  }
  if (onText && res.body && (res.headers.get('Content-Type') ?? '').includes('text/event-stream')) return readStream(res.body, onText)
  const j = await res.json()
  const m = j.choices?.[0]?.message
  if (!m) throw new AiError('The model sent an empty answer.', 'provider')
  if (m.content) onText?.(m.content)
  return { role: 'assistant', content: m.content ?? null, tool_calls: m.tool_calls }
}

/** Reads an OpenAI-style event stream: text deltas, and tool calls assembled from their pieces. */
export async function readStream(stream: ReadableStream<Uint8Array>, onText: (text: string) => void): Promise<ChatMessage> {
  const reader = stream.pipeThrough(new TextDecoderStream()).getReader()
  let buf = '', content = ''
  const calls: ToolCall[] = []
  const take = (line: string) => {
    if (!line.startsWith('data:')) return
    const data = line.slice(5).trim()
    if (!data || data === '[DONE]') return
    let j: { error?: { message?: string }; choices?: { delta?: { content?: string | null; tool_calls?: { index?: number; id?: string; function?: { name?: string; arguments?: string } }[] } }[] }
    try { j = JSON.parse(data) } catch { return }
    if (j.error) throw new AiError(`The model returned an error: ${j.error.message ?? 'unknown error'}`, 'provider')
    const d = j.choices?.[0]?.delta
    if (!d) return
    if (d.content) { content += d.content; onText(content) }
    for (const tc of d.tool_calls ?? []) {
      const c = (calls[tc.index ?? 0] ??= { id: '', type: 'function', function: { name: '', arguments: '' } })
      if (tc.id) c.id = tc.id
      if (tc.function?.name) c.function.name += tc.function.name
      if (tc.function?.arguments) c.function.arguments += tc.function.arguments
    }
  }
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += value
    let i: number
    while ((i = buf.indexOf('\n')) >= 0) { take(buf.slice(0, i).trim()); buf = buf.slice(i + 1) }
  }
  take(buf.trim())
  const toolCalls = calls.filter(Boolean).map((c, i) => ({ ...c, id: c.id || `call_${i}` }))
  if (!content && !toolCalls.length) throw new AiError('The model sent an empty answer.', 'provider')
  return { role: 'assistant', content: content || null, tool_calls: toolCalls.length ? toolCalls : undefined }
}

// --- Tools ---------------------------------------------------------------------------------

const P = (props: Record<string, unknown>, required: string[] = []) => ({ type: 'object', properties: props, required })
const DATE = { type: 'string', description: 'YYYY-MM-DD' }

export const TOOL_SPECS = [
  { type: 'function', function: { name: 'overview', description: 'Net worth, cash, investments, money owed to and by the user, and this month\'s income and spending. Start here for general questions.', parameters: P({}) } },
  { type: 'function', function: { name: 'spending_summary', description: 'Income and spending totals for a period, by top-level category and subcategory, plus the same-length previous period for comparison. Transfers, loan principal and money lent are never spending.', parameters: P({ from: DATE, to: DATE }, ['from', 'to']) } },
  { type: 'function', function: { name: 'search_transactions', description: 'Find individual transactions. Use for questions about specific payees, items or dates. Returns at most 50 rows plus the total count and sum of all matches.', parameters: P({ from: DATE, to: DATE, text: { type: 'string', description: 'Matches payee or note' }, category: { type: 'string', description: 'Category name (matches subcategories too)' }, type: { type: 'string', enum: ['expense', 'income', 'transfer'] }, min_amount: { type: 'number', description: 'In major units, e.g. 500 for ₹500' }, max_amount: { type: 'number' } }) } },
  { type: 'function', function: { name: 'monthly_trend', description: 'Income and spending per month for the last N months (default 12), optionally for one category.', parameters: P({ months: { type: 'integer' }, category: { type: 'string' } }) } },
  { type: 'function', function: { name: 'accounts', description: 'Every account with its current balance and type.', parameters: P({}) } },
  { type: 'function', function: { name: 'loans', description: 'Bank loans: outstanding principal, EMI, next due date, instalments paid and left, interest paid and still to pay.', parameters: P({}) } },
  { type: 'function', function: { name: 'deposits', description: 'Fixed and recurring deposits: amount now, rate, maturity date and expected maturity value (estimate unless the bank figure was entered).', parameters: P({}) } },
  { type: 'function', function: { name: 'people_debts', description: 'Money lent to and borrowed from friends and family: who owes what, due dates, overdue.', parameters: P({}) } },
  { type: 'function', function: { name: 'upcoming', description: 'Bills, EMIs, deposit maturities and personal debts due in the next N days (default 30).', parameters: P({ days: { type: 'integer' } }) } },
  { type: 'function', function: { name: 'budgets', description: 'Active budgets with limit, spent, left and days left.', parameters: P({}) } },
  { type: 'function', function: { name: 'goals', description: 'Savings goals: target, saved so far, percent, target date, monthly saving needed and whether on track.', parameters: P({}) } },
  { type: 'function', function: { name: 'net_worth_history', description: 'Month-end net worth, assets and debts for the last N months (default 12).', parameters: P({ months: { type: 'integer' } }) } },
]

export interface ToolRun { name: string; args: Record<string, unknown>; result: unknown; error?: string; link?: { route: string; params: Record<string, string> } }

/** Friendly names for the chat UI; falls back to the raw tool name. */
export const TOOL_LABELS: Record<string, string> = {
  overview: 'Money overview',
  spending_summary: 'Spending for a period',
  search_transactions: 'Transaction search',
  monthly_trend: 'Monthly trend',
  accounts: 'Account balances',
  loans: 'Loans & EMIs',
  deposits: 'Deposits',
  people_debts: 'People & debts',
  upcoming: 'What is coming up',
  budgets: 'Budgets',
  net_worth_history: 'Net worth history',
  goals: 'Goals',
}
export const toolLabel = (name: string): string => TOOL_LABELS[name] ?? name

interface Ctx { ledgerId: string; base: string; share: boolean }

const money = (v: number, cur: string) => ({ amount: formatMoney(v, cur), minor_units: v })

async function lookups(c: Ctx) {
  const [accounts, categories] = await Promise.all([api.records<{ name: string; type: string; currency: string; archived: boolean }>(c.ledgerId, 'accounts'), api.records<{ name: string; kind: string; parent_id: string | null }>(c.ledgerId, 'categories')])
  return {
    acc: new Map(accounts.filter((a) => !a.deleted).map((a) => [a.id, a.data])),
    cat: new Map(categories.filter((x) => !x.deleted).map((x) => [x.id, x.data])),
  }
}

function findCategory(cat: Map<string, { name: string; kind: string; parent_id: string | null }>, name?: string): string | undefined {
  if (!name) return undefined
  const n = name.trim().toLowerCase()
  for (const [id, c] of cat) if (c.name.toLowerCase() === n) return id
  for (const [id, c] of cat) if (c.name.toLowerCase().includes(n)) return id
  return undefined
}

const validDate = (s: unknown) => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s)

export async function runTool(c: Ctx, name: string, args: Record<string, unknown>): Promise<ToolRun> {
  const today = isoDate()
  const run: ToolRun = { name, args, result: null }
  try {
    switch (name) {
      case 'overview': {
        const [pos, [from, to]] = [await api.position(c.ledgerId), monthRange(today)]
        const sum = await api.summary(c.ledgerId, from, to)
        run.result = {
          as_of: today, note: 'Net worth = cash + investments + owed to you − cards & loans − you owe people. Credit limits are not assets.',
          positions: pos.map((p) => ({ currency: p.currency, net_worth: money(p.net_worth, p.currency), cash_and_bank: money(p.cash, p.currency), investments_and_deposits: money(p.investments, p.currency), owed_to_user: money(p.receivable, p.currency), owed_on_cards_and_loans: money(p.liabilities, p.currency), user_owes_people: money(p.payable, p.currency) })),
          this_month: { from, to, by_currency: sum.map((s) => ({ currency: s.currency, income: money(s.income, s.currency), spending: money(s.expense, s.currency) })) },
        }
        run.link = { route: 'dashboard', params: {} }
        break
      }
      case 'spending_summary': {
        if (!validDate(args.from) || !validDate(args.to)) throw new Error('from and to must be dates as YYYY-MM-DD')
        const from = args.from as string, to = args.to as string
        if (to < from) throw new Error('to is before from')
        const days = Math.round((Date.parse(to) - Date.parse(from)) / 864e5)
        const { cat } = await lookups(c)
        const [cur, prev] = await Promise.all([api.summary(c.ledgerId, from, to), api.summary(c.ledgerId, addDays(from, -days - 1), addDays(from, -1))])
        const shape = (list: typeof cur) => list.map((s) => {
          const roots = new Map<string, { total: number; sub: Map<string, number> }>()
          for (const x of s.expense_by_category) {
            const k = x.category_id ? cat.get(x.category_id) : undefined
            const root = k?.parent_id && cat.has(k.parent_id) ? cat.get(k.parent_id)!.name : k?.name ?? 'Uncategorised'
            const r = roots.get(root) ?? { total: 0, sub: new Map() }
            r.total += x.amount
            if (k?.parent_id) r.sub.set(k.name, (r.sub.get(k.name) ?? 0) + x.amount)
            roots.set(root, r)
          }
          return {
            currency: s.currency, income: money(s.income, s.currency), spending: money(s.expense, s.currency), net: money(s.net, s.currency),
            spending_by_category: [...roots].sort((a, b) => b[1].total - a[1].total).map(([n, r]) => ({ category: n, total: money(r.total, s.currency), share_of_spending: s.expense ? `${((r.total / s.expense) * 100).toFixed(1)}%` : '0%', subcategories: [...r.sub].sort((a, b) => b[1] - a[1]).map(([sn, v]) => ({ name: sn, total: money(v, s.currency) })) })),
            income_by_category: s.income_by_category.map((x) => ({ category: x.category_id ? cat.get(x.category_id)?.name ?? 'Unknown' : 'Uncategorised', total: money(x.amount, s.currency) })),
          }
        })
        run.result = { period: { from, to }, previous_period: { from: addDays(from, -days - 1), to: addDays(from, -1) }, current: shape(cur), previous: shape(prev) }
        run.link = { route: 'reports', params: {} }
        break
      }
      case 'search_transactions': {
        const { acc, cat } = await lookups(c)
        const params: Record<string, string> = {}
        if (validDate(args.from)) params.from = args.from as string
        if (validDate(args.to)) params.to = args.to as string
        if (typeof args.text === 'string' && args.text.trim()) params.q = args.text.trim()
        if (typeof args.type === 'string') params.type = args.type
        const catId = findCategory(cat, args.category as string | undefined)
        if (args.category && !catId) { run.result = { matches: 0, note: `No category named “${args.category}”. Categories: ${[...cat.values()].map((x) => x.name).join(', ')}` }; break }
        if (catId) params.category_id = catId
        const scale = 10 ** currency(c.base).digits
        if (typeof args.min_amount === 'number' && args.min_amount > 0) params.min_amount = String(Math.round(args.min_amount * scale))
        if (typeof args.max_amount === 'number' && args.max_amount > 0) params.max_amount = String(Math.round(args.max_amount * scale))
        if (!params.from && !params.to) params.from = monthRange(today, -11)[0]
        const txs = await allTransactions(c.ledgerId, params)
        const curOf = (id: string) => acc.get(id)?.currency ?? c.base
        const totals = new Map<string, { spent: number; received: number }>()
        for (const t of txs) {
          if (t.status === 'planned' || t.type === 'transfer') continue
          const k = curOf(t.account_id), x = totals.get(k) ?? { spent: 0, received: 0 }
          if (t.type === 'expense') x.spent += t.amount; else x.received += t.amount
          totals.set(k, x)
        }
        run.result = {
          filters: params, matches: txs.length,
          totals: [...totals].map(([k, v]) => ({ currency: k, spent: money(v.spent, k), received: money(v.received, k) })),
          note: 'Totals include every match; rows are the 50 most recent. Linked debt movements and loan principal are included here even though reports do not count them as spending.',
          rows: txs.slice(-50).reverse().map((t) => ({ date: t.local_date, type: t.type, amount: formatMoney(t.amount, curOf(t.account_id)), account: acc.get(t.account_id)?.name, category: t.category_id ? cat.get(t.category_id)?.name : null,
            ...(c.share ? { payee: t.payee, note: t.note } : {}), linked: t.loan_id ? 'loan' : t.deposit_id ? 'deposit' : t.debt_id ? 'lent/borrowed' : undefined, part: t.part ?? undefined, status: t.status })),
        }
        run.link = { route: 'transactions', params: { ...(params.q ? { q: params.q } : {}), ...(catId ? { category: catId } : {}), all: '1' } }
        break
      }
      case 'monthly_trend': {
        const n = Math.min(60, Math.max(1, Number(args.months) || 12))
        const from = monthRange(today, -(n - 1))[0], to = monthRange(today)[1]
        const { cat } = await lookups(c)
        const catId = findCategory(cat, args.category as string | undefined)
        if (catId) {
          const months: { month: string; spent: number }[] = []
          for (let i = n - 1; i >= 0; i--) {
            const [f, t] = monthRange(today, -i)
            const s = (await api.summary(c.ledgerId, f, t)).find((x) => x.currency === c.base)
            const total = (s?.expense_by_category ?? []).filter((x) => x.category_id === catId || (x.category_id && cat.get(x.category_id)?.parent_id === catId)).reduce((a, x) => a + x.amount, 0)
            months.push({ month: f.slice(0, 7), spent: total })
          }
          run.result = { category: cat.get(catId)!.name, currency: c.base, months: months.map((m) => ({ month: m.month, spent: money(m.spent, c.base) })), total: money(months.reduce((a, m) => a + m.spent, 0), c.base) }
        } else {
          const pts = await api.trend(c.ledgerId, from, to, 'month')
          run.result = { from, to, months: pts.map((p) => ({ month: p.period, currency: p.currency, income: money(p.income, p.currency), spending: money(p.expense, p.currency) })) }
        }
        run.link = { route: 'reports', params: {} }
        break
      }
      case 'accounts': {
        const b = await api.balances(c.ledgerId)
        run.result = b.filter((x) => !x.archived).map((x) => ({ name: x.name, type: x.type, currency: x.currency, balance: money(x.balance, x.currency), counts_in_net_worth: x.include_in_net_worth, note: x.type === 'credit_card' || x.type === 'loan' ? 'negative = owed' : undefined }))
        run.link = { route: 'accounts', params: {} }
        break
      }
      case 'loans': {
        const [loans, balances, moves] = await Promise.all([api.records<Loan>(c.ledgerId, 'loans'), api.balances(c.ledgerId), allTransactions(c.ledgerId, { loan_id: 'any' })])
        run.result = loans.filter((r) => !r.deleted).map((r) => {
          const l = r.data, s = loanStatus(l, moves.filter((m) => m.loan_id === r.id), balances.find((b) => b.account_id === l.account_id)?.balance ?? 0, today, l.account_id)
          return { name: l.name, lender: l.lender, kind: l.kind, closed: l.closed, rate: l.annual_rate ? `${l.annual_rate}% ${l.rate_type}` : 'interest-free', borrowed: money(l.principal, l.currency), outstanding: money(s.outstanding, l.currency), emi: money(s.emi, l.currency),
            next_due: s.nextDue, overdue_instalments: s.overdue, instalments_paid: s.paidInstalments, instalments_left: s.remainingInstalments, expected_end: s.endDate,
            interest_paid_so_far: money(s.interestPaid, l.currency), interest_still_to_pay_estimate: money(s.projectedInterest, l.currency) }
        })
        run.link = { route: 'loans', params: {} }
        break
      }
      case 'deposits': {
        const [deps, balances] = await Promise.all([api.records<Deposit>(c.ledgerId, 'deposits'), api.balances(c.ledgerId)])
        run.result = deps.filter((r) => !r.deleted).map((r) => {
          const d = r.data, e = estimate(d, today)
          return { name: d.name, institution: d.institution, kind: d.kind, status: d.status, rate: `${d.annual_rate}%`, start: d.start_date, matures: d.maturity_date, now_in_deposit: money(balances.find((b) => b.account_id === d.account_id)?.balance ?? 0, d.currency),
            at_maturity: money(e.maturity, d.currency), interest_over_term: money(e.interest, d.currency), basis: e.fromBank ? "bank's figure" : 'estimate from the terms' }
        })
        run.link = { route: 'deposits', params: {} }
        break
      }
      case 'people_debts': {
        const [debts, moves] = await Promise.all([api.records<Debt>(c.ledgerId, 'debts'), allTransactions(c.ledgerId, { debt_id: 'any' })])
        const mv = new Map<string, Move[]>()
        for (const m of moves) if (m.debt_id) mv.set(m.debt_id, [...(mv.get(m.debt_id) ?? []), m])
        const list = people(debts.filter((r) => !r.deleted).map((r) => ({ ...r.data, id: r.id })), mv, today)
        run.result = list.filter((p) => p.open > 0).map((p, i) => ({ person: c.share ? p.name : `Person ${i + 1}`, currency: p.currency, ...(p.net > 0 ? { owes_user: money(p.net, p.currency) } : { user_owes: money(-p.net, p.currency) }), open_debts: p.open, next_due: p.nextDue, overdue: p.overdue }))
        run.link = { route: 'debts', params: {} }
        break
      }
      case 'upcoming': {
        const days = Math.min(365, Math.max(1, Number(args.days) || 30))
        const until = addDays(today, days)
        const [rules, loans, deps, debts, balances, moves, accounts] = await Promise.all([api.records<RecurringRule>(c.ledgerId, 'recurring_rules'), api.records<Loan>(c.ledgerId, 'loans'), api.records<Deposit>(c.ledgerId, 'deposits'), api.records<Debt>(c.ledgerId, 'debts'), api.balances(c.ledgerId), allTransactions(c.ledgerId, { loan_id: 'any' }), api.records<{ currency: string }>(c.ledgerId, 'accounts')])
        const curOf = (id: string) => accounts.find((a) => a.id === id)?.data.currency ?? c.base
        const items: { date: string; what: string; amount?: ReturnType<typeof money>; kind: string; overdue?: boolean }[] = []
        for (const r of rules) if (!r.deleted && !r.data.paused) for (const d of dueDates(r.data, until, 6)) items.push({ date: d, what: r.data.name || r.data.payee || 'Bill', amount: money(r.data.amount, curOf(r.data.account_id)), kind: r.data.type === 'income' ? 'expected income' : 'bill', overdue: d < today })
        for (const r of loans) {
          if (r.deleted || r.data.closed) continue
          const s = loanStatus(r.data, moves.filter((m) => m.loan_id === r.id), balances.find((b) => b.account_id === r.data.account_id)?.balance ?? 0, today, r.data.account_id)
          for (const row of s.projection.rows) { if (row.date > until) break; items.push({ date: row.date, what: `${r.data.name} EMI`, amount: money(row.payment, r.data.currency), kind: 'emi', overdue: row.date < today }) }
        }
        for (const r of deps) if (!r.deleted && r.data.status === 'active' && r.data.maturity_date <= until) items.push({ date: r.data.maturity_date, what: `${r.data.name} matures`, amount: money(estimate(r.data, today).maturity, r.data.currency), kind: 'deposit maturity (money coming in)', overdue: r.data.maturity_date < today })
        for (const r of debts) if (!r.deleted && !r.data.closed && r.data.due_date && r.data.due_date <= until) items.push({ date: r.data.due_date, what: r.data.direction === 'lent' ? `${c.share ? r.data.person : 'Someone'} should repay you` : `You should repay ${c.share ? r.data.person : 'someone'}`, kind: 'personal debt', overdue: r.data.due_date < today })
        run.result = { from: today, to: until, note: 'Expected payments are not confirmed until recorded. Overdue means the due date passed with no payment recorded.', items: items.sort((a, b) => a.date.localeCompare(b.date)) }
        run.link = { route: 'recurring', params: {} }
        break
      }
      case 'budgets': {
        const [prog, budgets] = await Promise.all([api.budgetProgress(c.ledgerId, today), api.records<{ name: string }>(c.ledgerId, 'budgets')])
        run.result = prog.filter((p) => p.active).map((p) => ({ budget: budgets.find((b) => b.id === p.budget_id)?.data.name, period: p.window, limit: money(p.limit, p.currency), spent: money(p.spent, p.currency), left: money(p.limit - p.spent, p.currency), days_left: p.days_left, used: p.limit ? `${Math.round((p.spent / p.limit) * 100)}%` : '—' }))
        run.link = { route: 'budgets', params: {} }
        break
      }
      case 'goals': {
        const [goals, balances] = await Promise.all([api.records<Goal>(c.ledgerId, 'goals'), api.balances(c.ledgerId)])
        run.result = goals.filter((r) => !r.deleted && !r.data.archived).map((r) => {
          const g = r.data, p = goalProgress(g, balances)
          return { name: g.name, target: money(p.target, g.currency), saved: money(p.saved, g.currency), percent: `${Math.floor(p.fraction * 100)}%`, target_date: g.target_date,
            status: STATUS_TEXT[p.status].toLowerCase(), ...(p.perMonth ? { per_month: money(p.perMonth, g.currency) } : {}), still_to_save: money(p.left, g.currency) }
        })
        run.link = { route: 'goals', params: {} }
        break
      }
      case 'net_worth_history': {
        const n = Math.min(120, Math.max(1, Number(args.months) || 12))
        const pts = await api.netWorth(c.ledgerId, n)
        run.result = pts.map((p) => ({ date: p.date, positions: p.positions.map((x) => ({ currency: x.currency, net_worth: money(x.net_worth, x.currency), assets: money(x.cash + x.investments + x.receivable, x.currency), debts: money(x.liabilities + x.payable, x.currency) })) }))
        run.link = { route: 'tools', params: { tool: 'networth' } }
        break
      }
      default: throw new Error(`Unknown tool ${name}`)
    }
  } catch (e) {
    run.error = (e as Error).message
    run.result = { error: run.error }
  }
  return run
}

// --- Conversation ----------------------------------------------------------------------

export function systemPrompt(base: string, bookName: string): string {
  const fy = presets(isoDate()).find(([k]) => k === 'fy')
  return [
    `You are the LekhaMint assistant for the book “${bookName}”. Today is ${isoDate()}. The main currency is ${base}.`,
    fy ? `The current financial year is ${fy[1]} (${fy[2][0]} to ${fy[2][1]}). “This month” is ${monthRange(isoDate()).join(' to ')}.` : '',
    'Answer only from the user\'s own records, which you get by calling the tools. Never invent transactions, balances, rates or dates.',
    'Use the amounts the tools return; they are exact. Prefer tool totals over adding numbers yourself. If you must combine figures, say so.',
    'Always say which period and which records an answer is based on. If the data needed is missing or ambiguous, say what is missing instead of guessing.',
    'Keep these distinctions: transfers between own accounts, loan principal, money lent or borrowed and deposit movements are not income or spending; interest and fees are. Credit limits are not money the user has. Projected EMIs and maturity values are estimates, not confirmed payments.',
    'Separate facts from suggestions: label suggestions as suggestions. You cannot change any records; if asked to, explain which screen does it.',
    'Be brief and clear. Use short paragraphs or bullet points, and format money as the tools do.',
  ].filter(Boolean).join('\n')
}

/** A compact snapshot for models without tool calling. */
export async function snapshot(c: Ctx): Promise<string> {
  const [from, to] = monthRange(isoDate())
  const [prevFrom, prevTo] = monthRange(isoDate(), -1)
  const parts = await Promise.all([
    runTool(c, 'overview', {}), runTool(c, 'spending_summary', { from: prevFrom, to: prevTo }), runTool(c, 'spending_summary', { from, to }),
    runTool(c, 'loans', {}), runTool(c, 'deposits', {}), runTool(c, 'people_debts', {}), runTool(c, 'upcoming', { days: 30 }), runTool(c, 'budgets', {}),
  ])
  return 'This model cannot look things up, so here is a summary of the records. Answer only from it, and say when a question needs data that is not here.\n\n' +
    parts.map((p) => `## ${p.name}${p.args.from ? ` ${p.args.from}..${p.args.to}` : ''}\n${JSON.stringify(p.result)}`).join('\n\n')
}

export interface Turn { question: string; answer: string; runs: ToolRun[]; mode: 'tools' | 'snapshot' | 'quick'; error?: string; /** When it was answered (ms). */ at?: number }

/** Asks one question with the conversation so far; runs up to 6 rounds of tool
 *  calls. [onRun] fires after each lookup so the UI can show live progress, and
 *  [onText] gets the answer as it is written (streamed). */
export async function ask(s: AiSettings, c: Ctx, bookName: string, history: Turn[], question: string, signal?: AbortSignal, onRun?: (r: ToolRun) => void, onText?: (text: string) => void): Promise<Turn> {
  const runs: ToolRun[] = []
  const msgs: ChatMessage[] = [{ role: 'system', content: systemPrompt(c.base, bookName) }]
  for (const t of history.slice(-6)) { msgs.push({ role: 'user', content: t.question }); msgs.push({ role: 'assistant', content: t.answer }) }
  if (!s.tools) {
    msgs.push({ role: 'system', content: await snapshot(c) }, { role: 'user', content: question })
    const m = await complete(s, msgs, false, signal, onText)
    return { question, answer: m.content ?? '', runs, mode: 'snapshot' }
  }
  msgs.push({ role: 'user', content: question })
  for (let round = 0; round < 6; round++) {
    let m: ChatMessage
    try { m = await complete(s, msgs, true, signal, onText) } catch (e) {
      if (e instanceof AiError && e.kind === 'tools') {
        // The model cannot call tools: fall back to a summary for this question.
        msgs.splice(1, 0, { role: 'system', content: await snapshot(c) })
        onText?.('')
        const plain = await complete(s, msgs.filter((x) => x.role !== 'tool' && !x.tool_calls), false, signal, onText)
        return { question, answer: plain.content ?? '', runs, mode: 'snapshot' }
      }
      throw e
    }
    if (!m.tool_calls?.length) return { question, answer: m.content ?? '', runs, mode: 'tools' }
    onText?.('') // any text before a lookup ("Let me check…") is not the answer
    msgs.push(m)
    for (const call of m.tool_calls) {
      let args: Record<string, unknown> = {}
      try { args = JSON.parse(call.function.arguments || '{}') } catch { /* the model sent bad JSON; run with no arguments */ }
      const r = await runTool(c, call.function.name, args)
      runs.push(r)
      onRun?.(r)
      msgs.push({ role: 'tool', tool_call_id: call.id, name: call.function.name, content: JSON.stringify(r.result).slice(0, 24000) })
    }
  }
  return { question, answer: 'I looked up a lot of records but could not finish the answer. Try a narrower question, for example one period or one category.', runs, mode: 'tools' }
}
