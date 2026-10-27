// Typed client for the LekhaMint REST API (docs/07-api.md). The web app talks to the
// server it is served from, so there is no CORS and no configuration.

import { DEMO, demoFetch } from './demo'

export interface User { id: string; email: string; name: string; is_admin: boolean; totp_enabled?: boolean }
export interface Ledger { id: string; name: string; base_currency: string; seq: number; role?: string }

export interface Meta {
  version: string; signup_allowed: boolean
  oidc?: { enabled: boolean; name?: string; only?: boolean }
  notify?: { email: boolean; gotify: boolean; gotify_host?: string }
  /** What this server version offers; missing on older servers. */
  features?: { password_reset?: boolean; two_step?: boolean; attachments?: boolean; max_attachment_size?: number; fx_fetch?: boolean; history?: boolean }
}
export interface NotificationPrefs {
  notify_email: boolean; notify_gotify: boolean; gotify_token_set: boolean
  ev_security: boolean; ev_due: boolean; ev_budget: boolean; ev_digest?: boolean
  channels?: { email: boolean; gotify: boolean; gotify_host?: string }
}

export interface Rec<T> { entity: string; id: string; hlc: string; seq: number; deleted: boolean; data: T }

export interface Account {
  name: string; type: string; currency: string; opening_balance: number; credit_limit: number | null; statement_day?: number | null; due_day?: number | null
  color: string | null; icon: string | null; include_in_net_worth: boolean; archived: boolean
  sort_order: number; note: string | null; created_at: number
}
export interface Category {
  name: string; kind: 'expense' | 'income'; parent_id: string | null; nature: string | null
  color: string | null; icon: string | null; archived: boolean; sort_order: number
  /** Income-tax section this spending counts for (80C, 80D, HRA…), for the year's tax summary. */
  tax_section?: string | null
}
export interface Transaction {
  type: 'expense' | 'income' | 'transfer'; status: string; account_id: string; amount: number
  to_account_id: string | null; to_amount: number | null; category_id: string | null
  payee: string | null; note: string | null; occurred_at: number; local_date: string; tz_offset_min: number
  tag_ids: string[]; is_adjustment: boolean; excluded_from_reports: boolean; recurring_rule_id: string | null
  debt_id?: string | null
  loan_id?: string | null
  deposit_id?: string | null
  /** What a linked transaction does: principal, interest, fee or prepayment. */
  part?: 'principal' | 'interest' | 'fee' | 'prepayment' | null
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
export interface Loan {
  name: string; lender: string | null; kind: string; account_id: string; currency: string; principal: number
  annual_rate: string | null; rate_type: 'fixed' | 'floating'; start_date: string; first_due_date: string; tenure_months: number
  emi_amount: number | null; paid_before: number; pay_from_account_id: string | null; interest_category_id: string | null
  processing_fee: number | null; remind_days: number; closed: boolean; closed_date: string | null; note: string | null; created_at: number
}
export interface Deposit {
  name: string; institution: string | null; kind: 'fd' | 'rd' | 'other'; account_id: string; currency: string; amount: number
  annual_rate: string; start_date: string; maturity_date: string; compounding: string; payout: string
  payout_account_id: string | null; maturity_amount: number | null; tax_percent: string | null
  status: 'active' | 'matured' | 'closed'; closed_date: string | null; auto_renew: boolean; remind_days: number; note: string | null; created_at: number
}
export interface Position {
  currency: string; cash: number; investments: number; liabilities: number; receivable: number; payable: number; net_worth: number
  excluded_accounts: number; excluded_balance: number
  /** Investments with a valuation: what was put in, and the gain (negative = loss). */
  invested_cost?: number; gain?: number
}
/** A savings goal; progress = saved_amount + balances of account_ids (same currency). */
export interface Goal {
  name: string; target_amount: number; currency: string; target_date: string | null; start_date: string | null
  account_ids: string[]; saved_amount: number; color: string | null; icon: string | null; archived: boolean
  achieved_date: string | null; note: string | null; created_at: number
}
/** What an account was worth on a date (a fund at that day's price). */
export interface Valuation { account_id: string; date: string; value: number; note: string | null; created_at: number }
/** A file (receipt, bill) on a transaction; the content is fetched by sha256. */
export interface Attachment { transaction_id: string; name: string; mime: string; size: number; sha256: string; created_at: number }
export interface HistoryEntry {
  seq: number; entity: string; record_id: string; hlc: string; user_id: string; user_name: string; user_email: string
  device: string; deleted: boolean; data: Record<string, unknown>; at: number
}
export interface NetWorthPoint { date: string; positions: Position[] }
export interface DebtOutstanding { debt_id: string; person: string; direction: 'lent' | 'borrowed'; currency: string; amount: number; repaid: number; outstanding: number; closed: boolean; start: string }
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

export interface Balance {
  account_id: string; name: string; type: string; currency: string; archived: boolean; include_in_net_worth: boolean; opening_balance: number; balance: number
  /** Market value from the latest valuation (plus money moved since); balance is then what was put in. */
  value?: number; valued_on?: string
}
export interface CategoryTotal { category_id: string | null; parent_id: string | null; name: string; amount: number; count: number }
export interface CurrencySummary { currency: string; income: number; expense: number; net: number; expense_by_category: CategoryTotal[]; income_by_category: CategoryTotal[] }
export interface TrendPoint { period: string; currency: string; income: number; expense: number }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>, public requestId?: string) { super(message) }
}

const TOKEN_KEY = 'kosh.token'

// The session lives in an HttpOnly cookie set by the server, so no script on
// the page — and no XSS — can read it. Writes carry the X-Kosh-Client header
// (the CSRF defence for cookie-authenticated requests). `signedIn` only tracks
// whether to treat a 401 as "the session ended". The in-browser demo has no
// cookie jar (its server is a WASM module in this tab), so demo builds keep
// the token in memory for the life of the tab instead.
let signedIn = false
let demoBearer = ''
export const session = {
  get signedIn() { return signedIn },
  start(token?: string) {
    signedIn = true
    if (DEMO && token) demoBearer = token
    try { localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ }
  },
  end() { signedIn = false; demoBearer = ''; try { localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ } },
  /** Removes a token left by older builds that kept it in localStorage. */
  clearLegacyToken() { try { localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ } },
}
if (!DEMO) session.clearLegacyToken()

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

async function call<T>(method: string, path: string, body?: unknown, raw = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', 'X-Kosh-Client': 'web' }
  if (DEMO && demoBearer) headers.Authorization = `Bearer ${demoBearer}`
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
  if (!res.ok) {
    let code = `http_${res.status}`, message = res.statusText, fields
    try { const e = (await res.json()).error; code = e.code; message = e.message; fields = e.fields } catch { /* not JSON */ }
    // A wrong password or code (change password, delete account, two-step) is also a 401 but the session is still fine.
    if (res.status === 401 && signedIn && !['invalid_credentials', 'invalid_otp', 'otp_required'].includes(code)) { session.end(); onUnauthorized() }
    throw new ApiError(res.status, code, message, fields, res.headers.get('X-Request-ID') ?? undefined)
  }
  if (raw) return res as unknown as T
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

const L = (lid: string) => `/ledgers/${lid}`

export const api = {
  meta: () => call<Meta>('GET', '/meta'),
  /**
   * Waits up to 25 seconds for the book to change after [since] (its change-feed position).
   * Answers at once when it already has; older servers always answer at once.
   */
  waitForChange: async (lid: string, since: number, signal: AbortSignal) => {
    const res = await fetch(`/api/v1${L(lid)}/sync/pull?since=${since}&limit=1&wait=25`, { headers: { Accept: 'application/json', 'X-Kosh-Client': 'web' }, signal })
    if (!res.ok) throw new ApiError(res.status, `http_${res.status}`, res.statusText)
    return res.json() as Promise<{ changes: unknown[]; next_cursor: number; has_more: boolean }>
  },
  oidcExchange: (code: string) => call<{ user: User; token: string }>('POST', '/auth/oidc/exchange', { code }),
  notifications: () => call<NotificationPrefs>('GET', '/notifications'),
  saveNotifications: (patch: Partial<Omit<NotificationPrefs, 'gotify_token_set' | 'channels'>> & { gotify_token?: string }) => call<NotificationPrefs>('PUT', '/notifications', patch),
  testNotification: (channel: 'email' | 'gotify', gotify_token?: string) => call<{ sent: boolean }>('POST', '/notifications/test', { channel, ...(gotify_token ? { gotify_token } : {}) }),
  login: (email: string, password: string, otp?: string) => call<{ user: User; token: string }>('POST', '/auth/login', { email, password, device_name: 'Web browser', ...(otp ? { otp } : {}) }),
  /** Emails a reset link if the address has an account (the answer is the same either way). */
  forgotPassword: (email: string) => call<{ sent: boolean }>('POST', '/auth/forgot', { email }),
  resetPassword: (token: string, new_password: string) => call<{ reset: boolean }>('POST', '/auth/reset', { token, new_password }),
  twoStep: () => call<{ enabled: boolean; recovery_codes_left: number }>('GET', '/auth/2fa'),
  twoStepSetup: () => call<{ secret: string; otpauth_url: string }>('POST', '/auth/2fa/setup', {}),
  twoStepEnable: (code: string) => call<{ enabled: boolean; recovery_codes: string[] }>('POST', '/auth/2fa/enable', { code }),
  twoStepDisable: (password: string, code: string) => call<{ enabled: boolean }>('POST', '/auth/2fa/disable', { password, code }),
  twoStepNewCodes: (code: string) => call<{ recovery_codes: string[] }>('POST', '/auth/2fa/recovery-codes', { code }),
  register: (email: string, password: string, name: string) =>
    call<{ user: User; token: string }>('POST', '/auth/register', { email, password, name, device_name: 'Web browser' }),
  logout: () => call<void>('POST', '/auth/logout'),
  me: () => call<{ user: User }>('GET', '/auth/me'),
  /** The signed-in person's settings (theme, chosen book...), the same in every browser. */
  settings: () => call<{ settings: Record<string, unknown> }>('GET', '/settings').then((r) => r.settings ?? {}),
  putSetting: (key: string, value: unknown) => call<unknown>('PUT', `/settings/${encodeURIComponent(key)}`, { value }),
  deleteSetting: (key: string) => call<void>('DELETE', `/settings/${encodeURIComponent(key)}`),
  aiKey: () => call<{ key: string; key_set: boolean }>('GET', '/ai/key'),
  saveAiKey: (key: string) => call<{ key_set: boolean }>('PUT', '/ai/key', { key }),
  clearAiKey: () => call<void>('DELETE', '/ai/key'),
  sessions: () => call<{ sessions: Session[] }>('GET', '/auth/sessions').then((r) => r.sessions),
  /** A one-time code (5 minutes) that connects the phone app to this account; see docs/07. */
  pairStart: () => call<{ code: string; expires_at: number }>('POST', '/auth/pair'),
  pairStatus: (code: string) => call<{ claimed: boolean; device?: string; expired: boolean }>('GET', `/auth/pair/${encodeURIComponent(code)}`),
  revokeSession: (id: string) => call<void>('DELETE', `/auth/sessions/${id}`),
  changePassword: (current_password: string, new_password: string) => call<void>('POST', '/auth/password', { current_password, new_password }),
  deleteAccount: (password: string) => call<{ deleted_ledgers: string[] }>('DELETE', '/auth/me', { password }),

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
  /** Who changed a record and what it looked like after each change, newest first. */
  history: (lid: string, entity: string, id: string) =>
    call<{ history: HistoryEntry[] }>('GET', `${L(lid)}/records/${entity}/${id}/history`).then((r) => r.history),
  /** Uploads an attachment file; the server checks its type by content. */
  uploadBlob: (lid: string, file: Blob) => call<{ sha256: string; mime: string; size: number }>('POST', `${L(lid)}/blobs`, file),
  /** Address of an attachment file, for <img src> or a link (same origin, so the session cookie applies). */
  blobUrl: (lid: string, sha: string, name?: string, download = false) =>
    `/api/v1${L(lid)}/blobs/${sha}?${new URLSearchParams({ ...(name ? { name } : {}), ...(download ? { download: '1' } : {}) })}`,
  /** An attachment file's content (works in the in-browser demo too, unlike a plain URL). */
  fetchBlob: (lid: string, sha: string) => call<Response>('GET', `${L(lid)}/blobs/${sha}`, undefined, true).then((r) => r.blob()),
  /** Fetches today's reference exchange rates for [quotes] against [base] and saves them. */
  fetchRates: (lid: string, base: string, quotes: string[]) =>
    call<{ base: string; date: string; rates: Record<string, string>; missing: string[]; source: string }>('POST', `${L(lid)}/rates/fetch`, { base, quotes }),

  transactions: (lid: string, params: Record<string, string>) =>
    call<{ transactions: Rec<Transaction>[]; next_cursor?: string }>('GET', `${L(lid)}/transactions?${new URLSearchParams(params)}`),

  balances: (lid: string) => call<{ balances: Balance[] }>('GET', `${L(lid)}/reports/balances`).then((r) => r.balances),
  /** Balances at the end of [asOf] (YYYY-MM-DD). */
  balancesAsOf: (lid: string, asOf: string) => call<{ balances: Balance[] }>('GET', `${L(lid)}/reports/balances?as_of=${asOf}`).then((r) => r.balances),
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
  position: (lid: string, asOf = '') =>
    call<{ positions: Position[] }>('GET', `${L(lid)}/reports/position${asOf ? `?as_of=${asOf}` : ''}`).then((r) => r.positions),
  netWorth: (lid: string, months = 12) =>
    call<{ points: NetWorthPoint[] }>('GET', `${L(lid)}/reports/networth?months=${months}`).then((r) => r.points),
  debtTotals: (lid: string) => call<{ debts: DebtOutstanding[] }>('GET', `${L(lid)}/reports/debts`).then((r) => r.debts),
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
