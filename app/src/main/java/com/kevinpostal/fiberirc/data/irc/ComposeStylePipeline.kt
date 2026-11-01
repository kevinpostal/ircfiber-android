package com.kevinpostal.fiberirc.data.irc

/**
 * Turns the composer's plain text into styled IRC line(s) at send time. Port of iOS `ComposeStylePipeline.swift`
 * (the web client's `composePipeline.ts`). Synchronous — every `.flf` and the TDF pack ship as app assets.
 */
object ComposeStylePipeline {
    /** Byte budget a rendered line should fit under before `IrcClient`'s own space-boundary chunking splits it. */
    const val BUDGET_BYTES = 400

    private val newline = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    /**
     * [plain] joined by `\n` where it rendered multiple output lines (FIGlet/TDF banners, or a plain message that
     * needed pre-splitting to stay under budget once styled); `IrcClient.sendInput` splits on newlines.
     */
    fun render(plain: String, style: ComposeStyle): String {
        if (!style.isActive()) return plain
        val text = ComposeTextEffects.applyCase(plain, style.caseMode)

        fun styleLine(line: String): String {
            var s = line
            val font = style.font
            if (font is FontMode.Unicode) s = ComposeTextEffects.applyUnicodeStyle(s, font.style)
            if (style.zalgo != 0) s = ComposeTextEffects.applyZalgo(s, style.zalgo)
            return ComposeTextEffects.wrapAttrs(ComposeTextEffects.colorizeLine(s, style.color), style)
        }

        val out = ArrayList<String>()
        fun push(line: String) {
            if (IrcFormatting.strip(line).trimUnicodeWhitespace().isEmpty()) return
            out += line
        }

        when (val font = style.font) {
            is FontMode.Figlet -> {
                val width = if (style.artWrap.mode == ArtWrapMode.WORD) clampArtWrapWidth(style.artWrap.width) else 400
                val rendered = runCatching { renderFiglet(text, font.font, width) }.getOrDefault("")
                for (line in rendered.split("\n")) {
                    push(ComposeTextEffects.wrapAttrs(ComposeTextEffects.colorizeLine(line, style.color), style))
                }
                return out.joinToString("\n")
            }
            is FontMode.Tdf -> {
                val artLines = if (style.artWrap.mode == ArtWrapMode.WORD) {
                    renderTdfWrapped(text, font.font, clampArtWrapWidth(style.artWrap.width))
                } else {
                    tdf(text, font.font)
                }
                // TheDraw lines carry their own colours; only the attribute wrapper applies.
                for (line in artLines) push(ComposeTextEffects.wrapAttrs(line, style))
                return out.joinToString("\n")
            }
            else -> Unit
        }

        for (line in splitLines(text)) {
            val styled = styleLine(line)
            val lineCount = line.graphemeCount()
            if (utf8Count(styled) <= BUDGET_BYTES || lineCount < 2) {
                push(styled)
                continue
            }
            // Styling inflated this line past budget: re-split the *plain* line at a char budget scaled by how much
            // styling grew the byte count, then style each piece on its own (one adaptive pass, as on iOS).
            val ratio = lineCount.toDouble() * BUDGET_BYTES.toDouble() / maxOf(1, utf8Count(styled)).toDouble()
            val charBudget = maxOf(20, kotlin.math.floor(ratio).toInt())
            for (piece in greedyWordWrap(line, charBudget)) push(styleLine(piece))
        }
        return out.joinToString("\n")
    }

    // region TDF word-wrap (TheDraw grids have no fold column, so wrap between words)

    /**
     * Greedy word partition: `widths[i]` is word i in columns, [gap] the columns between joined words. Returns index
     * groups that fit [maxWidth]; a lone word wider than the limit keeps its own group — never cut mid-word.
     */
    fun groupWidths(widths: List<Int>, gap: Int, maxWidth: Int): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var curW = 0
        for ((i, w) in widths.withIndex()) {
            if (cur.isNotEmpty() && curW + gap + w > maxWidth) {
                groups += cur
                cur = ArrayList()
                curW = 0
            }
            cur += i
            curW += if (cur.size == 1) w else gap + w
        }
        if (cur.isNotEmpty()) groups += cur
        return groups
    }

    private fun tdf(text: String, name: String): List<String> =
        runCatching { TdfCatalog.render(text, name) }.getOrDefault(emptyList())

    private fun renderTdfWrapped(text: String, name: String, maxWidth: Int): List<String> {
        fun renderWidth(s: String): Int = tdf(s, name).maxOfOrNull { IrcFormatting.strip(it).graphemeCount() } ?: 0
        val out = ArrayList<String>()
        for (para in splitLines(text)) {
            if (para.trimUnicodeWhitespace().isEmpty()) continue
            val words = para.split(' ').filter { it.isNotEmpty() }
            if (words.size <= 1) {
                out += tdf(para, name)
                continue
            }
            val groups = groupWidths(words.map(::renderWidth), 4, maxWidth)
            if (groups.size <= 1) {
                out += tdf(para, name)
                continue
            }
            for (g in groups) out += tdf(g.joinToString(" ") { words[it] }, name)
        }
        return out
    }

    // endregion

    // region Helpers

    /** Swift `split(whereSeparator: \.isNewline)`: newline-separated, empty pieces dropped. */
    private fun splitLines(text: String): List<String> = text.split(newline).filter { it.isNotEmpty() }

    private fun utf8Count(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Greedy word-wrap [text] into pieces of at most [maxChars] characters, never splitting a word. */
    private fun greedyWordWrap(text: String, maxChars: Int): List<String> {
        val words = text.split(' ')
        val out = ArrayList<String>()
        var cur = ""
        for (word in words) {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && candidate.graphemeCount() > maxChars) {
                out += cur
                cur = word
            } else {
                cur = candidate
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out.ifEmpty { listOf(text) }
    }

    // endregion
}
