package app.kosh.sync

import app.kosh.data.KoshJson
import app.kosh.data.WireRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ApiUser(val id: String, val email: String, val name: String = "", val is_admin: Boolean = false)

@Serializable
data class AuthResponse(val user: ApiUser, val token: String)

@Serializable
data class ApiLedger(val id: String, val name: String, val base_currency: String, val seq: Long = 0, val role: String = "")

@Serializable
data class Rejection(val entity: String = "", val id: String, val error: String, val message: String = "")

@Serializable
data class PushRequest(val schema_version: Int, val device_id: String, val changes: List<WireRecord>)

@Serializable
data class PushResponse(
    val accepted: List<String> = emptyList(),
    val conflicts: List<WireRecord> = emptyList(),
    val rejected: List<Rejection> = emptyList(),
    val cursor: Long = 0,
)

@Serializable
data class PullResponse(
    val changes: List<WireRecord> = emptyList(),
    val next_cursor: Long = 0,
    val has_more: Boolean = false,
    val server_time: Long = 0,
)

@Serializable
data class ServerMeta(val version: String = "", val schema_version: Int = 0, val signup_allowed: Boolean = false)

@Serializable private data class LedgersResponse(val ledgers: List<ApiLedger> = emptyList())
@Serializable private data class LedgerResponse(val ledger: ApiLedger)
@Serializable private data class LoginBody(val email: String, val password: String, val device_name: String, val name: String? = null)
@Serializable private data class PasswordBody(val password: String)
@Serializable private data class CreateLedgerBody(val id: String, val name: String, val base_currency: String, val seed_default_categories: Boolean = false)

/** An error response from the server: `{error: {code, message}}`. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/** Minimal client for the LekhaMint REST API (docs/07). */
class KoshApi(baseUrl: String, private val token: () -> String?) {
    private val base = normalize(baseUrl)

    init {
        // Plain http:// would send the password and the sync token across the
        // internet unencrypted. Local networks are allowed (a home server on
        // LAN is a supported setup); anything else must use https.
        if (base.startsWith("http://") && !isLocalHost(base)) {
            throw ApiException(0, "insecure_url",
                "Plain http:// is only allowed for addresses on this device or your local network. Use https:// for this server.")
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun meta(): ServerMeta = get("/api/v1/meta", ServerMeta.serializer(), auth = false)

    suspend fun login(email: String, password: String, device: String): AuthResponse =
        post("/api/v1/auth/login", LoginBody(email, password, device), LoginBody.serializer(), AuthResponse.serializer(), auth = false)

    suspend fun register(email: String, password: String, name: String, device: String): AuthResponse =
        post("/api/v1/auth/register", LoginBody(email, password, device, name), LoginBody.serializer(), AuthResponse.serializer(), auth = false)

    suspend fun logout() {
        runCatching { send(Request.Builder().url("$base/api/v1/auth/logout").post(EMPTY), auth = true) }
    }

    /** Permanently deletes the signed-in account and the books only it owns (server side). */
    suspend fun deleteAccount(password: String) {
        val json = KoshJson.encodeToString(PasswordBody.serializer(), PasswordBody(password))
        send(Request.Builder().url("$base/api/v1/auth/me").delete(json.toRequestBody(JSON)), auth = true)
    }

    suspend fun ledgers(): List<ApiLedger> = get("/api/v1/ledgers", LedgersResponse.serializer()).ledgers

    suspend fun createLedger(id: String, name: String, currency: String): ApiLedger =
        post("/api/v1/ledgers", CreateLedgerBody(id, name, currency), CreateLedgerBody.serializer(), LedgerResponse.serializer()).ledger

    suspend fun push(ledgerId: String, body: PushRequest): PushResponse =
        post("/api/v1/ledgers/$ledgerId/sync/push", body, PushRequest.serializer(), PushResponse.serializer())

    suspend fun pull(ledgerId: String, since: Long, limit: Int = 1000): PullResponse =
        get("/api/v1/ledgers/$ledgerId/sync/pull?since=$since&limit=$limit", PullResponse.serializer())

    private suspend fun <T> get(path: String, out: KSerializer<T>, auth: Boolean = true): T {
        val body = send(Request.Builder().url(base + path).get(), auth)
        return KoshJson.decodeFromString(out, body)
    }

    private suspend fun <B, T> post(path: String, value: B, inS: KSerializer<B>, out: KSerializer<T>, auth: Boolean = true): T {
        val json = KoshJson.encodeToString(inS, value)
        val body = send(Request.Builder().url(base + path).post(json.toRequestBody(JSON)), auth)
        return KoshJson.decodeFromString(out, body)
    }

    private suspend fun send(builder: Request.Builder, auth: Boolean): String = withContext(Dispatchers.IO) {
        if (auth) token()?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Accept", "application/json")
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val (code, msg) = runCatching {
                    val err = KoshJson.parseToJsonElement(text).jsonObject["error"] as JsonObject
                    err["code"]!!.jsonPrimitive.content to (err["message"]?.jsonPrimitive?.content ?: "")
                }.getOrDefault("http_${resp.code}" to (resp.message.ifEmpty { "HTTP ${resp.code}" }))
                throw ApiException(resp.code, code, msg)
            }
            text
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private val PRIVATE_NET = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")

        /** Accepts "kosh.example.com", "192.168.1.5:8080" or a full URL. */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u
        }

        /** True for loopback, link-local and RFC1918 addresses, and .local/.lan names. */
        fun isLocalHost(url: String): Boolean {
            val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
            return host == "localhost" || host.startsWith("127.") || host.startsWith("10.") ||
                host.startsWith("192.168.") || host.startsWith("169.254.") || host.endsWith(".local") ||
                host.endsWith(".lan") || PRIVATE_NET.containsMatchIn(host)
        }
    }
}
