// Typed client for the LekhaMint REST API (docs/07-api.md). The web app talks to the
// server it is served from, so there is no CORS and no configuration.

import { DEMO, demoFetch } from './demo'

export interface User { id: string; email: string; name: string; is_admin: boolean }
export interface Ledger { id: string; name: string; base_currency: string; seq: number; role?: string }

export interface Rec<T> { entity: string; id: string; hlc: string; seq: number; deleted: boolean; data: T }

export interface Account {
  name: string; type: string; currency: string; opening_balance: number; credit_limit: number | null
  color: string | null; icon: string | null; include_in_net_worth: boolean; archived: boolean
  sort_order: number; note: string | null; created_at: number
}
export interface Category {
  name: string; kind: 'expense' | 'income'; parent_id: string | null; nature: string | null
  color: string | null; icon: string | null; archived: boolean; sort_order: number
}
export interface Transaction {
  type: 'expense' | 'income' | 'transfer'; status: string; account_id: string; amount: number
  to_account_id: string | null; to_amount: number | null; category_id: string | null
  payee: string | null; note: string | null; occurred_at: number; local_date: string; tz_offset_min: number
  tag_ids: string[]; is_adjustment: boolean; excluded_from_reports: boolean; recurring_rule_id: string | null
  debt_id?: string | null
}
export interface Budget {
  name: string; amount: number; currency: string; period: string; start_date: string; end_date: string | null
  category_ids: string[]; account_ids: string[]; rollover: boolean; alert_percent: number; archived: boolean
}
export interface RecurringRule {
  name: string | null; type: string; account_id: string; amount: number; category_id: string | null
  frequency: string; every: number; start_date: string; next_date: string | null; mode: string; paused: boolean; payee: string | null
  to_account_id?: string | null; to_amount?: number | null; end_date?: string | null; max_occurrences?: number | null
  note?: string | null; tag_ids?: string[]
}
export interface Debt { person: string; direction: 'lent' | 'borrowed'; amount: number; currency: string; due_date: string | null; note: string | null; closed: boolean; created_at: number }
export interface ExchangeRate { base: string; quote: string; rate: string; effective_date: string; source: string }
export interface PayeeTotal { payee: string; currency: string; expense: number; income: number; count: number; last_date: string }
export interface Session { id: string; device_name: string; created_at: number; last_used_at: number; expires_at: number; current: boolean }
export interface Member { ledger_id: string; user_id: string; email?: string; name?: string; role: 'owner' | 'editor' | 'viewer'; created_at: number }
export interface Tag { name: string; color: string | null; archived: boolean; created_at?: number }
export interface Rule {
  name: string | null; match_field: 'payee' | 'note' | 'any'; match_type: 'contains' | 'equals' | 'starts_with'; pattern: string
  tx_type: 'expense' | 'income' | null; account_id: string | null; min_amount: number | null; max_amount: number | null
  set_category_id: string | null; set_payee: string | null; add_tag_ids: string[]; sort_order: number; enabled: boolean; created_at: number
}
export interface Split { transaction_id: string; category_id: string; amount: number; note: string | null; sort_order: number }
export interface BudgetProgress {
  budget_id: string; active: boolean; window: { start: string; end: string }; amount: number; carry: number; limit: number
  spent: number; currency: string; days_left: number; per_day_left: number; pace_fraction: number
}
export interface TagTotal { tag_id: string; currency: string; expense: number; income: number; count: number }
export interface Converted { currency: string; income: number; expense: number; net: number; missing_rates: string[]; rates: { from: string; rate: string; effective_date: string; inverted: boolean }[] }
export interface BatchOp { op: 'create' | 'create_if_missing' | 'update' | 'delete' | 'restore'; entity: string; id?: string; data?: object }
export interface BatchResult { entity: string; id: string; status: 'created' | 'updated' | 'deleted' | 'restored' | 'skipped' }

/** Largest batch the server accepts (meta.limits.batch_ops). */
export const BATCH_OPS = 500
export interface TxRow extends Transaction { id: string; hlc: string; deleted: boolean }

export interface Balance { account_id: string; name: string; type: string; currency: string; archived: boolean; include_in_net_worth: boolean; opening_balance: number; balance: number }
export interface CategoryTotal { category_id: string | null; parent_id: string | null; name: string; amount: number; count: number }
export interface CurrencySummary { currency: string; income: number; expense: number; net: number; expense_by_category: CategoryTotal[]; income_by_category: CategoryTotal[] }
export interface TrendPoint { period: string; currency: string; income: number; expense: number }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>, public requestId?: string) { super(message) }
}

const TOKEN_KEY = 'kosh.token'

export const session = {
  get token(): string | null { try { return localStorage.getItem(TOKEN_KEY) } catch { return null } },
  set token(v: string | null) {
    try { if (v) localStorage.setItem(TOKEN_KEY, v); else localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ }
  },
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

async function call<T>(method: string, path: string, body?: unknown, raw = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const t = session.token
  if (t) headers.Authorization = `Bearer ${t}`
  let payload: BodyInit | undefined
  if (body instanceof Blob) payload = body
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body) }
  let res: Response
  try {
    const init = { method, headers, body: payload }
    res = await (DEMO ? demoFetch(`/api/v1${path}`, init) : fetch(`/api/v1${path}`, init))
  } catch {
    throw new ApiError(0, 'network', "Can't reach the LekhaMint server. Check your connection.")
  }
  if (res.status === 401 && t) { session.token = null; onUnauthorized() }
  if (!res.ok) {
    let code = `http_${res.status}`, message = res.statusText, fields
    try { const e = (await res.json()).error; code = e.code; message = e.message; fields = e.fields } catch { /* not JSON */ }
    throw new ApiError(res.status, code, message, fields, res.headers.get('X-Request-ID') ?? undefined)
  }
  if (raw) return res as unknown as T
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

const L = (lid: string) => `/ledgers/${lid}`

export const api = {
  meta: () => call<{ version: string; signup_allowed: boolean }>('GET', '/meta'),
  login: (email: string, password: string) => call<{ user: User; token: string }>('POST', '/auth/login', { email, password, device_name: 'Web browser' }),
  register: (email: string, password: string, name: string) =>
    call<{ user: User; token: string }>('POST', '/auth/register', { email, password, name, device_name: 'Web browser' }),
  logout: () => call<void>('POST', '/auth/logout'),
  me: () => call<{ user: User }>('GET', '/auth/me'),
  sessions: () => call<{ sessions: Session[] }>('GET', '/auth/sessions').then((r) => r.sessions),
  revokeSession: (id: string) => call<void>('DELETE', `/auth/sessions/${id}`),
  changePassword: (current_password: string, new_password: string) => call<void>('POST', '/auth/password', { current_password, new_password }),

  ledgers: () => call<{ ledgers: Ledger[] }>('GET', '/ledgers').then((r) => r.ledgers),
  createLedger: (name: string, base_currency: string) =>
    call<{ ledger: Ledger }>('POST', '/ledgers', { name, base_currency, seed_default_categories: true }).then((r) => r.ledger),
  updateLedger: (lid: string, patch: Partial<Pick<Ledger, 'name' | 'base_currency'>>) => call<{ ledger: Ledger }>('PATCH', L(lid), patch),
  deleteLedger: (lid: string) => call<void>('DELETE', `${L(lid)}?confirm=${lid}`),
  members: (lid: string) => call<{ members: Member[] }>('GET', `${L(lid)}/members`).then((r) => r.members),
  addMember: (lid: string, email: string, role: string) => call<{ member: Member }>('POST', `${L(lid)}/members`, { email, role }),
  removeMember: (lid: string, uid: string) => call<void>('DELETE', `${L(lid)}/members/${uid}`),

  /** All records of an entity, following keyset pages. */
  async records<T>(lid: string, entity: string, includeDeleted = false): Promise<Rec<T>[]> {
    const out: Rec<T>[] = []
    let after = ''
    for (;;) {
      const q = new URLSearchParams({ limit: '2000', ...(after ? { after } : {}), ...(includeDeleted ? { include_deleted: 'true' } : {}) })
      const r = await call<{ records: Rec<T>[]; next_after?: string }>('GET', `${L(lid)}/records/${entity}?${q}`)
      out.push(...r.records)
      if (!r.next_after) return out
      after = r.next_after
    }
  },
  create: <T>(lid: string, entity: string, id: string, data: Partial<T>) =>
    call<{ record: Rec<T> }>('POST', `${L(lid)}/records/${entity}`, { id, data }).then((r) => r.record),
  update: <T>(lid: string, entity: string, id: string, data: Partial<T>) =>
    call<{ record: Rec<T> }>('PATCH', `${L(lid)}/records/${entity}/${id}`, { data }).then((r) => r.record),
  remove: (lid: string, entity: string, id: string) => call<unknown>('DELETE', `${L(lid)}/records/${entity}/${id}`),
  restore: (lid: string, entity: string, id: string) => call<unknown>('POST', `${L(lid)}/records/${entity}/${id}/restore`),

  transactions: (lid: string, params: Record<string, string>) =>
    call<{ transactions: Rec<Transaction>[]; next_cursor?: string }>('GET', `${L(lid)}/transactions?${new URLSearchParams(params)}`),

  balances: (lid: string) => call<{ balances: Balance[] }>('GET', `${L(lid)}/reports/balances`).then((r) => r.balances),
  summary: (lid: string, from: string, to: string) =>
    call<{ currencies: CurrencySummary[] }>('GET', `${L(lid)}/reports/summary?from=${from}&to=${to}`).then((r) => r.currencies),
  trend: (lid: string, from: string, to: string, interval = 'month') =>
    call<{ points: TrendPoint[] }>('GET', `${L(lid)}/reports/trend?from=${from}&to=${to}&interval=${interval}`).then((r) => r.points),

  summaryConverted: (lid: string, from: string, to: string, convertTo: string) =>
    call<{ currencies: CurrencySummary[]; converted: Converted }>('GET', `${L(lid)}/reports/summary?from=${from}&to=${to}&convert_to=${convertTo}`),
  budgetProgress: (lid: string, date: string) =>
    call<{ budgets: BudgetProgress[] }>('GET', `${L(lid)}/reports/budgets?date=${date}`).then((r) => r.budgets),
  tagTotals: (lid: string, from: string, to: string) =>
    call<{ tags: TagTotal[] }>('GET', `${L(lid)}/reports/tags?from=${from}&to=${to}`).then((r) => r.tags),
  payeeTotals: (lid: string, from: string, to: string, limit = 10) =>
    call<{ payees: PayeeTotal[] }>('GET', `${L(lid)}/reports/payees?from=${from}&to=${to}&limit=${limit}`).then((r) => r.payees),

  /**
   * Applies writes in batches of BATCH_OPS. Each batch is atomic on the
   * server; [onProgress] reports how many ops are done.
   */
  async batch(lid: string, ops: BatchOp[], onProgress?: (done: number) => void): Promise<BatchResult[]> {
    const out: BatchResult[] = []
    for (let i = 0; i < ops.length; i += BATCH_OPS) {
      const r = await call<{ results: BatchResult[] }>('POST', `${L(lid)}/batch`, { ops: ops.slice(i, i + BATCH_OPS) })
      out.push(...r.results)
      onProgress?.(out.length)
    }
    return out
  },

  exportLedger: (lid: string) => call<Response>('GET', `${L(lid)}/export`, undefined, true).then((r) => r.blob()),
  importLedger: (lid: string, file: Blob) =>
    call<{ accepted: number; skipped_newer_exists: number; rejected: unknown[] }>('POST', `${L(lid)}/import`, file),
}
