package app.kosh.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import app.kosh.BuildConfig
import app.kosh.core.Hlc
import app.kosh.core.Ids
import app.kosh.data.KoshJson
import app.kosh.data.Repository
import app.kosh.data.SecretStore
import app.kosh.data.SyncTable
import app.kosh.data.WireRecord
import app.kosh.data.db.Dirty
import app.kosh.data.db.Synced
import app.kosh.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.math.BigDecimal
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupLedger(val id: String, val name: String, val base_currency: String = "", val records: Int = 0)

@Serializable
data class BackupEncryption(
    val kdf: String = BackupCrypto.KDF,
    val memory_kib: Int = BackupCrypto.MEMORY_KIB,
    val iterations: Int = BackupCrypto.ITERATIONS,
    val parallelism: Int = BackupCrypto.PARALLELISM,
    val salt: String,
    val cipher: String = BackupCrypto.CIPHER,
    val chunk_size: Int = BackupCrypto.CHUNK,
    val nonce_prefix: Map<String, String>,
)

/** manifest.json of a .kosh file (docs/06). */
@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val format_version: Int = 1,
    val schema_version: Int = SyncEngine.SCHEMA_VERSION,
    val app: String = "kosh-android/${BuildConfig.VERSION_NAME}",
    val created_at: Long,
    val ledgers: List<BackupLedger>,
    val sha256: Map<String, String>,
    val encryption: BackupEncryption? = null,
) {
    companion object {
        const val FORMAT = "kosh-backup"
    }
}

/** What a backup file contains, shown before the user confirms a restore. */
data class BackupPreview(
    val ledgerName: String,
    val createdAt: Long,
    val records: Int,
    val encrypted: Boolean,
    val plainLedgerJson: Boolean,
)

enum class RestoreMode { MERGE, REPLACE }

data class RestoreResult(val applied: Int, val skipped: Int, val invalid: Int)

class BackupException(message: String) : Exception(message)

/**
 * Writes and reads .kosh backups and kosh-ledger JSON. Restoring is a merge by
 * HLC inside one database transaction, so a failed restore changes nothing.
 */
class BackupManager(
    private val context: Context,
    private val repo: Repository,
    private val sync: SyncEngine,
    private val secrets: SecretStore,
) {
    private val autoDir get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val settings get() = repo.settings

    // --- Passphrase for automatic folder backups ------------------------------

    val hasAutoPassphrase: Boolean get() = secrets.get(PASS_KEY) != null
    fun setAutoPassphrase(p: String?) = if (p.isNullOrEmpty()) secrets.remove(PASS_KEY) else secrets.put(PASS_KEY, p)

    // --- Export -------------------------------------------------------------

    /** Writes a complete .kosh backup to [out]. Returns the number of records. */
    suspend fun writeBackup(out: OutputStream, passphrase: CharArray?): Int = withContext(Dispatchers.IO) {
        val s = settings.current
        val entryName = "ledger-${s.ledgerId}.json"
        val tmp = File.createTempFile("kosh-export", ".tmp", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var encryption: BackupEncryption? = null
            val count: Int
            tmp.outputStream().buffered().use { fileOut ->
                val sink: OutputStream = if (passphrase != null && passphrase.isNotEmpty()) {
                    val salt = BackupCrypto.randomBytes(16)
                    val prefix = BackupCrypto.randomBytes(8)
                    encryption = BackupEncryption(salt = b64(salt), nonce_prefix = mapOf(entryName to b64(prefix)))
                    BackupCrypto.encrypting(fileOut, BackupCrypto.deriveKey(passphrase, salt), prefix)
                } else {
                    fileOut
                }
                val writer = OutputStreamWriter(DigestOutputStream(sink, digest), Charsets.UTF_8).buffered(64 * 1024)
                count = writeLedgerJson(writer)
                writer.close()
            }
            val manifest = BackupManifest(
                created_at = System.currentTimeMillis(),
                ledgers = listOf(BackupLedger(s.ledgerId, s.ledgerName, s.baseCurrency, count)),
                sha256 = mapOf(entryName to hex(digest.digest())),
                encryption = encryption,
            )
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(KoshJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(entryName))
                tmp.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            count
        } finally {
            tmp.delete()
        }
    }

    /** Plain kosh-ledger JSON, the same format as the server's export/import API. */
    suspend fun writeLedgerJson(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val w = OutputStreamWriter(out, Charsets.UTF_8).buffered(64 * 1024)
        val n = writeLedgerJson(w)
        w.flush()
        n
    }

    private suspend fun writeLedgerJson(w: Writer): Int {
        val s = settings.current
        val ledger = KoshJson.encodeToString(BackupLedger.serializer(), BackupLedger(s.ledgerId, s.ledgerName, s.baseCurrency))
        w.write(
            """{"format":"kosh-ledger","format_version":1,"schema_version":${SyncEngine.SCHEMA_VERSION},""" +
                """"exported_at":${System.currentTimeMillis()},"ledger":$ledger,"entities":{""",
        )
        var count = 0
        var firstEntity = true
        suspend fun section(entity: String, rows: List<WireRecord>) {
            if (rows.isEmpty()) return
            if (!firstEntity) w.write(",")
            firstEntity = false
            w.write("\"$entity\":[")
            rows.forEachIndexed { i, r ->
                if (i > 0) w.write(",")
                w.write(KoshJson.encodeToString(ExportRecord.serializer(), ExportRecord(r.id, r.hlc, r.deleted, r.data)))
                w.write("\n")
            }
            w.write("]")
            count += rows.size
        }
        for (t in repo.tables.typed) section(t.entity, wireRows(t))
        repo.db.raw().everything().groupBy { it.entity }.forEach { (entity, rows) ->
            section(entity, rows.map(repo.tables.raw::toWire))
        }
        w.write("}}\n")
        return count
    }

    private suspend fun <T : Synced<T>> wireRows(t: SyncTable<T>) = t.everything().map(t::toWire)

    @Serializable
    private data class ExportRecord(val id: String, val hlc: String, val deleted: Boolean, val data: JsonObject)

    // --- Automatic backups ----------------------------------------------------

    /** Daily backup into app storage (and the user's folder, if chosen). Keeps 7 daily + 4 weekly. */
    suspend fun autoBackup(today: LocalDate = LocalDate.now()): File = withContext(Dispatchers.IO) {
        val file = File(autoDir, "kosh-auto-$today.kosh")
        val tmp = File(autoDir, "${file.name}.tmp")
        tmp.outputStream().use { writeBackup(it, null) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        pruneAuto()
        settings.current.backupFolderUri?.let { folder ->
            runCatching { copyToFolder(Uri.parse(folder), "kosh-backup-$today.kosh") }
                .onFailure { settings.setBackupResult(System.currentTimeMillis(), "Couldn't write to the backup folder: ${it.message}") }
                .onSuccess { settings.setBackupResult(System.currentTimeMillis(), null) }
        } ?: settings.setBackupResult(System.currentTimeMillis(), null)
        file
    }

    /** Safety copy before a destructive operation. */
    suspend fun safetyBackup(reason: String): File = withContext(Dispatchers.IO) {
        val file = File(autoDir, "kosh-$reason-${System.currentTimeMillis()}.kosh")
        file.outputStream().use { writeBackup(it, null) }
        autoDir.listFiles { f -> f.name.startsWith("kosh-$reason-") }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
        file
    }

    fun localBackups(): List<File> =
        autoDir.listFiles { f -> f.name.endsWith(".kosh") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun pruneAuto() {
        val files = autoDir.listFiles { f -> f.name.startsWith("kosh-auto-") && f.name.endsWith(".kosh") }
            ?.sortedByDescending { it.name } ?: return
        val keep = files.take(7).toMutableSet()
        val weeks = HashSet<String>()
        for (f in files.drop(7)) {
            val date = runCatching { LocalDate.parse(f.name.removePrefix("kosh-auto-").removeSuffix(".kosh")) }.getOrNull() ?: continue
            val week = "${date.get(IsoFields.WEEK_BASED_YEAR)}-${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            if (weeks.size < 4 && weeks.add(week)) keep += f
        }
        files.filter { it !in keep }.forEach { it.delete() }
    }

    private suspend fun copyToFolder(tree: Uri, name: String) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw BackupException("The folder refused the file")
        val pass = secrets.get(PASS_KEY)?.toCharArray()
        resolver.openOutputStream(doc, "wt")!!.use { writeBackup(it, pass) }
        pruneFolder(tree)
    }

    private fun pruneFolder(tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith("kosh-backup-") && name.endsWith(".kosh")) found += c.getString(0) to name
            }
        }
        found.sortedByDescending { it.second }.drop(FOLDER_KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    // --- Restore --------------------------------------------------------------

    /** Reads only the manifest (or JSON header) so the UI can describe the file. */
    suspend fun preview(open: () -> InputStream): BackupPreview = withContext(Dispatchers.IO) {
        open().use { raw ->
            val input = raw.buffered()
            input.mark(4)
            val magic = ByteArray(2).also { input.read(it) }
            input.reset()
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                val zip = ZipInputStream(input)
                val first = zip.nextEntry ?: throw BackupException("This file is empty")
                if (first.name != "manifest.json") throw BackupException("Not a LekhaMint backup (no manifest)")
                val m = readManifest(zip)
                BackupPreview(
                    m.ledgers.firstOrNull()?.name ?: "?", m.created_at, m.ledgers.sumOf { it.records },
                    m.encryption != null, plainLedgerJson = false,
                )
            } else {
                val head = readLedgerHeader(input)
                BackupPreview(head.second?.name ?: "?", head.first, -1, encrypted = false, plainLedgerJson = true)
            }
        }
    }

    /**
     * Restores a .kosh file or kosh-ledger JSON. The file is fully decrypted and its
     * checksum verified before any data is touched; records are then applied in
     * one transaction.
     */
    suspend fun restore(open: () -> InputStream, passphrase: CharArray?, mode: RestoreMode): RestoreResult =
        withContext(Dispatchers.IO) {
            val plain = File.createTempFile("kosh-restore", ".json", context.cacheDir)
            try {
                extractVerified(open, passphrase, plain)
                if (mode == RestoreMode.REPLACE) safetyBackup("pre-restore")
                applyLedgerFile(plain, mode)
            } finally {
                plain.delete()
            }
        }

    private fun extractVerified(open: () -> InputStream, passphrase: CharArray?, dest: File) {
        open().use { raw ->
            val input = raw.buffered()
            input.mark(4)
            val magic = ByteArray(2).also { input.read(it) }
            input.reset()
            if (!(magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())) {
                dest.outputStream().use { input.copyTo(it) } // plain kosh-ledger JSON
                return
            }
            val zip = ZipInputStream(input)
            val first = zip.nextEntry ?: throw BackupException("This file is empty")
            if (first.name != "manifest.json") throw BackupException("Not a LekhaMint backup (no manifest)")
            val m = readManifest(zip)
            if (m.format != BackupManifest.FORMAT) throw BackupException("Not a LekhaMint backup")
            if (m.schema_version > SyncEngine.SCHEMA_VERSION) {
                throw BackupException("This backup was made by a newer version of LekhaMint. Update the app to restore it.")
            }
            val entryName = m.sha256.keys.firstOrNull() ?: throw BackupException("The backup has no ledger")
            var entry = zip.nextEntry
            while (entry != null && entry.name != entryName) entry = zip.nextEntry
            if (entry == null) throw BackupException("The backup is incomplete")
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(dest.outputStream().buffered(), digest).use { out ->
                val enc = m.encryption
                if (enc == null) {
                    zip.copyTo(out)
                } else {
                    if (passphrase == null || passphrase.isEmpty()) throw BackupDecryptException("This backup needs its password")
                    if (enc.kdf != BackupCrypto.KDF || enc.cipher != BackupCrypto.CIPHER) {
                        throw BackupException("Unsupported encryption (${enc.kdf}/${enc.cipher})")
                    }
                    val key = BackupCrypto.deriveKey(passphrase, unb64(enc.salt), enc.memory_kib, enc.iterations, enc.parallelism)
                    val prefix = enc.nonce_prefix[entryName] ?: throw BackupException("The backup is damaged")
                    BackupCrypto.decrypt(zip, out, key, unb64(prefix))
                }
            }
            if (hex(digest.digest()) != m.sha256[entryName]) {
                throw BackupException("Checksum mismatch: the backup is damaged. Nothing was changed.")
            }
        }
    }

    private suspend fun applyLedgerFile(file: File, mode: RestoreMode): RestoreResult {
        var applied = 0; var skipped = 0; var invalid = 0
        var ledger: BackupLedger? = null
        repo.db.withTransaction {
            if (mode == RestoreMode.REPLACE) repo.wipeAll()
            JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "format" -> if (r.nextString() != "kosh-ledger") throw BackupException("Not a LekhaMint ledger file")
                        "schema_version" -> if (r.nextInt() > SyncEngine.SCHEMA_VERSION) {
                            throw BackupException("This file was made by a newer version of LekhaMint. Update the app first.")
                        }
                        "ledger" -> ledger = readLedger(r)
                        "entities" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val entity = r.nextName()
                                r.beginArray()
                                while (r.hasNext()) {
                                    val rec = readRecord(r, entity)
                                    when {
                                        rec == null -> invalid++
                                        mode == RestoreMode.REPLACE -> { sync.store(rec, Dirty.PENDING); applied++ }
                                        sync.applyRemote(rec, Dirty.PENDING) -> applied++
                                        else -> skipped++
                                    }
                                }
                                r.endArray()
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
        }
        val l = ledger
        if (mode == RestoreMode.REPLACE && l != null && Ids.isValid(l.id)) {
            settings.setLedger(l.id, l.name.ifBlank { settings.current.ledgerName }, l.base_currency.ifBlank { settings.current.baseCurrency })
            settings.syncCursor = 0
        }
        if (!settings.current.onboarded) settings.setOnboarded()
        repo.clock.now() // keep the clock ahead of restored HLCs
        return RestoreResult(applied, skipped, invalid)
    }

    private fun readRecord(r: JsonReader, entity: String): WireRecord? {
        var id: String? = null; var hlc: String? = null; var deleted = false
        var data: JsonObject = JsonObject(emptyMap())
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "hlc" -> hlc = r.nextString()
                "deleted" -> deleted = r.nextBoolean()
                "data" -> data = readElement(r) as? JsonObject ?: JsonObject(emptyMap())
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id == null || !Ids.isValid(id) || Hlc.parse(hlc) == null) return null
        return WireRecord(entity, id, hlc!!, deleted, data)
    }

    private fun readElement(r: JsonReader): JsonElement = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, JsonElement>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = readElement(r)
            r.endObject()
            JsonObject(m)
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<JsonElement>()
            r.beginArray()
            while (r.hasNext()) l += readElement(r)
            r.endArray()
            JsonArray(l)
        }
        JsonToken.STRING -> JsonPrimitive(r.nextString())
        JsonToken.NUMBER -> {
            val s = r.nextString()
            s.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(BigDecimal(s))
        }
        JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
        JsonToken.NULL -> { r.nextNull(); JsonNull }
        else -> { r.skipValue(); JsonNull }
    }

    private fun readLedger(r: JsonReader): BackupLedger {
        var id = ""; var name = ""; var cur = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "name" -> name = r.nextString()
                "base_currency" -> cur = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return BackupLedger(id, name, cur)
    }

    /** (exported_at, ledger) from the start of a kosh-ledger JSON stream. */
    private fun readLedgerHeader(input: InputStream): Pair<Long, BackupLedger?> {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        var at = 0L; var ledger: BackupLedger? = null
        var format: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextString()
                "exported_at" -> at = r.nextLong()
                "ledger" -> ledger = readLedger(r)
                "entities" -> break
                else -> r.skipValue()
            }
        }
        if (format != "kosh-ledger") throw BackupException("This is not a LekhaMint backup or ledger export")
        return at to ledger
    }

    private fun readManifest(zip: ZipInputStream): BackupManifest {
        val text = zip.readBytes().decodeToString()
        return runCatching { KoshJson.decodeFromString(BackupManifest.serializer(), text) }
            .getOrElse { throw BackupException("The backup manifest is damaged") }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val PASS_KEY = "backup_passphrase"
        const val FOLDER_KEEP = 14
    }
}
