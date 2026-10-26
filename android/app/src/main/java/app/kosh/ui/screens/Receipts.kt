package app.kosh.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.kosh.AppContainer
import app.kosh.core.Ids
import app.kosh.domain.Attachment
import app.kosh.ui.components.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file on the transaction being edited: saved with it, or removed when it is saved. */
data class FileItem(val id: String, val name: String, val mime: String, val size: Long, val sha: String, val existing: Boolean, val removed: Boolean = false) {
    val isImage get() = mime.startsWith("image/") && mime != "image/heic" && mime != "image/heif"
}

/** Existing attachments of a transaction. */
suspend fun loadFiles(c: AppContainer, txId: String): List<FileItem> =
    c.repo.db.raw().ofEntity(Attachment.ENTITY).mapNotNull(Attachment::parse).filter { it.transactionId == txId }
        .sortedBy { it.createdAt }.map { FileItem(it.id, it.name, it.mime, it.size, it.sha256, existing = true) }

/** Saves the attachment list with the transaction: new files become records, removed ones go to the Trash. */
suspend fun saveFiles(c: AppContainer, txId: String, files: List<FileItem>) {
    for (f in files) when {
        f.existing && f.removed -> c.repo.deleteRaw(Attachment.ENTITY, f.id)
        !f.existing && !f.removed -> c.repo.saveRaw(Attachment.ENTITY, f.id,
            Attachment(f.id, txId, f.name, f.mime, f.size, f.sha, System.currentTimeMillis()).toJson())
    }
}

fun fileFromStore(s: app.kosh.data.BlobStore.Stored) = FileItem(Ids.newV7(), s.name, s.mime, s.size, s.sha256, existing = false)

/**
 * Opens an attachment in a viewer app. A file added on another device is
 * downloaded from the server first.
 */
suspend fun openAttachment(context: Context, c: AppContainer, f: FileItem): String? {
    val blobs = c.repo.blobs
    if (!blobs.has(f.sha)) {
        val api = c.sync.api() ?: return "This file was added on another device. Connect to your server under More → Sync to open it here."
        try {
            val bytes = withContext(Dispatchers.IO) { api.downloadBlob(c.settings.current.ledgerId, f.sha) }
            withContext(Dispatchers.IO) { blobs.save(f.sha, bytes) }
        } catch (e: Exception) {
            return app.kosh.sync.SyncEngine.describe(e)
        }
    }
    // Hand out a copy with a readable name and extension, never the stored file itself.
    val ext = when { f.mime == "application/pdf" -> ".pdf"; f.mime == "image/png" -> ".png"; f.mime.startsWith("image/") -> ".jpg"; else -> "" }
    val out = File(File(context.cacheDir, "open").apply { mkdirs() }, f.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").substringBeforeLast('.') + ext)
    withContext(Dispatchers.IO) { blobs.file(f.sha).copyTo(out, overwrite = true) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", out)
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }.getOrElse { "No app on this phone can open this kind of file." }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptsField(files: List<FileItem>, onAdd: (FileItem) -> Unit, onRemove: (FileItem) -> Unit, readOnly: Boolean = false) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = app.kosh.ui.rememberSafeScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    fun take(uri: Uri?) {
        if (uri == null) return
        busy = true; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.repo.blobs.import(uri) } }
            r.onSuccess { onAdd(fileFromStore(it)) }.onFailure { message = it.message ?: "Could not add the file" }
            busy = false
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { take(it) }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { take(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) take(cameraUri) }
    val shown = files.filter { !it.removed }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Receipts and bills", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (f in shown) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { scope.launch { message = openAttachment(context, c, f) } }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Thumb(f)
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (f.size >= 1_000_000) "%.1f MB".format(f.size / 1e6) else "${maxOf(1, f.size / 1000)} KB") + if (!f.existing) " · saved with the transaction" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!readOnly) IconButton(onClick = { onRemove(f) }) { Icon(Icons.Rounded.Close, "Remove ${f.name}") }
            }
        }
        if (!readOnly) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {
                val f = File(File(context.cacheDir, "camera").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                cameraUri = uri
                camera.launch(uri)
            }, label = { Text("Scan receipt") }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)) }, enabled = !busy)
            AssistChip(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                label = { Text("Photo") }, leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) }, enabled = !busy)
            AssistChip(onClick = { doc.launch(arrayOf("application/pdf", "image/*")) },
                label = { Text("PDF or file") }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) }, enabled = !busy)
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically))
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Thumb(f: FileItem) {
    val c = LocalContainer.current
    val bmp by produceState<android.graphics.Bitmap?>(null, f.sha) {
        value = if (!f.isImage || !c.repo.blobs.has(f.sha)) null else withContext(Dispatchers.IO) {
            // Upright, whatever way the camera held it.
            runCatching { app.kosh.ocr.Images.decodeUpright(c.repo.blobs.file(f.sha).readBytes(), 160) }.getOrNull()
        }
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        else Icon(if (f.isImage) Icons.Rounded.Image else Icons.Rounded.Description, null)
    }
}
