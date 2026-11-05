package com.kevinpostal.fiberirc.ui.encryption

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QR codes for device linking and safety numbers (ZXing core only: no camera, no scanning activity). Codes are
 * drawn from text, and read back from an image the user picked with the system photo picker.
 */
object QrCodes {
    /** [text] as QR modules: true = dark, one row per entry, a one-module quiet zone. */
    fun encode(text: String): Array<BooleanArray> {
        val m = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m[x, y] } }
    }

    /** [modules] drawn [scale] pixels per module, black on white. */
    fun bitmap(modules: Array<BooleanArray>, scale: Int = 8): Bitmap {
        val size = modules.size
        val pixels = IntArray(size * scale * size * scale)
        for (y in 0 until size * scale) {
            val row = modules[y / scale]
            for (x in 0 until size * scale) pixels[y * size * scale + x] = if (row[x / scale]) BLACK else WHITE
        }
        return Bitmap.createBitmap(pixels, size * scale, size * scale, Bitmap.Config.ARGB_8888)
    }

    /** The text of the QR code in ARGB [pixels]; null when there is none. Pure (no Android types). */
    fun decode(width: Int, height: Int, pixels: IntArray): String? {
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        for (binary in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(HybridBinarizer(source.invert())))) {
            try {
                return reader.decodeWithState(binary).text
            } catch (_: NotFoundException) {
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /** Reads the QR code in the image at [uri] (a photo-picker result), downscaled for decoding. */
    suspend fun decode(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
        val bitmap = load(context, uri) ?: return@withContext null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        decode(bitmap.width, bitmap.height, pixels)
    }

    private fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DIMENSION) {
                    val f = MAX_DIMENSION.toFloat() / longest
                    decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()

    private const val MAX_DIMENSION = 2048
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
