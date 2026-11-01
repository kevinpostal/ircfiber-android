package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Case-, diacritic- and width-insensitive text folding (Foundation's `[.caseInsensitive, .diacriticInsensitive,
 * .widthInsensitive]`): NFKD (fullwidth → ASCII, accents split off), combining marks dropped, lower-cased.
 */
object SearchFold {
    fun fold(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    /** Whether [text] contains [query] under the fold. */
    fun contains(text: String, query: String): Boolean = fold(text).contains(fold(query))
}

/**
 * Search in one conversation (gear → Search, ports `BufferSearch.swift`): the chat rows (messages, actions, notices)
 * whose text, as displayed with IRC formatting stripped, contains the query — case-, diacritic- and
 * width-insensitive, newest first.
 */
object BufferSearch {
    /** [ranges]: every occurrence of the query in [text] (UTF-16 offsets, for annotated strings). */
    data class Match(val line: IrcLine, val text: String, val ranges: List<IntRange>)

    /** A cut of a match's text and the occurrences wholly inside it, shifted to it. */
    data class Snippet(val text: String, val ranges: List<IntRange>) {
        fun annotated(highlight: SpanStyle): AnnotatedString = AnnotatedString.Builder(text).apply {
            for (r in ranges) addStyle(highlight, r.first, r.last + 1)
        }.toAnnotatedString()
    }

    /**
     * Rows of [lines] that contain [query], newest first. [hidden] drops rows the chat does not show (blocked
     * senders). A blank query matches nothing.
     */
    fun matches(lines: List<IrcLine>, query: String, hidden: (IrcLine) -> Boolean = { false }): List<Match> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (line in lines.asReversed()) {
            if (line.kind != IrcLineKind.MESSAGE && line.kind != IrcLineKind.ACTION && line.kind != IrcLineKind.NOTICE) continue
            if (hidden(line)) continue
            val text = IrcFormatting.strip(line.text)
            val ranges = occurrences(needle, text)
            if (ranges.isNotEmpty()) out.add(Match(line, text, ranges))
        }
        return out
    }

    /**
     * Non-overlapping occurrences of [needle] in [text], left to right, as UTF-16 ranges of [text]. Each code point of
     * [text] is folded on its own so a hit maps back to whole characters; a hit that would start or end inside one
     * character's fold (a ligature's half) is skipped. Combining marks after a hit's last character belong to it.
     */
    fun occurrences(needle: String, text: String): List<IntRange> {
        val folded = SearchFold.fold(needle)
        if (folded.isEmpty()) return emptyList()
        val hay = StringBuilder()
        // Per folded UTF-16 unit: the source code point's start and end in [text]; and whether it begins that fold.
        val srcStart = ArrayList<Int>()
        val srcEnd = ArrayList<Int>()
        val begins = ArrayList<Boolean>()
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            val f = SearchFold.fold(text.substring(i, i + n))
            for (k in f.indices) {
                hay.append(f[k]); srcStart.add(i); srcEnd.add(i + n); begins.add(k == 0)
            }
            i += n
        }
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = hay.indexOf(folded, from)
            if (at < 0) break
            val last = at + folded.length - 1
            val endsWhole = last + 1 == hay.length || begins[last + 1]
            if (!begins[at] || !endsWhole) {
                from = at + 1
                continue
            }
            var end = srcEnd[last]
            // Characters that fold to nothing (combining marks) right after the hit stay with it.
            val nextStart = if (last + 1 < hay.length) srcStart[last + 1] else text.length
            if (nextStart > end) end = nextStart
            ranges.add(srcStart[at] until end)
            from = last + 1
        }
        return ranges
    }

    /**
     * At most [maxLength] characters (grapheme clusters) of [match]'s text, around its first occurrence — a quarter of
     * the room before it — with "…" where text was cut, and the occurrences that lie wholly inside (shifted).
     */
    fun snippet(match: Match, maxLength: Int = 160): Snippet {
        val text = match.text
        val bounds = graphemeBounds(text)
        val count = bounds.size - 1
        val first = match.ranges.firstOrNull()
        if (count <= maxLength || first == null) return Snippet(text, match.ranges)
        val hit = bounds.indexOfLast { it <= first.first }.coerceAtLeast(0)
        var start = maxOf(0, hit - maxLength / 4)
        val end = minOf(start + maxLength, count)
        if (end - start < maxLength) start = maxOf(0, end - maxLength)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < count) "…" else ""
        val ws = bounds[start]
        val we = bounds[end]
        val shift = ws - prefix.length
        val ranges = match.ranges.filter { it.first >= ws && it.last < we }.map { (it.first - shift)..(it.last - shift) }
        return Snippet(prefix + text.substring(ws, we) + suffix, ranges)
    }

    /** UTF-16 offsets of every grapheme boundary of [text], 0 and `text.length` included. */
    private fun graphemeBounds(text: String): List<Int> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        while (b != BreakIterator.DONE) {
            out.add(b)
            b = it.next()
        }
        if (out.isEmpty()) out.add(0)
        return out
    }
}
