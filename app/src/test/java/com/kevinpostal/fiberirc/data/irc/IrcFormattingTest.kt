package com.kevinpostal.fiberirc.data.irc

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcFormattingTest {
    private fun hex(c: Color?): Int? = c?.let { it.toArgb() and 0xffffff }

    /** The run containing [text]. */
    private fun run(text: String, line: String): IrcRun = IrcFormatting.parse(line).first { it.text.contains(text) }

    private fun assertPlain(r: IrcRun) {
        assertFalse(r.bold || r.italic || r.underline || r.strike || r.reverse)
        assertNull(r.fg); assertNull(r.bg); assertNull(r.fgCode); assertNull(r.bgCode)
    }

    @Test fun toggles() {
        assertTrue(run("x", "\u0002x").bold)
        assertTrue(run("x", "\u001Dx").italic)
        assertTrue(run("x", "\u001Fx").underline)
        assertTrue(run("x", "\u0016x").reverse)
        assertTrue(run("x", "\u001Ex").strike)
        assertFalse(run("y", "\u0002x\u0002y").bold)
    }

    @Test fun monospaceCodeIsConsumedWithoutNewRun() {
        val runs = IrcFormatting.parse("a\u0011b")
        assertEquals(listOf("ab"), runs.map { it.text })
        assertPlain(runs[0])
    }

    @Test fun mircForegroundAndBackground() {
        val r = run("x", "\u000304,02x")
        assertEquals(0xff0000, hex(r.fg))
        assertEquals(0x00007f, hex(r.bg))
        assertEquals(4, r.fgCode)
        assertEquals(2, r.bgCode)
    }

    @Test fun commaWithoutBackgroundDigitStaysLiteral() {
        val runs = IrcFormatting.parse("\u00034,x")
        assertEquals(",x", runs.joinToString("") { it.text })
        assertEquals(0xff0000, hex(runs[0].fg))
        assertNull(runs[0].bg)
    }

    @Test fun commaWithoutForegroundStaysLiteral() {
        val runs = IrcFormatting.parse("\u0003,02x")
        assertEquals(",02x", runs.joinToString("") { it.text })
        assertPlain(runs[0])
    }

    @Test fun twoDigitForegroundCap() {
        val r = run("5x", "\u0003045x")
        assertEquals(4, r.fgCode)
    }

    @Test fun bareColorCodeResetsColors() {
        val r = run("plain", "\u000304,02red\u0003plain")
        assertNull(r.fg)
        assertNull(r.bg)
    }

    @Test fun code99IsDefaultColor() {
        val r = run("x", "\u000399,99x")
        assertNull(r.fg); assertNull(r.bg); assertNull(r.fgCode); assertNull(r.bgCode)
    }

    @Test fun hexColors() {
        val r = run("x", "\u0004FF8800,00FF88x")
        assertEquals(0xff8800, hex(r.fg))
        assertEquals(0x00ff88, hex(r.bg))
        assertNull(r.fgCode)
    }

    @Test fun shortHexIsNotAColor() {
        val r = run("x", "\u0004FF88x")
        assertNull(r.fg)
    }

    @Test fun hexClearsMircAndMircClearsHex() {
        assertNull(run("x", "\u000304\u0004FF8800x").fgCode)
        assertEquals(0xff8800, hex(run("x", "\u000304\u0004FF8800x").fg))
        assertEquals(0xff0000, hex(run("x", "\u0004FF8800\u000304x").fg))
    }

    @Test fun ansiSgr() {
        assertEquals(0x010203, hex(run("x", "\u001B[38;2;1;2;3mx").fg))
        assertEquals(0xff0000, hex(run("x", "\u001B[38;5;196mx").fg))
        assertEquals(0xcd0000, hex(run("x", "\u001B[31mx").fg))
        assertEquals(0xff0000, hex(run("x", "\u001B[91mx").fg))
        assertEquals(0x0000ee, hex(run("x", "\u001B[44mx").bg))
        assertEquals(0x5c5cff, hex(run("x", "\u001B[104mx").bg))
        assertEquals(0x080808, hex(run("x", "\u001B[48;5;232mx").bg))
        val r = run("x", "\u001B[1;3;4;7;9mx")
        assertTrue(r.bold && r.italic && r.underline && r.reverse && r.strike)
        assertPlain(run("y", "\u001B[1;31mx\u001B[0my"))
        assertPlain(run("y", "\u001B[1;31mx\u001B[my"))
    }

    @Test fun nonSgrCsiAndBareEscAreDropped() {
        val runs = IrcFormatting.parse("a\u001B[2Jb")
        assertEquals("ab", runs.joinToString("") { it.text })
        assertEquals(1, runs.size)
        assertPlain(runs[0])
        assertEquals("ab", IrcFormatting.strip("a\u001Bb"))
    }

    @Test fun resetClearsEverything() {
        assertPlain(run("plain", "\u0002\u001D\u001F\u001E\u0016\u000304,02x\u000Fplain"))
    }

    @Test fun newRunOnlyWhenStyleChanges() {
        // A bare \x03 on uncoloured text resolves to the same style: no new run.
        assertEquals(listOf("ab"), IrcFormatting.parse("a\u0003b").map { it.text })
        assertEquals(listOf("a", "b"), IrcFormatting.parse("a\u0002b").map { it.text })
    }

    @Test fun strip() {
        assertEquals("bold red plain", IrcFormatting.strip("\u0002bold\u0002 \u000304red\u0003 plain"))
    }

    @Test fun palette() {
        assertEquals(0xffffff, hex(IrcFormatting.color(0)))
        assertEquals(0x000000, hex(IrcFormatting.color(1)))
        assertEquals(0xd2d2d2, hex(IrcFormatting.color(15)))
        assertEquals(0x470000, hex(IrcFormatting.color(16)))
        assertEquals(0xff8c00, hex(IrcFormatting.color(53)))
        assertEquals(0xff94d3, hex(IrcFormatting.color(87)))
        assertEquals(0x000000, hex(IrcFormatting.color(88)))
        assertEquals(0xffffff, hex(IrcFormatting.color(98)))
        assertNull(IrcFormatting.color(99))
        assertNull(IrcFormatting.color(-1))
    }

    @Test fun blockArtDetection() {
        assertTrue(IrcFormatting.isBlockArt("▀"))
        assertTrue(IrcFormatting.isBlockArt("\uD83E\uDF00")) // U+1FB00 sextant
        assertTrue(IrcFormatting.isBlockArt("\u001B[31mx".repeat(6)))
        assertTrue(IrcFormatting.isBlockArt("\u000304#\u000305#\u000306#\u000307#\u000308#\u000309#  ....  "))
        assertFalse(IrcFormatting.isBlockArt("hello \u000304red"))
        assertFalse(IrcFormatting.isBlockArt("\u001B[31mx".repeat(5)))
    }

    @Test fun artRowResolvesReverseAndColumns() {
        val text = Color(0xFFCCCCCC)
        val canvas = Color(0xFF000000)
        val row = IrcFormatting.artRow("\u0016▀", text, canvas)!!
        assertEquals(hex(canvas), hex(row.glyphs[0].fg))
        assertEquals(hex(text), hex(row.glyphs[0].bg))
        assertEquals(3, IrcFormatting.artRow("\u000304,02▀▀▀", text, canvas)!!.columns)
        assertNull(IrcFormatting.artRow("hello", text, canvas))
    }

    @Test fun annotatedReverseSwapsColors() {
        val a = IrcFormatting.toAnnotatedString("\u000304,02\u0016x", Color(0xFFCCCCCC), Color.Black)
        val span = a.spanStyles.single().item
        assertEquals(0x00007f, hex(span.color))
        assertEquals(0xff0000, hex(span.background))
        assertEquals("4", a.getStringAnnotations(IrcFormatting.FOREGROUND_TAG, 0, 1).single().item)
    }
}
