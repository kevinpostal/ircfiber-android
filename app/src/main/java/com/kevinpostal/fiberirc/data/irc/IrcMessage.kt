package com.kevinpostal.fiberirc.data.irc

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/** One parsed IRC line: IRCv3 tags + prefix + command + params (ports `ios/IRC/IRCMessage.swift`). */
data class IrcMessage(
    val tags: Map<String, String> = emptyMap(),
    val prefix: String? = null,
    /** Server name when the prefix has no `!`. */
    val nick: String? = null,
    val user: String? = null,
    val host: String? = null,
    /** Upper-cased. */
    val command: String,
    val params: List<String> = emptyList(),
    /** The `@time` tag, else when the line was parsed. */
    val time: Instant,
) {
    /** Inner text of a `\u0001ACTION …\u0001` last param, else null. */
    val ctcpActionText: String?
        get() {
            val last = params.lastOrNull() ?: return null
            val head = "\u0001ACTION "
            if (!last.startsWith(head) || !last.endsWith("\u0001") || last.length <= head.length) return null
            return last.substring(head.length, last.length - 1)
        }

    companion object {
        /** IRCv3 `server-time` output format (`YYYY-MM-DDTHH:MM:SS.mmmZ`), shared with `MARKREAD timestamp=`. */
        private val timeOutput: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

        /** Internet date-time with optional fractional seconds and a `Z` or `±HH:MM` offset. */
        private val timeInput: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .appendOffset("+HH:MM", "Z")
            .toFormatter(Locale.US)

        fun formatTime(time: Instant): String = timeOutput.format(time)

        fun parseTime(text: String): Instant? = try {
            OffsetDateTime.parse(text, timeInput).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }

        /** Grammar: `['@' tags SP] [':' prefix SP] command *(SP middle) [SP ':' trailing]`. Null when malformed. */
        fun parse(line: String, now: Instant = Instant.now()): IrcMessage? {
            var rest = line
            val tags = HashMap<String, String>()

            if (rest.startsWith("@")) {
                val sp = rest.indexOf(' ')
                if (sp < 0) return null
                val tagStr = rest.substring(1, sp)
                rest = rest.substring(sp + 1)
                for (kv in tagStr.split(';')) {
                    if (kv.isEmpty()) continue
                    val eq = kv.indexOf('=')
                    if (eq >= 0) tags[kv.substring(0, eq)] = unescapeTagValue(kv.substring(eq + 1))
                    else tags[kv] = ""
                }
            }
            rest = rest.trimStart(' ')

            var prefix: String? = null
            if (rest.startsWith(":")) {
                val sp = rest.indexOf(' ')
                if (sp < 0) return null
                prefix = rest.substring(1, sp)
                rest = rest.substring(sp + 1).trimStart(' ')
            }
            if (rest.isEmpty()) return null

            var trailing: String? = null
            val tr = rest.indexOf(" :")
            if (tr >= 0) {
                trailing = rest.substring(tr + 2)
                rest = rest.substring(0, tr)
            } else if (rest.startsWith(":")) {
                return null
            }
            val tokens = rest.split(' ').filter { it.isNotEmpty() }.toMutableList()
            if (trailing != null) tokens.add(trailing)
            if (tokens.isEmpty()) return null

            var nick: String? = null
            var user: String? = null
            var host: String? = null
            if (prefix != null) {
                val bang = prefix.indexOf('!')
                if (bang >= 0) {
                    nick = prefix.substring(0, bang)
                    val uh = prefix.substring(bang + 1)
                    val at = uh.indexOf('@')
                    if (at >= 0) {
                        user = uh.substring(0, at)
                        host = uh.substring(at + 1)
                    } else {
                        user = uh
                    }
                } else {
                    nick = prefix
                }
            }
            val t = tags["time"]
            val time = if (!t.isNullOrEmpty()) parseTime(t) ?: now else now
            return IrcMessage(
                tags = tags,
                prefix = prefix,
                nick = nick,
                user = user,
                host = host,
                command = tokens.removeAt(0).uppercase(Locale.ROOT),
                params = tokens,
                time = time,
            )
        }

        /** IRCv3 message-tags unescape: `\:`→`;`, `\s`→space, `\\`→`\`, `\r`/`\n`; other escapes drop the
         *  backslash; a lone trailing backslash is dropped. */
        fun unescapeTagValue(raw: String): String {
            if (!raw.contains('\\')) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i++]
                if (c != '\\') { out.append(c); continue }
                if (i >= raw.length) break // trailing lone backslash dropped
                when (val e = raw[i++]) {
                    ':' -> out.append(';')
                    's' -> out.append(' ')
                    '\\' -> out.append('\\')
                    'r' -> out.append('\r')
                    'n' -> out.append('\n')
                    else -> out.append(e)
                }
            }
            return out.toString()
        }

        fun escapeTagValue(value: String): String {
            val out = StringBuilder(value.length)
            for (c in value) {
                when (c) {
                    '\\' -> out.append("\\\\")
                    ';' -> out.append("\\:")
                    ' ' -> out.append("\\s")
                    '\r' -> out.append("\\r")
                    '\n' -> out.append("\\n")
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        /**
         * Serializes without CRLF; the last param is prefixed with ':' only when needed. `tags` lead as `@k=v;…`
         * in key order, values escaped per IRCv3 message-tags (an empty value sends the bare key).
         */
        fun line(tags: Map<String, String> = emptyMap(), command: String, params: List<String>): String {
            val sb = StringBuilder()
            if (tags.isNotEmpty()) {
                sb.append('@')
                tags.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(';')
                    sb.append(k)
                    if (v.isNotEmpty()) sb.append('=').append(escapeTagValue(v))
                }
                sb.append(' ')
            }
            sb.append(command)
            params.forEachIndexed { i, p ->
                sb.append(' ')
                if (i == params.size - 1 && (p.isEmpty() || p.contains(' ') || p.startsWith(":"))) sb.append(':')
                sb.append(p)
            }
            return sb.toString()
        }
    }
}
