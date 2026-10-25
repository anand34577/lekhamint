import { beforeEach, describe, expect, it, vi } from 'vitest'

const store = new Map<string, string>()
vi.stubGlobal('localStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v) }, removeItem: (k: string) => { store.delete(k) } })

const batch = vi.fn()
vi.mock('./api', async (orig) => ({ ...(await orig<typeof import('./api')>()), api: { batch: (...a: unknown[]) => batch(...a) } }))

const { outbox, flushOutbox } = await import('./outbox')
const { ApiError } = await import('./api')

describe('offline outbox', () => {
  beforeEach(() => { store.clear(); batch.mockReset() })
  it('sends in order, drops refused changes and stops when still offline', async () => {
    outbox.add('L', [{ op: 'create_if_missing', entity: 'transactions', id: 'a' }], 'New transaction')
    outbox.add('L', [{ op: 'update', entity: 'transactions', id: 'b' }], 'Edited transaction')
    outbox.add('L', [{ op: 'delete', entity: 'transactions', id: 'c' }], 'Deleted transaction')
    batch.mockResolvedValueOnce([]).mockRejectedValueOnce(new ApiError(422, 'validation', 'amount: is required'))
      .mockRejectedValueOnce(new ApiError(0, 'network', 'offline'))
    expect(await flushOutbox()).toBe(1)
    expect(outbox.list().map((q) => q.label)).toEqual(['Deleted transaction'])
    expect(outbox.failed()[0]).toMatchObject({ label: 'Edited transaction', message: 'amount: is required' })
    batch.mockResolvedValueOnce([])
    expect(await flushOutbox()).toBe(1)
    expect(outbox.list()).toEqual([])
    expect(batch.mock.calls.map((c) => (c[1] as { id: string }[])[0].id)).toEqual(['a', 'b', 'c', 'c'])
  })
  it('keeps changes when the server has a temporary problem', async () => {
    outbox.add('L', [{ op: 'delete', entity: 'tags', id: 'x' }], 'Deleted tag')
    batch.mockRejectedValueOnce(new ApiError(503, 'unavailable', 'down'))
    expect(await flushOutbox()).toBe(0)
    expect(outbox.list()).toHaveLength(1)
    expect(outbox.failed()).toEqual([])
  })
})
