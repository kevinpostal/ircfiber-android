package com.kevinpostal.fiberirc.data.irc

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.Instant

class ComposeStylePipelineTest {
    companion object {
        /** Gradle runs unit tests from the module directory, so the bundled assets are on disk here. */
        @JvmStatic
        @BeforeClass
        fun installAssets() {
            val root = File("src/main/assets")
            ArtFontAssets.install(object : ArtFontAssets.Source {
                override fun list(dir: String) = File(root, dir).list()?.toList().orEmpty()
                override fun open(path: String) = File(root, path).takeIf { it.isFile }?.readBytes()
            })
        }
    }

    private fun render(text: String, style: ComposeStyle) = ComposeStylePipeline.render(text, style)

    @Test fun inactiveStyleSendsPlainText() {
        assertEquals("hi there", render("hi there", ComposeStyle()))
    }

    @Test fun attributesWrapInOrderWithReset() {
        val style = ComposeStyle(bold = true, italic = true, underline = true, strikethrough = true, monospace = true, reverse = true)
        assertEquals("\u0002\u001D\u001F\u001E\u0011\u0016hi\u000F", render("hi", style))
    }

    @Test fun solidColor() {
        assertEquals("\u000304hi\u000F", render("hi", ComposeStyle(color = ColorMode.Solid(4))))
        assertEquals("\u000304,01hi\u000F", render("hi", ComposeStyle(color = ColorMode.Solid(4, bg = 1))))
    }

    @Test fun rainbowColorsEachNonSpaceCharacter() {
        assertEquals(
            "\u000304a\u000307b\u000308c \u000309d\u000F",
            render("abc d", ComposeStyle(color = ColorMode.Rainbow(RainbowPalette.CLASSIC))),
        )
        // Background only on the first code.
        assertEquals(
            "\u000352,01a\u000353b\u000F",
            render("ab", ComposeStyle(color = ColorMode.Rainbow(RainbowPalette.SMOOTH, bg = 1))),
        )
    }

    @Test fun gradientEndsOnExactColorsAndLerpsBetween() {
        // Midpoint of ff0000 → 0000fc is (127.5, 0, 126): nearest palette entry is 38 (0x740074).
        assertEquals(
            "\u000304a\u000338b\u000312c\u000F",
            render("abc", ComposeStyle(color = ColorMode.Gradient(4, 12))),
        )
    }

    @Test fun wordCycleColorsPerWord() {
        assertEquals(
            "\u000303ab \u000305cd \u000303ef\u000F",
            render("ab cd ef", ComposeStyle(color = ColorMode.WordCycle(listOf(3, 5)))),
        )
        assertEquals("\u000300ab cd\u000F", render("ab cd", ComposeStyle(color = ColorMode.WordCycle(emptyList()))))
    }

    @Test fun overBudgetLineIsRewrappedOnPlainWords() {
        val plain = List(60) { "abcd" }.joinToString(" ") // 299 chars
        val out = render(plain, ComposeStyle(color = ColorMode.Rainbow(RainbowPalette.CLASSIC))).split("\n")
        // Styled = 240 × (3-byte code + char) + 59 spaces + reset = 1020 bytes → budget floor(299·400/1020) = 117
        // chars → 23 words (114 chars) per piece.
        assertEquals(3, out.size)
        assertEquals(listOf(114, 114, 69), out.map { IrcFormatting.strip(it).length })
        assertEquals(plain, out.joinToString(" ") { IrcFormatting.strip(it) })
        assertTrue(out.all { it.toByteArray(Charsets.UTF_8).size <= ComposeStylePipeline.BUDGET_BYTES })
    }

    @Test fun blankLinesAreDropped() {
        assertEquals("\u0002a\u000F\n\u0002b\u000F", render("a\n\n  \nb", ComposeStyle(bold = true)))
    }

    @Test fun caseModes() {
        assertEquals("ABC", render("abc", ComposeStyle(caseMode = CaseMode.UPPER)))
        assertEquals("hElLo, WoRlD", render("Hello, World", ComposeStyle(caseMode = CaseMode.MOCKING)))
    }

    @Test fun unicodePseudoFonts() {
        assertEquals("\uD835\uDC00\uD835\uDC1B\uD835\uDFCF", render("Ab1", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.BOLD))))
        assertEquals("ℎ", render("h", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.ITALIC))))
        assertEquals("ℂ", render("C", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.DOUBLE_STRUCK))))
        assertEquals("⓪①", render("01", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.CIRCLED))))
        assertEquals("ＡＢ\u3000ｃ", render("AB c", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.FULLWIDTH))))
        assertEquals("ʜɪ", render("Hi", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.SMALL_CAPS))))
        assertEquals("¡ɐq", render("ba!", ComposeStyle(font = FontMode.Unicode(UnicodeStyle.UPSIDE_DOWN))))
    }

    @Test fun zalgoAddsCombiningMarksAfterNonSpace() {
        val out = ComposeTextEffects.applyZalgo("a b", 1)
        val marks = out.count { it.code in 0x300..0x36F }
        assertTrue(marks in 2..4)
        assertTrue(out.startsWith("a"))
        assertEquals("a b", ComposeTextEffects.applyZalgo("a b", 0))
    }

    @Test fun groupWidthsNeverSplitsWords() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), ComposeStylePipeline.groupWidths(listOf(10, 10, 10), 4, 24))
        assertEquals(listOf(listOf(0), listOf(1)), ComposeStylePipeline.groupWidths(listOf(50, 5), 4, 20))
    }

    @Test fun figletRendersBlockFontAsUtf8() {
        assertTrue(FigletCatalog.names.size >= 300)
        val banner = renderFiglet("Hi", "ANSI Regular", 400)
        assertTrue(banner, banner.contains("█"))
        assertFalse(banner, banner.contains("â"))
        val sent = render("Hi", ComposeStyle(font = FontMode.Figlet("Standard")))
        assertEquals(sent.split("\n").size, sent.split("\n").count { it.isNotBlank() })
        assertTrue(sent.split("\n").size >= 4)
    }

    @Test fun everyBundledFigletFontLoads() {
        assertEquals(emptyList<String>(), FigletCatalog.names.filter { FigletCatalog.rows(it) == 0 })
    }

    @Test fun tdfRendersColouredLines() {
        val font = TdfCatalog.listFonts().first()
        val lines = render("AB", ComposeStyle(font = FontMode.Tdf(font.name))).split("\n")
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.contains('\u0003') })
        assertTrue(lines.filter { it.contains('\u0003') }.all { it.endsWith('\u000F') })
    }

    @Test fun styleJsonUsesKindDiscriminator() {
        val json = Json { ignoreUnknownKeys = true }
        val style = ComposeStyle(color = ColorMode.Gradient(4, 12, bg = 1), font = FontMode.Unicode(UnicodeStyle.BOLD_ITALIC))
        val encoded = json.encodeToString(ComposeStyle.serializer(), style)
        assertTrue(encoded, encoded.contains("\"kind\":\"gradient\""))
        assertTrue(encoded, encoded.contains("\"style\":\"boldItalic\""))
        assertEquals(style, json.decodeFromString(ComposeStyle.serializer(), encoded))
        val recent = ComposeStyleRecent(style, composeStyleSummary(style), Instant.parse("2026-10-31T12:00:00Z"))
        assertEquals(recent, json.decodeFromString(ComposeStyleRecent.serializer(), json.encodeToString(ComposeStyleRecent.serializer(), recent)))
    }
}
