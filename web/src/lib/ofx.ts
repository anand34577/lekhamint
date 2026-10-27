// OFX (Open Financial Exchange, versions 1 and 2) and QIF (Quicken) statement
// files, which many banks and older money apps export. Both become the same
// rows as a CSV import, so duplicates, rules and IDs work the same way.

import { detectDateFormat, parseAmount, parseDateIn, type ParsedRow, type RowError } from './csv'

export type StatementKind = 'ofx' | 'qif' | 'csv'

/** What kind of statement a file holds, from its name and first bytes. */
export function statementKind(name: string, text: string): StatementKind {
  const head = text.slice(0, 2000).toUpperCase()
  if (/\.(ofx|qfx)$/i.test(name) || head.includes('<OFX>') || head.includes('OFXHEADER')) return 'ofx'
  if (/\.qif$/i.test(name) || /^\s*!(TYPE|ACCOUNT|OPTION)/i.test(text.slice(0, 200))) return 'qif'
  return 'csv'
}

export interface Parsed { rows: ParsedRow[]; errors: RowError[]; currency?: string; account?: string }

/** OFX: every <STMTTRN> block, in SGML (tags left open) or XML form. */
export function parseOfx(text: string, currency: string): Parsed {
  const rows: ParsedRow[] = [], errors: RowError[] = []
  const cur = /<CURDEF>\s*([A-Z]{3})/i.exec(text)?.[1]?.toUpperCase()
  const acct = /<ACCTID>\s*([^<\r\n]+)/i.exec(text)?.[1]?.trim()
  const blocks = text.split(/<STMTTRN>/i).slice(1)
  blocks.forEach((raw, k) => {
    const block = raw.split(/<\/STMTTRN>|<\/BANKTRANLIST>|<\/CCSTMTRS>/i)[0]
    const f: Record<string, string> = {}
    for (const m of block.matchAll(/<([A-Z0-9.]+)>([^<\r\n]*)/gi)) {
      const v = decodeEntities(m[2].trim())
      if (v) f[m[1].toUpperCase()] = v
    }
    const line = k + 1
    // DTPOSTED is YYYYMMDD, optionally followed by HHMMSS; midnight is a padded bare date.
    const date = /^(\d{4})(\d{2})(\d{2})(?:(\d{2})(\d{2}))?/.exec(f.DTPOSTED ?? f.DTUSER ?? '')
    if (!date) { errors.push({ line, message: 'No date' }); return }
    const time = date[4] && date[5] && +date[4] < 24 && +date[5] < 60 && date[4] + date[5] !== '0000' ? `${date[4]}:${date[5]}` : ''
    const amtRaw = f.TRNAMT ?? ''
    const amount = parseAmount(amtRaw, cur ?? currency, /,\d{1,2}$/.test(amtRaw) && !amtRaw.includes('.'))
    if (amount === null || amount === 0) { errors.push({ line, message: `Unreadable amount “${amtRaw}”` }); return }
    const name = f.NAME ?? f.PAYEE ?? ''
    const memo = f.MEMO ?? ''
    rows.push({
      line, date: `${date[1]}-${date[2]}-${date[3]}`, time, type: amount < 0 ? 'expense' : 'income', amount: Math.abs(amount),
      description: (name || memo).replace(/\s+/g, ' '), note: name && memo && memo !== name ? memo : '', category: '',
      ref: f.FITID ? `ofx:${acct ?? ''}:${f.FITID}` : undefined,
    })
  })
  return { rows, errors, currency: cur, account: acct }
}

/** QIF: records end with "^"; D date, T/U amount, P payee, M memo, L category. */
export function parseQif(text: string, currency: string, preferUS = false): Parsed {
  const records: Record<string, string>[] = []
  let cur: Record<string, string> = {}
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd()
    if (!line || line.startsWith('!')) continue
    if (line === '^') { if (Object.keys(cur).length) records.push(cur); cur = {}; continue }
    const code = line[0], val = line.slice(1).trim()
    if (!(code in cur)) cur[code] = val // split lines (S, $, E) repeat; the first value is the transaction's
  }
  if (Object.keys(cur).length) records.push(cur)
  // QIF dates look like 10/24/2026, 24/10'26 or 10/24' 6: normalise, then pick day or month first.
  const norm = (d: string) => {
    const s = d.replace(/'\s*/g, '/').replace(/\s+/g, '')
    const p = s.split(/[/.-]/)
    if (p.length === 3 && p[2].length === 1) p[2] = '200' + p[2]
    return p.join('/')
  }
  const fmt = detectDateFormat(records.map((r) => norm(r.D ?? '')).filter(Boolean), preferUS)
  const rows: ParsedRow[] = [], errors: RowError[] = []
  records.forEach((r, k) => {
    const line = k + 1
    const date = parseDateIn(norm(r.D ?? ''), fmt)
    if (!date) { errors.push({ line, message: `Unreadable date “${r.D ?? ''}”` }); return }
    const amtRaw = r.T ?? r.U ?? ''
    const amount = parseAmount(amtRaw, currency, /,\d{1,2}$/.test(amtRaw) && !amtRaw.includes('.'))
    if (amount === null || amount === 0) { errors.push({ line, message: `Unreadable amount “${amtRaw}”` }); return }
    // Categories in brackets are transfers to another account; keep the name as a note.
    const cat = r.L && !/^\[.*\]$/.test(r.L) ? r.L.replace(/:/g, ' · ') : ''
    rows.push({
      line, date, time: '', type: amount < 0 ? 'expense' : 'income', amount: Math.abs(amount),
      description: (r.P ?? r.M ?? '').replace(/\s+/g, ' '), note: [r.P ? r.M : '', r.L && !cat ? `Transfer ${r.L}` : '', r.N ? `#${r.N}` : ''].filter(Boolean).join(' · '),
      category: cat,
    })
  })
  return { rows, errors }
}

function decodeEntities(s: string): string {
  return s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;|&#39;/g, "'")
}
