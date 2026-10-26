// Per-person settings (theme, chosen book, assistant options, dismissed tips...), kept on
// the server so they are the same in every browser. They load once after sign-in and are
// held in memory; a change shows at once and is saved in the background. Nothing is kept
// in the browser's storage.

import { useSyncExternalStore } from 'react'
import { api } from './api'

type Values = Record<string, unknown>

let values: Values = {}
let loaded = false
const listeners = new Set<() => void>()
const pending = new Map<string, ReturnType<typeof setTimeout>>()

function emit() { for (const l of listeners) l() }
function subscribe(fn: () => void) { listeners.add(fn); return () => { listeners.delete(fn) } }

/** Settings older versions of the web app kept in localStorage, and their names on the server. */
const LEGACY: [RegExp, (m: RegExpMatchArray) => string][] = [
  [/^kosh\.theme$/, () => 'theme'],
  [/^kosh\.accent$/, () => 'accent'],
  [/^kosh\.ledger$/, () => 'ledger'],
  [/^kosh\.hide$/, () => 'hide_amounts'],
  [/^kosh\.rail$/, () => 'nav_rail'],
  [/^kosh\.txview$/, () => 'tx_view'],
  [/^kosh\.fy\.(.+)$/, (m) => `fy_start.${m[1]}`],
  [/^kosh\.lastAccount\.(.+)$/, (m) => `last_account.${m[1]}`],
  [/^kosh\.subs\.hidden\.(.+)$/, (m) => `subscriptions_hidden.${m[1]}`],
  [/^kosh\.start\.hidden\.(.+)$/, (m) => `getting_started_hidden.${m[1]}`],
  [/^kosh\.chatlist$/, () => 'assistant_chat_list'],
  [/^kosh\.ai$/, () => 'assistant'],
]

/** Values as they were stored in localStorage (strings), turned back into what the app uses. */
function legacyValue(key: string, raw: string): unknown {
  if (key === 'hide_amounts' || key === 'nav_rail') return raw === '1'
  if (key.startsWith('getting_started_hidden.') || key === 'assistant_chat_list') return raw === '1'
  if (key.startsWith('fy_start.')) return Number(raw) || null
  if (key.startsWith('subscriptions_hidden.') || key === 'assistant') { try { return JSON.parse(raw) } catch { return null } }
  return raw
}

/**
 * Moves settings an older build left in this browser to the server, once, then removes them
 * from the browser. Values already on the server win: they may come from a newer device.
 */
async function migrateLegacy() {
  let keys: string[] = []
  try { keys = Object.keys(localStorage) } catch { return }
  for (const k of keys) {
    for (const [re, name] of LEGACY) {
      const m = k.match(re)
      if (!m) continue
      const key = name(m)
      let raw: string | null = null
      try { raw = localStorage.getItem(k) } catch { /* blocked */ }
      if (raw != null && !(key in values)) {
        const v = legacyValue(key, raw)
        if (v != null && v !== '') { values[key] = v; await api.putSetting(key, v).catch(() => {}) }
      }
      try { localStorage.removeItem(k) } catch { /* ignore */ }
    }
  }
}

/** Loads the signed-in person's settings. Call once after sign-in; safe to call again. */
export async function loadSettings(): Promise<void> {
  try {
    values = await api.settings()
  } catch {
    values = {} // offline or an older server: defaults this session
  }
  loaded = true
  await migrateLegacy()
  emit()
}

/** Forgets the settings in memory (on sign-out). */
export function clearSettings() {
  for (const t of pending.values()) clearTimeout(t)
  pending.clear()
  values = {}
  loaded = false
  emit()
}

export function settingsLoaded() { return loaded }

export function getSetting<T>(key: string, fallback: T): T {
  return key in values ? (values[key] as T) : fallback
}

/**
 * Changes a setting. The app sees it at once; the save is sent after a short pause so quick
 * changes (a slider, typing) become one request. null or undefined removes it.
 */
export function setSetting(key: string, value: unknown) {
  if (value == null) delete values[key]
  else values[key] = value
  values = { ...values }
  emit()
  clearTimeout(pending.get(key))
  pending.set(key, setTimeout(() => {
    pending.delete(key)
    const req = value == null ? api.deleteSetting(key) : api.putSetting(key, value)
    req.catch(() => { /* kept for this session; the next change tries again */ })
  }, 400))
}

/** A setting as React state: re-renders when it changes anywhere in the app. */
export function useSetting<T>(key: string, fallback: T): [T, (v: T) => void] {
  const v = useSyncExternalStore(subscribe, () => (key in values ? values[key] : undefined))
  return [v === undefined ? fallback : (v as T), (next: T) => setSetting(key, next)]
}
