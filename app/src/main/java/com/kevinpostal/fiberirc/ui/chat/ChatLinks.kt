package com.kevinpostal.fiberirc.ui.chat

import androidx.collection.LruCache

/** A tappable span in a chat line (web autolinker.ts: URLs and channel names). Port of iOS `ChatLink`. */
sealed interface ChatLink {
    data class Url(val url: String) : ChatLink

    data class Channel(val name: String) : ChatLink
}

/** A [ChatLink] found at UTF-16 offsets [range] of a plain (formatting-stripped) chat text. */
data class FoundLink(val range: IntRange, val link: ChatLink)

/**
 * Port of iOS `IRCFormatting.links(in:)`: http/https/mailto URLs and channel names in plain chat text, sorted by
 * location. iOS finds URLs with `NSDataDetector`; Android has no equivalent off the main SDK's `Linkify`, so URLs are
 * matched by scheme (`http://`, `https://`, `mailto:`) or a leading `www.` — bare `example.com` text is not linked.
 * Cached per distinct text.
 */
object ChatLinks {
    private val urlPattern = Regex("""(?i)(?<![\w@])(?:https?://|mailto:|www\.)[^\s<>"]+""")

    /** `#name` at the start or after whitespace, `[` or `(` (web autolinker.ts). */
    private val channelPattern = Regex("""(?:^|(?<=[\s\[(]))(#[^\s<>"',&;\[\]()]+)""")

    /** Numbers and colour codes look like channels but aren't linked ("#123", "#fff"). */
    private val notChannelPattern = Regex("""^#(?:\d+|[0-9a-fA-F]{3,6})$""")

    /** Trailing punctuation that ends a sentence rather than the URL (NSDataDetector drops it the same way). */
    private const val TRAILING = ".,!?:;'\"*_"

    private val cache = LruCache<String, List<FoundLink>>(2000)

    fun find(text: String): List<FoundLink> {
        cache[text]?.let { return it }
        val out = ArrayList<FoundLink>()
        for (m in urlPattern.findAll(text)) {
            var end = m.range.last + 1
            while (end > m.range.first && text[end - 1] in TRAILING) end--
            // An unmatched closing bracket ends the URL ("(see https://x.y/z)").
            while (end > m.range.first && text[end - 1] in ")]}") {
                val close = text[end - 1]
                val open = when (close) { ')' -> '('; ']' -> '['; else -> '{' }
                val body = text.substring(m.range.first, end)
                if (body.count { it == open } >= body.count { it == close }) break
                end--
                while (end > m.range.first && text[end - 1] in TRAILING) end--
            }
            val raw = text.substring(m.range.first, end)
            val lower = raw.lowercase()
            if (lower == "www." || lower.endsWith("://") || lower == "mailto:") continue
            val url = if (lower.startsWith("www.")) "http://$raw" else raw
            out += FoundLink(m.range.first until end, ChatLink.Url(url))
        }
        val urlRanges = out.map { it.range }
        for (m in channelPattern.findAll(text)) {
            val group = m.groups[1] ?: continue
            val r = group.range
            if (notChannelPattern.matches(group.value)) continue
            if (urlRanges.any { it.first <= r.last && r.first <= it.last }) continue
            out += FoundLink(r, ChatLink.Channel(group.value))
        }
        out.sortBy { it.range.first }
        cache.put(text, out)
        return out
    }
}
