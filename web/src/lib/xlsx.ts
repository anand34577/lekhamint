// A small, dependency-free Excel (.xlsx) writer for exports. It writes real
// numbers (so sums and filters work in Excel, LibreOffice and Google Sheets),
// real dates, a bold frozen header row with filters, and sensible column
// widths. Files are stored uncompressed in the ZIP, which every reader accepts.

export type Cell = string | number | null | undefined | { date: string } | { money: number; digits?: number } | { formula: string; money?: boolean }

export interface Sheet {
  name: string
  /** Optional lines above the table (title, period, filters). */
  intro?: string[]
  header: string[]
  rows: Cell[][]
  /** Optional totals row, styled bold. */
  total?: Cell[]
  widths?: number[]
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  // Characters XML 1.0 does not allow.
  // eslint-disable-next-line no-control-regex
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')

function colName(i: number): string {
  let s = ''
  for (i++; i > 0; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s
  return s
}

/** Excel's date serial (days since 1899-12-30) for YYYY-MM-DD. */
function serial(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number)
  return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(1899, 11, 30)) / 864e5)
}

// Style indexes in styles.xml: 0 normal, 1 bold, 2 date, 3 money (2 dp), 4 bold money, 5 title, 6 money 0 dp, 7 money 3 dp, 8 muted
const MONEY_STYLE: Record<number, number> = { 0: 6, 2: 3, 3: 7 }

function cellXml(ref: string, v: Cell, bold = false): string {
  if (v === null || v === undefined || v === '') return ''
  if (typeof v === 'number') return `<c r="${ref}"${bold ? ' s="1"' : ''}><v>${v}</v></c>`
  if (typeof v === 'string') return `<c r="${ref}" t="inlineStr"${bold ? ' s="1"' : ''}><is><t xml:space="preserve">${esc(v)}</t></is></c>`
  if ('date' in v) return /^\d{4}-\d{2}-\d{2}$/.test(v.date) ? `<c r="${ref}" s="2"><v>${serial(v.date)}</v></c>` : ''
  if ('formula' in v) return `<c r="${ref}"${v.money ? ` s="${bold ? 4 : 3}"` : bold ? ' s="1"' : ''}><f>${esc(v.formula)}</f></c>`
  const digits = v.digits ?? 2
  const value = v.money / 10 ** digits
  return `<c r="${ref}" s="${bold ? 4 : MONEY_STYLE[digits] ?? 3}"><v>${value.toFixed(digits)}</v></c>`
}

function sheetXml(s: Sheet): string {
  const rows: string[] = []
  let r = 1
  for (const line of s.intro ?? []) rows.push(`<row r="${r}">${cellXml(`A${r}`, line)}</row>`.replace('t="inlineStr"', `t="inlineStr" s="${r === 1 ? 5 : 8}"`)), r++
  if (s.intro?.length) r++ // blank line
  const headerRow = r
  rows.push(`<row r="${r}">${s.header.map((h, i) => cellXml(`${colName(i)}${r}`, h, true)).join('')}</row>`)
  r++
  for (const row of s.rows) { rows.push(`<row r="${r}">${row.map((c, i) => cellXml(`${colName(i)}${r}`, c)).join('')}</row>`); r++ }
  if (s.total) { rows.push(`<row r="${r}">${s.total.map((c, i) => cellXml(`${colName(i)}${r}`, c, true)).join('')}</row>`); r++ }
  const last = colName(Math.max(0, s.header.length - 1))
  const widths = s.header.map((h, i) => {
    const w = s.widths?.[i] ?? Math.min(60, Math.max(h.length + 2, ...s.rows.slice(0, 200).map((row) => {
      const c = row[i]
      return typeof c === 'string' ? c.length + 1 : c && typeof c === 'object' ? 14 : typeof c === 'number' ? String(c).length + 2 : 4
    })))
    return `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`
  }).join('')
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow}" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<cols>${widths}</cols><sheetData>${rows.join('')}</sheetData>
${s.rows.length ? `<autoFilter ref="A${headerRow}:${last}${headerRow + s.rows.length}"/>` : ''}
</worksheet>`
}

const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="4"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/><numFmt numFmtId="165" formatCode="#,##0.00;[Red]-#,##0.00"/><numFmt numFmtId="166" formatCode="#,##0;[Red]-#,##0"/><numFmt numFmtId="167" formatCode="#,##0.000;[Red]-#,##0.000"/></numFmts>
<fonts count="4"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font><font><sz val="10"/><color rgb="FF666666"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEEF2F1"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="9">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="1" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`

/** Builds the .xlsx bytes for [sheets]. Sheet names are made unique and Excel-safe. */
export function workbook(sheets: Sheet[]): Uint8Array {
  const used = new Set<string>()
  const names = sheets.map((s) => {
    let n = s.name.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31).trim() || 'Sheet'
    for (let i = 2; used.has(n.toLowerCase()); i++) n = `${n.slice(0, 28)} ${i}`
    used.add(n.toLowerCase())
    return n
  })
  const files: [string, string][] = [
    ['[Content_Types].xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
${sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`],
    ['_rels/.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
    ['xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
${names.map((n, i) => `<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>`],
    ['xl/_rels/workbook.xml.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}
<Relationship Id="rId${sheets.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`],
    ['xl/styles.xml', STYLES],
    ...sheets.map((s, i) => [`xl/worksheets/sheet${i + 1}.xml`, sheetXml(s)] as [string, string]),
  ]
  return zip(files.map(([n, c]) => [n, new TextEncoder().encode(c)]))
}

// --- ZIP (stored, no compression) ---------------------------------------------

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 }
  return t
})()

export function crc32(b: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < b.length; i++) c = CRC_TABLE[(c ^ b[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

export function zip(files: [string, Uint8Array][]): Uint8Array {
  const enc = new TextEncoder()
  const parts: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const [name, data] of files) {
    const n = enc.encode(name)
    const crc = crc32(data)
    const local = new DataView(new ArrayBuffer(30))
    local.setUint32(0, 0x04034b50, true); local.setUint16(4, 20, true); local.setUint16(6, 0x0800, true) // UTF-8 names
    local.setUint16(8, 0, true); local.setUint32(14, crc, true); local.setUint32(18, data.length, true); local.setUint32(22, data.length, true)
    local.setUint16(26, n.length, true)
    parts.push(new Uint8Array(local.buffer), n, data)
    const c = new DataView(new ArrayBuffer(46))
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true)
    c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, n.length, true)
    c.setUint32(42, offset, true)
    central.push(new Uint8Array(c.buffer), n)
    offset += 30 + n.length + data.length
  }
  const size = central.reduce((a, b) => a + b.length, 0)
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true)
  end.setUint32(12, size, true); end.setUint32(16, offset, true)
  const all = [...parts, ...central, new Uint8Array(end.buffer)]
  const out = new Uint8Array(all.reduce((a, b) => a + b.length, 0))
  let p = 0
  for (const a of all) { out.set(a, p); p += a.length }
  return out
}

export const XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
