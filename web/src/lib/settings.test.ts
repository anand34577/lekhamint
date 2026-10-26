import { beforeEach, describe, expect, it, vi } from 'vitest'

const store = new Map<string, string>()
// Object.keys(localStorage) in the module reads own keys; expose them through a proxy.
vi.stubGlobal('localStorage', new Proxy({}, {
  ownKeys: () => [...store.keys()],
  getOwnPropertyDescriptor: () => ({ enumerable: true, configurable: true }),
  get: (_t, p) => {
    if (p === 'getItem') return (k: string) => store.get(k) ?? null
    if (p === 'setItem') return (k: string, v: string) => { store.set(k, v) }
    if (p === 'removeItem') return (k: string) => { store.delete(k) }
    return undefined
  },
}))

const server = new Map<string, unknown>()
vi.mock('./api', () => ({
  api: {
    settings: vi.fn(async () => Object.fromEntries(server)),
    putSetting: vi.fn(async (k: string, v: unknown) => { server.set(k, v) }),
    deleteSetting: vi.fn(async (k: string) => { server.delete(k) }),
  },
}))

const { clearSettings, getSetting, loadSettings, setSetting } = await import('./settings')

describe('settings', () => {
  beforeEach(() => { store.clear(); server.clear(); clearSettings(); vi.useRealTimers() })

  it('loads what the server has and falls back to defaults', async () => {
    server.set('theme', 'dark')
    await loadSettings()
    expect(getSetting('theme', 'system')).toBe('dark')
    expect(getSetting('accent', 'teal')).toBe('teal')
  })

  it('shows a change at once and saves it shortly after; null removes it', async () => {
    vi.useFakeTimers()
    await loadSettings()
    setSetting('tx_view', 'calendar')
    expect(getSetting('tx_view', 'list')).toBe('calendar')
    expect(server.has('tx_view')).toBe(false)
    await vi.advanceTimersByTimeAsync(500)
    expect(server.get('tx_view')).toBe('calendar')
    setSetting('tx_view', null)
    await vi.advanceTimersByTimeAsync(500)
    expect(server.has('tx_view')).toBe(false)
    expect(getSetting('tx_view', 'list')).toBe('list')
  })

  it('moves settings an older build left in the browser to the server, once', async () => {
    store.set('kosh.theme', 'light')
    store.set('kosh.hide', '1')
    store.set('kosh.fy.l1', '4')
    store.set('kosh.subs.hidden.l1', '["netflix"]')
    store.set('kosh.outbox', '[]') // not a setting: stays
    server.set('theme', 'dark') // already on the server: the server wins
    await loadSettings()
    expect(getSetting('theme', '')).toBe('dark')
    expect(server.get('hide_amounts')).toBe(true)
    expect(server.get('fy_start.l1')).toBe(4)
    expect(server.get('subscriptions_hidden.l1')).toEqual(['netflix'])
    expect([...store.keys()]).toEqual(['kosh.outbox'])
  })
})
