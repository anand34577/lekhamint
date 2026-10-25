import { createContext, useContext, useMemo, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type Account, type Attachment, type BatchOp, type Budget, type Category, type Debt, type Deposit, type ExchangeRate, type Goal, type Loan, type Ledger, type Rec, type RecurringRule, type Rule, type Split, type Tag, type Transaction, type Valuation } from './api'
import { isoDate, uuidv7 } from './util'
import { isOffline, outbox } from './outbox'

export const LedgerContext = createContext<Ledger | null>(null)

export function useLedger(): Ledger {
  const l = useContext(LedgerContext)
  if (!l) throw new Error('No ledger selected')
  return l
}

export type WithId<T> = T & { id: string; hlc: string; deleted: boolean }
const flat = <T,>(r: Rec<T>): WithId<T> => ({ ...r.data, id: r.id, hlc: r.hlc, deleted: r.deleted })

function useRecords<T>(entity: string, includeDeleted = false) {
  const l = useLedger()
  return useQuery({
    queryKey: [l.id, 'records', entity, includeDeleted],
    queryFn: async () => (await api.records<T>(l.id, entity, includeDeleted)).map(flat),
  })
}

export const useAccounts = () => useRecords<Account>('accounts')
export const useCategories = () => useRecords<Category>('categories')
export const useBudgets = () => useRecords<Budget>('budgets')
export const useRules = () => useRecords<RecurringRule>('recurring_rules')
export const useTags = () => useRecords<Tag>('tags')
export const useCategorizationRules = () => useRecords<Rule>('rules')
export const useSplits = () => useRecords<Split>('transaction_splits')
export const useDebts = () => useRecords<Debt>('debts')
export const useExchangeRates = () => useRecords<ExchangeRate>('exchange_rates')
export const useLoans = () => useRecords<Loan>('loans')
export const useDeposits = () => useRecords<Deposit>('deposits')
export const useGoals = () => useRecords<Goal>('goals')
export const useValuations = () => useRecords<Valuation>('valuations')
export const useAttachments = () => useRecords<Attachment>('attachments')

/** Cash, investments, liabilities, debts and net worth per currency (server-calculated, docs/02). */
export function usePosition() {
  const l = useLedger()
  return useQuery({ queryKey: [l.id, 'position'], queryFn: () => api.position(l.id) })
}

/** Every personal debt with repaid and outstanding amounts. */
export function useDebtTotals() {
  const l = useLedger()
  return useQuery({ queryKey: [l.id, 'debt-totals'], queryFn: () => api.debtTotals(l.id) })
}

/** Transactions linked to loans or deposits ([kind]), grouped by the linked record. */
export function useLinkedMoves(kind: 'loan_id' | 'deposit_id') {
  const l = useLedger()
  return useQuery({
    queryKey: [l.id, 'transactions', 'linked', kind],
    queryFn: async () => {
      const m = new Map<string, (Transaction & { id: string })[]>()
      for (let cursor = '', more = true; more;) {
        const page = await api.transactions(l.id, { [kind]: 'any', limit: '500', ...(cursor ? { cursor } : {}) })
        for (const r of page.transactions) {
          const key = r.data[kind]
          if (key) m.set(key, [...(m.get(key) ?? []), { ...r.data, id: r.id }])
        }
        cursor = page.next_cursor ?? ''; more = !!cursor
      }
      for (const list of m.values()) list.sort((a, b) => a.local_date.localeCompare(b.local_date) || a.occurred_at - b.occurred_at)
      return m
    },
  })
}

/**
 * A record ID that stays the same while a form is open, so pressing Save
 * twice, or retrying after a dropped connection, can never create two records
 * (the server skips a create_if_missing whose ID already exists).
 */
export function useStableId(existing?: string): string {
  const ref = useRef<string>()
  if (!ref.current) ref.current = existing ?? uuidv7()
  return existing ?? ref.current
}

/** Viewers can read a shared book but not change it. */
export function useCanWrite(): boolean {
  return useLedger().role !== 'viewer'
}

/** Server-side budget progress for [date]: rollover, account filters and splits included. */
export function useBudgetProgress(date = isoDate()) {
  const l = useLedger()
  return useQuery({ queryKey: [l.id, 'budget-progress', date], queryFn: () => api.budgetProgress(l.id, date) })
}

/** Live tags by ID, plus the ones shown in pickers. */
export function useTagIndex() {
  const { data = [] } = useTags()
  return useMemo(() => {
    const live = data.filter((t) => !t.deleted).sort((a, b) => a.name.localeCompare(b.name))
    const byId = new Map(live.map((t) => [t.id, t]))
    return { all: live, active: live.filter((t) => !t.archived), byId, name: (id: string) => byId.get(id)?.name }
  }, [data])
}

/** Split lines grouped by transaction. */
export function useSplitIndex() {
  const { data = [] } = useSplits()
  return useMemo(() => {
    const m = new Map<string, WithId<Split>[]>()
    for (const s of data) if (!s.deleted) m.set(s.transaction_id, [...(m.get(s.transaction_id) ?? []), s])
    for (const list of m.values()) list.sort((a, b) => a.sort_order - b.sort_order)
    return m
  }, [data])
}

export function useBalances() {
  const l = useLedger()
  return useQuery({ queryKey: [l.id, 'balances'], queryFn: () => api.balances(l.id) })
}

export function useSummary(from: string, to: string) {
  const l = useLedger()
  return useQuery({ queryKey: [l.id, 'summary', from, to], queryFn: () => api.summary(l.id, from, to) })
}

/** Category helpers shared by every screen. */
export function useCategoryIndex() {
  const { data = [] } = useCategories()
  return useMemo(() => {
    const live = data.filter((c) => !c.deleted)
    const byId = new Map(live.map((c) => [c.id, c]))
    const sort = (a: WithId<Category>, b: WithId<Category>) => a.sort_order - b.sort_order || a.name.localeCompare(b.name)
    const roots = (kind: string) => live.filter((c) => c.kind === kind && !c.archived && !(c.parent_id && byId.has(c.parent_id))).sort(sort)
    const children = (id: string) => live.filter((c) => c.parent_id === id && !c.archived).sort(sort)
    const name = (id: string | null | undefined) => (id && byId.get(id)?.name) || 'Uncategorised'
    const path = (id: string | null | undefined) => {
      const c = id ? byId.get(id) : undefined
      if (!c) return 'Uncategorised'
      const p = c.parent_id ? byId.get(c.parent_id) : undefined
      return p ? `${p.name} · ${c.name}` : c.name
    }
    const color = (id: string | null | undefined): string => {
      const c = id ? byId.get(id) : undefined
      return c?.color ?? (c?.parent_id ? byId.get(c.parent_id)?.color : null) ?? '#94A3B8'
    }
    const icon = (id: string | null | undefined): string | null => {
      const c = id ? byId.get(id) : undefined
      return c?.icon ?? (c?.parent_id ? byId.get(c.parent_id)?.icon : null) ?? null
    }
    const rootOf = (id: string | null | undefined) => {
      const c = id ? byId.get(id) : undefined
      return c?.parent_id && byId.has(c.parent_id) ? c.parent_id : (id ?? null)
    }
    return { all: live, byId, roots, children, name, path, color, icon, rootOf }
  }, [data])
}

/** A write: update when [id] is set, otherwise create with a new UUIDv7. Unknown fields are ignored by the server. */
export interface SaveInput { entity: string; id?: string; data: object; /** ID for a new record; pass useStableId() so a retry cannot create a second one. */ newId?: string }

const LABELS: Record<string, string> = { transactions: 'transaction', accounts: 'account', budgets: 'budget', categories: 'category', goals: 'goal', recurring_rules: 'recurring item' }

/**
 * Runs a write; when the server cannot be reached, keeps it in the outbox to
 * send later (lib/outbox.ts) instead of failing, so work done offline is not lost.
 */
async function orQueue<T>(ledgerId: string, ops: BatchOp[], label: string, run: () => Promise<T>, offline: T): Promise<T> {
  try { return await run() } catch (e) {
    if (!isOffline(e)) throw e
    outbox.add(ledgerId, ops, label)
    return offline
  }
}

/** Create, update, delete and restore records; refreshes every view of the ledger afterwards. */
export function useWrite() {
  const l = useLedger()
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: [l.id] })
  const what = (entity: string) => LABELS[entity] ?? entity.replace(/_/g, ' ')
  return {
    save: useMutation({
      mutationFn: async (v: SaveInput): Promise<{ id: string }> => {
        if (v.id) {
          const id = v.id
          return orQueue(l.id, [{ op: 'update', entity: v.entity, id, data: v.data }], `Edited ${what(v.entity)}`, async () => ({ id: (await api.update(l.id, v.entity, id, v.data)).id }), { id })
        }
        // create_if_missing: a second press, or a retry after a lost response, is a no-op.
        const id = v.newId ?? uuidv7()
        const ops: BatchOp[] = [{ op: 'create_if_missing', entity: v.entity, id, data: { created_at: Date.now(), ...v.data } }]
        await orQueue(l.id, ops, `New ${what(v.entity)}`, () => api.batch(l.id, ops), [])
        return { id }
      },
      onSuccess: invalidate,
    }),
    remove: useMutation({ mutationFn: (v: { entity: string; id: string }) => orQueue(l.id, [{ op: 'delete', entity: v.entity, id: v.id }], `Deleted ${what(v.entity)}`, () => api.remove(l.id, v.entity, v.id), null), onSuccess: invalidate }),
    restore: useMutation({ mutationFn: (v: { entity: string; id: string }) => orQueue(l.id, [{ op: 'restore', entity: v.entity, id: v.id }], `Restored ${what(v.entity)}`, () => api.restore(l.id, v.entity, v.id), null), onSuccess: invalidate }),
    /** Many writes at once (bulk edits, imports); atomic per 500 ops. */
    batch: useMutation({
      mutationFn: (ops: BatchOp[]) => orQueue(l.id, ops, ops.length === 1 || ops.every((o) => o.entity === ops[0].entity && o.op === ops[0].op)
        ? `${ops[0].op === 'create_if_missing' || ops[0].op === 'create' ? 'New' : ops[0].op === 'delete' ? 'Deleted' : ops[0].op === 'restore' ? 'Restored' : 'Edited'} ${ops.length > 1 ? `${ops.length} ` : ''}${what(ops[0].entity)}${ops.length > 1 ? 's' : ''}`
        : `${ops.length} changes`, () => api.batch(l.id, ops), []),
      onSuccess: invalidate,
    }),
  }
}

export const ACCOUNT_TYPES: [string, string][] = [
  ['cash', 'Cash'], ['bank', 'Bank account'], ['wallet', 'Wallet / UPI'], ['credit_card', 'Credit card'],
  ['savings', 'Savings'], ['investment', 'Investment'], ['deposit', 'Fixed / recurring deposit'], ['loan', 'Loan'], ['other', 'Other'],
]
export const accountTypeLabel = (t: string) => ACCOUNT_TYPES.find(([k]) => k === t)?.[1] ?? t
export const isLiability = (t: string) => t === 'credit_card' || t === 'loan'
export const isInvestment = (t: string) => t === 'investment' || t === 'deposit'
