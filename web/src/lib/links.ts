// Building the transactions that loans, deposits and personal debts create
// (docs/02, "Loans and deposits"). Every money movement is an ordinary
// transaction so balances, statements and exports stay complete; the link
// fields say what it belongs to, and `part` what it does.

import type { BatchOp, Category, Transaction } from './api'
import { atTime } from './util'

export type Part = 'principal' | 'interest' | 'fee' | 'prepayment' | null

export interface LinkedTx {
  type: 'expense' | 'income' | 'transfer'
  account_id: string
  amount: number
  to_account_id?: string | null
  to_amount?: number | null
  category_id?: string | null
  payee?: string | null
  note?: string | null
  date: string
  /** Time of day, "HH:MM"; empty means now (today) or noon (another day). */
  time?: string
  part: Part
  debt_id?: string
  loan_id?: string
  deposit_id?: string
}

/** [date] at [time] ("HH:MM") when given; otherwise now for today and noon for other days. */
export const occurredAt = (date: string, time?: string): number => atTime(date, time)

export function linkedTxData(t: LinkedTx): Partial<Transaction> & { created_at: number } {
  const at = occurredAt(t.date, t.time)
  return {
    type: t.type, status: 'cleared', account_id: t.account_id, amount: t.amount,
    to_account_id: t.type === 'transfer' ? t.to_account_id ?? null : null,
    to_amount: t.type === 'transfer' ? t.to_amount ?? t.amount : null,
    category_id: t.type === 'transfer' ? null : t.category_id ?? null,
    payee: t.payee ?? null, note: t.note ?? null, local_date: t.date, occurred_at: at, tz_offset_min: -new Date(at).getTimezoneOffset(),
    tag_ids: [], is_adjustment: false, excluded_from_reports: false,
    debt_id: t.debt_id ?? null, loan_id: t.loan_id ?? null, deposit_id: t.deposit_id ?? null, part: t.part,
    created_at: Date.now(),
  }
}

/** A create that is skipped if the ID already exists, so a retried save never doubles up. */
export function createOp(entity: string, id: string, data: object): BatchOp {
  return { op: 'create_if_missing', entity, id, data }
}

/** Finds a live category of [kind] by any of [names] (case-insensitive), children first. */
export function findCategory(all: (Category & { id: string; deleted?: boolean })[], kind: 'expense' | 'income', names: string[]): string | undefined {
  const live = all.filter((c) => !c.deleted && !c.archived && c.kind === kind)
  for (const n of names) {
    const hit = live.find((c) => c.name.toLowerCase() === n.toLowerCase())
    if (hit) return hit.id
  }
  return undefined
}

/** Category for loan interest: the loan's own choice, then Mortgage interest (home loans) or Interest & fees. */
export function interestExpenseCategory(all: (Category & { id: string; deleted?: boolean })[], kind: string, chosen?: string | null): string | undefined {
  if (chosen && all.some((c) => c.id === chosen && !c.deleted)) return chosen
  return findCategory(all, 'expense', kind === 'home' ? ['Mortgage interest', 'Interest & fees', 'Loan interest'] : ['Interest & fees', 'Loan interest', 'Bank fees'])
}

export const interestIncomeCategory = (all: (Category & { id: string; deleted?: boolean })[]) => findCategory(all, 'income', ['Interest', 'Interest income'])
export const feeCategory = (all: (Category & { id: string; deleted?: boolean })[]) => findCategory(all, 'expense', ['Bank fees', 'Interest & fees'])
