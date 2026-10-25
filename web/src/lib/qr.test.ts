import { describe, expect, it } from 'vitest'
import jsQR from 'jsqr'
import { qrMatrix } from './qr'

/** Renders a matrix as an RGBA image (4 px per module, 4-module quiet zone) and decodes it. */
function decode(m: boolean[][]): string | null {
  const scale = 4, quiet = 4, size = (m.length + quiet * 2) * scale
  const px = new Uint8ClampedArray(size * size * 4).fill(255)
  m.forEach((row, y) => row.forEach((dark, x) => {
    if (!dark) return
    for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
      const i = (((y + quiet) * scale + dy) * size + (x + quiet) * scale + dx) * 4
      px[i] = px[i + 1] = px[i + 2] = 0
    }
  }))
  return jsQR(px, size, size)?.data ?? null
}

describe('qr', () => {
  it('encodes text that a decoder reads back, across versions', () => {
    const samples = [
      'HELLO',
      'otpauth://totp/LekhaMint:asha%40example.com?algorithm=SHA1&digits=6&issuer=LekhaMint&period=30&secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
      'x'.repeat(150), // version 7+: version information blocks
      'ünïcödé ₹ 1,250 — लेखा',
      'a'.repeat(300),
    ]
    for (const s of samples) expect(decode(qrMatrix(s))).toBe(s)
  })
  it('grows with the text and refuses text that is too long', () => {
    expect(qrMatrix('hi').length).toBe(21)
    expect(qrMatrix('x'.repeat(150)).length).toBeGreaterThanOrEqual(45)
    expect(() => qrMatrix('x'.repeat(1000))).toThrow()
  })
})
