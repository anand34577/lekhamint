import { expect, test } from 'vitest'
import { crc32, workbook } from './xlsx'

test('crc32 check value', () => {
  expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926)
})

test('workbook is a zip with the expected parts', () => {
  const b = workbook([{ name: 'Loan: schedule/2026', intro: ['Home loan', 'Generated today'], header: ['#', 'Date', 'Interest', 'Note'],
    rows: [[1, { date: '2026-01-05' }, { money: 708333 }, 'a & b <c>'], [2, { date: '2026-02-05' }, { money: 707204 }, null]],
    total: ['Total', null, { money: 1415537 }, null] }])
  expect([b[0], b[1], b[2], b[3]]).toEqual([0x50, 0x4b, 0x03, 0x04])
  const text = new TextDecoder().decode(b)
  expect(text).toContain('Loan  schedule 2026')
  expect(text).toContain('a &amp; b &lt;c&gt;')
  expect(text).toContain('<v>7083.33</v>')
  expect(text).toContain('<v>46027</v>') // 2026-01-05 as an Excel serial
})
