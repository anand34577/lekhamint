package app.kosh.sync

import java.net.URLDecoder

/**
 * The link in the web app's "Connect a phone" QR code: lekhamint://pair?server=<url>&code=<code>.
 * The code works once, for five minutes, and signs this phone in without a password.
 */
data class PairLink(val server: String, val code: String) {
    companion object {
        const val SCHEME = "lekhamint"

        /** Set when the link opened the app, until the Sync screen picks it up. */
        val pending = kotlinx.coroutines.flow.MutableStateFlow<PairLink?>(null)

        fun parse(text: String): PairLink? {
            val t = text.trim()
            if (!t.startsWith("$SCHEME://pair", ignoreCase = true)) return null
            val query = t.substringAfter('?', "")
            val params = query.split('&').mapNotNull { p ->
                val k = p.substringBefore('=', "")
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrDefault("")
            }.toMap()
            val server = params["server"].orEmpty().trim()
            val code = normalizeCode(params["code"].orEmpty())
            if (!(server.startsWith("https://") || server.startsWith("http://")) || code.length != CODE_LENGTH) return null
            return PairLink(server.trimEnd('/'), code)
        }

        const val CODE_LENGTH = 10

        /** What people type: lower case, spaces, dashes, and O / I / L for 0 / 1 / 1 (the server reads them the same way). */
        fun normalizeCode(code: String): String =
            code.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')
    }
}
