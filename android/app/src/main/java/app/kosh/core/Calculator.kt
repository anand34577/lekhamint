package app.kosh.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The amount pad's arithmetic, e.g. "450+800×2". Supports + − × ÷ with normal
 * precedence. [evaluate] returns null for incomplete or invalid input.
 */
object Calculator {
    private const val OPS = "+−×÷"

    fun isOperator(c: Char) = c in OPS

    fun hasOperator(expr: String) = expr.any { isOperator(it) }

    fun evaluate(expr: String): BigDecimal? {
        val tokens = tokenize(expr.trimEnd { isOperator(it) || it == '.' }) ?: return null
        if (tokens.isEmpty()) return null
        // First pass: × and ÷.
        val terms = ArrayList<Any>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == '×' || t == '÷') {
                val left = terms.removeAt(terms.size - 1) as BigDecimal
                val right = tokens.getOrNull(i + 1) as? BigDecimal ?: return null
                terms += if (t == '×') {
                    left.multiply(right)
                } else {
                    if (right.signum() == 0) return null
                    left.divide(right, MathContext(20, RoundingMode.HALF_EVEN))
                }
                i += 2
            } else {
                terms += t
                i++
            }
        }
        // Second pass: + and −.
        var acc = terms[0] as? BigDecimal ?: return null
        var j = 1
        while (j < terms.size) {
            val op = terms[j] as Char
            val v = terms.getOrNull(j + 1) as? BigDecimal ?: return null
            acc = if (op == '+') acc.add(v) else acc.subtract(v)
            j += 2
        }
        return acc
    }

    private fun tokenize(expr: String): List<Any>? {
        val out = ArrayList<Any>()
        val num = StringBuilder()
        fun flush(): Boolean {
            if (num.isEmpty()) return false
            out += num.toString().toBigDecimalOrNull() ?: return false
            num.clear()
            return true
        }
        for (c in expr) {
            when {
                c.isDigit() || c == '.' -> num.append(c)
                isOperator(c) -> {
                    if (!flush()) return null
                    out += c
                }
                c == ',' || c == ' ' -> Unit
                else -> return null
            }
        }
        if (!flush()) return null
        return out
    }

    /**
     * Applies one key press to the expression, keeping it well formed: one decimal
     * point per number, at most [maxDecimals] fraction digits, no doubled operators.
     */
    fun press(expr: String, key: Char, maxDecimals: Int): String {
        val opIndex = expr.indexOfLast { isOperator(it) }
        val current = if (opIndex < 0) expr else expr.substring(opIndex + 1)
        return when {
            key == '⌫' -> expr.dropLast(1)
            isOperator(key) -> when {
                expr.isEmpty() -> expr
                isOperator(expr.last()) || expr.last() == '.' -> expr.dropLast(1) + key
                else -> expr + key
            }
            key == '.' -> when {
                maxDecimals == 0 || current.contains('.') -> expr
                current.isEmpty() -> expr + "0."
                else -> expr + key
            }
            key.isDigit() -> when {
                current.contains('.') && current.substringAfter('.').length >= maxDecimals -> expr
                current == "0" -> expr.dropLast(1) + key
                current.length >= 13 -> expr
                else -> expr + key
            }
            else -> expr
        }
    }
}
