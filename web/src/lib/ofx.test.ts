import { describe, expect, it } from 'vitest'
import { parseOfx, parseQif, statementKind } from './ofx'
import { fingerprints } from './csv'

const OFX1 = `OFXHEADER:100
DATA:OFXSGML
<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>INR<BANKACCTFROM><ACCTID>12345</BANKACCTFROM>
<BANKTRANLIST>
<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261020193000[+5:30:IST]<TRNAMT>-450.00<FITID>A1<NAME>SWIGGY &amp; CO<MEMO>UPI dinner
<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261001<TRNAMT>85000.00<FITID>A2<NAME>SALARY
</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>`

const OFX2 = `<?xml version="1.0"?><OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>USD</CURDEF>
<BANKTRANLIST><STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20261003</DTPOSTED><TRNAMT>-12.99</TRNAMT><FITID>X9</FITID><NAME>Netflix</NAME></STMTTRN></BANKTRANLIST>
</CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>`

const QIF = `!Type:Bank
D10/24'26
T-1,250.00
PBig Bazaar
MGroceries for the week
LFood:Groceries
^
D10/25/2026
T5000
PRefund
L[Savings]
^
`

describe('statement files', () => {
  it('recognises the kind of file', () => {
    expect(statementKind('x.ofx', '')).toBe('ofx')
    expect(statementKind('x.txt', OFX2)).toBe('ofx')
    expect(statementKind('x.qif', '')).toBe('qif')
    expect(statementKind('x.csv', 'date,amount')).toBe('csv')
  })
  it('reads OFX 1 (SGML) and OFX 2 (XML)', () => {
    const a = parseOfx(OFX1, 'INR')
    expect(a.currency).toBe('INR')
    expect(a.rows).toEqual([
      { line: 1, date: '2026-10-20', time: '19:30', type: 'expense', amount: 45000, description: 'SWIGGY & CO', note: 'UPI dinner', category: '', ref: 'ofx:12345:A1' },
      { line: 2, date: '2026-10-01', time: '', type: 'income', amount: 8500000, description: 'SALARY', note: '', category: '', ref: 'ofx:12345:A2' },
    ])
    expect(fingerprints(a.rows)).toEqual(['ofx:12345:A1', 'ofx:12345:A2'])
    const b = parseOfx(OFX2, 'INR')
    expect(b.currency).toBe('USD')
    expect(b.rows[0]).toMatchObject({ date: '2026-10-03', amount: 1299, type: 'expense', description: 'Netflix' })
  })
  it('reads QIF with categories and transfers', () => {
    const q = parseQif(QIF, 'INR', true)
    expect(q.errors).toEqual([])
    expect(q.rows[0]).toMatchObject({ date: '2026-10-24', type: 'expense', amount: 125000, description: 'Big Bazaar', note: 'Groceries for the week', category: 'Food · Groceries' })
    expect(q.rows[1]).toMatchObject({ date: '2026-10-25', type: 'income', amount: 500000, category: '', note: 'Transfer [Savings]' })
  })
})
