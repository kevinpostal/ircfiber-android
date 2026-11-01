package com.kevinpostal.fiberirc.data.irc

import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.media.MediaUrls
import java.net.URI
import java.net.URISyntaxException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An inline preview under a chat line: an image, a YouTube thumbnail, a text snippet or an uploaded file (ports
 * `MediaItem`, `ios/UI/Media/MediaLinks.swift:4-9`). [url] is the link the preview stands for — the image, the
 * YouTube watch page, the snippet page or the uploaded file — and the key of [IrcBuffer.hiddenMedia] (a closed
 * preview).
 */
sealed class MediaItem {
    abstract val url: String

    data class Image(override val url: String) : MediaItem()
    data class YouTube(val id: String, override val url: String) : MediaItem()
    data class Paste(val id: String, override val url: String) : MediaItem()
    data class File(override val url: String, val name: String) : MediaItem()
}

/**
 * Finds the previewable links in a message body, in order and without repeats (ports
 * `ios/UI/Media/MediaLinks.swift`).
 *
 * Images and YouTube follow the web client (`site/frontend/src/lib/imageInline.ts` `normalizeImageUrl` /
 * `extractImageUrlsFromText` and `youtube.ts` `extractYoutubeId`, both IRCCloud ports). Links on the site itself
 * are snippets (`<site>/?/pastebin=<id>`), uploads (`<site>/uploads/<name>`: an image or a file) or site images.
 * Other links must be http(s) and are rejected when their host is loopback or private; they come back as https.
 */
object MediaLinks {
    fun items(text: String, siteBase: String = BuildConfig.API_BASE_URL): List<MediaItem> {
        if (text.isEmpty()) return emptyList()
        val site = Site.parse(siteBase) ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (match in Pattern.link.findAll(text)) {
            val raw = trimmingTrailingPunctuation(match.value)
            val item = item(raw, site) ?: continue
            if (item !in out) out.add(item)
        }
        return out
    }

    // region Scanning

    /** Peels punctuation that ends a sentence rather than the link, and closing brackets with no opener in the link. */
    internal fun trimmingTrailingPunctuation(url: String): String {
        val openers = mapOf(')' to '(', ']' to '[', '}' to '{')
        var s = url
        while (s.isNotEmpty()) {
            val last = s.last()
            val opener = openers[last]
            s = when {
                last in ".,!?:;*_'\"" -> s.dropLast(1)
                opener != null && s.count { it == last } > s.count { it == opener } -> s.dropLast(1)
                else -> break
            }
        }
        return s
    }

    private fun item(raw: String, site: Site): MediaItem? {
        val c = components(raw) ?: return null
        val host = c.host.lowercase()
        if (host.isEmpty()) return null
        if (host == site.host.lowercase() && c.port == site.port) {
            return siteItem(c.copy(scheme = site.scheme), site)
        }
        if (isPrivateHost(host)) return null
        val https = c.copy(scheme = "https", host = host)
        val id = youtubeId(https)
        if (id != null) return MediaItem.YouTube(id, youtubeWatchUrl(id, youtubeStart(https)))
        return imageUrl(https)?.let { MediaItem.Image(it) }
    }

    /** Parses an http(s) link; a bare `domain.tld/path` or protocol-relative link becomes https. */
    private fun components(raw: String): Parts? {
        var s = raw
        val lower = s.lowercase()
        if (lower.startsWith("//")) {
            s = "https:$s"
        } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (!Pattern.bareLink.containsMatchIn(s)) return null
            s = "https://$s"
        }
        val c = Parts.parse(percentEncodingNonAscii(s)) ?: return null
        val scheme = c.scheme.lowercase()
        return if (scheme == "http" || scheme == "https") c else null
    }

    /** RFC 3986 wants ASCII, so characters outside it are percent-encoded first (UTF-8), as a browser would. */
    private fun percentEncodingNonAscii(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp < 0x80) {
                out.append(cp.toChar())
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    out.append('%').append("%02X".format(b.toInt() and 0xff))
                }
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    // endregion

    // region Site links

    private fun siteItem(c: Parts, site: Site): MediaItem? {
        val path = c.rawPath
        val query = c.rawQuery
        if ((path.isEmpty() || path == "/") && query != null) {
            val encoded = captures(Pattern.pasteQuery, query)?.get(0)
            val id = encoded?.let { percentDecode(it) }
            if (id != null && id.codePointCount(0, id.length) >= 8) {
                return MediaItem.Paste(id, MediaUrls.pasteUrl(id, site.base))
            }
        }
        val url = c.url()
        if (isImage(c)) return MediaItem.Image(url)
        if (path.startsWith("/uploads/") && path.length > "/uploads/".length) {
            return MediaItem.File(url, lastPathComponent(url))
        }
        return null
    }

    // endregion

    // region Images (`normalizeImageUrl`)

    private fun isImage(c: Parts): Boolean =
        listOf(c.path, c.query.orEmpty(), c.fragment.orEmpty()).any { Pattern.imageExtension.containsMatchIn(it) }

    /** IRCCloud's `imageUrl` host rewrites, then the extension check. `c` is already https with a lower-cased host. */
    private fun imageUrl(c: Parts): String? {
        val host = c.host
        val path = c.rawPath
        if (Pattern.dropboxHost.containsMatchIn(host)) {
            captures(Pattern.dropboxPath, path)?.let { return "https://dl.dropboxusercontent.com/s/${it[0]}/${it[1]}" }
        }
        if (host == "pbs.twimg.com") {
            captures(Pattern.twimgPath, path)?.let { return "https://pbs.twimg.com/media/${it[0]}?format=jpg&name=small" }
        }
        if (host == "droplr.com" || host == "d.pr") {
            captures(Pattern.droplrPath, path)?.let { return "https://d.pr/i/${it[0]}+" }
        }
        if (Pattern.steamHost.containsMatchIn(host) && Pattern.steamPath.containsMatchIn(path)) return c.url()
        if (Pattern.googleHost.containsMatchIn(host) || Pattern.redditHost.containsMatchIn(host)) return c.url()
        return if (isImage(c)) c.url() else null
    }

    // endregion

    // region YouTube (`extractYoutubeId`)

    private fun youtubeId(c: Parts): String? {
        val host = c.host
        val path = c.rawPath
        val id = if (Pattern.youtubeHost.containsMatchIn(host)) {
            val search = c.rawQuery?.let { "?$it" } ?: ""
            captures(Pattern.youtubeQuery, search)?.get(0)
                ?: captures(Pattern.youtubePath, path)?.get(0)
                ?: captures(Pattern.youtubeEmbedPath, path)?.get(0)
        } else if (Pattern.youtuBeHost.containsMatchIn(host)) {
            captures(Pattern.youtuBePath, path)?.get(0)
        } else {
            null
        }
        return id?.takeIf { Pattern.youtubeId.containsMatchIn(it) }
    }

    /** The `t=` start time (`#t=` first, then `?t=`), in seconds (`youtube.ts` `extractYoutubeStart`). */
    private fun youtubeStart(c: Parts): Int? {
        val t = c.rawFragment?.let { captures(Pattern.startParameter, it)?.get(0) }
            ?: c.rawQuery?.let { captures(Pattern.startParameter, it)?.get(0) }
        return t?.let { startSeconds(it) }
    }

    /** IRCCloud's `ytStart`: `1h2m3s`, `1m30s`, `90s` or `90`. */
    internal fun startSeconds(value: String): Int? {
        var s = value.lowercase().trim { it == ' ' || it == '\t' }
        var hours = 0
        var minutes = 0
        var seconds = 0
        val hSplit = s.split("h")
        if (hSplit.size > 1) {
            hours = leadingInt(hSplit[0]) ?: 0
            s = hSplit.drop(1).joinToString("h")
        }
        val mSplit = s.split("m")
        if (mSplit.size > 1) {
            minutes = leadingInt(mSplit[0]) ?: 0
            val sSplit = mSplit.drop(1).joinToString("m").split("s")
            if (sSplit[0].isNotEmpty()) {
                seconds = leadingInt(sSplit[0]) ?: 0
            } else if (sSplit.size > 1) {
                seconds = leadingInt(sSplit[1]) ?: 0
            }
        } else {
            seconds = leadingInt(s.split("s")[0]) ?: 0
        }
        val total = hours * 3600 + minutes * 60 + seconds
        return if (total > 0) total else null
    }

    /** JavaScript's `parseInt(s, 10)`: an optional sign and the leading digits, ignoring what follows. */
    private fun leadingInt(s: String): Int? = captures(Pattern.leadingInt, s)?.get(0)?.toIntOrNull()

    /** `youtube.ts` `youtubeWatchUrl`. */
    private fun youtubeWatchUrl(id: String, start: Int?): String {
        val url = "https://www.youtube.com/watch?v=$id"
        return if (start == null) url else "$url&t=${start}s"
    }

    // endregion

    // region Hosts

    /**
     * Loopback, private, link-local, CGNAT, multicast and reserved addresses, `localhost`, `.local` and `.internal`
     * names, and numeric hosts that aren't a plain dotted quad (`127.1`, `0x7f.0.0.1`), which browsers read as IPv4.
     * IPv6 literals never get here: like the web's, the link pattern stops at `[`.
     */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.trim('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true
        }
        val labels = h.split(".")
        val last = labels.last()
        if (!Pattern.numericLabel.containsMatchIn(last)) return false
        val octets = labels.mapNotNull { label ->
            if (Pattern.octet.containsMatchIn(label)) label.toIntOrNull()?.takeIf { it <= 255 } else null
        }
        if (labels.size != 4 || octets.size != 4) return true
        val a = octets[0]
        val b = octets[1]
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    // endregion

    // region Patterns

    private object Pattern {
        /**
         * The web scanner's pattern (scheme or protocol-relative links, and bare `domain.tld/path` links), except that
         * a match can't start inside a word or another scheme's link, so `ftp://host/a.png` doesn't read as
         * `https://host/a.png`. `\p{Z}` sits beside `\s` because ICU's (iOS, Android) `\s` covers Unicode spaces while
         * the JVM's doesn't.
         */
        val link = Regex(
            """(?<![A-Za-z0-9+.:/@-])(?:(?:https?://|//)[^\s\p{Z}<>"{}|\\^`\[\]]+|[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?:/[^\s\p{Z}<>"{}|\\^`\[\]]*)?)""",
            RegexOption.IGNORE_CASE,
        )
        val bareLink = Regex("""^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/""")
        val pasteQuery = Regex("""^/pastebin=([^&]+)""")
        val imageExtension = Regex("""\.(?:jpe?g|gif|png|webp)$""", RegexOption.IGNORE_CASE)
        val dropboxHost = Regex("""^(?:www\.)?dropbox\.com$""")
        val dropboxPath = Regex("""^/s/([^/&]+)/([^/&]+)/?$""")
        val twimgPath = Regex("""^/media/([^/:]+)(?::\w+)?/?$""")
        val droplrPath = Regex("""^/i/([^/+]+)""")
        val steamHost = Regex("""\.steam(?:powered|usercontent)\.com$""")
        val steamPath = Regex("""^/ugc/[^/&]+/[^/&]+""")
        val googleHost = Regex("""^lh\d+\.(?:googleusercontent|ggpht)\.com$""")
        val redditHost = Regex("""i\.reddituploads\.com$""")
        val youtubeHost = Regex("""(?:^|\.)youtube(?:-nocookie)?(?:\.googleapis)?\.com$""")
        val youtuBeHost = Regex("""(?:^|\.)youtu\.be$""")
        val youtubeQuery = Regex("""[?&]v=([^"&?/ ]{11})""")
        val youtubePath = Regex("""^/(?:v|e|shorts)/([^"&?/ ]{11})""")
        val youtubeEmbedPath = Regex("""^/embed/([^"&?/ ]{11})""")
        val youtuBePath = Regex("""^/([^"&?/ ]{11})""")
        val youtubeId = Regex("""^[A-Za-z0-9_-]{11}$""")
        val startParameter = Regex("""(?:^|&)t=([^&]+)""")
        val leadingInt = Regex("""^\s*([+-]?\d+)""")
        val numericLabel = Regex("""^(?:\d+|0x[0-9a-f]*)$""")
        val octet = Regex("""^(?:0|[1-9]\d{0,2})$""")
    }

    /** The capture groups of the first match (an unmatched group is ""), or null without a match. */
    private fun captures(re: Regex, s: String): List<String>? {
        val m = re.find(s) ?: return null
        return (1 until m.groups.size).map { m.groups[it]?.value ?: "" }
    }

    // endregion

    // region URL parts

    /** The base URL's scheme, host and explicit port (null when it has none, like `URL.port`). */
    private class Site(val base: String, val scheme: String, val host: String, val port: Int?) {
        companion object {
            fun parse(base: String): Site? = Parts.parse(base)?.let { Site(base, it.scheme, it.host, it.port) }
        }
    }

    /**
     * A parsed link (the `URLComponents` this ports reads): raw (percent-encoded) path/query/fragment for matching
     * and rebuilding, decoded ones for the extension check. The host is read off the authority by hand so hosts
     * `java.net.URI` treats as registry names (an `_` in a label) still parse, as they do for `URLComponents`.
     */
    private data class Parts(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val port: Int?,
        val rawPath: String,
        val rawQuery: String?,
        val rawFragment: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    ) {
        fun url(): String = buildString {
            append(scheme).append("://")
            if (userInfo != null) append(userInfo).append('@')
            append(host)
            if (port != null) append(':').append(port)
            append(rawPath)
            if (rawQuery != null) append('?').append(rawQuery)
            if (rawFragment != null) append('#').append(rawFragment)
        }

        companion object {
            fun parse(s: String): Parts? {
                val uri = try {
                    URI(s)
                } catch (_: URISyntaxException) {
                    return null
                }
                val scheme = uri.scheme ?: return null
                var authority = uri.rawAuthority ?: return null
                var userInfo: String? = null
                val at = authority.lastIndexOf('@')
                if (at >= 0) {
                    userInfo = authority.substring(0, at)
                    authority = authority.substring(at + 1)
                }
                var port: Int? = null
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    val digits = authority.substring(colon + 1)
                    if (digits.isNotEmpty()) port = digits.toIntOrNull() ?: return null
                    authority = authority.substring(0, colon)
                }
                return Parts(
                    scheme = scheme,
                    userInfo = userInfo,
                    host = authority,
                    port = port,
                    rawPath = uri.rawPath.orEmpty(),
                    rawQuery = uri.rawQuery,
                    rawFragment = uri.rawFragment,
                    path = uri.path.orEmpty(),
                    query = uri.query,
                    fragment = uri.fragment,
                )
            }
        }
    }

    /** `removingPercentEncoding`: null when an escape is malformed or the bytes aren't UTF-8. */
    internal fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%') {
                if (i + 2 >= s.length) return null
                val hex = s.substring(i + 1, i + 3)
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val v = hex.toInt(16)
                bytes.write(v)
                i += 3
            } else {
                bytes.write(String(Character.toChars(s.codePointAt(i))).toByteArray(Charsets.UTF_8))
                i += Character.charCount(s.codePointAt(i))
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /** `URL.lastPathComponent`, decoded: `""` for no path, `"/"` for the root. */
    internal fun lastPathComponent(url: String): String {
        val path = try {
            URI(url).path.orEmpty()
        } catch (_: URISyntaxException) {
            return ""
        }
        if (path.isEmpty()) return ""
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return "/"
        return trimmed.substringAfterLast('/')
    }

    // endregion
}

/** What inline media shows and where it loads from (ports `InlineMedia`, `ios/UI/Media/InlineMediaCell.swift:14-110`). */
object InlineMedia {
    /** The image box: at most this wide (and the text column) and this tall, in dp. */
    const val MAX_IMAGE_WIDTH = 300f
    const val MAX_IMAGE_HEIGHT = 200f

    /** Lines of a snippet the preview shows. */
    const val PASTE_PREVIEW_LINES = 5

    /**
     * Which previews a buffer shows: its effective Inline Images (external images and YouTube), Inline Files
     * (`<site>/uploads/…`) and Inline Pastes, and whether media may load on this network ([network]: Embed Using
     * Mobile Data, or the network isn't metered).
     */
    data class Policy(val images: Boolean, val files: Boolean, val pastes: Boolean, val network: Boolean) {
        fun allows(item: MediaItem, siteBase: String): Boolean {
            if (!network) return false
            return when (item) {
                is MediaItem.Image -> if (isUpload(item.url, siteBase)) files else images
                is MediaItem.YouTube -> images
                is MediaItem.Paste -> pastes
                is MediaItem.File -> files
            }
        }

        companion object {
            fun of(images: Boolean, files: Boolean, pastes: Boolean, embedOnCellular: Boolean, pathExpensive: Boolean) =
                Policy(images, files, pastes, embedOnCellular || !pathExpensive)
        }
    }

    /**
     * The previews among a message's links ([MediaLinks.items]) the policy allows, without the ones the user closed
     * ([hidden], by [MediaItem.url]). The chat's rows keep the first two.
     */
    fun visible(links: List<MediaItem>, siteBase: String, hidden: Set<String>, policy: Policy): List<MediaItem> {
        if (!policy.network) return emptyList()
        return links.filter { policy.allows(it, siteBase) && it.url !in hidden }
    }

    /** A link on the site itself (same host and port). */
    fun isSite(url: String, siteBase: String): Boolean {
        val u = hostPort(url) ?: return false
        val b = hostPort(siteBase) ?: return false
        return u == b
    }

    /** An upload on the site: `<site>/uploads/<name>`. */
    fun isUpload(url: String, siteBase: String): Boolean {
        if (!isSite(url, siteBase)) return false
        val path = try {
            URI(url).path.orEmpty()
        } catch (_: URISyntaxException) {
            return false
        }
        return path.startsWith("/uploads/")
    }

    /** YouTube's large thumbnail of a video. */
    fun youtubeThumbnail(id: String): String = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /**
     * Where a preview's content loads from: site images directly, other images and YouTube thumbnails through the
     * site's image proxy, a snippet's raw text; null for a file (no content to load).
     */
    fun sourceUrl(item: MediaItem, siteBase: String = BuildConfig.API_BASE_URL): String? = when (item) {
        is MediaItem.Image -> if (isSite(item.url, siteBase)) item.url else MediaUrls.imageProxyUrl(item.url, siteBase)
        is MediaItem.YouTube -> MediaUrls.imageProxyUrl(youtubeThumbnail(item.id), siteBase)
        is MediaItem.Paste -> MediaUrls.pasteRawUrl(item.id, siteBase)
        is MediaItem.File -> null
    }

    /**
     * The line under a preview: an image's file name, "YouTube", "Text snippet · 1 line" / "· N lines" (once the
     * snippet's length is known), or null for a file (its row is the name).
     */
    fun caption(item: MediaItem, pasteLines: Int?): String? = when (item) {
        is MediaItem.Image -> {
            val name = MediaLinks.lastPathComponent(item.url)
            if (name.isEmpty() || name == "/") {
                runCatching { URI(item.url).host }.getOrNull()
            } else {
                MediaLinks.percentDecode(name) ?: name
            }
        }
        is MediaItem.YouTube -> "YouTube"
        is MediaItem.Paste -> when (pasteLines) {
            null -> "Text snippet"
            1 -> "Text snippet · 1 line"
            else -> "Text snippet · $pasteLines lines"
        }
        is MediaItem.File -> null
    }

    /**
     * The image box for a picture of `pixelWidth`×`pixelHeight` (one pixel per dp, like the web): aspect-fit in
     * [maxWidth] × [maxHeight], never enlarged. `(0, 0)` for an empty picture.
     */
    fun imageSize(pixelWidth: Float, pixelHeight: Float, maxWidth: Float, maxHeight: Float = MAX_IMAGE_HEIGHT): Pair<Float, Float> {
        if (pixelWidth <= 0f || pixelHeight <= 0f || maxWidth <= 0f) return 0f to 0f
        val scale = min(1f, min(maxWidth / pixelWidth, maxHeight / pixelHeight))
        return max(1f, (pixelWidth * scale).roundToInt().toFloat()) to max(1f, (pixelHeight * scale).roundToInt().toFloat())
    }

    /** A snippet's first lines and its line count (a final newline doesn't start another line). */
    fun pastePreview(text: String): Pair<String, Int> {
        val lines = text.split(NEWLINE).toMutableList()
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.take(PASTE_PREVIEW_LINES).joinToString("\n") to lines.size
    }

    /** Swift's `Character.isNewline` separators (`\r\n` is one). */
    private val NEWLINE = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    /** `(host lowercased, explicit port or -1)`. */
    private fun hostPort(url: String): Pair<String, Int>? = try {
        val u = URI(url)
        val authority = u.rawAuthority ?: return null
        val hostPort = authority.substringAfterLast('@')
        val colon = hostPort.lastIndexOf(':')
        if (colon >= 0) {
            hostPort.substring(0, colon).lowercase() to (hostPort.substring(colon + 1).toIntOrNull() ?: -1)
        } else {
            hostPort.lowercase() to -1
        }
    } catch (_: URISyntaxException) {
        null
    }
}
