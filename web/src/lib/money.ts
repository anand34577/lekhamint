// Money is always an integer number of minor units (paise, cents), as on the
// server and Android. Never use floating point for amounts. Mirrors
// android/.../core/Money.kt so both clients format identically.

export interface CurrencyInfo { code: string; name: string; symbol: string; digits: number }

const LIST: CurrencyInfo[] = [
  ['INR', 'Indian rupee', '₹'], ['USD', 'US dollar', '$'], ['EUR', 'Euro', '€'], ['GBP', 'British pound', '£'],
  ['JPY', 'Japanese yen', '¥', 0], ['CNY', 'Chinese yuan', 'CN¥'], ['AUD', 'Australian dollar', 'A$'],
  ['CAD', 'Canadian dollar', 'C$'], ['CHF', 'Swiss franc', 'CHF '], ['SGD', 'Singapore dollar', 'S$'],
  ['AED', 'UAE dirham', 'AED '], ['SAR', 'Saudi riyal', 'SAR '], ['HKD', 'Hong Kong dollar', 'HK$'],
  ['NZD', 'New Zealand dollar', 'NZ$'], ['SEK', 'Swedish krona', 'kr '], ['NOK', 'Norwegian krone', 'kr '],
  ['DKK', 'Danish krone', 'kr '], ['PLN', 'Polish złoty', 'zł '], ['CZK', 'Czech koruna', 'Kč '],
  ['HUF', 'Hungarian forint', 'Ft '], ['RUB', 'Russian ruble', '₽'], ['TRY', 'Turkish lira', '₺'],
  ['BRL', 'Brazilian real', 'R$'], ['MXN', 'Mexican peso', 'MX$'], ['ZAR', 'South African rand', 'R '],
  ['NGN', 'Nigerian naira', '₦'], ['KES', 'Kenyan shilling', 'KSh '], ['PKR', 'Pakistani rupee', 'Rs '],
  ['BDT', 'Bangladeshi taka', '৳'], ['LKR', 'Sri Lankan rupee', 'Rs '], ['NPR', 'Nepalese rupee', 'Rs '],
  ['IDR', 'Indonesian rupiah', 'Rp '], ['MYR', 'Malaysian ringgit', 'RM '], ['THB', 'Thai baht', '฿'],
  ['VND', 'Vietnamese dong', '₫', 0], ['PHP', 'Philippine peso', '₱'], ['KRW', 'South Korean won', '₩', 0],
  ['ILS', 'Israeli shekel', '₪'], ['KWD', 'Kuwaiti dinar', 'KD ', 3], ['BHD', 'Bahraini dinar', 'BD ', 3],
  ['OMR', 'Omani rial', 'OMR ', 3], ['BTC', 'Bitcoin', '₿', 8],
].map(([code, name, symbol, digits]) => ({ code, name, symbol, digits: (digits ?? 2) as number }) as CurrencyInfo)

const BY_CODE = new Map(LIST.map((c) => [c.code, c]))
const INDIAN = new Set(['INR', 'PKR', 'NPR', 'BDT', 'LKR'])

export const currencies = LIST
export const currency = (code: string): CurrencyInfo => BY_CODE.get(code) ?? { code, name: code, symbol: code + ' ', digits: 2 }

function group(intPart: string, indian: boolean): string {
  if (intPart.length <= 3) return intPart
  const last3 = intPart.slice(-3)
  let rest = intPart.slice(0, -3)
  const size = indian ? 2 : 3
  const parts: string[] = []
  while (rest.length > size) { parts.unshift(rest.slice(-size)); rest = rest.slice(0, -size) }
  if (rest) parts.unshift(rest)
  return parts.join(',') + ',' + last3
}

/** Formats minor units: 125050 INR -> "₹1,250.50". Uses BigInt so huge values stay exact. */
export function formatMoney(minor: number, code: string, opts: { signed?: boolean; symbol?: boolean } = {}): string {
  const c = currency(code)
  const neg = minor < 0
  const abs = BigInt(Math.abs(Math.trunc(minor)))
  const scale = 10n ** BigInt(c.digits)
  const intPart = (abs / scale).toString()
  const frac = c.digits > 0 ? '.' + (abs % scale).toString().padStart(c.digits, '0') : ''
  const sign = neg ? '−' : opts.signed && minor > 0 ? '+' : ''
  const num = group(intPart, INDIAN.has(code)) + frac
  return opts.symbol === false ? sign + num : sign + c.symbol + num
}

/** Short chart labels: ₹1.2L, $12.5K. */
export function compactMoney(minor: number, code: string): string {
  const c = currency(code)
  const v = Math.abs(minor) / 10 ** c.digits
  const sign = minor < 0 ? '−' : ''
  const steps: [number, string][] = INDIAN.has(code) ? [[1e7, 'Cr'], [1e5, 'L'], [1e3, 'K']] : [[1e9, 'B'], [1e6, 'M'], [1e3, 'K']]
  for (const [d, s] of steps) if (v >= d) return `${sign}${c.symbol.trim()}${(v / d).toFixed(1).replace(/\.0$/, '')}${s}`
  return `${sign}${c.symbol.trim()}${Math.round(v)}`
}

/** Parses "1,250.50" into minor units, rounding half-up; null if invalid. */
export function parseMoney(text: string, code: string): number | null {
  // People group and mark decimals the way their region does: 1,234.56 · 1.234,56 · 1 234,56 · 1'234.56.
  // With both marks, the later one is the decimal point. "12,50" is twelve and a half: a lone comma
  // with one or two digits after it can't be thousands grouping. "1.234.567" has only grouping dots.
  let t = text.trim().replace(/[\s_'’  ]/g, '').replace(/−/g, '-')
  const lastDot = t.lastIndexOf('.'), lastComma = t.lastIndexOf(',')
  if (lastDot >= 0 && lastComma >= 0) t = lastComma > lastDot ? t.replace(/\./g, '').replace(',', '.') : t.replace(/,/g, '')
  else if (lastComma >= 0 && /^[^,]*,\d{1,2}$/.test(t)) t = t.replace(',', '.')
  else if (/^-?\d{1,3}(\.\d{3}){2,}$/.test(t)) t = t.replace(/\./g, '')
  const clean = t.replace(/,/g, '')
  if (!/^-?\d*\.?\d*$/.test(clean) || clean === '' || clean === '-' || clean === '.') return null
  const neg = clean.startsWith('-')
  const [i, f = ''] = clean.replace('-', '').split('.')
  const digits = currency(code).digits
  let minor = BigInt(i || '0') * 10n ** BigInt(digits)
  const fracDigits = (f + '0'.repeat(digits + 1)).slice(0, digits + 1)
  if (digits > 0) minor += BigInt(fracDigits.slice(0, digits))
  if (Number(fracDigits[digits] ?? '0') >= 5) minor += 1n
  const n = Number(neg ? -minor : minor)
  return Number.isSafeInteger(n) ? n : null
}

/** Minor units to a plain editable decimal: 125050 -> "1250.5". */
export function toPlain(minor: number, code: string): string {
  const d = currency(code).digits
  if (d === 0) return String(minor)
  const s = formatMoney(minor, code, { symbol: false }).replace(/,/g, '').replace('−', '-')
  return s.replace(/\.?0+$/, '')
}

export function defaultCurrency(): string {
  try {
    const region = new Intl.Locale(navigator.language).maximize().region
    const map: Record<string, string> = { IN: 'INR', US: 'USD', GB: 'GBP', JP: 'JPY', AU: 'AUD', CA: 'CAD', SG: 'SGD', AE: 'AED' }
    return (region && map[region]) || 'USD'
  } catch { return 'USD' }
}
