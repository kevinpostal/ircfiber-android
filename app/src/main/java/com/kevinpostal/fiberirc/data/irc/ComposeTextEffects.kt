package com.kevinpostal.fiberirc.data.irc

import kotlin.random.Random

/**
 * Pure string transforms for outgoing-message styling. Port of iOS `ComposeTextEffects.swift` (the web
 * client's `textEffects.ts`) — algorithms and lookup tables match exactly. A Swift `Character` is one
 * grapheme cluster here.
 */
object ComposeTextEffects {

    // region Case

    fun applyCase(text: String, mode: CaseMode): String = when (mode) {
        CaseMode.UPPER -> text.uppercase()
        CaseMode.LOWER -> text.lowercase()
        CaseMode.MOCKING -> {
            val out = StringBuilder()
            var upper = false
            for (ch in text.graphemes()) {
                val lo = ch.lowercase()
                val up = ch.uppercase()
                if (lo == up) { out.append(ch); continue } // not a letter: does not advance the toggle
                out.append(if (upper) up else lo)
                upper = !upper
            }
            out.toString()
        }
        CaseMode.NONE -> text
    }

    // endregion

    // region Unicode pseudo-fonts

    private const val A = 0x41
    private const val a = 0x61
    private const val ZERO = 0x30

    private fun mapAlnum(cp: Int, upperBase: Int?, lowerBase: Int?, digitBase: Int?, ex: Map<Int, Int> = emptyMap()): Int? {
        ex[cp]?.let { return it }
        if (upperBase != null && cp >= A && cp <= A + 25) return upperBase + (cp - A)
        if (lowerBase != null && cp >= a && cp <= a + 25) return lowerBase + (cp - a)
        if (digitBase != null && cp >= ZERO && cp <= ZERO + 9) return digitBase + (cp - ZERO)
        return null
    }

    private val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ".graphemes()
    private val FLIP_LOWER = "ɐqɔpǝɟƃɥıɾʞlɯuodbɹsʇnʌʍxʎz".graphemes()
    private val FLIP_UPPER = "∀ꓭƆᗡƎℲ⅁HIſꓘ⅂WNOԀὉᴚS⊥∩ΛMX⅄Z".graphemes()
    private val FLIP_DIGIT = "0ƖᄅƐㄣϛ9ㄥ86".graphemes()
    private val FLIP_PUNCT = mapOf(
        "." to "˙", "," to "'", "'" to ",", "?" to "¿", "!" to "¡",
        "(" to ")", ")" to "(", "[" to "]", "]" to "[", "{" to "}", "}" to "{", "<" to ">", ">" to "<", "_" to "‾",
    )
    private val ITALIC_EX = mapOf(0x68 to 0x210e)
    private val DOUBLE_STRUCK_EX = mapOf(
        0x43 to 0x2102, 0x48 to 0x210d, 0x4e to 0x2115, 0x50 to 0x2119, 0x51 to 0x211a, 0x52 to 0x211d, 0x5a to 0x2124,
    )

    private fun firstCp(ch: String): Int = if (ch.isEmpty()) 0 else ch.codePointAt(0)

    fun applyUnicodeStyle(text: String, style: UnicodeStyle): String {
        val out = StringBuilder()
        when (style) {
            UnicodeStyle.SMALL_CAPS -> {
                for (ch in text.lowercase().graphemes()) {
                    val cp = firstCp(ch)
                    if (cp >= a && cp <= a + 25) out.append(SMALL_CAPS[cp - a]) else out.append(ch)
                }
            }
            UnicodeStyle.UPSIDE_DOWN -> {
                val flipped = ArrayList<String>()
                for (ch in text.graphemes()) {
                    val cp = firstCp(ch)
                    flipped += when {
                        cp >= a && cp <= a + 25 -> FLIP_LOWER[cp - a]
                        cp >= A && cp <= A + 25 -> FLIP_UPPER[cp - A]
                        cp >= ZERO && cp <= ZERO + 9 -> FLIP_DIGIT[cp - ZERO]
                        else -> FLIP_PUNCT[ch] ?: ch
                    }
                }
                for (i in flipped.indices.reversed()) out.append(flipped[i])
            }
            UnicodeStyle.FULLWIDTH -> {
                for (ch in text.graphemes()) {
                    val cp = firstCp(ch)
                    when {
                        cp == 0x20 -> out.append('\u3000')
                        cp in 0x21..0x7e -> out.appendCodePoint(0xff01 + (cp - 0x21))
                        else -> out.append(ch)
                    }
                }
            }
            else -> {
                for (ch in text.graphemes()) {
                    val cp = firstCp(ch)
                    val m: Int? = when (style) {
                        UnicodeStyle.BOLD -> mapAlnum(cp, 0x1d400, 0x1d41a, 0x1d7ce)
                        UnicodeStyle.ITALIC -> mapAlnum(cp, 0x1d434, 0x1d44e, null, ITALIC_EX)
                        UnicodeStyle.BOLD_ITALIC -> mapAlnum(cp, 0x1d468, 0x1d482, null)
                        UnicodeStyle.SCRIPT -> mapAlnum(cp, 0x1d4d0, 0x1d4ea, null)
                        UnicodeStyle.FRAKTUR -> mapAlnum(cp, 0x1d56c, 0x1d586, null)
                        UnicodeStyle.DOUBLE_STRUCK -> mapAlnum(cp, 0x1d538, 0x1d552, 0x1d7d8, DOUBLE_STRUCK_EX)
                        UnicodeStyle.MONOSPACE -> mapAlnum(cp, 0x1d670, 0x1d68a, 0x1d7f6)
                        UnicodeStyle.CIRCLED -> when {
                            cp == ZERO -> 0x24ea
                            cp > ZERO && cp <= ZERO + 9 -> 0x2460 + (cp - ZERO - 1)
                            else -> mapAlnum(cp, 0x24b6, 0x24d0, null)
                        }
                        else -> null
                    }
                    if (m != null && Character.isValidCodePoint(m)) out.appendCodePoint(m) else out.append(ch)
                }
            }
        }
        return out.toString()
    }

    // endregion

    // region Zalgo

    /** [strength] 1–3; anything else (0 = off) returns [text] unchanged. */
    fun applyZalgo(text: String, strength: Int, random: Random = Random.Default): String {
        val range = when (strength) {
            1 -> 1..2
            2 -> 2..4
            3 -> 4..8
            else -> return text
        }
        val out = StringBuilder()
        for (ch in text.graphemes()) {
            out.append(ch)
            if (ch.codePoints().allMatch(::isUnicodeWhitespace)) continue
            val k = random.nextInt(range.first, range.last + 1)
            repeat(k) { out.appendCodePoint(0x300 + random.nextInt(0, 0x70)) }
        }
        return out.toString()
    }

    // endregion

    // region Colour

    private val rgbTable: List<DoubleArray> = (0..98).map { code ->
        val hex = IrcFormatting.colorHex(code) ?: 0
        doubleArrayOf(((hex shr 16) and 0xff).toDouble(), ((hex shr 8) and 0xff).toDouble(), (hex and 0xff).toDouble())
    }

    /** Nearest mIRC 0–98 code to an RGB triple (squared Euclidean distance over the palette). */
    fun nearestIrcColor(r: Double, g: Double, b: Double): Int {
        var best = 0
        var bestD = Double.POSITIVE_INFINITY
        for ((code, c) in rgbTable.withIndex()) {
            val d = (c[0] - r) * (c[0] - r) + (c[1] - g) * (c[1] - g) + (c[2] - b) * (c[2] - b)
            if (d < bestD) { bestD = d; best = code }
        }
        return best
    }

    private fun two(n: Int): String = if (n < 10) "0$n" else "$n"

    private fun colorCode(fg: Int, bg: Int?, withBg: Boolean): String =
        if (!withBg || bg == null) "\u0003${two(fg)}" else "\u0003${two(fg)},${two(bg)}"

    /** Emits IRC colour codes for [line] per [mode]; codes are always two digits. */
    fun colorizeLine(line: String, mode: ColorMode): String {
        if (mode.kind == "none" || line.trimUnicodeWhitespace().isEmpty()) return line
        if (mode is ColorMode.Solid) return colorCode(mode.fg, mode.bg, true) + line

        val chars = line.graphemes()
        val nonSpace = chars.count { !isUnicodeWhitespace(firstCp(it)) }

        val pal = if (mode is ColorMode.Rainbow && mode.palette == RainbowPalette.SMOOTH) RAINBOW_SMOOTH else RAINBOW_CLASSIC
        val fromC = if (mode is ColorMode.Gradient) rgbTable[mode.from] else null
        val toC = if (mode is ColorMode.Gradient) rgbTable[mode.to] else null
        val denom = maxOf(1, nonSpace - 1)
        val cols = if (mode is ColorMode.WordCycle) mode.colors.ifEmpty { listOf(0) } else listOf(0)

        fun fgAt(i: Int, w: Int): Int = when (mode) {
            is ColorMode.Rainbow -> pal[i % pal.size]
            is ColorMode.Gradient -> when {
                i == 0 -> mode.from
                i == nonSpace - 1 -> mode.to
                else -> {
                    val t = i.toDouble() / denom.toDouble()
                    nearestIrcColor(
                        fromC!![0] + (toC!![0] - fromC[0]) * t,
                        fromC[1] + (toC[1] - fromC[1]) * t,
                        fromC[2] + (toC[2] - fromC[2]) * t,
                    )
                }
            }
            is ColorMode.WordCycle -> cols[w % cols.size]
            ColorMode.None, is ColorMode.Solid -> 0
        }

        val out = StringBuilder()
        var i = 0
        var word = -1
        var inWord = false
        var active: Int? = null
        for (ch in chars) {
            if (isUnicodeWhitespace(firstCp(ch))) { inWord = false; out.append(ch); continue }
            if (!inWord) { inWord = true; word++ }
            val fg = fgAt(i, word)
            if (fg != active) { out.append(colorCode(fg, mode.bg, active == null)); active = fg }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /** Prefixes attribute codes for each set flag; suffixes a reset when anything was emitted. */
    fun wrapAttrs(line: String, style: ComposeStyle): String {
        if (line.trimUnicodeWhitespace().isEmpty()) return line
        val prefix = StringBuilder()
        if (style.bold) prefix.append('\u0002')
        if (style.italic) prefix.append('\u001D')
        if (style.underline) prefix.append('\u001F')
        if (style.strikethrough) prefix.append('\u001E')
        if (style.monospace) prefix.append('\u0011')
        if (style.reverse) prefix.append('\u0016')
        if (prefix.isEmpty() && !line.contains('\u0003')) return line
        return prefix.toString() + line + (if (line.endsWith('\u000F')) "" else "\u000F")
    }

    // endregion
}
