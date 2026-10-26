package app.kosh.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Receipt and bill files, kept in the app's private storage by their SHA-256
 * (the same name the server uses). A file is uploaded during sync, before the
 * attachment record that points at it; "<sha>.up" marks it as on the server.
 */
class BlobStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "blobs").apply { mkdirs() }

    fun file(sha: String): File = File(dir, sha)
    fun has(sha: String) = file(sha).isFile
    fun isUploaded(sha: String) = File(dir, "$sha.up").isFile
    fun markUploaded(sha: String) { File(dir, "$sha.up").writeText("") }

    data class Stored(val sha256: String, val size: Long, val mime: String, val name: String)

    /**
     * Copies a picked or shared file in. Large JPEG/PNG/WebP photos are scaled
     * to 2000 px on the long side and saved as JPEG, like the web app does.
     */
    fun import(uri: Uri, fallbackName: String = "receipt"): Stored {
        val cr = context.contentResolver
        var mime = cr.getType(uri) ?: "application/octet-stream"
        var name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: fallbackName
        var bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read the file")
        if (mime in setOf("image/jpeg", "image/png", "image/webp") && bytes.size > 1_500_000) {
            shrink(bytes)?.let { bytes = it; mime = "image/jpeg"; name = name.substringBeforeLast('.') + ".jpg" }
        }
        require(bytes.size <= MAX_SIZE) { "The file is larger than 10 MB" }
        require(mime.startsWith("image/") || mime == "application/pdf") { "Only photos and PDF files can be attached" }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val f = file(sha)
        if (!f.isFile) f.writeBytes(bytes)
        return Stored(sha, bytes.size.toLong(), mime, name.take(200))
    }

    /**
     * Re-encodes a large photo at 2000 px. The camera's rotation flag is applied first: the new JPEG carries no
     * such flag, so without this a portrait receipt would be saved lying on its side.
     */
    private fun shrink(bytes: ByteArray): ByteArray? = runCatching {
        val out = app.kosh.ocr.Images.decodeUpright(bytes, 2000) ?: return null
        ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, 85, s); s.toByteArray() }.takeIf { it.size < bytes.size }
    }.getOrNull()

    fun save(sha: String, bytes: ByteArray) { file(sha).writeBytes(bytes); markUploaded(sha) }

    companion object { const val MAX_SIZE = 10 shl 20 }
}
