// Income-tax sections a category can count towards, and the financial year.
// The limits are the commonly quoted Indian old-regime figures, shown only as a
// guide: LekhaMint adds up what you recorded; it does not give tax advice.
import { getSetting, setSetting } from './settings'

export interface TaxSection { code: string; label: string; examples: string; /** Yearly limit in minor units of INR, if there is a simple one. */ limit?: number }

const L = (rupees: number) => rupees * 100

export const TAX_SECTIONS: TaxSection[] = [
  { code: '80C', label: '80C · Investments and payments', examples: 'PPF, EPF, ELSS, life insurance, tuition fees, home loan principal', limit: L(150000) },
  { code: '80CCD(1B)', label: '80CCD(1B) · NPS (extra)', examples: 'Your own NPS contribution beyond 80C', limit: L(50000) },
  { code: '80D', label: '80D · Health insurance', examples: 'Health insurance premiums, preventive check-ups', limit: L(25000) },
  { code: '80D-parents', label: '80D · Parents’ health insurance', examples: 'Premiums for parents (₹50,000 if they are seniors)', limit: L(25000) },
  { code: '80E', label: '80E · Education loan interest', examples: 'Interest on a higher-education loan (no limit)' },
  { code: '80G', label: '80G · Donations', examples: 'Donations to eligible charities and funds' },
  { code: '80TTA', label: '80TTA · Savings interest', examples: 'Interest from savings accounts', limit: L(10000) },
  { code: '24(b)', label: '24(b) · Home loan interest', examples: 'Interest on a loan for a self-occupied home', limit: L(200000) },
  { code: 'HRA', label: 'HRA · Rent paid', examples: 'Rent, for house rent allowance or 80GG' },
  { code: 'medical', label: 'Medical expenses', examples: 'Bills you may claim or get reimbursed' },
  { code: 'business', label: 'Business or work expense', examples: 'Costs you claim against business or freelance income' },
  { code: 'other', label: 'Other tax-relevant', examples: 'Anything else you want in the year’s tax summary' },
]

export const taxSection = (code: string | null | undefined) => TAX_SECTIONS.find((s) => s.code === code)

/** Month the financial year starts (1–12): April for India, else January, unless the person chose otherwise. */
export function fyStartMonth(ledgerId: string, currency: string): number {
  const v = Number(getSetting(`fy_start.${ledgerId}`, 0))
  if (v >= 1 && v <= 12) return v
  return currency === 'INR' ? 4 : 1
}

export function setFyStartMonth(ledgerId: string, month: number) {
  setSetting(`fy_start.${ledgerId}`, month)
}

/** The financial year containing [date]: [from, to, label]. */
export function financialYear(date: string, startMonth: number, offset = 0): [string, string, string] {
  const y = Number(date.slice(0, 4)), m = Number(date.slice(5, 7))
  const first = (m >= startMonth ? y : y - 1) + offset
  const pad = (n: number) => String(n).padStart(2, '0')
  const from = `${first}-${pad(startMonth)}-01`
  const endMonth = startMonth === 1 ? 12 : startMonth - 1
  const endYear = startMonth === 1 ? first : first + 1
  const to = `${endYear}-${pad(endMonth)}-${new Date(endYear, endMonth, 0).getDate()}`
  const label = startMonth === 1 ? `${first}` : `FY ${first}–${String(first + 1).slice(2)}`
  return [from, to, label]
}

/** The months of a financial year as YYYY-MM. */
export function fyMonths(from: string): string[] {
  const out: string[] = []
  let y = Number(from.slice(0, 4)), m = Number(from.slice(5, 7))
  for (let i = 0; i < 12; i++) {
    out.push(`${y}-${String(m).padStart(2, '0')}`)
    m++; if (m > 12) { m = 1; y++ }
  }
  return out
}
