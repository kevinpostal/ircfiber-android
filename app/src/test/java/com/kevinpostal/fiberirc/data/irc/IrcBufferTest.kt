package com.kevinpostal.fiberirc.data.irc

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `FiberIRCTests/IRCBufferTests.swift`, plus ordering, dedup, trimming and member sorting. */
class IrcBufferTest {
    private val t0: Instant = Instant.parse("2026-09-27T10:00:00Z")

    private fun line(
        text: String,
        from: String = "alice",
        kind: IrcLineKind = IrcLineKind.MESSAGE,
        at: Instant = Instant.now(),
        msgid: String? = null,
    ) = IrcLine(at, from, text, kind, msgid = msgid)

    /** Web `checkHighlight` parity: whole-word, case-insensitive; nick characters are not boundaries. */
    @Test
    fun mentionsMatchOwnNickOnWordBoundaries() {
        assertTrue(line("hey tester").mentions("tester"))
        assertTrue(line("TESTER!").mentions("tester"))
        assertTrue(line("tester: hi").mentions("tester"))
        assertTrue(line("waves at tester", kind = IrcLineKind.ACTION).mentions("tester"))
        assertFalse(line("testers").mentions("tester"))
        assertFalse(line("[tester]").mentions("tester"))
        assertFalse(line("hey tester", from = "tester").mentions("tester"))
        assertFalse(line("tester joined", kind = IrcLineKind.EVENT).mentions("tester"))
        assertFalse(line("hey tester").mentions(""))
    }

    @Test
    fun outOfOrderAppendIsInsertedByTime() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.append(line("c", at = t0.plusSeconds(3)))
        b.append(line("a", at = t0.plusSeconds(1)))
        b.append(line("d", at = t0.plusSeconds(4)))
        b.append(line("b", at = t0.plusSeconds(2)))
        // Equal times keep arrival order (inserted after the existing ones).
        b.append(line("b2", at = t0.plusSeconds(2)))
        assertEquals(listOf("a", "b", "b2", "c", "d"), b.lines.map { it.text })
    }

    @Test
    fun insertHistoryDedupesByMsgidElseByTimeSenderKindText() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.append(line("held", at = t0.plusSeconds(10), msgid = "m1"))
        b.append(line("no id", at = t0.plusSeconds(11)))
        val added = b.insertHistory(
            listOf(
                line("held (other text, same id)", at = t0.plusSeconds(10), msgid = "m1"),
                line("no id", at = t0.plusSeconds(11)), // same time/sender/kind/text
                line("no id", from = "bob", at = t0.plusSeconds(11)), // different sender
                line("older", at = t0, msgid = "m0"),
                line("older", at = t0, msgid = "m0"), // duplicate within the page
            ),
        )
        assertEquals(2, added)
        assertEquals(listOf("older", "held", "no id", "no id"), b.lines.map { it.text })
        assertEquals(0, b.unreadCount)
    }

    @Test
    fun containsLineComparesOnlyLinesAtThatTime() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.append(line("x", at = t0, msgid = "a"))
        b.append(line("y", at = t0.plusSeconds(1), msgid = "b"))
        assertTrue(b.containsLine("b", t0.plusSeconds(1)))
        assertFalse(b.containsLine("b", t0))
        assertFalse(b.containsLine("zzz", t0.plusSeconds(1)))
    }

    @Test
    fun offScreenBufferTrimsTo2000AndReopensPaging() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.historyExhausted = true
        for (i in 0 until 2000) b.append(line("$i", at = t0.plusSeconds(i.toLong())))
        assertTrue(b.historyExhausted)
        b.append(line("2000", at = t0.plusSeconds(2000)))
        assertEquals(IrcBuffer.MAX_LINES, b.lines.size)
        assertEquals("1", b.lines.first().text)
        assertEquals("2000", b.lines.last().text)
        assertFalse(b.historyExhausted)
    }

    @Test
    fun onScreenBufferKeepsEverythingUntilItLeavesTheScreen() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.onScreen = true
        for (i in 0 until 2100) b.append(line("$i", at = t0.plusSeconds(i.toLong())))
        assertEquals(2100, b.lines.size)
        b.historyExhausted = true
        b.onScreen = false
        assertEquals(2000, b.lines.size)
        assertEquals("100", b.lines.first().text)
        assertFalse(b.historyExhausted)
    }

    @Test
    fun redactAndEditReplaceRowsInPlace() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.append(line("hello", at = t0, msgid = "m1"))
        assertTrue(b.replaceText("m1", "hello, edited"))
        assertEquals("hello, edited", b.lines[0].text)
        assertTrue(b.lines[0].edited)
        assertTrue(b.redact("m1"))
        assertTrue(b.lines[0].redacted)
        assertFalse(b.redact("missing"))
        assertFalse(b.replaceText("missing", "x"))
    }

    @Test
    fun clearLinesStopsPaging() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.append(line("x"))
        b.clearLines()
        assertTrue(b.lines.isEmpty())
        assertTrue(b.historyExhausted)
    }

    @Test
    fun membersSortByRankThenCaseInsensitiveNick() {
        val b = IrcBuffer("#c", IrcBufferKind.CHANNEL)
        b.setMembers(
            listOf(
                IrcMember("zed", ""),
                IrcMember("Bob", "+"),
                IrcMember("alice", ""),
                IrcMember("op", "@"),
                IrcMember("owner", "~@"),
                IrcMember("oper", "!"),
                IrcMember("amy", "+"),
            ),
        )
        assertEquals(listOf("oper", "owner", "op", "amy", "Bob", "alice", "zed"), b.members.map { it.nick })
        assertEquals(5, b.member("OWNER")!!.rank)
        assertTrue(b.renameMember("zed", "aaron"))
        assertEquals(listOf("aaron", "alice"), b.members.takeLast(2).map { it.nick })
        assertTrue(b.updateMember("aaron") { it.copy(away = true) })
        assertFalse(b.updateMember("aaron") { it.copy(away = true) })
        assertTrue(b.removeMember("AARON"))
        assertFalse(b.removeMember("aaron"))
    }

    @Test
    fun rankIsTheHighestPrefix() {
        assertEquals(0, IrcMember("a", "").rank)
        assertEquals(1, IrcMember("a", "+").rank)
        assertEquals(3, IrcMember("a", "+@").rank)
        assertEquals(7, IrcMember("a", "!*~&@%+").rank)
        assertEquals(6, IrcMember("a", "*").rank)
        assertEquals(4, IrcMember("a", "&").rank)
        assertEquals(2, IrcMember("a", "%").rank)
    }
}
