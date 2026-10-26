package app.kosh.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Currency metadata. Amounts are always Long minor units (paise, cents). */
data class CurrencyInfo(val code: String, val name: String, val symbol: String, val digits: Int = 2)

object Currencies {
    val all: List<CurrencyInfo> = listOf(
        CurrencyInfo("INR", "Indian rupee", "₹"),
        CurrencyInfo("USD", "US dollar", "$"),
        CurrencyInfo("EUR", "Euro", "€"),
        CurrencyInfo("GBP", "British pound", "£"),
        CurrencyInfo("JPY", "Japanese yen", "¥", 0),
        CurrencyInfo("CNY", "Chinese yuan", "CN¥"),
        CurrencyInfo("AUD", "Australian dollar", "A$"),
        CurrencyInfo("CAD", "Canadian dollar", "C$"),
        CurrencyInfo("CHF", "Swiss franc", "CHF "),
        CurrencyInfo("SGD", "Singapore dollar", "S$"),
        CurrencyInfo("AED", "UAE dirham", "AED "),
        CurrencyInfo("SAR", "Saudi riyal", "SAR "),
        CurrencyInfo("HKD", "Hong Kong dollar", "HK$"),
        CurrencyInfo("NZD", "New Zealand dollar", "NZ$"),
        CurrencyInfo("SEK", "Swedish krona", "kr "),
        CurrencyInfo("NOK", "Norwegian krone", "kr "),
        CurrencyInfo("DKK", "Danish krone", "kr "),
        CurrencyInfo("PLN", "Polish złoty", "zł "),
        CurrencyInfo("CZK", "Czech koruna", "Kč "),
        CurrencyInfo("HUF", "Hungarian forint", "Ft "),
        CurrencyInfo("RUB", "Russian ruble", "₽"),
        CurrencyInfo("TRY", "Turkish lira", "₺"),
        CurrencyInfo("BRL", "Brazilian real", "R$"),
        CurrencyInfo("MXN", "Mexican peso", "MX$"),
        CurrencyInfo("ARS", "Argentine peso", "AR$"),
        CurrencyInfo("ZAR", "South African rand", "R "),
        CurrencyInfo("NGN", "Nigerian naira", "₦"),
        CurrencyInfo("KES", "Kenyan shilling", "KSh "),
        CurrencyInfo("EGP", "Egyptian pound", "E£"),
        CurrencyInfo("PKR", "Pakistani rupee", "Rs "),
        CurrencyInfo("BDT", "Bangladeshi taka", "৳"),
        CurrencyInfo("LKR", "Sri Lankan rupee", "Rs "),
        CurrencyInfo("NPR", "Nepalese rupee", "Rs "),
        CurrencyInfo("IDR", "Indonesian rupiah", "Rp "),
        CurrencyInfo("MYR", "Malaysian ringgit", "RM "),
        CurrencyInfo("THB", "Thai baht", "฿"),
        CurrencyInfo("VND", "Vietnamese dong", "₫", 0),
        CurrencyInfo("PHP", "Philippine peso", "₱"),
        CurrencyInfo("KRW", "South Korean won", "₩", 0),
        CurrencyInfo("TWD", "New Taiwan dollar", "NT$"),
        CurrencyInfo("ILS", "Israeli shekel", "₪"),
        CurrencyInfo("KWD", "Kuwaiti dinar", "KD ", 3),
        CurrencyInfo("BHD", "Bahraini dinar", "BD ", 3),
        CurrencyInfo("OMR", "Omani rial", "OMR ", 3),
        CurrencyInfo("BTC", "Bitcoin", "₿", 8),
    )

    private val byCode = all.associateBy { it.code }

    fun get(code: String): CurrencyInfo = byCode[code] ?: CurrencyInfo(code, code, "$code ", 2)

    fun digits(code: String): Int = get(code).digits

    fun isValidCode(code: String): Boolean = Regex("[A-Z0-9]{3,10}").matches(code)

    /** Best default for a fresh install, from the device locale. */
    fun forLocale(locale: Locale = Locale.getDefault()): String = runCatching {
        java.util.Currency.getInstance(locale).currencyCode
    }.getOrNull()?.takeIf { byCode.containsKey(it) } ?: "USD"

    /** Currencies that group digits as 1,23,45,678 (lakh/crore). */
    private val indianGrouping = setOf("INR", "PKR", "NPR", "BDT", "LKR")
    fun usesIndianGrouping(code: String) = code in indianGrouping
}

object Money {
    /** Formats [minor] units, e.g. 125050 INR -> "₹1,250.50". */
    fun format(
        minor: Long,
        currency: String,
        signed: Boolean = false,
        showSymbol: Boolean = true,
        trimZeroFraction: Boolean = false,
        locale: Locale = Locale.getDefault(),
    ): String {
        val info = Currencies.get(currency)
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val number = formatNumber(
            abs(minor), info.digits, Currencies.usesIndianGrouping(currency), symbols, trimZeroFraction,
        )
        val sign = when {
            minor < 0 -> "−"
            signed && minor > 0 -> "+"
            else -> ""
        }
        if (!showSymbol) return sign + number
        return if (symbolAfter(locale) && !Currencies.usesIndianGrouping(currency)) {
            "$sign$number ${info.symbol.trim()}"
        } else {
            "$sign${info.symbol}$number"
        }
    }

    /**
     * For amounts on screen: like [format], but a whole amount drops its zero fraction ("₹4,000", not "₹4,000.00").
     * Exports and statements keep [format] so they always show every digit.
     */
    fun ui(minor: Long, currency: String, signed: Boolean = false, showSymbol: Boolean = true, locale: Locale = Locale.getDefault()): String =
        format(minor, currency, signed = signed, showSymbol = showSymbol, trimZeroFraction = true, locale = locale)

    /** Short form for chart labels: ₹1.2L, ₹3.4Cr, $12.5K. */
    fun compact(minor: Long, currency: String): String {
        val info = Currencies.get(currency)
        val value = BigDecimal(abs(minor)).movePointLeft(info.digits).toDouble()
        val sign = if (minor < 0) "−" else ""
        val (scaled, suffix) = if (Currencies.usesIndianGrouping(currency)) {
            when {
                value >= 1e7 -> value / 1e7 to "Cr"
                value >= 1e5 -> value / 1e5 to "L"
                value >= 1e3 -> value / 1e3 to "K"
                else -> value to ""
            }
        } else {
            when {
                value >= 1e9 -> value / 1e9 to "B"
                value >= 1e6 -> value / 1e6 to "M"
                value >= 1e3 -> value / 1e3 to "K"
                else -> value to ""
            }
        }
        val text = if (suffix.isEmpty()) {
            String.format(Locale.ROOT, "%.0f", scaled)
        } else {
            String.format(Locale.ROOT, "%.1f", scaled).removeSuffix(".0")
        }
        return "$sign${info.symbol.trim()}$text$suffix"
    }

    /** Plain decimal for an edit field: 125050 -> "1250.5" (no grouping). */
    fun toPlain(minor: Long, currency: String): String =
        BigDecimal(minor).movePointLeft(Currencies.digits(currency)).stripTrailingZeros().toPlainString()

    /** Parses user input ("1,250.50") into minor units; null if invalid. */
    fun parse(text: String, currency: String): Long? {
        var t = text.trim().replace(" ", "").replace("_", "")
        // "12,50" from a comma-decimal keyboard is twelve and a half, not 1,250. A lone comma followed by one or two
        // digits (and no dot) can't be thousands grouping, which always has three digits after the comma.
        if ('.' !in t && t.count { it == ',' } == 1 && t.substringAfter(',').length in 1..2) t = t.replace(',', '.')
        // Both separators ("1.250,50" or "1,250.50"): whichever comes last is the decimal point.
        if ('.' in t && ',' in t && t.lastIndexOf(',') > t.lastIndexOf('.')) t = t.replace(".", "").replace(',', '.')
        val clean = t.replace(",", "")
        if (clean.isEmpty()) return null
        val value = clean.toBigDecimalOrNull() ?: return null
        return fromDecimal(value, currency)
    }

    /** Rounds half-up to the currency's precision and converts to minor units. */
    fun fromDecimal(value: BigDecimal, currency: String): Long? {
        val digits = Currencies.digits(currency)
        return runCatching {
            value.setScale(digits, RoundingMode.HALF_UP).movePointRight(digits).longValueExact()
        }.getOrNull()
    }

    private fun formatNumber(
        minor: Long,
        digits: Int,
        indian: Boolean,
        symbols: DecimalFormatSymbols,
        trimZeroFraction: Boolean,
    ): String {
        val raw = BigDecimal(minor).movePointLeft(digits).setScale(digits).toPlainString()
        val intPart = raw.substringBefore('.')
        val frac = if (digits > 0) raw.substringAfter('.') else ""
        val grouped = group(intPart, indian, symbols.groupingSeparator)
        return if (frac.isEmpty() || (trimZeroFraction && frac.all { it == '0' })) {
            grouped
        } else {
            "$grouped${symbols.decimalSeparator}$frac"
        }
    }

    private fun group(digits: String, indian: Boolean, sep: Char): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val size = if (indian) 2 else 3
        val parts = rest.reversed().chunked(size).map { it.reversed() }.reversed()
        return parts.joinToString(sep.toString()) + sep + last3
    }

    private fun symbolAfter(locale: Locale): Boolean {
        val pattern = (NumberFormat.getCurrencyInstance(locale) as? DecimalFormat)?.toPattern() ?: return false
        return !pattern.substringBefore(';').trim().startsWith("¤")
    }
}
