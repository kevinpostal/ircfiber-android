package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.prefs.HighlightOptions
import com.kevinpostal.fiberirc.data.prefs.ModeIndicator
import com.kevinpostal.fiberirc.ui.theme.nickColor
import java.util.regex.Pattern

// Chat-row helpers that follow the account display prefs (`DisplayPrefs`), ported from `ChatDisplay.swift:58-142`
// (itself a port of the web client) so every client renders a line the same way. Main thread only.

/**
 * Port of web `lib/emoji.ts` `isEmojiOnly`: a line that is nothing but emoji (and spaces) is shown large when Enlarge
 * emoji-only messages is on.
 *
 * The iOS pattern uses `\p{Emoji_Presentation}`, `\p{Extended_Pictographic}`, `\p{Emoji_Modifier}` and
 * `\p{Regional_Indicator}`, which neither the JVM's `java.util.regex` nor Android's ICU regex (before API 29) accept
 * uniformly. The same grammar is built here from explicit code-point classes: the tables below are those properties'
 * ranges as JDK 21 (Unicode 15) reports them (`Character.isEmojiPresentation` / `isExtendedPictographic` /
 * `isEmojiModifier`), so the pattern compiles and matches the same on every API level and in JVM unit tests.
 */
object EmojiOnly {
    /** 32sp like the web's emoji-only rows, scaled with the text size up to 56sp. */
    val fontSize = 32.sp
    val maxFontSize = 56.sp

    private val EMOJI_PRESENTATION = intArrayOf(
        0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
        0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
        0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F201, 0x1F201, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F236,
        0x1F238, 0x1F23A, 0x1F250, 0x1F251, 0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393,
        0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440,
        0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596,
        0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D7,
        0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FA88, 0x1FA90, 0x1FABD, 0x1FABF, 0x1FAC5,
        0x1FACE, 0x1FADB, 0x1FAE0, 0x1FAE8, 0x1FAF0, 0x1FAF8,
    )

    private val EXTENDED_PICTOGRAPHIC = intArrayOf(
        0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328, 0x2388, 0x2388, 0x23CF, 0x23CF,
        0x23E9, 0x23F3, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0,
        0x25FB, 0x25FE, 0x2600, 0x2605, 0x2607, 0x2612, 0x2614, 0x2685, 0x2690, 0x2705, 0x2708, 0x2712,
        0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728, 0x2733, 0x2734,
        0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757,
        0x2763, 0x2767, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2934, 0x2935,
        0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030, 0x303D, 0x303D,
        0x3297, 0x3297, 0x3299, 0x3299, 0x1F000, 0x1F0FF, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F, 0x1F16C, 0x1F171,
        0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AD, 0x1F1E5, 0x1F201, 0x1F20F, 0x1F21A, 0x1F21A,
        0x1F22F, 0x1F22F, 0x1F232, 0x1F23A, 0x1F23C, 0x1F23F, 0x1F249, 0x1F3FA, 0x1F400, 0x1F53D, 0x1F546, 0x1F64F,
        0x1F680, 0x1F6FF, 0x1F774, 0x1F77F, 0x1F7D5, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F, 0x1F85A, 0x1F85F,
        0x1F888, 0x1F88F, 0x1F8AE, 0x1F8FF, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1FAFF, 0x1FC00, 0x1FFFD,
    )

    private fun cls(ranges: IntArray): String = buildString {
        append('[')
        for (i in ranges.indices step 2) append("\\x{%X}-\\x{%X}".format(ranges[i], ranges[i + 1]))
        append(']')
    }

    private val regex: Regex = run {
        val ep = cls(EMOJI_PRESENTATION)
        val xp = cls(EXTENDED_PICTOGRAPHIC)
        val em = "[\\x{1F3FB}-\\x{1F3FF}]"
        val ri = "[\\x{1F1E6}-\\x{1F1FF}]"
        // ICU `\s` (Unicode whitespace); java.util.regex's `\s` is ASCII-only.
        val ws = "[\\t\\n\\x{0B}\\f\\r\\x{85}\\p{Z}]"
        val cluster = "(?:$ri$ri|\\x{1F3F4}[\\x{E0020}-\\x{E007F}]{2,}" +
            "|[\\x{23}\\x{2A}\\x{30}-\\x{39}]\\x{FE0F}?\\x{20E3}" +
            "|(?:$ep|$xp\\x{FE0F})$em?" +
            "(?:\\x{200D}(?:$ep|$xp\\x{FE0F}?)$em?)*)"
        Regex("^(?:$ws*$cluster)+$ws*$")
    }

    fun matches(text: String): Boolean {
        val plain = IrcFormatting.strip(text)
        if (plain.isBlank()) return false
        return regex.matches(plain)
    }
}

/**
 * The account's highlight words (web `checkHighlight`, Notification Policy v2 `matchWord`): someone else's chat line
 * containing one is highlighted like a mention of your nick. [HighlightOptions] decide whole-word (nick characters
 * `A-Z a-z 0-9 _ [ ] { }` aren't boundaries) and case-sensitive matching; v1 servers: whole word, ignoring case.
 */
object HighlightMatcher {
    private const val EDGE = "[a-zA-Z0-9_\\[\\]{}]"

    /** One compiled pattern per word list and options. */
    private var cachedKey: Pair<List<String>, HighlightOptions>? = null
    private var cachedRegex: Regex? = null

    fun matches(line: IrcLine, words: List<String>, ownNick: String, options: HighlightOptions = HighlightOptions()): Boolean {
        if (words.isEmpty()) return false
        if (line.kind != IrcLineKind.MESSAGE && line.kind != IrcLineKind.ACTION && line.kind != IrcLineKind.NOTICE) return false
        val sender = line.sender
        if (sender != null && sender.equals(ownNick, ignoreCase = true)) return false
        val key = words to options
        if (cachedKey != key) {
            cachedRegex = pattern(words, options)
            cachedKey = key
        }
        val regex = cachedRegex ?: return false
        return regex.containsMatchIn(IrcFormatting.strip(line.text))
    }

    /** Whether plain [text] contains [word] under [options]. */
    fun matchesWord(text: String, word: String, options: HighlightOptions): Boolean =
        pattern(listOf(word), options)?.containsMatchIn(text) ?: false

    /** Any of [words] (trimmed, blanks dropped) under [options]; null when there is nothing to match. */
    private fun pattern(words: List<String>, options: HighlightOptions): Regex? {
        val terms = words.map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        val alternation = terms.joinToString("|") { Pattern.quote(it) }
        val body = if (options.wholeWord) "(?<!$EDGE)(?:$alternation)(?!$EDGE)" else "(?:$alternation)"
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(body, flags) }.getOrNull()
    }
}

/**
 * Port of web `buildNickMentionPattern` + `wrapTextSegment` (Colourise mentions): a nick named in chat text, with or
 * without a leading `@`, takes that nick's colour. [create] answers null when there is nothing to match.
 */
class MentionColorizer private constructor(private val pattern: Pattern) {
    /** Every mention inside [range] of [text]: the whole match (with any `@`) and the nick as written. */
    fun mentions(text: String, range: IntRange = text.indices): List<Pair<IntRange, String>> {
        if (range.isEmpty()) return emptyList()
        val m = pattern.matcher(text).region(range.first, range.last + 1)
        val out = ArrayList<Pair<IntRange, String>>()
        while (m.find()) out.add((m.start() until m.end()) to m.group(2))
        return out
    }

    /** Colours every mention inside [range] of [text] (the builder's text) with its nick colour. */
    fun apply(b: AnnotatedString.Builder, text: String, range: IntRange, dark: Boolean) {
        for ((r, nick) in mentions(text, range)) {
            b.addStyle(SpanStyle(color = nickColor(nick, dark)), r.first, r.last + 1)
        }
    }

    companion object {
        /** Candidates: the channel's members, your nick and your highlight words. */
        fun create(nicks: List<String>): MentionColorizer? {
            val seen = HashSet<String>()
            val candidates = nicks.map { it.lowercase() }
                .filter { it.isNotEmpty() && seen.add(it) }
                .sortedByDescending { it.length } // longest first, so "bobby" wins over "bob"
            if (candidates.isEmpty()) return null
            val alternation = candidates.joinToString("|") { Pattern.quote(it) }
            val p = "(?<![a-zA-Z0-9_\\[\\]{}\\\\@])(@?)($alternation)(?![a-zA-Z0-9_\\[\\]{}\\\\])"
            return runCatching {
                MentionColorizer(Pattern.compile(p, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))
            }.getOrNull()
        }
    }
}

/** The mode indicator before a sender (Settings → Display → Mode indicators): a dot, the prefix symbol, or nothing. */
object ModeGlyph {
    fun glyph(prefixes: String, mode: ModeIndicator): String? {
        val first = prefixes.firstOrNull() ?: return null
        return when (mode) {
            ModeIndicator.HIDDEN -> null
            ModeIndicator.SYMBOLS -> first.toString()
            ModeIndicator.DOTS -> "•"
        }
    }
}
