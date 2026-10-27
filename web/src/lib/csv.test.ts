import { describe, expect, it } from 'vitest'
import { detectDateFormat, detectDelimiter, findDuplicates, fingerprints, guessRoles, matchCategory, parseAmount, parseCsv, parseDateIn, parseTimeIn, toRows } from './csv'
import { ID_NAMESPACE, uuidv5 } from './util'

describe('csv parsing', () => {
  it('handles quotes, embedded commas, newlines and a BOM', () => {
    const t = '﻿Date,Narration,Amount\r\n24/10/2026,"SWIGGY, BLR","-1,250.50"\n25/10/2026,"Line ""one""\ntwo",100\n\n'
    expect(parseCsv(t)).toEqual([['Date', 'Narration', 'Amount'], ['24/10/2026', 'SWIGGY, BLR', '-1,250.50'], ['25/10/2026', 'Line "one"\ntwo', '100']])
  })
  it('detects delimiters', () => {
    expect(detectDelimiter('a;b;c\n1;2;3')).toBe(';')
    expect(detectDelimiter('a\tb\n1\t2')).toBe('\t')
    expect(detectDelimiter('a,b\n"1;x",2')).toBe(',')
  })
  it('guesses common bank headers', () => {
    expect(guessRoles(['Txn Date', 'Narration', 'Chq./Ref.No.', 'Withdrawal Amt.', 'Deposit Amt.', 'Closing Balance']))
      .toEqual(['date', 'description', 'note', 'debit', 'credit', 'ignore'])
    expect(guessRoles(['Date', 'Title', 'Category', 'Account', 'Amount', 'Currency', 'Type']))
      .toEqual(['date', 'description', 'category', 'ignore', 'amount', 'ignore', 'type'])
  })
})

describe('dates and amounts', () => {
  it('parses many date styles', () => {
    expect(parseDateIn('24/10/2026', 'DMY')).toBe('2026-10-24')
    expect(parseDateIn('10/24/2026', 'MDY')).toBe('2026-10-24')
    expect(parseDateIn('2026-10-24', 'DMY')).toBe('2026-10-24')
    expect(parseDateIn('24-Oct-26', 'DMY')).toBe('2026-10-24')
    expect(parseDateIn('Oct 24, 2026', 'DMY')).toBe('2026-10-24')
    expect(parseDateIn('2026-10-24 13:45:00', 'DMY')).toBe('2026-10-24')
    expect(parseDateIn('31/02/2026', 'DMY')).toBeNull()
    expect(detectDateFormat(['01/02/2026', '13/02/2026'])).toBe('DMY')
    expect(detectDateFormat(['02/13/2026', '02/01/2026'])).toBe('MDY')
    expect(detectDateFormat(['2026-10-01', '2026-10-05'])).toBe('YMD')
  })
  it('keeps the time of day when a statement has one', () => {
    expect(parseTimeIn('2026-10-24 13:45:00')).toBe('13:45')
    expect(parseTimeIn('24/10/2026 2:05 PM')).toBe('14:05')
    expect(parseTimeIn('12:30 a.m.')).toBe('00:30')
    expect(parseTimeIn('2026-10-24T09:05')).toBe('09:05')
    expect(parseTimeIn('24/10/2026 00:00:00')).toBe('') // a bare date padded with midnight
    expect(parseTimeIn('24/10/2026')).toBe('')
    expect(guessRoles(['Date', 'Time', 'Details', 'Amount'])).toEqual(['date', 'time', 'description', 'amount'])
  })
  it('parses signed amounts exactly', () => {
    expect(parseAmount('-1,250.50', 'INR')).toBe(-125050)
    expect(parseAmount('(45.00)', 'USD')).toBe(-4500)
    expect(parseAmount('₹ 1,250', 'INR')).toBe(125000)
    expect(parseAmount('120.00 DR', 'INR')).toBe(-12000)
    expect(parseAmount('120.00 Cr', 'INR')).toBe(12000)
    expect(parseAmount('1.234,56', 'EUR', true)).toBe(123456)
    expect(parseAmount('1500', 'JPY')).toBe(1500)
    expect(parseAmount('abc', 'USD')).toBeNull()
    expect(parseAmount('0.1', 'USD')).toBe(10)
  })
})

describe('rows, duplicates and ids', () => {
  const table = [
    ['Date', 'Narration', 'Withdrawal', 'Deposit'],
    ['01/10/2026', 'SALARY OCT', '', '85,000.00'],
    ['02/10/2026', 'SWIGGY  ORDER', '450.00', ''],
    ['02/10/2026', 'SWIGGY  ORDER', '450.00', ''],
    ['bad', 'x', '1', ''],
    ['03/10/2026', 'ZERO', '', ''],
  ]
  const o = { roles: guessRoles(table[0]), hasHeader: true, dateFormat: 'DMY' as const, currency: 'INR', decimalComma: false, positiveIsExpense: false }
  it('turns debit/credit columns into typed rows and reports bad lines', () => {
    const { rows, errors } = toRows(table, o)
    expect(rows.map((r) => [r.date, r.type, r.amount, r.description])).toEqual([
      ['2026-10-01', 'income', 8500000, 'SALARY OCT'], ['2026-10-02', 'expense', 45000, 'SWIGGY ORDER'], ['2026-10-02', 'expense', 45000, 'SWIGGY ORDER'],
    ])
    expect(errors.map((e) => e.line)).toEqual([5, 6])
  })
  it('numbers identical rows so re-imports map to the same ids', async () => {
    const { rows } = toRows(table, o)
    const f = fingerprints(rows)
    expect(f[1]).not.toBe(f[2])
    expect(fingerprints(rows)).toEqual(f)
    const id = await uuidv5(ID_NAMESPACE, 'import:acc:' + f[1])
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(await uuidv5(ID_NAMESPACE, 'import:acc:' + f[1])).toBe(id)
  })
  it('matches the server and Android UUIDv5', async () => {
    expect(await uuidv5(ID_NAMESPACE, '0192f0c4-0000-7000-8000-000000000001:2026-01-31')).toBe('1e0966bb-cefe-5d1f-ab05-ff19e702f91b')
  })
  it('pairs duplicates one-to-one within two days', () => {
    const { rows } = toRows(table, o)
    const existing = [
      { id: 'e1', type: 'expense', amount: 45000, local_date: '2026-10-03', account_id: 'acc' },
      { id: 'e2', type: 'expense', amount: 45000, local_date: '2026-10-09', account_id: 'acc' },
      { id: 'e3', type: 'income', amount: 8500000, local_date: '2026-10-01', account_id: 'other' },
    ]
    const d = findDuplicates(rows, 'acc', existing)
    expect([...d.entries()]).toEqual([[1, 'e1']])
  })
  it('matches categories by name or path', () => {
    const cats = [
      { id: 'f', name: 'Food', parent_id: null, kind: 'expense' }, { id: 'g', name: 'Groceries', parent_id: 'f', kind: 'expense' },
      { id: 's', name: 'Salary', parent_id: null, kind: 'income' },
    ]
    expect(matchCategory('groceries', cats, 'expense')).toBe('g')
    expect(matchCategory('Food: Groceries', cats, 'expense')).toBe('g')
    expect(matchCategory('Food > Groceries', cats, 'expense')).toBe('g')
    expect(matchCategory('salary', cats, 'income')).toBe('s')
    expect(matchCategory('Nope', cats, 'expense')).toBeNull()
  })
})
