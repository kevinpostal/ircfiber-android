package com.kevinpostal.fiberirc.data.irc

import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.text.BreakIterator

/**
 * One run of text with its resolved formatting. `fg`/`bg` null = the caller's default / no background.
 * `fgCode`/`bgCode` are the mIRC 0–98 codes behind `fg`/`bg` when they came from `\x03` (null for hex, ANSI
 * and no colour).
 */
data class IrcRun(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val reverse: Boolean,
    val fg: Color?,
    val bg: Color?,
    val fgCode: Int?,
    val bgCode: Int?,
)

/**
 * Port of iOS `IRCFormatting` (itself a port of the web client's `parseIrcFormatting`): mIRC toggles,
 * `\x03` 0–98 colours, `\x04` hex colours, `\x0F` reset and ANSI CSI SGR (16 / 256 / truecolor).
 * Non-SGR CSI sequences and bare ESC are dropped.
 */
object IrcFormatting {
    /** String-annotation tags holding a run's mIRC colour code (0–98) on [toAnnotatedString] output. */
    const val FOREGROUND_TAG = "com.ircfiber.ircForeground"
    const val BACKGROUND_TAG = "com.ircfiber.ircBackground"

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val reverse: Boolean = false,
        val fg: Color? = null,
        val bg: Color? = null,
        val fgCode: Int? = null,
        val bgCode: Int? = null,
    )

    // region Parsing

    fun parse(text: String): List<IrcRun> {
        val s = text.codePoints().toArray()
        val runs = ArrayList<IrcRun>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var reverse = false
        var fg: Int? = null
        var bg: Int? = null
        var hexFg: Color? = null
        var hexBg: Color? = null
        var current = Style()

        fun flush() {
            if (buf.isEmpty()) return
            runs += IrcRun(
                buf.toString(), current.bold, current.italic, current.underline, current.strike, current.reverse,
                current.fg, current.bg, current.fgCode, current.bgCode,
            )
            buf.setLength(0)
        }

        var i = 0
        while (i < s.size) {
            val ch = s[i]
            var changed = true
            when (ch) {
                0x02 -> { bold = !bold; i++ }
                0x1D -> { italic = !italic; i++ }
                0x1F -> { underline = !underline; i++ }
                0x16 -> { reverse = !reverse; i++ }
                0x1E -> { strike = !strike; i++ }
                0x11 -> { changed = false; i++ } // monospace: the app is already monospace
                0x0F -> {
                    bold = false; italic = false; underline = false; strike = false; reverse = false
                    fg = null; bg = null; hexFg = null; hexBg = null
                    i++
                }
                0x03 -> {
                    i++
                    val (f, afterF) = digits(s, i, 2)
                    i = afterF
                    var b: Int? = null
                    // mIRC rule: the comma belongs to the code only when fg digits precede and a bg digit follows.
                    if (f != null && i + 1 < s.size && s[i] == ','.code && isDigit(s[i + 1])) {
                        i++
                        val (bv, afterB) = digits(s, i, 2)
                        b = bv
                        i = afterB
                    }
                    if (f != null) {
                        fg = f
                        if (b != null) bg = b
                    } else {
                        fg = null; bg = null
                    }
                    hexFg = null; hexBg = null
                }
                0x04 -> {
                    i++
                    val (hf, afterF) = hexDigits(s, i)
                    i = afterF
                    var hb: Color? = null
                    if (i < s.size && s[i] == ','.code) {
                        i++
                        val (hbv, afterB) = hexDigits(s, i)
                        hb = hbv
                        i = afterB
                    }
                    hexFg = hf; hexBg = hb
                    fg = null; bg = null
                }
                0x1B -> {
                    i++
                    changed = false
                    if (i < s.size && s[i] == '['.code) {
                        i++
                        val params = StringBuilder()
                        while (i < s.size && s[i] in 0x20..0x3F) {
                            params.appendCodePoint(s[i]); i++
                        }
                        val final = if (i < s.size) s[i] else 0
                        if (final in 0x40..0x7E) i++
                        if (final == 0x6D) { // only SGR ('m') changes state
                            changed = true
                            val p: List<Int?> =
                                if (params.isEmpty()) listOf(0) else params.split(';').map(::jsParseInt)
                            var k = 0
                            sgr@ while (k < p.size) {
                                val n = p[k]
                                if (n != null) {
                                    when (n) {
                                        0 -> {
                                            bold = false; italic = false; underline = false; reverse = false; strike = false
                                            fg = null; bg = null; hexFg = null; hexBg = null
                                        }
                                        1 -> bold = true
                                        3 -> italic = true
                                        4 -> underline = true
                                        7 -> reverse = true
                                        9 -> strike = true
                                        22 -> bold = false
                                        23 -> italic = false
                                        24 -> underline = false
                                        27 -> reverse = false
                                        29 -> strike = false
                                        in 30..37 -> { hexFg = ansi16[n - 30]; fg = null }
                                        in 90..97 -> { hexFg = ansi16[n - 90 + 8]; fg = null }
                                        39 -> { hexFg = null; fg = null }
                                        in 40..47 -> { hexBg = ansi16[n - 40]; bg = null }
                                        in 100..107 -> { hexBg = ansi16[n - 100 + 8]; bg = null }
                                        49 -> { hexBg = null; bg = null }
                                        38, 48 -> {
                                            val mode = if (k + 1 < p.size) p[k + 1] else null
                                            val c: Color
                                            if (mode == 2 && k + 4 < p.size) {
                                                c = rgb((byte(p[k + 2]) shl 16) or (byte(p[k + 3]) shl 8) or byte(p[k + 4]))
                                                k += 4
                                            } else if (mode == 5 && k + 2 < p.size) {
                                                c = ansi256(minOf(255, maxOf(0, p[k + 2] ?: 0)))
                                                k += 2
                                            } else {
                                                break@sgr
                                            }
                                            if (n == 38) { hexFg = c; fg = null } else { hexBg = c; bg = null }
                                        }
                                        else -> Unit
                                    }
                                }
                                k++
                            }
                        }
                    } // bare ESC: dropped
                }
                else -> {
                    changed = false
                    buf.appendCodePoint(ch); i++
                }
            }
            if (changed) {
                val fgCode = if (hexFg == null) fg?.takeIf { color(it) != null } else null
                val bgCode = if (hexBg == null) bg?.takeIf { color(it) != null } else null
                val next = Style(
                    bold, italic, underline, strike, reverse,
                    fg = hexFg ?: fg?.let(::color),
                    bg = hexBg ?: bg?.let(::color),
                    fgCode = fgCode, bgCode = bgCode,
                )
                if (next != current) { flush(); current = next }
            }
        }
        flush()
        return runs
    }

    fun strip(text: String): String = parse(text).joinToString("") { it.text }

    /**
     * Chat-text rendering (iOS `attributed`). `colors` false (Format colours off): mIRC/ANSI colours give way to
     * [color] on the [canvas]; reverse still swaps the two. Bold, italic, underline and strike always apply.
     * [legible] keeps an explicit foreground on the bare canvas readable (pass the theme's `legible`).
     * Base font family/size come from the caller's text style.
     */
    fun toAnnotatedString(
        text: String,
        color: Color,
        canvas: Color,
        colors: Boolean = true,
        legible: (Color) -> Color = { it },
    ): AnnotatedString {
        val out = AnnotatedString.Builder()
        for (run in parse(text)) {
            var fg = run.fg ?: color
            var bg = run.bg
            if (!colors) {
                fg = if (run.reverse) canvas else color
                bg = if (run.reverse) color else null
            } else if (run.reverse) {
                fg = run.bg ?: canvas
                bg = run.fg ?: color
            } else if (run.fg != null && bg == null) {
                // A colour on the canvas itself: keep it readable. Explicit fg+bg pairs and reverse runs stay as authored.
                fg = legible(run.fg)
            }
            val decorations = buildList {
                if (run.underline) add(TextDecoration.Underline)
                if (run.strike) add(TextDecoration.LineThrough)
            }
            val start = out.length
            out.pushStyle(
                SpanStyle(
                    color = fg,
                    background = bg ?: Color.Unspecified,
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                ),
            )
            out.append(run.text)
            out.pop()
            run.fgCode?.let { out.addStringAnnotation(FOREGROUND_TAG, it.toString(), start, out.length) }
            run.bgCode?.let { out.addStringAnnotation(BACKGROUND_TAG, it.toString(), start, out.length) }
        }
        return out.toAnnotatedString()
    }

    // endregion

    // region Block art

    private val blockArtCache = LruCache<String, Boolean>(2000)
    private val artRowCache = LruCache<String, ArtRowEntry>(500)

    private class ArtRowEntry(val row: ArtRow?)

    /** Port of the single-line rules of MessageRow.svelte `containsBlockArt`. Cached per distinct text. */
    fun isBlockArt(text: String): Boolean {
        blockArtCache[text]?.let { return it }
        val result = computeIsBlockArt(text)
        blockArtCache.put(text, result)
        return result
    }

    private fun computeIsBlockArt(text: String): Boolean {
        var escBrackets = 0
        var colorCodes = 0
        var artGlyphs = 0
        var hasDotHashBlock = false
        var hasDoubleSpace = false
        var prevEsc = false
        var prevSpace = false
        var i = 0
        while (i < text.length) {
            val v = text.codePointAt(i)
            i += Character.charCount(v)
            if (v in 0x2580..0x259F || v in 0x1FB00..0x1FBFF || v in 0x1CC00..0x1CEBF) return true
            if (prevEsc && v == 0x5B) escBrackets++
            prevEsc = v == 0x1B
            if (v == 0x03 || v == 0x04) colorCodes++
            if (v == 0x2E || v == 0x23) { artGlyphs++; hasDotHashBlock = true }
            val space = isUnicodeWhitespace(v)
            if (space && prevSpace) hasDoubleSpace = true
            prevSpace = space
        }
        if (escBrackets >= 6) return true
        if (colorCodes >= 6 && text.graphemeCount() >= 30) {
            if (hasDotHashBlock && hasDoubleSpace) return true
            if (artGlyphs >= 8) return true
        }
        return false
    }

    /**
     * Grid model for an art line; null when [text] is not block art. [chatText]/[canvas] are the theme's default
     * text colour and background (reverse resolves against them). Cached per distinct text + colours.
     */
    fun artRow(text: String, chatText: Color, canvas: Color): ArtRow? {
        val key = "${chatText.value}|${canvas.value}|$text"
        artRowCache[key]?.let { return it.row }
        val row = if (isBlockArt(text)) ArtRow.build(parse(text), chatText, canvas) else null
        artRowCache.put(key, ArtRowEntry(row))
        return row
    }

    // endregion

    // region Palettes

    /** mIRC 0–98 (IRC_COLORS 0–15 + EXTENDED_COLORS 16–98); 99 / out of range → null. */
    fun color(code: Int): Color? = if (code in palette.indices) paletteColors[code] else null

    /** 0xRRGGBB of mIRC [code], or null (99 / out of range). */
    fun colorHex(code: Int): Int? = if (code in palette.indices) palette[code] else null

    private val palette = intArrayOf(
        0xffffff, 0x000000, 0x00007f, 0x009300, 0xff0000, 0x7f0000, 0x9c009c, 0xfc7f00,
        0xffff00, 0x00fc00, 0x009393, 0x00ffff, 0x0000fc, 0xff00ff, 0x7f7f7f, 0xd2d2d2,
        0x470000, 0x472100, 0x474700, 0x324700, 0x004700, 0x00472c, 0x004747, 0x002747,
        0x000047, 0x2e0047, 0x470047, 0x47002a, 0x740000, 0x743a00, 0x747400, 0x517400,
        0x007400, 0x007449, 0x007474, 0x004074, 0x000074, 0x4b0074, 0x740074, 0x740045,
        0xb50000, 0xb56300, 0xb5b500, 0x7db500, 0x00b500, 0x00b571, 0x00b5b5, 0x0063b5,
        0x0000b5, 0x7500b5, 0xb500b5, 0xb5006b, 0xff0000, 0xff8c00, 0xffff00, 0xb2ff00,
        0x00ff00, 0x00ffa0, 0x00ffff, 0x008cff, 0x0000ff, 0xa500ff, 0xff00ff, 0xff0098,
        0xff5959, 0xffb459, 0xffff71, 0xcfff60, 0x6fff6f, 0x65ffc9, 0x6dffff, 0x59b4ff,
        0x5959ff, 0xc459ff, 0xff66ff, 0xff59bc, 0xff9c9c, 0xffd39c, 0xffff9c, 0xe2ff9c,
        0x9cff9c, 0x9cffdb, 0x9cffff, 0x9cd3ff, 0x9c9cff, 0xdc9cff, 0xff9cff, 0xff94d3,
        0x000000, 0x131313, 0x282828, 0x363636, 0x4d4d4d, 0x656565, 0x818181, 0x9f9f9f,
        0xbcbcbc, 0xe2e2e2, 0xffffff,
    )
    private val paletteColors: Array<Color> = Array(palette.size) { rgb(palette[it]) }

    /** xterm defaults for SGR 30–37 / 90–97 (fg) and 40–47 / 100–107 (bg). */
    private val ansi16Hex = intArrayOf(
        0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
        0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
    )
    private val ansi16: Array<Color> = Array(ansi16Hex.size) { rgb(ansi16Hex[it]) }

    /** xterm 256-colour index → colour (16 base, 6×6×6 cube, 24 greys). */
    fun ansi256(n: Int): Color {
        if (n < 16) return ansi16[n]
        if (n < 232) {
            val idx = n - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            return rgb((steps[idx / 36] shl 16) or (steps[(idx / 6) % 6] shl 8) or steps[idx % 6])
        }
        val v = 8 + (n - 232) * 10
        return rgb((v shl 16) or (v shl 8) or v)
    }

    private fun rgb(hex: Int): Color = Color(0xFF000000.toInt() or hex)

    // endregion

    // region Scanning helpers

    private fun isDigit(cp: Int) = cp in 0x30..0x39

    /** Up to [max] ASCII digits at [start]; returns (value or null when none, index after). */
    private fun digits(s: IntArray, start: Int, max: Int): Pair<Int?, Int> {
        var i = start
        var n = 0
        var count = 0
        while (count < max && i < s.size && isDigit(s[i])) {
            n = n * 10 + (s[i] - 0x30); i++; count++
        }
        return (if (count > 0) n else null) to i
    }

    private fun hexValue(cp: Int): Int = when (cp) {
        in 0x30..0x39 -> cp - 0x30
        in 0x41..0x46 -> cp - 0x41 + 10
        in 0x61..0x66 -> cp - 0x61 + 10
        else -> -1
    }

    /** Up to 6 hex digits; a colour only when exactly 6 were present. */
    private fun hexDigits(s: IntArray, start: Int): Pair<Color?, Int> {
        var i = start
        var n = 0
        var count = 0
        while (count < 6 && i < s.size) {
            val d = hexValue(s[i])
            if (d < 0) break
            n = n * 16 + d; i++; count++
        }
        return (if (count == 6) rgb(n) else null) to i
    }

    /** JS `parseInt(s, 10)` on an SGR parameter: "" → 0, leading digits → value, otherwise NaN (null). */
    private fun jsParseInt(sub: String): Int? {
        if (sub.isEmpty()) return 0
        val lead = sub.takeWhile { it in '0'..'9' }
        return if (lead.isEmpty()) null else lead.take(9).toInt()
    }

    /** JS `(v | 0) & 0xff`. */
    private fun byte(v: Int?): Int = (v ?: 0) and 0xff

    // endregion
}

/** One art cell. [fg]/[bg] already account for reverse; [bg] null = the art canvas shows through. */
data class ArtGlyph(
    val char: String,
    val fg: Color,
    val bg: Color?,
    val bold: Boolean,
    val underline: Boolean,
)

/** One art line as a grid of cells: one glyph per grapheme cluster, reverse already applied. */
class ArtRow(val glyphs: List<ArtGlyph>) {
    val columns: Int get() = glyphs.size

    companion object {
        fun build(runs: List<IrcRun>, chatText: Color, canvas: Color): ArtRow {
            val glyphs = ArrayList<ArtGlyph>()
            for (run in runs) {
                var fg = run.fg ?: chatText
                var bg = run.bg
                if (run.reverse) {
                    fg = run.bg ?: canvas
                    bg = run.fg ?: chatText
                }
                for (c in run.text.graphemes()) glyphs += ArtGlyph(c, fg, bg, run.bold, run.underline)
            }
            return ArtRow(glyphs)
        }
    }
}

// region Text helpers shared by the formatting/compose-style ports (Swift `Character` = grapheme cluster)

/** Unicode White_Space (Swift `Character.isWhitespace` / `CharacterSet.whitespacesAndNewlines`). */
internal fun isUnicodeWhitespace(cp: Int): Boolean = when (cp) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimUnicodeWhitespace(): String {
    var start = 0
    var end = length
    while (start < end) {
        val cp = codePointAt(start)
        if (!isUnicodeWhitespace(cp)) break
        start += Character.charCount(cp)
    }
    while (end > start) {
        val cp = codePointBefore(end)
        if (!isUnicodeWhitespace(cp)) break
        end -= Character.charCount(cp)
    }
    return substring(start, end)
}

/** Extended grapheme clusters (Swift `Array(string)`). */
internal fun String.graphemes(): List<String> {
    if (isEmpty()) return emptyList()
    val it = BreakIterator.getCharacterInstance()
    it.setText(this)
    val out = ArrayList<String>(length)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out += substring(start, end)
        start = end
        end = it.next()
    }
    return out
}

/** Swift `String.count`. */
internal fun String.graphemeCount(): Int {
    if (isEmpty()) return 0
    val it = BreakIterator.getCharacterInstance()
    it.setText(this)
    var n = 0
    while (it.next() != BreakIterator.DONE) n++
    return n
}

// endregion
