package com.kevinpostal.fiberirc.data.irc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

/**
 * FIGlet (.flf) banner font parser and renderer, port of iOS `FigletFont.swift`. Shares the exact `.flf` files
 * (under `assets/figlet`, copied verbatim from the iOS bundle).
 *
 * Layout mode: every font is rendered with **kerning** (characters pushed together until they touch, never
 * overlapping) rather than each font's own declared smushing rules — same as iOS.
 */
class FigletParseException(message: String) : Exception(message)

class FigletFont(
    val hardblank: String,
    val height: Int,
    val baseline: Int,
    val maxLength: Int,
    val oldLayout: Int,
    /** `glyphs[code]` is `height` rows of that character's stored width, hardblank already replaced with a space. */
    val glyphs: Map<Int, List<String>>,
)

object FigletCatalog {
    @Volatile
    private var cachedNames: List<String>? = null

    /** Every bundled font's file stem (e.g. "Standard"), case-insensitively sorted. Empty until assets are installed. */
    val names: List<String>
        get() = cachedNames ?: ArtFontAssets.list(FIGLET_DIR)
            .filter { it.endsWith(".flf") }
            .map { it.removeSuffix(".flf") }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .also { if (it.isNotEmpty()) cachedNames = it }

    private val cache = ConcurrentHashMap<String, FigletFont>()

    /** Rows a font's glyphs occupy (its declared height); 0 if the font failed to load. */
    private val rowsCache = ConcurrentHashMap<String, Int>()

    fun rows(name: String): Int = rowsCache.getOrPut(name) {
        runCatching { font(name).height }.getOrDefault(0)
    }

    fun font(name: String): FigletFont {
        cache[name]?.let { return it }
        val bytes = ArtFontAssets.open("$FIGLET_DIR/$name.flf") ?: throw FigletParseException("missing resource $name")
        // Block-glyph fonts (ANSI Regular, TOIlet "tlf2a" files) are UTF-8; classic FIGlet files are Latin-1, the
        // fallback when the bytes aren't valid UTF-8. A UTF-8 BOM would hide the "flf2" signature.
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val text = decodeUtf8OrLatin1(bytes, start)
        val font = parseFiglet(text)
        cache[name] = font
        rowsCache[name] = font.height
        return font
    }

    private const val FIGLET_DIR = "figlet"
}

private fun decodeUtf8OrLatin1(bytes: ByteArray, start: Int): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, start, bytes.size - start, Charsets.ISO_8859_1)
    }
}

/** The 102 characters every FIGfont must define: ASCII 32–126, then Ä Ö Ü ä ö ü ß in FIGfont's fixed order. */
private val requiredCodes: List<Int> = (32..126).toList() + listOf(196, 214, 220, 228, 246, 252, 223)

fun parseFiglet(text: String): FigletFont {
    val lines = text.split("\n")
    // CRLF files leave "\r" on the header's last field, so trim it.
    val header = lines.firstOrNull()?.trimUnicodeWhitespace() ?: throw FigletParseException("bad header")
    if (!header.startsWith("flf2") && !header.startsWith("tlf2")) throw FigletParseException("bad header")
    // "flf2a$ height baseline maxlen oldlayout commentlines [printdir [fulllayout [codetagcount]]]"
    val sigEnd = header.indexOf(' ')
    if (sigEnd < 0) throw FigletParseException("bad header")
    val signature = header.substring(0, sigEnd)
    val hardblank = signature.graphemes().lastOrNull() ?: throw FigletParseException("bad header")
    val fields = header.substring(sigEnd + 1).split(' ').filter { it.isNotEmpty() }
    if (fields.size < 5) throw FigletParseException("bad header")
    val height = fields[0].toIntOrNull()
    val baseline = fields[1].toIntOrNull()
    val maxLength = fields[2].toIntOrNull()
    val oldLayout = fields[3].toIntOrNull()
    val commentLines = fields[4].toIntOrNull()
    if (height == null || baseline == null || maxLength == null || oldLayout == null || commentLines == null) {
        throw FigletParseException("bad header")
    }

    // Glyph lines start after the header line.
    var idx = 1 + commentLines
    val glyphs = HashMap<Int, List<String>>()

    fun readGlyph(): List<String>? {
        val rows = ArrayList<String>(maxOf(height, 0))
        repeat(height) {
            if (idx >= lines.size) return null
            var row = lines[idx]; idx++
            // Strip trailing CR then the font's own end-mark run (one or two of the same trailing character).
            if (row.endsWith("\r")) row = row.dropLast(1)
            if (row.isNotEmpty()) {
                val mark = String(Character.toChars(row.codePointBefore(row.length)))
                while (row.endsWith(mark)) row = row.dropLast(mark.length)
            }
            rows += row.replace(hardblank, " ")
        }
        return rows
    }

    for (code in requiredCodes) {
        val g = readGlyph() ?: break
        glyphs[code] = g
    }
    // Code-tagged (non-ASCII) glyphs beyond the required 102 aren't read; unlisted characters are skipped at render.
    return FigletFont(hardblank, height, baseline, maxLength, oldLayout, glyphs)
}

// region Rendering

private fun trailingBlanks(s: String): Int {
    var n = 0
    for (i in s.indices.reversed()) if (s[i] == ' ') n++ else break
    return n
}

private fun leadingBlanks(s: String): Int {
    var n = 0
    for (c in s) if (c == ' ') n++ else break
    return n
}

/** One character's `height` rows, already stripped of end-marks and hardblanks. */
private fun rowsFor(font: FigletFont, ch: String): List<String>? {
    if (ch.isEmpty()) return null
    return font.glyphs[ch.codePointAt(0)]
}

/** Kerns [rows] (one character) onto [acc] (the banner so far), in place. Both have `font.height` rows. */
private fun kernOnto(acc: Array<String>, rows: List<String>, fullWidth: Boolean) {
    if (fullWidth) {
        for (r in acc.indices) acc[r] += rows[r]
        return
    }
    val shift = acc.indices.minOfOrNull { trailingBlanks(acc[it]) + leadingBlanks(rows[it]) } ?: 0
    for (r in acc.indices) {
        val t = minOf(shift, trailingBlanks(acc[r]))
        acc[r] = acc[r].dropLast(t)
        // shift - t ≤ leading blanks of this row, so dropping UTF-16 units only ever drops spaces.
        acc[r] += rows[r].drop(minOf(shift - t, rows[r].length))
    }
}

private fun renderWord(font: FigletFont, word: String): Array<String> {
    val acc = Array(font.height) { "" }
    val fullWidth = font.oldLayout == -1
    for (ch in word.graphemes()) {
        val rows = rowsFor(font, ch) ?: continue
        kernOnto(acc, rows, fullWidth)
    }
    return acc
}

private fun bannerWidth(banner: Array<String>): Int = banner.maxOfOrNull { it.graphemeCount() } ?: 0

/**
 * Renders [text] in [fontName]; lines are right-trimmed and blank edge rows dropped. [width] folds between words so
 * a banner line never exceeds that many columns (never mid-glyph) — pass 400 for effectively-unwrapped chat art.
 */
fun renderFiglet(text: String, fontName: String, width: Int = 400): String {
    val font = FigletCatalog.font(fontName)
    val words = text.split(' ')
    val outputLines = ArrayList<String>()
    var current = Array(font.height) { "" }
    var currentWidth = 0
    var started = false

    fun flush() {
        if (!started) return
        for (row in current) outputLines += row.trimEnd(' ')
    }

    for (word in words) {
        if (word.isEmpty()) continue
        val rendered = renderWord(font, word)
        val renderedWidth = bannerWidth(rendered)
        val spaceRows = rowsFor(font, " ")
        val spaceWidth = when {
            !started -> 0
            spaceRows == null -> 1
            else -> spaceRows.firstOrNull()?.graphemeCount() ?: 1
        }
        if (started && currentWidth + spaceWidth + renderedWidth > width) {
            flush()
            current = Array(font.height) { "" }
            currentWidth = 0
            started = false
        }
        if (started && spaceRows != null) {
            kernOnto(current, spaceRows, font.oldLayout == -1)
        } else if (started) {
            for (r in current.indices) current[r] += " "
        }
        for (r in current.indices) current[r] += rendered[r]
        currentWidth = bannerWidth(current)
        started = true
    }
    flush()

    while (outputLines.isNotEmpty() && outputLines.first().trimUnicodeWhitespace().isEmpty()) outputLines.removeAt(0)
    while (outputLines.isNotEmpty() && outputLines.last().trimUnicodeWhitespace().isEmpty()) outputLines.removeAt(outputLines.size - 1)
    return outputLines.joinToString("\n")
}

// endregion
