package com.kevinpostal.fiberirc.ui.chat

import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.prefs.ModeIndicator
import com.kevinpostal.fiberirc.data.prefs.TimestampFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Ports `FiberIRCTests/ChatDisplayTests.swift`. */
class ChatDisplayTest {
    private fun line(text: String, from: String = "bob", kind: IrcLineKind = IrcLineKind.MESSAGE) =
        IrcLine(java.time.Instant.now(), from, text, kind)

    @Test
    fun messageTimeFormats() {
        val t = LocalDateTime.of(2026, 10, 25, 14, 5, 9).toInstant(ZoneOffset.UTC)
        fun s(f: TimestampFormat, sec: Boolean) = MessageTime.string(t, f, sec, ZoneOffset.UTC)
        assertEquals("2:05:09 PM", s(TimestampFormat.TWELVE_HOUR, true))
        assertEquals("14:05:09", s(TimestampFormat.TWENTY_FOUR_HOUR, true))
        assertEquals("2:05 PM", s(TimestampFormat.TWELVE_HOUR, false))
        assertEquals("14:05", s(TimestampFormat.TWENTY_FOUR_HOUR, false))
        assertFalse("automatic drops the seconds too", s(TimestampFormat.AUTO, false).contains("09"))
    }

    @Test
    fun emojiOnly() {
        for (text in listOf("😀", "😀 👍", "❤️", "1️⃣", "🇺🇸", "\u000304🤔", "👍🏽", "👨‍👩‍👧", "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F")) {
            assertTrue(text, EmojiOnly.matches(text))
        }
        for (text in listOf("", "hi 😀", "❤", "▪▪▪", ":smile:", "   ")) {
            assertFalse(text, EmojiOnly.matches(text))
        }
    }

    @Test
    fun highlightWordsMatchWholeWordsInOtherPeoplesChat() {
        val words = listOf("Deploy")
        assertTrue(HighlightMatcher.matches(line("starting the deploy now"), words, "me"))
        assertTrue(HighlightMatcher.matches(line("DEPLOY!"), words, "me"))
        assertFalse(HighlightMatcher.matches(line("deployment done"), words, "me"))
        assertFalse(HighlightMatcher.matches(line("see [deploy]"), words, "me"))
        assertFalse(HighlightMatcher.matches(line("deploy", from = "Me"), words, "me"))
        assertFalse(HighlightMatcher.matches(line("deploy joined", kind = IrcLineKind.EVENT), words, "me"))
    }

    @Test
    fun mentionColorizerFindsNicksWithAndWithoutAt() {
        val c = MentionColorizer.create(listOf("Bob", "alice"))
        assertNotNull(c)
        val text = "hi bob, @bob and bobby"
        val hits = c!!.mentions(text)
        assertEquals(listOf(3..5 to "bob", 8..11 to "bob"), hits)
        assertNull(MentionColorizer.create(listOf("", "")))
    }

    @Test
    fun modeGlyph() {
        assertEquals("@", ModeGlyph.glyph("@+", ModeIndicator.SYMBOLS))
        assertEquals("•", ModeGlyph.glyph("+", ModeIndicator.DOTS))
        assertNull(ModeGlyph.glyph("@", ModeIndicator.HIDDEN))
        assertNull(ModeGlyph.glyph("", ModeIndicator.SYMBOLS))
    }
}
