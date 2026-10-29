package app.kosh.sync

import androidx.room.withTransaction
import app.kosh.data.KoshJson
import app.kosh.data.Repository
import app.kosh.data.SyncTable
import app.kosh.data.WireRecord
import app.kosh.data.db.Dirty
import app.kosh.data.db.Synced
import app.kosh.data.db.SyncBaseEntity
import kotlinx.serialization.json.JsonObject
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
            // The look (theme, colours) follows the person across phones. Never fails a sync.
            runCatching { syncAppearance(api) }
            val report = SyncReport(pushed.first, pulled, pushed.second, pushed.third)
            settings.setSyncResult(System.currentTimeMillis(), null)
            report
        } catch (e: Exception) {
            settings.setSyncResult(System.currentTimeMillis(), describe(e))
            // The sign-in was revoked (this phone removed on the web or from another device): stop syncing
            // instead of retrying forever, and keep everything on the phone.
            if (e is ApiException && e.status == 401 && e.code == "unauthenticated") settings.markRemoved()
            throw e
        } finally {
            _running.value = false
        }
    }

    /**
     * One server setting, "android.appearance", holds the theme, palette and dark-mode choices. A change made on
     * this phone is sent; otherwise a newer one from another phone is applied.
     */
    private suspend fun syncAppearance(api: KoshApi) {
        val s = settings.current
        val at = settings.appearanceAt
        val remote = api.settings()[APPEARANCE_KEY] as? kotlinx.serialization.json.JsonObject
        val remoteAt = (remote?.get("updated_at") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if (settings.appearanceDirty || remote == null) {
            if (remote != null && remoteAt > at) return applyAppearance(remote, remoteAt)
            api.putSetting(APPEARANCE_KEY, kotlinx.serialization.json.buildJsonObject {
                put("theme", kotlinx.serialization.json.JsonPrimitive(s.theme.name))
                put("palette", kotlinx.serialization.json.JsonPrimitive(s.palette.name))
                put("amoled", kotlinx.serialization.json.JsonPrimitive(s.amoled))
                put("dynamic_color", kotlinx.serialization.json.JsonPrimitive(s.dynamicColor))
                put("updated_at", kotlinx.serialization.json.JsonPrimitive(at.coerceAtLeast(1)))
            })
            settings.appearanceSent(at)
        } else if (remoteAt > at) {
            applyAppearance(remote, remoteAt)
        }
    }

    private fun applyAppearance(o: kotlinx.serialization.json.JsonObject, at: Long) {
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val s = settings.current
        settings.applyAppearance(
            runCatching { app.kosh.data.ThemeMode.valueOf(str("theme")!!) }.getOrDefault(s.theme),
            runCatching { app.kosh.data.ThemePalette.valueOf(str("palette")!!) }.getOrDefault(s.palette),
            str("amoled")?.toBooleanStrictOrNull() ?: s.amoled,
            str("dynamic_color")?.toBooleanStrictOrNull() ?: s.dynamicColor,
            at,
        )
    }

    /** Files attached on this phone go up before the records that point at them. */
    private suspend fun uploadBlobs(api: KoshApi, ledgerId: String) {
        val pending = repo.db.raw().ofEntity(app.kosh.domain.Attachment.ENTITY).filter { it.dirty == Dirty.PENDING && !it.deleted }
            .mapNotNull(app.kosh.domain.Attachment::parse)
        for (a in pending) {
            if (repo.blobs.isUploaded(a.sha256) || !repo.blobs.has(a.sha256)) continue
            try {
                api.uploadBlob(ledgerId, repo.blobs.file(a.sha256).readBytes(), a.mime)
            } catch (e: ApiException) {
                // The book is at the server's storage limit: keep the file on this phone and try again
                // on later syncs, rather than letting one receipt stop everything else from syncing.
                if (e.code == "storage_full") continue
                throw e
            }
            repo.blobs.markUploaded(a.sha256)
        }
    }

    /** Returns (accepted, conflicts, rejected). */
    private suspend fun push(api: KoshApi, ledgerId: String): Triple<Int, Int, Int> {
        uploadBlobs(api, ledgerId)
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
                    rememberBase(r)
                }
                for (c in resp.conflicts) {
                    repo.clock.observe(c.hlc)
                    // The server's row wins the LWW race, but an edit made
                    // locally after this batch was collected is newer than
                    // both; leave it dirty so the next push retries it.
                    val local = localMeta(c.entity, c.id)
                    if (local == null || local.second == Dirty.CLEAN || local.first < c.hlc) {
                        store(c, Dirty.CLEAN)
                    }
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
        if (settings.resyncPending) return fullDownload(api, ledgerId)
        return try {
            pullFrom(api, ledgerId, settings.syncCursor, null)
        } catch (e: ApiException) {
            when (e.code) {
                // The server cleared deleted items older than this phone's last sync, so it can no
                // longer say which records were deleted since. Download the whole book again; any
                // synced record the server no longer has was deleted elsewhere.
                "resync_required" -> {}
                // The server's copy is older than what this phone has seen (restored from a backup, or
                // the book made again): it may have lost records this phone sent. Send everything again
                // first, so the full download below finds them on the server instead of dropping them.
                "cursor_ahead" -> resend(api, ledgerId)
                else -> throw e
            }
            settings.resyncPending = true
            fullDownload(api, ledgerId)
        }
    }

    /** Marks every local record unsent and pushes them all. The server keeps whichever version is newer. */
    private suspend fun resend(api: KoshApi, ledgerId: String) {
        repo.markEverythingDirty()
        repo.blobs.forgetUploaded()
        push(api, ledgerId)
    }

    /**
     * Downloads every record and then marks local copies the server no longer has as deleted. The
     * cursor is saved only at the end: if the download stops halfway, the next sync starts it
     * again from the beginning instead of continuing without knowing what is missing.
     */
    private suspend fun fullDownload(api: KoshApi, ledgerId: String): Int {
        val seen = HashSet<String>()
        var cursor = 0L
        val n = pullFrom(api, ledgerId, 0, seen) { cursor = it }
        repo.db.withTransaction { dropMissing(seen) }
        settings.syncCursor = cursor
        settings.resyncPending = false
        return n
    }

    private suspend fun pullFrom(
        api: KoshApi, ledgerId: String, from: Long, seen: MutableSet<String>?,
        saveCursor: (Long) -> Unit = { settings.syncCursor = it },
    ): Int {
        var cursor = from
        var total = 0
        while (true) {
            val page = api.pull(ledgerId, cursor, PULL_LIMIT)
            repo.db.withTransaction {
                for (r in page.changes) {
                    repo.clock.observe(r.hlc)
                    seen?.add(r.entity + ":" + r.id)
                    applyRemote(r)
                }
            }
            total += page.changes.size
            cursor = page.next_cursor
            saveCursor(cursor)
            if (!page.has_more || page.changes.isEmpty()) break
        }
        return total
    }

    /** After a full download: synced, unchanged local records the server no longer has are marked deleted. */
    private suspend fun dropMissing(seen: Set<String>) {
        for (t in tables.typed) dropMissingIn(t, seen)
        val raws = repo.db.raw().everything()
        repo.db.raw().upsertAll(raws.filter { !it.deleted && it.dirty == Dirty.CLEAN && (it.entity + ":" + it.id) !in seen }
            .map { it.withMeta(it.hlc, true, Dirty.CLEAN) })
    }

    private suspend fun <T : Synced<T>> dropMissingIn(t: SyncTable<T>, seen: Set<String>) {
        val gone = t.everything().filter { !it.deleted && it.dirty == Dirty.CLEAN && (t.entity + ":" + it.id) !in seen }
        if (gone.isNotEmpty()) t.upsert(gone.map { it.withMeta(it.hlc, true, Dirty.CLEAN) })
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
        // Tell the server which version each edit started from, so a clash with another device's edit
        // keeps both sets of changes instead of dropping one.
        return out.map { r ->
            if (r.deleted) r else repo.db.syncBase().find(r.entity, r.id)?.let { b ->
                r.copy(base = runCatching { KoshJson.parseToJsonElement(b.data) as? JsonObject }.getOrNull())
            } ?: r
        }
    }

    /** Remembers [r] as the version the server now holds, for the next edit's three-way merge. */
    private suspend fun rememberBase(r: WireRecord) {
        if (r.deleted) repo.db.syncBase().delete(r.entity, r.id)
        else repo.db.syncBase().upsertAll(listOf(SyncBaseEntity(r.entity, r.id, KoshJson.encodeToString(JsonObject.serializer(), r.data))))
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
        // A row that mirrors the server is the starting point of its next edit.
        if (dirty == Dirty.CLEAN) rememberBase(r)
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
        const val APPEARANCE_KEY = "android.appearance"
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
