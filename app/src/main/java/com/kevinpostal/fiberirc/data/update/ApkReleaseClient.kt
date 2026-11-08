package com.kevinpostal.fiberirc.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The newest complete APK release: its manifest identity and the pinned, versioned APK asset URL. */
data class ApkRelease(val versionCode: Int, val versionName: String, val minSdk: Int, val apkUrl: String)

/** The APK channel can't answer: offline, rate-limited, malformed, incomplete pagination, no candidate or deadline. */
class ApkReleaseUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads the GitHub releases of `kevinpostal/ircfiber-pages` for APK installs. Every page is read until exhaustion,
 * the highest numeric published, non-draft, non-prerelease `android-v<versionName>` release that carries both
 * [APK_ASSET] and [MANIFEST_ASSET] is selected, and its manifest must name this package, positive integer
 * code/minSdk and exactly that versionName. One [deadlineMillis] budget covers the whole [latest] call — every
 * page, the manifest and their bodies.
 *
 * [client] must stay a plain client: no cookie jar, no IRC device/auth interceptors ([plainHttpClient]).
 * [releasesUrl] is injectable for tests; asset destinations stay the fixed `https://github.com` tag paths.
 */
class ApkReleaseClient(
    private val client: OkHttpClient = plainHttpClient(),
    private val releasesUrl: HttpUrl = RELEASES_URL.toHttpUrl(),
    private val deadlineMillis: Long = CHECK_DEADLINE_MILLIS,
) {
    /** The newest complete APK release; throws [ApkReleaseUnavailableException] when none can be established. */
    suspend fun latest(): ApkRelease = withContext(Dispatchers.IO) {
        val deadlineAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
        val release = try {
            // On Dispatchers.IO this timeout runs on the real clock, even inside runTest.
            withTimeoutOrNull(deadlineMillis) { resolve(deadlineAt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApkReleaseUnavailableException) {
            throw e
        } catch (e: IOException) {
            throw ApkReleaseUnavailableException("APK release check failed: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw ApkReleaseUnavailableException("APK release metadata is malformed: ${e.message}", e)
        }
        release ?: throw ApkReleaseUnavailableException("APK release check exceeded $deadlineMillis ms")
    }

    private suspend fun resolve(deadlineAt: Long): ApkRelease {
        // Only the newest complete pair matters: keep a running maximum, flagging numerically equal tags.
        var best: Candidate? = null
        var ambiguous = false
        var page = 1
        while (true) {
            val url = releasesUrl.newBuilder()
                .setQueryParameter("per_page", PER_PAGE.toString())
                .setQueryParameter("page", page.toString())
                .build()
            val releases = Json.parseToJsonElement(fetch(url, GITHUB_JSON, deadlineAt)) as? JsonArray
                ?: throw ApkReleaseUnavailableException("releases page $page is not a list")
            for (element in releases) {
                val candidate = candidate(element) ?: continue
                val order = best?.let { compareVersions(candidate.version, it.version) } ?: 1
                if (order > 0) {
                    best = candidate
                    ambiguous = false
                } else if (order == 0) {
                    ambiguous = true
                }
            }
            if (releases.size < PER_PAGE) break
            page++
        }
        val selected = best ?: throw ApkReleaseUnavailableException("no complete APK release")
        if (ambiguous) throw ApkReleaseUnavailableException("more than one release numerically equal to ${selected.tag}")
        // The selected record is validated as a whole: a bad newest release never falls back to an older one.
        requirePinned(selected, selected.apk, APK_ASSET)
        requirePinned(selected, selected.manifest, MANIFEST_ASSET)
        val manifest = Json.parseToJsonElement(fetch(assetUrl(selected.tag, MANIFEST_ASSET).toHttpUrl(), null, deadlineAt))
        return release(selected, manifest)
    }

    /** One GET under the remaining shared deadline; the call timeout also bounds reading the body. */
    private suspend fun fetch(url: HttpUrl, accept: String?, deadlineAt: Long): String {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineAt - System.nanoTime())
        if (remaining <= 0) throw ApkReleaseUnavailableException("APK release check exceeded $deadlineMillis ms")
        val request = Request.Builder().url(url).get().apply {
            if (accept != null) header("Accept", accept)
        }.build()
        val call = client.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        return call.awaitBody()
    }

    private class Candidate(
        val tag: String,
        val versionName: String,
        val version: IntArray,
        val apk: JsonObject,
        val manifest: JsonObject,
    )

    /**
     * A published, non-draft, non-prerelease `android-v<numeric>` release carrying both named assets; null for
     * unrelated, legacy (APK-only) or unpublished releases. Asset URLs/state are checked only once selected.
     */
    private fun candidate(element: JsonElement): Candidate? {
        val release = element as? JsonObject ?: return null
        val tag = release.string("tag_name") ?: return null
        if (!tag.startsWith(TAG_PREFIX)) return null
        val versionName = tag.substring(TAG_PREFIX.length)
        if (!isNumericVersion(versionName)) return null
        if (release.boolean("draft") != false || release.boolean("prerelease") != false) return null
        if (release.string("published_at").isNullOrEmpty()) return null
        val assets = release["assets"] as? JsonArray ?: return null
        val apk = assets.named(APK_ASSET) ?: return null
        val manifest = assets.named(MANIFEST_ASSET) ?: return null
        val version = parseVersion(versionName)
            ?: throw ApkReleaseUnavailableException("$tag has a version component beyond Int range")
        return Candidate(tag, versionName, version, apk, manifest)
    }

    private fun JsonArray.named(name: String): JsonObject? {
        for (element in this) {
            val asset = element as? JsonObject ?: continue
            if (asset.string("name") == name) return asset
        }
        return null
    }

    private fun requirePinned(candidate: Candidate, asset: JsonObject, name: String) {
        if (asset.string("state") != "uploaded" ||
            asset.string("browser_download_url") != assetUrl(candidate.tag, name)
        ) {
            throw ApkReleaseUnavailableException("${candidate.tag} $name is not the pinned uploaded asset")
        }
    }

    private fun release(candidate: Candidate, element: JsonElement): ApkRelease {
        val manifest = element as? JsonObject ?: throw ApkReleaseUnavailableException("manifest is not an object")
        if (manifest.string("applicationId") != APPLICATION_ID) {
            throw ApkReleaseUnavailableException("manifest names another package")
        }
        if (manifest.string("versionName") != candidate.versionName) {
            throw ApkReleaseUnavailableException("manifest versionName does not match ${candidate.tag}")
        }
        val code = manifest.positiveInt("versionCode")
            ?: throw ApkReleaseUnavailableException("manifest versionCode is not a positive integer")
        val minSdk = manifest.positiveInt("minSdk")
            ?: throw ApkReleaseUnavailableException("manifest minSdk is not a positive integer")
        return ApkRelease(code, candidate.versionName, minSdk, assetUrl(candidate.tag, APK_ASSET))
    }

    companion object {
        const val APPLICATION_ID = "com.kevinpostal.fiberirc"
        const val RELEASES_URL = "https://api.github.com/repos/kevinpostal/ircfiber-pages/releases"
        const val DOWNLOAD_BASE = "https://github.com/kevinpostal/ircfiber-pages/releases/download"
        const val TAG_PREFIX = "android-v"
        const val APK_ASSET = "ircfiber-android.apk"
        const val MANIFEST_ASSET = "android-update.json"
        const val CHECK_DEADLINE_MILLIS = 15_000L
        const val PER_PAGE = 100
        private const val GITHUB_JSON = "application/vnd.github+json"

        /** The fixed asset destination for [tag]; never `latest`, never derived from API-supplied URLs. */
        fun assetUrl(tag: String, name: String): String = "$DOWNLOAD_BASE/$tag/$name"

        /**
         * A fresh unauthenticated client: no cookie jar or IRC header interceptors, 15 s timeouts, redirects
         * followed (GitHub → HTTPS CDN) but never across schemes, so HTTPS can't be downgraded.
         */
        fun plainHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(CHECK_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        /** Dotted ASCII digit components, none empty: `1`, `1.0.10`; not `1.`, `.1`, `1..0` or `1.0-beta`. */
        internal fun isNumericVersion(name: String): Boolean {
            if (name.isEmpty() || name[0] == '.' || name[name.length - 1] == '.') return false
            for (i in name.indices) {
                val ch = name[i]
                if (ch == '.') {
                    if (name[i - 1] == '.') return false
                } else if (ch !in '0'..'9') {
                    return false
                }
            }
            return true
        }

        /** Components of an [isNumericVersion] name; null when one exceeds Int range. */
        internal fun parseVersion(name: String): IntArray? {
            val parts = IntArray(name.count { it == '.' } + 1)
            var index = 0
            var value = 0L
            for (ch in name) {
                if (ch == '.') {
                    parts[index++] = value.toInt()
                    value = 0L
                } else {
                    value = value * 10 + (ch - '0')
                    if (value > Int.MAX_VALUE) return null
                }
            }
            parts[index] = value.toInt()
            return parts
        }

        /** Numeric component order with zero padding: 1.10 > 1.9, 2 == 2.0. */
        internal fun compareVersions(a: IntArray, b: IntArray): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** A literal JSON integer in 1..Int.MAX_VALUE: no quoted strings, fractions, exponents or overflow. */
private fun JsonObject.positiveInt(key: String): Int? {
    val value = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    return value.content.takeIf { POSITIVE_INT_LITERAL.matches(it) }?.toIntOrNull()
}

private val POSITIVE_INT_LITERAL = Regex("[1-9][0-9]*")

/**
 * Suspends for the whole response body, read on OkHttp's callback thread so cancelling the coroutine cancels the
 * call mid-body too (the shared `Call.await()` stops listening once headers arrive).
 */
private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val body = runCatching {
                response.use { r ->
                    if (!r.isSuccessful) {
                        val limited = r.header("X-RateLimit-Remaining") == "0" || r.code == 429
                        throw ApkReleaseUnavailableException(
                            "HTTP ${r.code}${if (limited) " (rate limited)" else ""} from ${r.request.url.redact()}",
                        )
                    }
                    r.body?.string() ?: throw ApkReleaseUnavailableException("empty response body")
                }
            }
            if (cont.isActive) cont.resumeWith(body)
        }
    })
}
