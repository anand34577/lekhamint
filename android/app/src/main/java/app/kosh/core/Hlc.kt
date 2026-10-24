package app.kosh.core

import java.security.SecureRandom
import java.util.Locale

/**
 * Hybrid logical clock timestamp: `<13-digit ms>-<4 hex counter>-<16 hex node>`.
 * Fixed width, so string comparison gives the causal order (see docs/05).
 */
data class Hlc(val wall: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun toString(): String = String.format(Locale.ROOT, "%013d-%04x-%s", wall, counter, node)

    override fun compareTo(other: Hlc): Int = toString().compareTo(other.toString())

    companion object {
        const val LENGTH = 13 + 1 + 4 + 1 + 16

        fun parse(s: String?): Hlc? {
            if (s == null || s.length != LENGTH || s[13] != '-' || s[18] != '-') return null
            if (!s.substring(0, 13).all { it in '0'..'9' }) return null
            val ctr = s.substring(14, 18)
            val node = s.substring(19)
            if (!(ctr + node).all { it in '0'..'9' || it in 'a'..'f' }) return null
            return Hlc(s.substring(0, 13).toLong(), ctr.toInt(16), node)
        }

        fun randomNode(): String {
            val b = ByteArray(8)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/**
 * Issues strictly increasing timestamps for this device. [persist] is called with
 * every new value so the clock never goes backwards across restarts.
 */
class HlcClock(
    node: String,
    initial: String?,
    private val persist: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var last: Hlc = Hlc.parse(initial)?.copy(node = node) ?: Hlc(0, 0, node)

    @Synchronized
    fun now(): String {
        val wall = nowMs()
        last = when {
            wall > last.wall -> last.copy(wall = wall, counter = 0)
            last.counter >= 0xffff -> last.copy(wall = last.wall + 1, counter = 0)
            else -> last.copy(counter = last.counter + 1)
        }
        return last.toString().also(persist)
    }

    /** Moves the clock forward so the next [now] is greater than [remote]. */
    @Synchronized
    fun observe(remote: String) {
        val r = Hlc.parse(remote) ?: return
        if (r.wall > last.wall || (r.wall == last.wall && r.counter > last.counter)) {
            last = last.copy(wall = r.wall, counter = r.counter)
            persist(last.toString())
        }
    }
}
