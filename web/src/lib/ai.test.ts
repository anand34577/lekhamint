import { describe, expect, it } from 'vitest'
import { readStream } from './ai'

/** A response body that arrives in the given chunks (split anywhere, as networks do). */
const body = (chunks: string[]) => new ReadableStream<Uint8Array>({
  start(c) { for (const x of chunks) c.enqueue(new TextEncoder().encode(x)); c.close() },
})

describe('assistant stream', () => {
  it('joins text deltas and reports the answer so far', async () => {
    const seen: string[] = []
    const m = await readStream(body(['data: {"choices":[{"delta":{"content":"You sp"}}]}\n\nda', 'ta: {"choices":[{"delta":{"content":"ent ₹1,250."}}]}\n\n', 'data: [DONE]\n\n']), (t) => seen.push(t))
    expect(m.content).toBe('You spent ₹1,250.')
    expect(m.tool_calls).toBeUndefined()
    expect(seen).toEqual(['You sp', 'You spent ₹1,250.'])
  })
  it('assembles tool calls sent in pieces', async () => {
    const m = await readStream(body([
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"spending_summary","arguments":""}}]}}]}\n',
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"from\\":\\"2026-10-01\\","}}]}}]}\n',
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"to\\":\\"2026-10-31\\"}"}},{"index":1,"function":{"name":"budgets","arguments":"{}"}}]}}]}\n',
      'data: [DONE]\n',
    ]), () => {})
    expect(m.tool_calls).toHaveLength(2)
    expect(m.tool_calls![0]).toMatchObject({ id: 'c1', function: { name: 'spending_summary' } })
    expect(JSON.parse(m.tool_calls![0].function.arguments)).toEqual({ from: '2026-10-01', to: '2026-10-31' })
    expect(m.tool_calls![1]).toMatchObject({ id: 'call_1', function: { name: 'budgets', arguments: '{}' } })
  })
  it('turns an error event into a readable error', async () => {
    await expect(readStream(body(['data: {"error":{"message":"model overloaded"}}\n\n']), () => {})).rejects.toThrow('model overloaded')
  })
})
