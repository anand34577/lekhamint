package app.kosh.sync

import androidx.room.withTransaction
import app.kosh.data.KoshJson
import app.kosh.data.Repository
import app.kosh.data.SyncTable
import app.kosh.data.WireRecord
import app.kosh.data.db.Dirty
import app.kosh.data.db.Synced
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncReport(val pushed: Int, val pulled: Int, val conflicts: Int, val rejected: Int)

/**
 * Push/pull loop from docs/05:
 *  1. push dirty rows in batches; accepted rows are cleaned only if unchanged since,
 *     conflicts are replaced with the server's winning version;
 *  2. pull everything after the saved cursor, applying a record only if it is newer
 *     than the local copy or the local copy has no unpushed change.
 */
class SyncEngine(private val repo: Repository) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val settings get() = repo.settings
    private val tables get() = repo.tables

    fun api(): KoshApi? = settings.current.serverUrl?.let { url -> KoshApi(url) { settings.token } }

    suspend fun sync(): SyncReport = mutex.withLock {
        val api = api() ?: throw IllegalStateException("Sync is not set up")
        val ledgerId = settings.current.ledgerId
        _running.value = true
        try {
            val pushed = try {
                push(api, ledgerId)
            } catch (e: ApiException) {
                // First sync of a book created on this device (or restored from a backup):
                // create it on the server, then push again. Idempotent for members.
                if (e.status != 404) throw e
                val s = settings.current
                api.createLedger(ledgerId, s.ledgerName, s.baseCurrency)
                push(api, ledgerId)
            }
            val pulled = pull(api, ledgerId)
            val report = SyncReport(pushed.first, pulled, pushed.second, pushed.third)
            settings.setSyncResult(System.currentTimeMillis(), null)
            report
        } catch (e: Exception) {
            settings.setSyncResult(System.currentTimeMillis(), describe(e))
            throw e
        } finally {
            _running.value = false
        }
    }

    /** Returns (accepted, conflicts, rejected). */
    private suspend fun push(api: KoshApi, ledgerId: String): Triple<Int, Int, Int> {
        var accepted = 0; var conflicts = 0; var rejected = 0
        // Bounded so a server that answers without acknowledging rows cannot loop us forever.
        repeat(MAX_PUSH_BATCHES) {
            val batch = collectDirty(BATCH)
            if (batch.isEmpty()) return Triple(accepted, conflicts, rejected)
            val resp = api.push(ledgerId, PushRequest(SCHEMA_VERSION, settings.nodeId, batch))
            val byId = batch.associateBy { it.id }
            repo.db.withTransaction {
                for (id in resp.accepted) {
                    val r = byId[id] ?: continue
                    clearDirty(r.entity, r.id, r.hlc)
                }
                for (c in resp.conflicts) {
                    repo.clock.observe(c.hlc)
                    store(c, Dirty.CLEAN)
                }
                for (rej in resp.rejected) {
                    val r = byId[rej.id] ?: continue
                    markRejected(r.entity, r.id, r.hlc)
                }
            }
            accepted += resp.accepted.size; conflicts += resp.conflicts.size; rejected += resp.rejected.size
            if (resp.accepted.isEmpty() && resp.conflicts.isEmpty() && resp.rejected.isEmpty()) {
                return Triple(accepted, conflicts, rejected)
            }
        }
        return Triple(accepted, conflicts, rejected)
    }

    private suspend fun pull(api: KoshApi, ledgerId: String): Int {
        var cursor = settings.syncCursor
        var total = 0
        while (true) {
            val page = api.pull(ledgerId, cursor, PULL_LIMIT)
            repo.db.withTransaction {
                for (r in page.changes) {
                    repo.clock.observe(r.hlc)
                    applyRemote(r)
                }
            }
            total += page.changes.size
            cursor = page.next_cursor
            settings.syncCursor = cursor
            if (!page.has_more || page.changes.isEmpty()) break
        }
        return total
    }

    /** Applies a pulled record unless a newer unpushed local edit exists. */
    suspend fun applyRemote(r: WireRecord, dirtyAfter: Int = Dirty.CLEAN): Boolean {
        val local = localMeta(r.entity, r.id)
        if (local != null && local.second != Dirty.CLEAN && local.first >= r.hlc) return false
        if (local != null && local.first > r.hlc) return false
        store(r, dirtyAfter)
        return true
    }

    private suspend fun collectDirty(limit: Int): List<WireRecord> {
        val out = ArrayList<WireRecord>()
        for (t in tables.typed) {
            if (out.size >= limit) break
            out += dirtyOf(t, limit - out.size)
        }
        if (out.size < limit) out += repo.db.raw().dirty(limit - out.size).map(tables.raw::toWire)
        return out
    }

    private suspend fun <T : Synced<T>> dirtyOf(t: SyncTable<T>, limit: Int) = t.dirty(limit).map(t::toWire)

    private suspend fun localMeta(entity: String, id: String): Pair<String, Int>? {
        val t = tables.table(entity)
        val row: Synced<*>? = if (t != null) t.find(id) else tables.raw.find(entity, id)
        return row?.let { it.hlc to it.dirty }
    }

    suspend fun store(r: WireRecord, dirty: Int) {
        val t = tables.table(r.entity)
        if (t == null) tables.raw.upsert(listOf(tables.raw.fromWire(r, dirty))) else storeTyped(t, r, dirty)
    }

    private suspend fun <T : Synced<T>> storeTyped(t: SyncTable<T>, r: WireRecord, dirty: Int) =
        t.upsert(listOf(t.fromWire(r, dirty)))

    private suspend fun clearDirty(entity: String, id: String, hlc: String) {
        val t = tables.table(entity)
        if (t == null) repo.db.raw().clearDirty(entity, id, hlc) else t.clearDirty(id, hlc)
    }

    private suspend fun markRejected(entity: String, id: String, hlc: String) {
        val t = tables.table(entity)
        if (t == null) repo.db.raw().markRejected(entity, id, hlc) else t.markRejected(id, hlc)
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private const val BATCH = 500
        private const val PULL_LIMIT = 1000
        private const val MAX_PUSH_BATCHES = 1000

        fun describe(e: Throwable): String = when (e) {
            is ApiException -> when (e.code) {
                "unauthenticated" -> "Signed out by the server. Sign in again."
                "server_outdated" -> "The server is older than this app. Update the server."
                "not_found" -> "This book no longer exists on the server, or you lost access."
                else -> e.message ?: e.code
            }
            is java.net.UnknownHostException -> "Server not found. Check the address and your connection."
            is java.net.ConnectException, is java.net.SocketTimeoutException -> "Couldn't reach the server."
            is javax.net.ssl.SSLException -> "Secure connection failed (${e.message})."
            else -> e.message ?: e.javaClass.simpleName
        }

        /** Encodes a record list for debugging/export. */
        fun encode(records: List<WireRecord>): String =
            KoshJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WireRecord.serializer()), records)
    }
}
