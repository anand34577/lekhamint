package app.kosh.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate

/**
 * Record identifiers. Mirrors server/internal/ids: UUIDv7 for new records,
 * UUIDv5 for recurring occurrences so two devices generate the same ID.
 */
object Ids {
    /** Must equal ids.RecurringNamespace on the server and on every client. */
    const val RECURRING_NAMESPACE = "6f1a3c52-8d2e-4b7a-9c41-0e5d7b2a9f13"

    private val random = SecureRandom()

    fun newV7(nowMs: Long = System.currentTimeMillis()): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        for (i in 0 until 6) b[i] = (nowMs ushr (40 - 8 * i)).toByte()
        b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        return format(b)
    }

    fun v5(namespace: String, name: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(parse(namespace))
        md.update(name.toByteArray(Charsets.UTF_8))
        val b = md.digest().copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        return format(b)
    }

    /** ID of the transaction a recurring rule generates on [date]. */
    fun recurringOccurrence(ruleId: String, date: LocalDate): String = v5(RECURRING_NAMESPACE, "$ruleId:$date")

    fun isValid(s: String): Boolean {
        if (s.length != 36) return false
        for ((i, c) in s.withIndex()) {
            val ok = when (i) {
                8, 13, 18, 23 -> c == '-'
                else -> c in '0'..'9' || c in 'a'..'f'
            }
            if (!ok) return false
        }
        return true
    }

    private fun parse(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        require(hex.length == 32) { "invalid UUID $uuid" }
        return ByteArray(16) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    private fun format(b: ByteArray): String {
        val hex = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
