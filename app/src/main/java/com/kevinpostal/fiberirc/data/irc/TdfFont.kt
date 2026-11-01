package com.kevinpostal.fiberirc.data.irc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * TheDraw (.TDF) colour font renderer. Port of iOS `TdfFont.swift` (the web client's `tdf.ts`, itself a port of
 * tdfiglet's reader).
 *
 * Record layout (offsets relative to the record):
 *   4        name length, 5.. name (≤ 16 CP437 bytes)
 *   21       font type (0 outline, 1 block, 2 colour — only 2 supported)
 *   22       spacing between glyphs
 *   23..24   block size (u16 LE)
 *   25..212  94 × u16 LE glyph offsets for "!".."~" (0xffff = missing)
 *   213..    glyph data: width, height, then cells (CP437 char, attr) with 0x0d as row break and 0x00 ending the glyph.
 * Attribute byte: low nibble fg, bits 4–6 bg, in TheDraw's (CGA) order; mapped to mIRC colours exactly as tdfiglet.
 *
 * Resource: `assets/TdfFonts.bin`, the pack the web and iOS clients ship: u32 LE header length, JSON header
 * `{fonts:[{name,file,off,len,spacing,height}]}`, then the concatenated records.
 */
object TdfFontFormat {
    const val CHARLIST = "!\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
    const val NUM_CHARS = 94
    const val RECORD_HEADER = 213

    /** CGA order: BLK BLU GRN CYN RED MAG BRN GRY, then bright variants. */
    val FG_MIRC = intArrayOf(1, 2, 3, 10, 5, 6, 7, 15, 14, 12, 9, 11, 4, 13, 8, 0)

    /** CP437 → Unicode for the upper half; 0x20..0x7e map to themselves. */
    val CP437_HIGH = intArrayOf(
        0x00c7, 0x00fc, 0x00e9, 0x00e2, 0x00e4, 0x00e0, 0x00e5, 0x00e7, 0x00ea, 0x00eb, 0x00e8, 0x00ef, 0x00ee, 0x00ec, 0x00c4, 0x00c5,
        0x00c9, 0x00e6, 0x00c6, 0x00f4, 0x00f6, 0x00f2, 0x00fb, 0x00f9, 0x00ff, 0x00d6, 0x00dc, 0x00a2, 0x00a3, 0x00a5, 0x20a7, 0x0192,
        0x00e1, 0x00ed, 0x00f3, 0x00fa, 0x00f1, 0x00d1, 0x00aa, 0x00ba, 0x00bf, 0x2310, 0x00ac, 0x00bd, 0x00bc, 0x00a1, 0x00ab, 0x00bb,
        0x2591, 0x2592, 0x2593, 0x2502, 0x2524, 0x2561, 0x2562, 0x2556, 0x2555, 0x2563, 0x2551, 0x2557, 0x255d, 0x255c, 0x255b, 0x2510,
        0x2514, 0x2534, 0x252c, 0x251c, 0x2500, 0x253c, 0x255e, 0x255f, 0x255a, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550, 0x256c, 0x2567,
        0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256b, 0x256a, 0x2518, 0x250c, 0x2588, 0x2584, 0x258c, 0x2590, 0x2580,
        0x03b1, 0x00df, 0x0393, 0x03c0, 0x03a3, 0x03c3, 0x00b5, 0x03c4, 0x03a6, 0x0398, 0x03a9, 0x03b4, 0x221e, 0x03c6, 0x03b5, 0x2229,
        0x2261, 0x00b1, 0x2265, 0x2264, 0x2320, 0x2321, 0x00f7, 0x2248, 0x00b0, 0x2219, 0x00b7, 0x221a, 0x207f, 0x00b2, 0x25a0, 0x00a0,
    )

    fun cp437(b: Int): Char = when {
        b < 0x20 -> ' '
        b < 0x7f -> b.toChar()
        b == 0x7f -> '\u2302'
        else -> CP437_HIGH[b - 0x80].toChar()
    }
}

class TdfGlyph(
    val width: Int,
    val height: Int,
    /** Row-major `width × font.height` cells: CP437-decoded char + attribute byte. */
    val chars: CharArray,
    val attrs: IntArray,
)

class TdfFont(
    val name: String,
    val spacing: Int,
    /** Tallest glyph — every glyph's cell grid is padded to this many rows. */
    val height: Int,
    val glyphs: List<TdfGlyph?>,
)

class TdfParseException(message: String) : Exception(message)

/** Parses one font record (a .TDF file minus its 20-byte magic). Throws on non-colour fonts. */
fun parseTdfRecord(b: ByteArray): TdfFont {
    fun u(i: Int): Int = b[i].toInt() and 0xff
    val nameLen = u(4)
    val name = buildString {
        for (i in 5 until 5 + minOf(nameLen, 16)) append(TdfFontFormat.cp437(u(i)))
    }.trimEnd('\u0000')
    val type = u(21)
    if (type != 2) throw TdfParseException("unsupported type $type")
    val spacing = u(22)
    val offsets = IntArray(TdfFontFormat.NUM_CHARS) { u(25 + it * 2) or (u(26 + it * 2) shl 8) }

    var height = 0
    for (i in 0 until TdfFontFormat.NUM_CHARS) {
        if (offsets[i] == 0xffff) continue
        val p = TdfFontFormat.RECORD_HEADER + offsets[i]
        if (p + 2 > b.size) throw TdfParseException("offset past end")
        if (u(p + 1) > height) height = u(p + 1)
    }

    val glyphs = ArrayList<TdfGlyph?>(TdfFontFormat.NUM_CHARS)
    for (i in 0 until TdfFontFormat.NUM_CHARS) {
        if (offsets[i] == 0xffff) { glyphs += null; continue }
        var p = TdfFontFormat.RECORD_HEADER + offsets[i]
        val width = u(p); p++
        val gh = u(p); p++
        val chars = CharArray(width * height) { ' ' }
        val attrs = IntArray(width * height)
        var row = 0
        var col = 0
        while (p < b.size && u(p) != 0) {
            val ch = u(p); p++
            if (ch == 0x0d) { row++; col = 0; continue }
            if (p >= b.size) break
            val attr = u(p); p++
            if (row < height && col < width) {
                chars[row * width + col] = TdfFontFormat.cp437(ch)
                attrs[row * width + col] = attr
            }
            col++
        }
        glyphs += TdfGlyph(width, gh, chars, attrs)
    }
    return TdfFont(name, spacing, height, glyphs)
}

/** Always two digits per index so a following digit glyph can't extend the code. */
private fun mircCode(attr: Int): String {
    val fg = TdfFontFormat.FG_MIRC[attr and 0x0f]
    val bg = TdfFontFormat.FG_MIRC[(attr shr 4) and 0x07]
    return "\u0003${if (fg < 10) "0$fg" else "$fg"},${if (bg < 10) "0$bg" else "$bg"}"
}

data class TdfRenderOptions(
    /** Cells for a space (TDF fonts have no space glyph); default 4, plus the font's spacing. */
    val spaceWidth: Int = 4,
    /** Emit mIRC colour codes (default true); false gives plain block art. */
    val color: Boolean = true,
)

/**
 * Renders [text] as up to `font.height` lines (all-blank edge rows dropped). Characters the font lacks are
 * skipped. Colour codes are emitted on change only; blank cells on a black background are plain spaces (a bare
 * `\x03` closes any open colour first). Lines that carry colour end with `\x0F`.
 */
fun renderTdfFont(font: TdfFont, text: String, opts: TdfRenderOptions = TdfRenderOptions()): List<String> {
    // null = space slot
    val slots = ArrayList<TdfGlyph?>()
    for (c in text.graphemes()) {
        if (c == " ") { slots += null; continue }
        if (c.length != 1) continue
        val idx = TdfFontFormat.CHARLIST.indexOf(c[0])
        if (idx >= 0) font.glyphs[idx]?.let { slots += it }
    }
    val lines = ArrayList<String>()
    for (row in 0 until maxOf(font.height, 0)) {
        val out = StringBuilder()
        var last: Int? = null
        var hasOpenColor = false
        fun pad(n: Int) {
            if (n <= 0) return
            if (last != null) { out.append('\u0003'); last = null }
            repeat(n) { out.append(' ') }
        }
        for ((gi, g) in slots.withIndex()) {
            if (g == null) {
                pad(opts.spaceWidth)
            } else {
                for (x in 0 until g.width) {
                    val ch = g.chars[row * g.width + x]
                    val attr = g.attrs[row * g.width + x]
                    if (!opts.color) { out.append(ch); continue }
                    if (ch == ' ' && ((attr shr 4) and 0x07) == 0) { pad(1); continue }
                    if (attr != last) { out.append(mircCode(attr)); last = attr; hasOpenColor = true }
                    out.append(ch)
                }
            }
            if (gi < slots.size - 1) pad(font.spacing)
        }
        var s = out.toString().trimEnd(' ')
        if (s.endsWith('\u0003')) s = s.dropLast(1)
        if (opts.color && (hasOpenColor || s.contains('\u0003'))) s += "\u000F"
        lines += s
    }
    while (lines.isNotEmpty() && lines.first().trimUnicodeWhitespace().isEmpty()) lines.removeAt(0)
    while (lines.isNotEmpty() && lines.last().trimUnicodeWhitespace().isEmpty()) lines.removeAt(lines.size - 1)
    return lines
}

@Serializable
data class TdfFontMeta(
    val name: String,
    val file: String,
    /** Byte offset of the record, relative to the pack's data start. */
    val off: Int,
    val len: Int,
    val spacing: Int,
    val height: Int,
)

object TdfCatalog {
    @Serializable
    private data class PackHeader(val fonts: List<TdfFontMeta>)

    private class Pack(val fonts: List<TdfFontMeta>, val data: ByteArray, val dataStart: Int)

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loaded: Pack? = null

    /** The decoded pack; retried on each call until the asset is readable (e.g. before `ArtFontAssets.install`). */
    private fun pack(): Pack {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val buf = ArtFontAssets.open("TdfFonts.bin") ?: throw TdfParseException("resource missing")
            if (buf.size < 4) throw TdfParseException("bad header")
            val headerLen = (buf[0].toInt() and 0xff) or ((buf[1].toInt() and 0xff) shl 8) or
                ((buf[2].toInt() and 0xff) shl 16) or ((buf[3].toInt() and 0xff) shl 24)
            if (headerLen < 0 || buf.size < 4 + headerLen) throw TdfParseException("bad header")
            val header = json.decodeFromString<PackHeader>(String(buf, 4, headerLen, Charsets.UTF_8))
            return Pack(header.fonts, buf, 4 + headerLen).also { loaded = it }
        }
    }

    /** Every packed font, in pack order (sorted by file name at build time). */
    fun listFonts(): List<TdfFontMeta> = pack().fonts

    private val parsedCache = ConcurrentHashMap<String, TdfFont>()

    /** Renders [text] in a packed font (TheDraw name or source file stem); IRC-coloured lines. */
    fun render(text: String, fontName: String): List<String> {
        val p = pack()
        val meta = p.fonts.firstOrNull { it.name == fontName || it.file == fontName }
            ?: throw TdfParseException("font missing: $fontName")
        val font = parsedCache.getOrPut(meta.name) {
            val from = p.dataStart + meta.off
            parseTdfRecord(p.data.copyOfRange(from, from + meta.len))
        }
        return renderTdfFont(font, text)
    }
}
