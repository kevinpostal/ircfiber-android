package com.kevinpostal.fiberirc.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.getExtra
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Precision
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Loads inline media (ports `ios/UI/Media/ImageLoader.swift`): images downsampled to the size they are shown at and
 * full-size for the viewer. Requests go through the app's one ircfiber.com `OkHttpClient`, so the account's cookie
 * reaches the image proxy and site uploads; responses are kept in a 100 MB disk cache, decoded images in a 50 MB
 * memory cache. Identical concurrent requests share one load (Coil does this itself). GIFs animate. A request made
 * for a [ThumbnailKind.Video] or [ThumbnailKind.Pdf] decodes a video frame or a PDF's first page instead.
 *
 * Install once at app start with [install]; composables then use Coil's singleton.
 */
object FiberImageLoader {
    const val DISK_CACHE_BYTES = 100L * 1024 * 1024
    const val MEMORY_CACHE_BYTES = 50L * 1024 * 1024

    fun create(context: PlatformContext, client: OkHttpClient): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_BYTES).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("inline_media").toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .components {
            add(ThumbnailFetcher.Factory(client))
            add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .build()

    /** Makes [create]'s loader Coil's singleton (`AsyncImage` without an explicit loader uses it). */
    fun install(client: OkHttpClient) {
        SingletonImageLoader.setSafe { create(it, client) }
    }

    /** The memory-cache key of a downsampled variant: `"<maxPixel>|<url>"` (`ImageLoader.key`). */
    fun key(url: String, maxPixel: Int): String = "$maxPixel|$url"

    /** Pixels for `dp` at `density`, at least 2× (iOS decodes at `max(displayScale, 2)`), rounded up. */
    fun maxPixel(dp: Float, density: Float): Int = ceil(dp * maxOf(density, 2f)).toInt().coerceAtLeast(1)

    /**
     * [url] decoded with its longest side at most [maxPixel] pixels, never enlarged: as an image, or (per [kind]) a
     * video's frame or a PDF's first page.
     */
    fun request(context: Context, url: String, maxPixel: Int, kind: ThumbnailKind = ThumbnailKind.Image): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(maxPixel)
            .precision(Precision.INEXACT)
            .memoryCacheKey(key(url, maxPixel))
            .apply { if (kind != ThumbnailKind.Image) extras.set(ThumbnailKindKey, kind) }
            .build()
}

/** What a [FiberImageLoader.request] decodes: image bytes (Coil's own path), a video's frame, or a PDF's first page. */
enum class ThumbnailKind { Image, Video, Pdf }

private val ThumbnailKindKey = Extras.Key(ThumbnailKind.Image)

/**
 * A [ThumbnailKind.Video] or [ThumbnailKind.Pdf] request's thumbnail, fitted within the request's size. A video's
 * frame is read straight off the network by `MediaMetadataRetriever` (range requests, not the whole file); a PDF is
 * downloaded to the cache directory for `PdfRenderer`, which needs a file. Both send [client]'s cookie. Any failure
 * throws, so the request errors (the caller shows the type icon).
 */
private class ThumbnailFetcher(
    private val url: String,
    private val kind: ThumbnailKind,
    private val options: Options,
    private val client: OkHttpClient,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = when (kind) {
            ThumbnailKind.Video -> videoFrame()
            ThumbnailKind.Pdf -> pdfPage()
            ThumbnailKind.Image -> error("Images go through Coil's network fetcher")
        }
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.NETWORK)
    }

    /** The longest side the request allows, or null when it didn't size itself. */
    private val maxPixel: Int? = minOf(
        options.size.width.pxOrElse { Int.MAX_VALUE },
        options.size.height.pxOrElse { Int.MAX_VALUE },
    ).takeIf { it != Int.MAX_VALUE }

    private fun videoFrame(): Bitmap {
        val cookies = url.toHttpUrlOrNull()?.let { client.cookieJar.loadForRequest(it) }.orEmpty()
        val headers = if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies.joinToString("; ") { "${it.name}=${it.value}" })
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, headers)
            val max = maxPixel
            val frame = if (max != null && Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max, max)
            } else {
                retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { fit(it, max) }
            }
            return frame ?: throw IOException("No frame in $url")
        } finally {
            retriever.release()
        }
    }

    private fun pdfPage(): Bitmap {
        val file = File.createTempFile("upload", ".pdf", options.context.cacheDir)
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code} for $url")
                body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val longest = maxOf(page.width, page.height)
                        val scale = (maxPixel ?: longest).toFloat() / longest
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).roundToInt().coerceAtLeast(1),
                            (page.height * scale).roundToInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE) // PDF pages are drawn onto paper, not transparency
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    /** [bitmap] scaled so its longest side is at most [max] pixels (unchanged when it already fits or [max] is null). */
    private fun fit(bitmap: Bitmap, max: Int?): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (max == null || longest <= max) return bitmap
        val scale = max.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Takes http(s) requests marked [ThumbnailKind.Video] or [ThumbnailKind.Pdf]; the rest fall through to Coil's. */
    class Factory(private val client: OkHttpClient) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val kind = options.getExtra(ThumbnailKindKey)
            if (kind == ThumbnailKind.Image || (data.scheme != "http" && data.scheme != "https")) return null
            return ThumbnailFetcher(data.toString(), kind, options, client)
        }
    }
}

/**
 * Whether the active network is metered (cellular, a hotspot), for Embed Using Mobile Data (ports `MediaNetwork`,
 * `ImageLoader.swift:122-138`). One instance per process, watching from first use.
 */
class MediaNetwork private constructor(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _isMetered = MutableStateFlow(cm?.isActiveNetworkMetered ?: false)
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _isMetered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }

                override fun onLost(network: Network) {
                    _isMetered.value = cm.isActiveNetworkMetered
                }
            })
        }
    }

    companion object {
        @Volatile private var instance: MediaNetwork? = null

        fun get(context: Context): MediaNetwork =
            instance ?: synchronized(this) { instance ?: MediaNetwork(context).also { instance = it } }
    }
}
