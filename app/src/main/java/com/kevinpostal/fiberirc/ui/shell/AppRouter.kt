package com.kevinpostal.fiberirc.ui.shell

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

/** A buffer to open from outside the chat (notifications, shortcuts, links in chat, web and IRC links). */
sealed interface BufferLink {
    data class Buffer(val networkId: String, val name: String) : BufferLink
    data class Join(val networkId: String, val channel: String) : BufferLink

    /** `"<networkId>:<buffer>"` (a conversation shortcut id). */
    data class Key(val id: String) : BufferLink

    /** An ircfiber.com buffer page ([WebBufferRoute]), from an App Link. */
    data class Web(val url: String) : BufferLink

    /** An `irc://` or `ircs://` link ([IrcLinkRoute]). */
    data class Irc(val url: String) : BufferLink
}

/**
 * Turns launch intents into [BufferLink]s and hands them to the signed-in session (ports `AppRouter.swift`'s
 * `open`/`pendingOpen` and `SceneDelegate`'s URL dispatch). A link that arrives before the session exists waits in
 * [pending] until [com.kevinpostal.fiberirc.ui.shell.ShellScreen] takes it. The `fiberirc://auth` sign-in
 * hand-off is not a buffer link: [isAuthRedirect] tells it apart so `MainActivity` gives it to the login screen.
 */
object AppRouter {
    private val _pending = MutableStateFlow<BufferLink?>(null)

    /** The newest link not yet opened by the session. */
    val pending: StateFlow<BufferLink?> = _pending.asStateFlow()

    /** Opens [link] in the session, or keeps it until there is one (a newer link replaces a waiting one). */
    fun open(link: BufferLink) {
        _pending.value = link
    }

    /** The session took [link]; a different, newer link stays pending. */
    fun consume(link: BufferLink) {
        _pending.compareAndSet(link, null)
    }

    private val _pendingAuthRedirect = MutableStateFlow<String?>(null)

    /**
     * The newest `fiberirc://auth?code=…` hand-off not yet taken by the login screen (its host calls
     * `LoginViewModel.handleAuthRedirect(Uri.parse(it))`, then [consumeAuthRedirect]).
     */
    val pendingAuthRedirect: StateFlow<String?> = _pendingAuthRedirect.asStateFlow()

    fun consumeAuthRedirect(uri: String) {
        _pendingAuthRedirect.compareAndSet(uri, null)
    }

    /**
     * `MainActivity.onCreate`/`onNewIntent`: an auth hand-off waits in [pendingAuthRedirect] for the login screen;
     * a buffer, `irc(s)://` or App Link waits in [pending] for the session. Other intents are ignored.
     */
    fun handle(intent: Intent?) {
        val uri = intent?.dataString ?: return
        if (isAuthRedirect(uri)) {
            _pendingAuthRedirect.value = uri
            return
        }
        parse(uri)?.let(::open)
    }

    /** `fiberirc://auth?code=…`: the OAuth/app-login return, for `LoginViewModel.handleAuthRedirect`. */
    fun isAuthRedirect(uri: String?): Boolean {
        val u = uri?.let(::parseUri) ?: return false
        return u.scheme.equals(SCHEME, ignoreCase = true) && u.host.equals("auth", ignoreCase = true)
    }

    /** The buffer link an `ACTION_VIEW` intent carries, else null (the auth hand-off included). */
    fun parse(intent: Intent?): BufferLink? = parse(intent?.dataString)

    /**
     * `fiberirc://buffer/<networkId>/<buffer>` (conversation shortcuts, notifications) → [BufferLink.Buffer];
     * `irc(s)://…` → [BufferLink.Irc]; an http(s) `/irc/…` buffer page → [BufferLink.Web]; anything else null.
     */
    fun parse(uri: String?): BufferLink? {
        if (uri == null) return null
        val u = parseUri(uri) ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        return when (scheme) {
            SCHEME -> {
                if (!u.host.equals("buffer", ignoreCase = true)) return null
                val parts = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }
                if (parts.size != 2) return null
                val network = percentDecode(parts[0]) ?: return null
                val buffer = percentDecode(parts[1]) ?: return null
                if (network.isEmpty() || buffer.isEmpty()) null else BufferLink.Buffer(network, buffer)
            }
            "irc", "ircs" -> if (IrcLinkRoute.parse(uri) != null) BufferLink.Irc(uri) else null
            "http", "https" -> if (WebBufferRoute.parse(uri) != null) BufferLink.Web(uri) else null
            else -> null
        }
    }

    /** The `fiberirc://buffer/…` deep link [parse] reads (conversation shortcuts, notification taps). */
    fun bufferUri(networkId: String, buffer: String): String =
        "$SCHEME://buffer/${WebBufferRoute.encodeComponent(networkId)}/${WebBufferRoute.encodeComponent(buffer)}"

    const val SCHEME = "fiberirc"
}

/**
 * A buffer page of the web client, as its router builds and reads them (site `frontend/src/lib/routing.ts`):
 * `/irc/<net>` (server buffer), `/irc/<net>/channel/<chan>`, `/irc/<net>/messages/<nick>`. Each segment is
 * `encodeURIComponent`-encoded; a channel segment without a prefix is a legacy URL for `#<segment>`.
 * Ports `WebBufferRoute` (`ios/UI/Shell/ExternalLinks.swift`).
 */
data class WebBufferRoute(
    /** The network's name (the web's `net.name`), matched case-insensitively. */
    val networkName: String,
    /** Null: the network's server buffer. */
    val buffer: String?,
) {
    companion object {
        /** Null unless [url]'s path is one of the three buffer routes. */
        fun parse(url: String): WebBufferRoute? {
            val path = parseUri(url)?.rawPath ?: return null
            val parts = path.split('/').toMutableList()
            if (parts.size > 1 && parts.last() == "") parts.removeAt(parts.size - 1)   // trailing slash
            if ((parts.size != 3 && parts.size != 5) || parts[0].isNotEmpty() || parts[1] != "irc") return null
            val net = percentDecode(parts[2])?.takeIf { it.isNotEmpty() } ?: return null
            if (parts.size == 3) return WebBufferRoute(net, null)
            val target = percentDecode(parts[4])?.takeIf { it.isNotEmpty() } ?: return null
            return when (parts[3]) {
                "channel" -> WebBufferRoute(net, if (hasChannelPrefix(target)) target else "#$target")
                "messages" -> WebBufferRoute(net, target)
                else -> null
            }
        }

        /**
         * The page on [site] for a buffer: a channel's under `/channel/`, a conversation's under `/messages/`, the
         * server buffer ([buffer] null) at the network. Query and fragment of [site] are dropped.
         */
        fun url(site: String, networkName: String, buffer: String?, isChannel: Boolean): String? {
            val base = parseUri(site) ?: return null
            if (base.scheme == null || base.rawAuthority == null) return null
            var path = "/irc/" + encodeComponent(networkName)
            if (buffer != null) path += (if (isChannel) "/channel/" else "/messages/") + encodeComponent(buffer)
            return "${base.scheme}://${base.rawAuthority}$path"
        }

        /** JavaScript's `encodeURIComponent`: everything but letters, digits and `-_.!~*'()` is percent-encoded. */
        fun encodeComponent(s: String): String {
            val out = StringBuilder()
            for (byte in s.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
                }
            }
            return out.toString()
        }

        fun hasChannelPrefix(s: String): Boolean = s.firstOrNull()?.let { it in "#&+!" } ?: false

        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * An `irc://` or `ircs://` link: `irc(s)://host[:port]/<chan>[,<chan>]`. The channel list may sit in the path
 * (`/%23chan`, `/chan`) or, with a literal `#`, in the fragment (`/#chan`); a name without a prefix gets `#`.
 * Ports `IRCLinkRoute` (`ios/UI/Shell/ExternalLinks.swift`).
 */
data class IrcLinkRoute(
    /** Lower-cased. */
    val host: String,
    val port: Int?,
    /** `ircs`. */
    val tls: Boolean,
    val channels: List<String>,
) {
    companion object {
        /** Null unless the scheme is `irc` or `ircs` and there is a host. */
        fun parse(url: String): IrcLinkRoute? {
            val u = parseUri(url) ?: return null
            val scheme = u.scheme?.lowercase()
            if (scheme != "irc" && scheme != "ircs") return null
            val host = u.host?.takeIf { it.isNotEmpty() } ?: return null
            var list = (u.path ?: "").trimStart('/')
            u.fragment?.let { list += "#$it" }
            val channels = list.split(',').mapNotNull { part ->
                val name = part.trim(' ', '\t')
                if (name.isEmpty()) null else if (WebBufferRoute.hasChannelPrefix(name)) name else "#$name"
            }
            return IrcLinkRoute(host.lowercase(), u.port.takeIf { it >= 0 }, scheme == "ircs", channels)
        }
    }
}

private fun parseUri(s: String): URI? = try {
    URI(s)
} catch (_: URISyntaxException) {
    null
}

/** Foundation's `removingPercentEncoding`: null when an escape is malformed or the bytes aren't UTF-8. */
internal fun percentDecode(s: String): String? {
    if ('%' !in s) return s
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            if (i + 2 >= s.length) return null
            val hi = Character.digit(s[i + 1], 16)
            val lo = Character.digit(s[i + 2], 16)
            if (hi < 0 || lo < 0) return null
            out.write((hi shl 4) or lo)
            i += 3
        } else {
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    val bytes = out.toByteArray()
    val decoder = Charsets.UTF_8.newDecoder()
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}
