package app.kosh.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The native half: two ncnn models in libkoshocr (see src/main/cpp). */
internal object NativeOcr {
    val loaded: Boolean = runCatching { System.loadLibrary("koshocr") }.isSuccess

    external fun init(assets: android.content.res.AssetManager, threads: Int): Boolean
    external fun detect(bitmap: Bitmap, width: Int, height: Int): FloatArray?
    external fun recognize(bitmap: Bitmap): FloatArray?
    external fun release()
}

/**
 * Reads the text on a receipt photo, entirely on the phone: PaddleOCR's PP-OCRv5 models (Apache 2.0) running
 * with ncnn (BSD). No Google Play services, no network. A typical receipt takes a second or two.
 */
class ReceiptReader(private val context: Context) {
    private val mutex = Mutex()
    private var ready = false
    /** Set when the models would not load (a damaged install): the app then stops offering to read receipts. */
    @Volatile private var failed = false
    private val keys: List<String> by lazy {
        // Index 0 is the CTC "blank"; the last class is a space.
        listOf("") + context.assets.open("ocr/keys.txt").bufferedReader(Charsets.UTF_8).readLines() + listOf(" ")
    }

    val available: Boolean get() = NativeOcr.loaded && !failed

    private fun ensure(): Boolean {
        if (ready) return true
        if (!NativeOcr.loaded) return false
        ready = NativeOcr.init(context.assets, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        if (!ready) failed = true
        return ready
    }

    /** The lines of text in [file] (a photo), top to bottom. Empty when nothing could be read. */
    suspend fun lines(file: File): List<OcrLine> = withContext(Dispatchers.Default) {
        val bmp = Images.decodeUpright(file.readBytes(), 2000) ?: return@withContext emptyList()
        try { lines(bmp) } finally { bmp.recycle() }
    }

    suspend fun lines(bitmap: Bitmap): List<OcrLine> = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!ensure()) return@withContext emptyList()
            val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            // Detection runs at up to 960 px on the long side, in multiples of 32.
            val scale = minOf(1f, 960f / max(src.width, src.height))
            val dw = max(32, (src.width * scale / 32).roundToInt() * 32)
            val dh = max(32, (src.height * scale / 32).roundToInt() * 32)
            val prob = NativeOcr.detect(src, dw, dh) ?: return@withContext emptyList()
            val sx = dw.toFloat() / src.width
            val sy = dh.toFloat() / src.height
            val boxes = TextFinder.find(prob, dw, dh).map { it.scaled(sx, sy) }
            val read = boxes.mapNotNull { b -> read(src, b)?.let { (text, conf) -> Triple(b, text, conf) } }
                .filter { it.second.isNotBlank() && it.third >= 0.5f }
            if (src !== bitmap) src.recycle()
            TextFinder.lines(read) { it.first }.map { parts ->
                val b = parts.map { it.first }
                OcrLine(
                    parts.joinToString(" ") { it.second.trim() },
                    top = b.minOf { it.top }, height = b.map { it.h }.sorted()[b.size / 2],
                    confidence = parts.map { it.third }.average().toFloat(),
                )
            }
        }
    }

    /** Crops one box upright at 48 px high and reads it. */
    private fun read(src: Bitmap, b: TextBox): Pair<String, Float>? {
        if (b.h < 4 || b.w < 4) return null
        val outH = 48
        val s = outH / b.h
        val outW = ceil(b.w * s).toInt().coerceIn(16, 1600)
        val crop = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postTranslate(-b.cx, -b.cy)
            postRotate(-Math.toDegrees(b.angle.toDouble()).toFloat())
            postScale(s, s)
            postTranslate(outW / 2f, outH / 2f)
        }
        Canvas(crop).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        val steps = try { NativeOcr.recognize(crop) } finally { crop.recycle() } ?: return null
        return decode(steps)
    }

    /** CTC greedy decoding: drop blanks and repeats; confidence is the mean of the kept characters. */
    private fun decode(steps: FloatArray): Pair<String, Float> {
        val sb = StringBuilder()
        var sum = 0f
        var n = 0
        var prev = 0
        for (t in 0 until steps.size / 2) {
            val k = steps[t * 2].toInt()
            if (k != 0 && k != prev) {
                keys.getOrNull(k)?.let { sb.append(it); sum += steps[t * 2 + 1]; n++ }
            }
            prev = k
        }
        return sb.toString() to if (n == 0) 0f else sum / n
    }
}

/** Photos as they look to a person: camera rotation applied, scaled to a sensible size. */
object Images {
    fun decodeUpright(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val rotation = runCatching {
            android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (rotation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val scale = minOf(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (scale < 1f) m.postScale(scale, scale)
        if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }.getOrNull()
}
