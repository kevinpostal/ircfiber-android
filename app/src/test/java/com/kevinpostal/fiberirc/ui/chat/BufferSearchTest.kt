package com.kevinpostal.fiberirc.ui.chat

import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Ports `FiberIRCTests/BufferSearchTests.swift`, plus the 160-character default window and overlap cases. */
class BufferSearchTest {
    private fun line(sender: String?, text: String, kind: IrcLineKind = IrcLineKind.MESSAGE, at: Long = 0) =
        IrcLine(Instant.ofEpochSecond(1_800_000_000 + at), sender, text, kind)

    private fun found(matches: List<BufferSearch.Match>): List<String> =
        matches.flatMap { m -> m.ranges.map { m.text.substring(it.first, it.last + 1) } }

    @Test
    fun matchesIgnoreCaseAccentsAndWidthNewestFirst() {
        val lines = listOf(
            line("alice", "Café opens at nine", at = 1),
            line("bob", "the CAFE is closed", at = 2),
            line("carol", "nothing here", at = 3),
            line("dave", "ｃａｆｅ in full width", at = 4),
        )
        val matches = BufferSearch.matches(lines, "cafe")
        assertEquals(listOf("dave", "bob", "alice"), matches.map { it.line.sender })
        assertEquals(listOf("ｃａｆｅ", "CAFE", "Café"), found(matches))
    }

    @Test
    fun searchesTheDisplayedTextOfChatRowsOnly() {
        val lines = listOf(
            line("alice", "\u0002deploy\u0002 \u00034done", at = 1),
            line(null, "bob joined", IrcLineKind.EVENT, at = 2),
            line(null, "deploy failed", IrcLineKind.ERROR, at = 3),
            line("bob", "deploy notice", IrcLineKind.NOTICE, at = 4),
            line("carol", "deploys itself", IrcLineKind.ACTION, at = 5),
        )
        val matches = BufferSearch.matches(lines, "  deploy ")
        assertEquals(listOf("carol", "bob", "alice"), matches.map { it.line.sender })
        assertEquals("deploy done", matches.last().text)
        assertTrue(BufferSearch.matches(lines, "joined").isEmpty())
    }

    @Test
    fun everyOccurrenceIsMarkedAndHiddenRowsAreSkipped() {
        val lines = listOf(line("alice", "ha ha HA", at = 1), line("troll", "ha", at = 2))
        val matches = BufferSearch.matches(lines, "ha") { it.sender == "troll" }
        assertEquals(1, matches.size)
        assertEquals(listOf(0..1, 3..4, 6..7), matches.first().ranges)
    }

    @Test
    fun aBlankQueryMatchesNothing() {
        assertTrue(BufferSearch.matches(listOf(line("a", "x")), " \n").isEmpty())
        assertTrue(BufferSearch.occurrences("", "abc").isEmpty())
    }

    @Test
    fun occurrencesDoNotOverlapAndContinueFromEachHitsEnd() {
        assertEquals(listOf(0..1, 2..3), BufferSearch.occurrences("aa", "aaaaa"))
        assertEquals(listOf(0..2, 4..6), BufferSearch.occurrences("aba", "abababa"))
        assertEquals(listOf(4..6), BufferSearch.occurrences("bob", "hey BOB"))
    }

    @Test
    fun aDecomposedAccentStaysWithItsLetter() {
        val text = "cafe\u0301 time"
        val r = BufferSearch.occurrences("CAFÉ", text)
        assertEquals(listOf(0..4), r)
    }

    @Test
    fun shortTextIsItsOwnSnippet() {
        val m = BufferSearch.matches(listOf(line("a", "find the needle")), "needle").first()
        val s = BufferSearch.snippet(m)
        assertEquals("find the needle", s.text)
        assertEquals(m.ranges, s.ranges)
    }

    @Test
    fun textOfExactly160CharactersIsNotCut() {
        val text = "x".repeat(150) + " needle123"
        assertEquals(160, text.length)
        val m = BufferSearch.matches(listOf(line("a", text)), "needle").first()
        val s = BufferSearch.snippet(m)
        assertEquals(text, s.text)
        assertEquals(m.ranges, s.ranges)
    }

    @Test
    fun textOf161CharactersIsCutToA160Window() {
        val text = "x".repeat(100) + "needle" + "y".repeat(55)
        assertEquals(161, text.length)
        val m = BufferSearch.matches(listOf(line("a", text)), "needle").first()
        val s = BufferSearch.snippet(m)
        // Window starts 40 (160/4) before the hit at 100; 60 + 160 = 220 would run past 161, so it shifts back to 1.
        assertEquals("…" + text.substring(1, 161), s.text)
        assertTrue(s.text.startsWith("…"))
        assertFalse(s.text.endsWith("…"))
        assertEquals(161, s.text.length)
        assertEquals("needle", s.text.substring(s.ranges[0].first, s.ranges[0].last + 1))
    }

    @Test
    fun defaultWindowStartsAQuarterBeforeTheFirstHit() {
        val text = "a".repeat(300) + "needle" + "b".repeat(300)
        val m = BufferSearch.matches(listOf(line("a", text)), "needle").first()
        val s = BufferSearch.snippet(m)
        assertEquals("…" + text.substring(260, 420) + "…", s.text)
        assertEquals(listOf(41..46), s.ranges)
    }

    @Test
    fun longTextIsCutAroundTheFirstMatch() {
        val text = "a".repeat(300) + " needle " + "b".repeat(300) + " needle"
        val m = BufferSearch.matches(listOf(line("a", text)), "needle").first()
        val s = BufferSearch.snippet(m, maxLength = 80)
        assertTrue(s.text.startsWith("…") && s.text.endsWith("…"))
        assertEquals(82, s.text.length)
        assertEquals("the second needle is outside the window", 1, s.ranges.size)
        assertEquals("needle", s.text.substring(s.ranges[0].first, s.ranges[0].last + 1))
    }

    @Test
    fun aMatchNearTheEndKeepsAFullWindow() {
        val text = "é".repeat(200) + "needle"
        val m = BufferSearch.matches(listOf(line("a", text)), "NEEDLE").first()
        val s = BufferSearch.snippet(m, maxLength = 50)
        assertTrue(s.text.startsWith("…") && !s.text.endsWith("…"))
        assertEquals(51, s.text.length)
        assertEquals("needle", s.text.substring(s.ranges[0].first, s.ranges[0].last + 1))
    }
}
