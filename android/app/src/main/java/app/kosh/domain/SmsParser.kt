package app.kosh.domain

/**
 * Reads a bank SMS, a card alert or a payment app's message: the amount, whether money went out or came in,
 * who it was paid to or received from, and the last digits of the account or card. Only used to fill a form
 * or a "found payments" list; the person checks it before anything is saved. Runs on the phone only.
 */
object SmsParser {
    data class Result(
        val amount: String?,
        val type: String,
        val payee: String?,
        val note: String,
        /** Last 3–4 digits of the account or card, when the message names one ("A/c XX1234"). */
        val accountHint: String? = null,
        /** True when this looks like a real debit or credit, not an OTP, an offer or a reminder. */
        val isTransaction: Boolean = false,
    )

    private const val NUM = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private val currencyAmountRe = Regex("""(?i)(?:rs\.?|inr|₹|usd|\$|eur|€|gbp|£|aed|sgd)\s*:?\s*$NUM""")
    private val keywordAmountRe = Regex("""(?i)\b(?:debited|credited|deducted|paid|sent|received|spent|withdrawn)\s+(?:by|for|with|of)?\s*(?:rs\.?|inr|₹)?\s*$NUM""")
    private val labelAmountRe = Regex("""(?i)\bamount(?: of)?\s*:?\s*(?:rs\.?|inr|₹)?\s*$NUM""")
    /** Words just before an amount that make it a balance or limit, not the payment. */
    private val balanceBefore = Regex("""(?i)(bal|balance|lmt|limit|avl|available|outstanding|due|min(?:imum)?)\W{0,3}(?:is|of|:)?\W{0,3}$""")

    private val creditRe = Regex("""(?i)\b(credited|received|deposited|refund(?:ed)?|cashback of|credit of|added to your)\b""")
    private val debitRe = Regex("""(?i)\b(debited|spent|paid|sent|purchase|withdrawn|debit of|txn of|charged|deducted|transferred to)\b""")
    private val otpRe = Regex("""(?i)\b(otp|one[- ]time password|verification code|do not share|never share)\b""")
    private val promoRe = Regex(
        """(?i)\b(offer|win|won|get up ?to|upto|apply now|pre-?approved|click|limited period|voucher|coupon|eligible for|instant loan|emi starting|reward points? (?:worth|expir)|is due on|due date|will be debited|request(?:ed)? money|collect request)\b""",
    )
    private val accountRe = Regex("""(?i)\b(?:a/?c|acct|account|card|wallet)\b\s*(?:no\.?)?\s*(?:ending\s*(?:with|in)?)?\s*[x*.\s]*(\d{3,4})\b""")

    private const val STOP = """(?=\s+(?:on|via|ref\w*|upi|using|from|for|avl|avbl|is|was|txn|at|thru|through|dated?|and|if)\b|\s*[(.,;:]|\s+\d|$)"""
    private const val NAME = """([A-Za-z][A-Za-z0-9 &.'_@-]{1,40}?)"""
    private val debitPayeeRes = listOf(
        Regex("""(?i)\b(?:at|to|towards|trf to|paid to|sent to|transferred to)\s+(?:vpa\s+)?$NAME$STOP"""),
        Regex("""(?i)\binfo[:\s]+$NAME(?=[.,;]|\s+(?:avl|avbl|bal)\b|$)"""),
    )
    private val creditPayeeRes = listOf(
        Regex("""(?i)\b(?:from|by)\s+(?:vpa\s+)?$NAME$STOP"""),
    ) + debitPayeeRes
    /** Names that are channels or the person's own account, not a payee. */
    private val notPayee = Regex("""(?i)^(your|a/?c|ac|account|acct|the|neft|imps|rtgs|upi|atm|card|bank|self|mr|ms|mrs|vpa|transfer|net ?banking)\b.*""")

    fun parse(text: String): Result {
        val t = text.replace('\n', ' ').replace(Regex("""\s{2,}"""), " ").trim()
        val amount = findAmount(t)
        val credit = creditRe.find(t)
        val debit = debitRe.find(t)
        val type = if (credit != null && (debit == null || credit.range.first < debit.range.first)) "income" else "expense"
        val payee = (if (type == "income") creditPayeeRes else debitPayeeRes).asSequence()
            .flatMap { re -> re.findAll(t).map { it.groupValues[1].trim() } }
            .map { it.substringBefore("@").trim().trimEnd('.', '-') }
            .firstOrNull { it.length >= 3 && !notPayee.matches(it) && !it.all { ch -> ch.isDigit() || ch == 'X' || ch == 'x' } }
            // "swiggy" from a UPI handle reads better as "Swiggy".
            ?.let { p -> if (p == p.lowercase()) p.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else p }
        val hint = accountRe.find(t)?.groupValues?.get(1)
        val isTx = amount != null && (credit != null || debit != null) && !otpRe.containsMatchIn(t) && !promoRe.containsMatchIn(t)
        return Result(amount, type, payee, t.take(200), hint, isTx)
    }

    /** The payment amount: the first one that is not a balance or a limit. */
    private fun findAmount(t: String): String? {
        val candidates = (currencyAmountRe.findAll(t) + keywordAmountRe.findAll(t) + labelAmountRe.findAll(t))
            .map { it.groups[1]!! }
            .distinctBy { it.range.first }
            .sortedBy { it.range.first }
        val ok = candidates.firstOrNull { g ->
            val before = t.substring(maxOf(0, g.range.first - 20), g.range.first)
            // Strip the currency token so "Avl Bal Rs." still reads as a balance.
            !balanceBefore.containsMatchIn(before.replace(Regex("""(?i)(rs\.?|inr|₹|usd|\$|eur|€|gbp|£|aed|sgd)\s*:?\s*$"""), "").trimEnd())
        } ?: return null
        return ok.value.replace(",", "").takeIf { it.toDoubleOrNull()?.let { v -> v > 0 } == true }
    }
}
