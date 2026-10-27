package app.kosh.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/** Reads a QR code from a photo, on the phone (ZXing). Null when there is none it can read. */
object QrPhoto {
    fun read(context: Context, uri: Uri): String? {
        // A screen photo is large; about 1600 px across is plenty for a QR code and much faster.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val source = RGBLuminanceSource(bmp.width, bmp.height, px)
        bmp.recycle()
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        val reader = MultiFormatReader().apply { setHints(hints) }
        // Two ways of telling dark from light: one copes with glare and shadows, the other with a dim, even screen.
        for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source), HybridBinarizer(source.invert()))) {
            runCatching { return reader.decodeWithState(BinaryBitmap(bin)).text }
            reader.reset()
        }
        return null
    }
}
