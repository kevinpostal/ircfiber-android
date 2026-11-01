package com.kevinpostal.fiberirc.data.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** A file over [UploadFile.MAX_BYTES], refused before its bytes are read. */
class FileTooLarge : IOException("File too large (max 50 MB)")

/**
 * A file ready to upload (ports `UploadPayload`): its bytes in a cache-dir copy (so a 50 MB video is never held in
 * memory), a suggested name and type, and what the confirm screen previews (photos only).
 */
data class UploadPayload(val file: File, val filename: String, val mime: String, val preview: Bitmap?) {
    val size: Long get() = file.length()
}

/** The pure parts of sharing a file (ports `UploadFile`, `ios/UI/Media/UploadViewController.swift:5-70`). */
object UploadFile {
    /** The server's limit (the web's `MAX_UPLOAD_BYTES`). */
    const val MAX_BYTES = 50L * 1024 * 1024
    const val JPEG_QUALITY = 80

    fun isTooLarge(bytes: Long): Boolean = bytes > MAX_BYTES

    /** `IMG_<yyyyMMdd_HHmmss>.jpg` (a photo) or `VID_<yyyyMMdd_HHmmss>.<ext>` (a video), in local time. */
    fun defaultName(
        prefix: String = "IMG",
        ext: String = "jpg",
        date: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = "${prefix}_${DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).withZone(zone).format(date)}.$ext"

    /** A photo's pixel size with its longest side at most [maxDimension] (0 keeps the original); never enlarged. */
    fun resizedSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (maxDimension <= 0 || longest <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** The line sent after an upload or a snippet: `"<message> <url>"`, or the URL alone (the web's `joinMessageLink`). */
    fun message(message: String, url: String): String {
        val text = message.replace(TRAILING_SPACE, "")
        return if (text.isEmpty()) url else "$text $url"
    }

    /** The MIME type a file name's extension implies, else [fallback]. */
    fun mimeType(filename: String, fallback: String): String {
        val ext = filename.substringAfterLast('.', "")
        if (ext.isEmpty() || ext == filename) return fallback
        return MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext.lowercase()) ?: fallback
    }

    /** The extension a MIME type implies (`video/mp4` → `mp4`), or null. */
    fun extension(mime: String): String? = MimeTypeMap.getSingleton()?.getExtensionFromMimeType(mime)

    private val TRAILING_SPACE = Regex("""\s+$""")

    // region Reading picked content

    /** A content Uri's display name and size, when the provider knows them. */
    fun describe(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return name to size
    }

    /** A new empty file under `cacheDir/uploads` (shared through the app's FileProvider's cache root). */
    fun cacheFile(context: Context, ext: String): File {
        val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}.$ext")
    }

    /** Copies [uri] into the cache, refusing more than [MAX_BYTES] even when the provider didn't report a size. */
    fun copyToCache(context: Context, uri: Uri, ext: String): File {
        val out = cacheFile(context, ext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        input.use { src ->
            out.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (isTooLarge(total)) {
                        dst.close()
                        out.delete()
                        throw FileTooLarge()
                    }
                    dst.write(buf, 0, n)
                }
            }
        }
        return out
    }

    /** A video's bytes, named `VID_<date>.<ext>` (`videoPayload`). */
    fun videoPayload(context: Context, uri: Uri, mime: String?): UploadPayload {
        val (name, size) = describe(context.contentResolver, uri)
        if (size != null && isTooLarge(size)) throw FileTooLarge()
        val fromName = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it != name }?.lowercase()
        val ext = fromName ?: mime?.let { extension(it) } ?: "mp4"
        val filename = defaultName(prefix = "VID", ext = ext)
        val file = copyToCache(context, uri, ext)
        return UploadPayload(file, filename, mimeType(filename, mime ?: "video/mp4"), null)
    }

    /** A picked photo: a GIF as it is (it keeps its animation), anything else (HEIC included) as a resized JPEG. */
    fun photoPayload(context: Context, uri: Uri, mime: String?, maxDimension: Int): UploadPayload? {
        val (_, size) = describe(context.contentResolver, uri)
        if (size != null && isTooLarge(size)) throw FileTooLarge()
        if (mime == "image/gif") {
            val file = copyToCache(context, uri, "gif")
            return UploadPayload(file, defaultName(ext = "gif"), "image/gif", decodeScaled(file, 1024))
        }
        val source = copyToCache(context, uri, "img")
        try {
            return jpegPayload(context, source, maxDimension)
        } finally {
            source.delete()
        }
    }

    /** A camera photo already written to [file]: resized JPEG at 0.8 (`jpeg(from: UIImage)`). */
    fun cameraPhotoPayload(context: Context, file: File, maxDimension: Int): UploadPayload? =
        jpegPayload(context, file, maxDimension)

    /** A picked document as it is, its name kept; images get a preview (`documentPicker`). */
    fun documentPayload(context: Context, uri: Uri): UploadPayload {
        val (name, size) = describe(context.contentResolver, uri)
        if (size != null && isTooLarge(size)) throw FileTooLarge()
        val filename = name ?: "file"
        val mime = mimeType(filename, context.contentResolver.getType(uri) ?: "application/octet-stream")
        val ext = filename.substringAfterLast('.', "bin")
        val file = copyToCache(context, uri, ext)
        val preview = if (mime.startsWith("image/")) decodeScaled(file, 1024) else null
        return UploadPayload(file, filename, mime, preview)
    }

    /**
     * A photo as JPEG at 0.8 with its longest side at most [maxDimension] (0 keeps the size), EXIF orientation
     * applied; HEIC and other formats convert. Null when [source] isn't an image.
     */
    private fun jpegPayload(context: Context, source: File, maxDimension: Int): UploadPayload? {
        val bitmap = decodeResized(source, maxDimension) ?: return null
        val out = cacheFile(context, "jpg")
        out.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) return null }
        return UploadPayload(out, defaultName(), "image/jpeg", bitmap)
    }

    /** [file] decoded to [resizedSize] of its pixel size, upright. */
    private fun decodeResized(file: File, maxDimension: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val (w, h) = resizedSize(info.size.width, info.size.height, maxDimension)
                    decoder.setTargetSize(w, h)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val (w, h) = resizedSize(bounds.outWidth, bounds.outHeight, maxDimension)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = if (decoded.width != w || decoded.height != h) Bitmap.createScaledBitmap(decoded, w, h, true) else decoded
        return upright(scaled, file)
    }

    /** A preview at most [maxPixel] on its longest side (`ImageLoader.downsample`). */
    fun decodeScaled(file: File, maxPixel: Int): Bitmap? = decodeResized(file, maxPixel)

    /** API < 28: `BitmapFactory` ignores EXIF orientation; apply it by hand. */
    private fun upright(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    // endregion
}

/**
 * The pure parts of text snippets (ports `Snippet`, `ios/UI/Media/SnippetEditorViewController.swift:5-43`): when the
 * composer offers one, the `/paste` command, the syntax list and sending a body as messages.
 */
object Snippet {
    const val DEFAULT_NAME = "snippet.txt"

    /** The highlighters the editor offers (the server's `syntax` names). */
    val syntaxes = listOf(
        "text", "python", "javascript", "typescript", "bash", "json", "yaml", "markdown", "sql", "xml", "css",
        "swift", "go", "rust", "ruby", "php", "java", "cpp", "csharp",
    )

    /** A message longer than this (UTF-16 units, as IRCCloud counts) is offered as a snippet. */
    const val LONG_MESSAGE_LENGTH = 1080

    /** `/paste`, alone or followed by the snippet's text. */
    fun isPasteCommand(text: String): Boolean {
        val t = text.trimStart()
        if (t.length < 6 || !t.substring(0, 6).equals("/paste", ignoreCase = true)) return false
        return t.length == 6 || t[6].isWhitespace()
    }

    /** The text after `/paste` and its separator. */
    fun pasteBody(text: String): String {
        val t = text.trimStart().drop(6)
        return if (t.firstOrNull()?.isWhitespace() == true) t.drop(1) else t
    }

    /**
     * With Ask To Post A Snippet, a message (not a command) over 1080 characters or with a newline inside it opens
     * the snippet editor instead of sending (IRCCloud `MainViewController` `:2132-2146`).
     */
    fun offersSnippet(text: String, ask: Boolean): Boolean {
        if (!ask || text.startsWith("/")) return false
        return text.length > LONG_MESSAGE_LENGTH || text.trim { it in NEWLINES }.any { it in NEWLINES }
    }

    /** Send as Messages: each non-empty line as one message; a line starting with `/` is sent as text, not run. */
    fun messageLines(body: String): List<String> =
        body.split(NEWLINE_SPLIT)
            .filter { line -> line.isNotEmpty() && !line.all { it == ' ' || it == '\t' } }
            .map { if (it.startsWith("/")) "/$it" else it }

    /** Swift's `Character.isNewline`. */
    private const val NEWLINES = "\n\r\u000B\u000C\u0085\u2028\u2029"
    private val NEWLINE_SPLIT = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")
}
