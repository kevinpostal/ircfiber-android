package com.kevinpostal.fiberirc.data.media

import androidx.collection.LruCache
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.api.attempt
import com.kevinpostal.fiberirc.data.api.await
import com.kevinpostal.fiberirc.data.api.decodeAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Site URLs for media (ports `SiteAPI.pasteURL` / `pasteRawURL` / `imageProxyURL`, `SiteAPI.swift:747-767`). */
object MediaUrls {
    /** The web client's snippet page, `<site>/?/pastebin=<id>`. */
    fun pasteUrl(id: String, siteBase: String = BuildConfig.API_BASE_URL): String =
        siteBase.trimEnd('/') + "/?/pastebin=" + encodeUnreserved(id)

    /** The snippet as plain text (no sign-in needed): `<site>/api/pastebins/<id>/raw`. */
    fun pasteRawUrl(id: String, siteBase: String = BuildConfig.API_BASE_URL): String =
        siteBase.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("pastebins").addPathSegment(id)
            .addPathSegment("raw").build().toString()

    /** GET /api/image-proxy?url=… — an external image fetched by the server (no third-party request from the phone). */
    fun imageProxyUrl(image: String, siteBase: String = BuildConfig.API_BASE_URL): String =
        siteBase.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("image-proxy")
            .addEncodedQueryParameter("url", encodeUnreserved(image)).build().toString()

    /** Percent-encodes everything outside RFC 3986's unreserved set (`A-Za-z0-9-._~`), as UTF-8. */
    fun encodeUnreserved(s: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append("%02X".format(c))
            }
        }
        return out.toString()
    }
}

/** POST /api/upload's answer (site `uploadFile`); `url` and `pageUrl` are the same `/uploads/<file>` link. */
@Serializable
data class UploadResult(val id: String, val url: String, val pageUrl: String, val name: String, val size: Long)

/** A file the account uploaded (GET /api/uploads). `createdAt` is Unix milliseconds. */
@Serializable
data class Upload(
    val id: String,
    val url: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val buffer: String,
    val networkId: String,
    val createdAt: Long,
)

/** An upload's kind by MIME type: the File Uploads filter chips and the tile's fallback icon. */
enum class UploadCategory { Image, Video, File }

fun Upload.category(): UploadCategory = when {
    mimeType.startsWith("image/") -> UploadCategory.Image
    mimeType.startsWith("video/") -> UploadCategory.Video
    else -> UploadCategory.File
}

/** A text snippet (pastebin). `createdAt` is Unix milliseconds. */
@Serializable
data class Paste(
    val id: String,
    val name: String,
    val syntax: String,
    val lines: Int,
    val body: String,
    val createdAt: Long,
)

/** One page of a newest-first list; [total] counts all of them. */
data class MediaPage<T>(val items: List<T>, val total: Int)

@Serializable
private class UploadsAnswer(val uploads: List<Upload>, val total: Int)

@Serializable
private class PastebinsAnswer(val pastebins: List<Paste>, val total: Int)

/**
 * Uploads, snippets and raw media bytes (ports the "Uploads and snippets" part of `SiteAPI.swift:651-767` and
 * `ImageLoader.text/data`). [client] is the app's one ircfiber.com `OkHttpClient` (cookie jar + headers), so the
 * session reaches the upload endpoint, snippet bodies and the image proxy. JSON calls go through [api].
 */
class MediaApi(
    private val client: OkHttpClient,
    private val api: SiteApi,
) {
    /** The site [api] talks to: snippet pages ([MediaUrls.pasteUrl]) link here, not to the build's default site. */
    val siteBase: String get() = api.baseUrl

    /** Uploads may take longer than the 20 s API timeouts; same connection pool and interceptors. */
    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Snippet bodies, by URL (2 MB, like the iOS `texts` cache). */
    private val texts = object : LruCache<String, String>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: String): Int = value.length * 2
    }

    /**
     * POST /api/upload: multipart text fields `buffer`, `filename`, `networkId` (sorted by name, as
     * `MultipartUpload.request` writes them), then the `file` part. [progress] gets 0…1 as the body is written
     * (on OkHttp's thread). 50 MB at most ([UploadFile.MAX_BYTES], checked by the caller).
     */
    suspend fun upload(
        file: File,
        filename: String,
        mime: String,
        networkId: String,
        buffer: String,
        progress: (Double) -> Unit = {},
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        attempt {
            val fields = sortedMapOf("filename" to filename, "networkId" to networkId, "buffer" to buffer)
            val body = MultipartBody.Builder("FiberIRC-${UUID.randomUUID().toString().uppercase()}")
                .setType(MultipartBody.FORM)
                .apply { for ((k, v) in fields) addFormDataPart(k, v) }
                .addFormDataPart("file", filename, ProgressFileBody(file, mime.toMediaTypeOrNull(), progress))
                .build()
            val request = Request.Builder().url(api.url("api", "upload")).post(body).build()
            val response = uploadClient.newCall(request).await()
            val (code, text) = response.use { it.code to it.body?.string().orEmpty() }
            if (code !in 200 until 300) throw SiteApiError.failure(code, text)
            try {
                SiteJson.decodeFromString(UploadResult.serializer(), text)
            } catch (_: SerializationException) {
                throw SiteApiError.Malformed
            } catch (_: IllegalArgumentException) {
                throw SiteApiError.Malformed
            }
        }
    }

    /** GET /api/uploads — newest first. */
    suspend fun uploads(offset: Int, limit: Int): Result<MediaPage<Upload>> =
        api.getJson(listOf("api", "uploads"), mapOf("offset" to "$offset", "limit" to "$limit"))
            .decodeAs(UploadsAnswer.serializer())
            .map { MediaPage(it.uploads, it.total) }

    suspend fun deleteUpload(id: String): Result<Unit> = api.deletePath(listOf("api", "uploads", id))

    /** POST /api/pastebins — `syntax` is a highlighter name ("text", "python", …). */
    suspend fun createPastebin(name: String, body: String, syntax: String, networkId: String, buffer: String): Result<Paste> =
        api.postJson(
            listOf("api", "pastebins"),
            buildJsonObject {
                put("name", name)
                put("body", body)
                put("syntax", syntax)
                put("networkId", networkId)
                put("buffer", buffer)
            },
        ).decodeAs(Paste.serializer())

    /** GET /api/pastebins — newest first. */
    suspend fun pastebins(offset: Int, limit: Int): Result<MediaPage<Paste>> =
        api.getJson(listOf("api", "pastebins"), mapOf("offset" to "$offset", "limit" to "$limit"))
            .decodeAs(PastebinsAnswer.serializer())
            .map { MediaPage(it.pastebins, it.total) }

    suspend fun deletePastebin(id: String): Result<Unit> = api.deletePath(listOf("api", "pastebins", id))

    /** A text response (a snippet's raw body), UTF-8, cached in memory. */
    suspend fun text(url: String): Result<String> {
        texts[url]?.let { return Result.success(it) }
        return bytes(url).map { String(it, Charsets.UTF_8) }.onSuccess { texts.put(url, it) }
    }

    /** The whole response (the viewer's full-size image, a GIF to save); [MediaLoadError.Status] on non-2xx. */
    suspend fun bytes(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        attempt {
            val response = client.newCall(Request.Builder().url(url).get().build()).await()
            response.use {
                if (it.code !in 200 until 300) throw MediaLoadError.Status(it.code)
                it.body?.bytes() ?: ByteArray(0)
            }
        }
    }
}

/** Why media didn't load (ports `ImageLoader.LoadError`). */
sealed class MediaLoadError(message: String) : Exception(message) {
    data class Status(val code: Int) : MediaLoadError("The server answered $code.")
    data object Undecodable : MediaLoadError("This isn't an image the app can show.")
}

/** A file part that reports how much of it has been written. */
private class ProgressFileBody(
    private val file: File,
    private val type: MediaType?,
    private val progress: (Double) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        file.source().use { source ->
            while (true) {
                val n = source.read(sink.buffer, 8192)
                if (n == -1L) break
                sent += n
                sink.flush()
                if (total > 0) progress(sent.toDouble() / total)
            }
        }
    }
}
