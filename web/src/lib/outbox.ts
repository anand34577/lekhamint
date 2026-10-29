// Changes made while offline wait here, in this browser, and are sent in order
// when the connection is back. Every queued write is idempotent on the server
// (create_if_missing with a fixed ID, update, delete, restore), so sending one
// twice after a lost response does no harm.

import { ApiError, api, type BatchOp } from './api'

export interface Queued { key: string; ledgerId: string; ops: BatchOp[]; label: string; at: number }
export interface Failed { label: string; message: string; at: number }

const KEY = 'kosh.outbox'
const FAILED = 'kosh.outbox.failed'
const listeners = new Set<() => void>()

function read<T>(k: string): T[] {
  try { return JSON.parse(localStorage.getItem(k) ?? '[]') as T[] } catch { return [] }
}
function write<T>(k: string, v: T[]) {
  try { localStorage.setItem(k, JSON.stringify(v)) } catch { /* storage full or blocked: the change stays in memory for this tab */ }
  listeners.forEach((f) => f())
}

export const outbox = {
  list: (): Queued[] => read<Queued>(KEY),
  failed: (): Failed[] => read<Failed>(FAILED),
  subscribe(fn: () => void) { listeners.add(fn); return () => { listeners.delete(fn) } },
  add(ledgerId: string, ops: BatchOp[], label: string) {
    write(KEY, [...read<Queued>(KEY), { key: `${Date.now()}-${Math.random().toString(36).slice(2)}`, ledgerId, ops, label, at: Date.now() }])
  },
  clearFailed() { write(FAILED, []) },
  /** Forgets everything (on sign-out). */
  clear() { write(KEY, []); write(FAILED, []) },
}

/** True for errors where the server was never reached (offline, DNS, dropped connection). */
export const isOffline = (e: unknown) => e instanceof ApiError && e.status === 0

let flushing: Promise<number> | null = null

/**
 * Sends queued changes in order. Stops at the first one that cannot reach the
 * server (still offline); a change the server refuses is dropped and listed as
 * failed, so one bad item never blocks the rest. Returns how many were sent.
 */
export function flushOutbox(): Promise<number> {
  flushing ??= (async () => {
    let sent = 0
    try {
      for (;;) {
        const next = read<Queued>(KEY)[0]
        if (!next) break
        try {
          // Updates say when they were made, so a field someone else changed since keeps their newer value.
          await api.batch(next.ledgerId, next.ops.map((o) => (o.op === 'update' ? { ...o, edited_at: next.at } : o)))
          sent++
        } catch (e) {
          if (isOffline(e)) break
          // Refused for good (validation, no access, deleted book): keep a note and move on.
          if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429) {
            write(FAILED, [...read<Failed>(FAILED), { label: next.label, message: e.message, at: Date.now() }])
          } else break
        }
        write(KEY, read<Queued>(KEY).filter((q) => q.key !== next.key))
      }
    } finally { flushing = null }
    return sent
  })()
  return flushing
}
