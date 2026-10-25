/**
 * A small QR code encoder (ISO/IEC 18004), enough for authenticator links:
 * byte mode, error correction level M, versions 1–15 (up to 412 bytes).
 * Written here so the app needs no extra dependency; tested against a decoder.
 */

// Per version (index = version): EC codewords per block, then [blocks, data codewords] groups.
const BLOCKS_M: [number, [number, number][]][] = [
  [0, []],
  [10, [[1, 16]]], [16, [[1, 28]]], [26, [[1, 44]]], [18, [[2, 32]]], [24, [[2, 43]]],
  [16, [[4, 27]]], [18, [[4, 31]]], [22, [[2, 38], [2, 39]]], [22, [[3, 36], [2, 37]]], [26, [[4, 43], [1, 44]]],
  [30, [[1, 50], [4, 51]]], [22, [[6, 36], [2, 37]]], [22, [[8, 37], [1, 38]]], [24, [[4, 40], [5, 41]]], [24, [[5, 41], [5, 42]]],
]
const ALIGN: number[][] = [
  [], [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50],
  [6, 30, 54], [6, 32, 58], [6, 34, 62], [6, 26, 46, 66], [6, 26, 48, 70],
]

function gfMul(x: number, y: number): number {
  let z = 0
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d)
    z ^= ((y >>> i) & 1) * x
  }
  return z & 0xff
}

function rsDivisor(degree: number): number[] {
  const out = new Array<number>(degree).fill(0)
  out[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      out[j] = gfMul(out[j], root)
      if (j + 1 < degree) out[j] ^= out[j + 1]
    }
    root = gfMul(root, 2)
  }
  return out
}

function rsRemainder(data: number[], divisor: number[]): number[] {
  const out = divisor.map(() => 0)
  for (const b of data) {
    const factor = b ^ (out.shift() as number)
    out.push(0)
    divisor.forEach((c, i) => { out[i] ^= gfMul(c, factor) })
  }
  return out
}

const dataCapacity = (v: number) => BLOCKS_M[v][1].reduce((a, [n, d]) => a + n * d, 0)

/** Encodes text; returns the module matrix (true = dark), or throws if too long. */
export function qrMatrix(text: string): boolean[][] {
  const bytes = Array.from(new TextEncoder().encode(text))
  let version = 1
  for (; version <= 15; version++) {
    const countBits = version < 10 ? 8 : 16
    if (4 + countBits + bytes.length * 8 <= dataCapacity(version) * 8) break
  }
  if (version > 15) throw new Error('Text is too long for a QR code')
  const countBits = version < 10 ? 8 : 16

  // Bit stream: byte mode, length, data, terminator, padding.
  const bits: number[] = []
  const put = (val: number, n: number) => { for (let i = n - 1; i >= 0; i--) bits.push((val >>> i) & 1) }
  put(0b0100, 4)
  put(bytes.length, countBits)
  for (const b of bytes) put(b, 8)
  const capBits = dataCapacity(version) * 8
  put(0, Math.min(4, capBits - bits.length))
  while (bits.length % 8) bits.push(0)
  for (let pad = 0xec; bits.length < capBits; pad ^= 0xec ^ 0x11) put(pad, 8)
  const data: number[] = []
  for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0))

  // Split into blocks, add error correction, interleave.
  const [ecLen, groups] = BLOCKS_M[version]
  const div = rsDivisor(ecLen)
  const blocks: number[][] = []
  const ecs: number[][] = []
  let k = 0
  for (const [n, d] of groups) for (let i = 0; i < n; i++) {
    const block = data.slice(k, k + d); k += d
    blocks.push(block)
    ecs.push(rsRemainder(block, div))
  }
  const final: number[] = []
  const maxData = Math.max(...blocks.map((b) => b.length))
  for (let i = 0; i < maxData; i++) for (const b of blocks) if (i < b.length) final.push(b[i])
  for (let i = 0; i < ecLen; i++) for (const e of ecs) final.push(e[i])

  // Function patterns.
  const size = version * 4 + 17
  const m: boolean[][] = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  const fn: boolean[][] = Array.from({ length: size }, () => new Array<boolean>(size).fill(false))
  const set = (x: number, y: number, dark: boolean) => { m[y][x] = dark; fn[y][x] = true }
  for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0) }
  const finder = (cx: number, cy: number) => {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy
      if (x < 0 || y < 0 || x >= size || y >= size) continue
      const dist = Math.max(Math.abs(dx), Math.abs(dy))
      set(x, y, dist !== 2 && dist !== 4)
    }
  }
  finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
  const al = ALIGN[version]
  for (let i = 0; i < al.length; i++) for (let j = 0; j < al.length; j++) {
    if ((i === 0 && j === 0) || (i === 0 && j === al.length - 1) || (i === al.length - 1 && j === 0)) continue
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
  }
  const drawFormat = (mask: number) => {
    const d = (0 << 3) | mask // level M has format bits 00
    let rem = d
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
    const f = ((d << 10) | rem) ^ 0x5412
    const bit = (i: number) => ((f >>> i) & 1) === 1
    for (let i = 0; i <= 5; i++) set(8, i, bit(i))
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i))
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i))
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i))
    set(8, size - 8, true)
  }
  drawFormat(0)
  if (version >= 7) {
    let rem = version
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25)
    const v = (version << 12) | rem
    for (let i = 0; i < 18; i++) {
      const dark = ((v >>> i) & 1) === 1
      const a = size - 11 + (i % 3), b = Math.floor(i / 3)
      set(a, b, dark); set(b, a, dark)
    }
  }

  // Data in the zigzag order.
  let i = 0
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) {
      const x = right - j
      const upward = ((right + 1) & 2) === 0
      const y = upward ? size - 1 - vert : vert
      if (!fn[y][x] && i < final.length * 8) {
        m[y][x] = ((final[i >>> 3] >>> (7 - (i & 7))) & 1) === 1
        i++
      }
    }
  }

  // Pick the mask that leaves the fewest confusing patterns.
  const masks: ((x: number, y: number) => boolean)[] = [
    (x, y) => (x + y) % 2 === 0, (_x, y) => y % 2 === 0, (x) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ]
  const apply = (k: number) => {
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fn[y][x] && masks[k](x, y)) m[y][x] = !m[y][x]
  }
  let best = 0, bestScore = Infinity
  for (let k = 0; k < 8; k++) {
    apply(k); drawFormat(k)
    const s = penalty(m)
    if (s < bestScore) { bestScore = s; best = k }
    apply(k) // undo (XOR)
  }
  apply(best); drawFormat(best)
  return m
}

/** Rules 1, 2 and 4 of the standard's mask penalty (runs, 2×2 blocks, dark balance). */
function penalty(m: boolean[][]): number {
  const n = m.length
  let score = 0, dark = 0
  for (let y = 0; y < n; y++) {
    for (const line of [(i: number) => m[y][i], (i: number) => m[i][y]]) {
      let run = 1
      for (let i = 1; i <= n; i++) {
        if (i < n && line(i) === line(i - 1)) run++
        else { if (run >= 5) score += run - 2; run = 1 }
      }
    }
    for (let x = 0; x < n; x++) {
      if (m[y][x]) dark++
      if (x < n - 1 && y < n - 1 && m[y][x] === m[y][x + 1] && m[y][x] === m[y + 1][x] && m[y][x] === m[y + 1][x + 1]) score += 3
    }
  }
  score += Math.floor(Math.abs(dark * 20 - n * n * 10) / (n * n)) * 10
  return score
}

/** An SVG path ("M x y h1 v1 h-1 z" per dark module) for a matrix with a quiet zone. */
export function qrPath(m: boolean[][], quiet = 4): string {
  let d = ''
  m.forEach((row, y) => row.forEach((dark, x) => { if (dark) d += `M${x + quiet} ${y + quiet}h1v1h-1z` }))
  return d
}
