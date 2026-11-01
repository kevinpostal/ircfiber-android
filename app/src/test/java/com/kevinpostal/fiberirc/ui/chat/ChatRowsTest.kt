package com.kevinpostal.fiberirc.ui.chat

import com.kevinpostal.fiberirc.data.irc.IrcEvent
import com.kevinpostal.fiberirc.data.irc.IrcEventKind
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChatRowsTest {
    private val utc = ZoneOffset.UTC
    private val day1 = Instant.parse("2026-10-27T10:00:00Z")
    private val day2 = Instant.parse("2026-10-28T09:00:00Z")

    private fun msg(sender: String, at: Instant, text: String = "hi", kind: IrcLineKind = IrcLineKind.MESSAGE, msgid: String? = null) =
        IrcLine(time = at, sender = sender, text = text, kind = kind, msgid = msgid)

    private fun event(kind: IrcEventKind, nick: String, at: Instant) =
        IrcLine(time = at, sender = null, text = "$nick ${kind.name.lowercase()}", kind = IrcLineKind.EVENT, event = IrcEvent(kind, nick))

    private fun build(lines: List<IrcLine>, configure: ChatRowsInput.() -> ChatRowsInput = { this }) =
        ChatRows.build(ChatRowsInput(lines = lines, zone = utc, openedAt = day2.plusSeconds(86_400)).configure())

    private fun headers(rows: List<ChatRow>) = rows.filterIsInstance<ChatRow.Line>().map { it.header }

    @Test
    fun headerStartsOnlyWhenSenderOrKindChanges() {
        val rows = build(
            listOf(
                msg("alice", day1),
                msg("ALICE", day1.plusSeconds(3600)), // same sender, case-insensitive, no time window
                msg("bob", day1.plusSeconds(3601)),
                msg("bob", day1.plusSeconds(3602), kind = IrcLineKind.NOTICE), // kind change
                msg("bob", day1.plusSeconds(3603), kind = IrcLineKind.ACTION), // actions never header
                msg("bob", day1.plusSeconds(3604)), // after an action: previous is the action (other kind)
            ),
        )
        assertEquals(listOf(true, false, true, true, false, true), headers(rows))
    }

    @Test
    fun separateNickLineOffMakesNoHeaders() {
        val rows = build(listOf(msg("alice", day1), msg("bob", day1.plusSeconds(1)))) { copy(separateNickLine = false) }
        assertEquals(listOf(false, false), headers(rows))
    }

    @Test
    fun separatorsResetGrouping() {
        val rows = build(listOf(msg("alice", day1), msg("alice", day2)))
        // The day row between them resets `previous`, so the second message heads its group again.
        assertEquals(listOf(true, true), headers(rows))
    }

    @Test
    fun dayRowBeforeEachLocalDay() {
        val rows = build(listOf(msg("a", day1), msg("b", day1.plusSeconds(60)), msg("c", day2)))
        val days = rows.mapIndexedNotNull { i, r -> (r as? ChatRow.Day)?.let { i to it.date } }
        assertEquals(listOf(0 to LocalDate.of(2026, 10, 27), 3 to LocalDate.of(2026, 10, 28)), days)
        assertTrue(rows[1] is ChatRow.Line && rows[2] is ChatRow.Line && rows[4] is ChatRow.Line)
    }

    @Test
    fun dayFollowsZone() {
        // 23:30 UTC on the 27th is the 28th at UTC+2.
        val late = Instant.parse("2026-10-27T23:30:00Z")
        val rows = ChatRows.build(ChatRowsInput(listOf(msg("a", late)), zone = ZoneOffset.ofHours(2)))
        assertEquals(ChatRow.Day(LocalDate.of(2026, 10, 28)), rows[0])
    }

    @Test
    fun twoSameDayEventsCollapseIntoMembership() {
        val rows = build(listOf(event(IrcEventKind.JOIN, "a", day1), event(IrcEventKind.PART, "b", day1.plusSeconds(1))))
        assertEquals(2, rows.size)
        val m = rows[1] as ChatRow.Membership
        assertEquals(2, m.group.lines.size)
        assertFalse(m.expanded)
    }

    @Test
    fun singleEventStaysALine() {
        val rows = build(listOf(event(IrcEventKind.JOIN, "a", day1), msg("x", day1.plusSeconds(1))))
        assertTrue(rows[1] is ChatRow.Line && !(rows[1] as ChatRow.Line).header)
        assertEquals(0, rows.count { it is ChatRow.Membership })
    }

    @Test
    fun eventRunsSplitAtDayChange() {
        val rows = build(listOf(event(IrcEventKind.JOIN, "a", day1), event(IrcEventKind.QUIT, "b", day2)))
        assertEquals(0, rows.count { it is ChatRow.Membership })
        assertEquals(2, rows.count { it is ChatRow.Day })
    }

    @Test
    fun collapseOffKeepsEventLines() {
        val lines = listOf(event(IrcEventKind.JOIN, "a", day1), event(IrcEventKind.MODE, "b", day1.plusSeconds(1)))
        val rows = build(lines) { copy(collapse = false) }
        assertEquals(listOf(false, false), headers(rows))
        assertEquals(0, rows.count { it is ChatRow.Membership })
    }

    @Test
    fun expandedGroupIsFollowedByItsLines() {
        val first = event(IrcEventKind.JOIN, "a", day1)
        val lines = listOf(first, event(IrcEventKind.NICK, "b", day1.plusSeconds(1)))
        val id = "${first.time}|a"
        val rows = build(lines) { copy(expanded = setOf(id)) }
        assertEquals(listOf(ChatRow.Day::class, ChatRow.Membership::class, ChatRow.Line::class, ChatRow.Line::class), rows.map { it::class })
        assertTrue((rows[1] as ChatRow.Membership).expanded)
    }

    @Test
    fun kicksDontCollapse() {
        val rows = build(listOf(event(IrcEventKind.KICK, "a", day1), event(IrcEventKind.KICK, "b", day1.plusSeconds(1))))
        assertEquals(0, rows.count { it is ChatRow.Membership })
    }

    @Test
    fun showJoinPartOffDropsMembershipButKeepsModes() {
        val lines = listOf(event(IrcEventKind.JOIN, "a", day1), event(IrcEventKind.MODE, "b", day1.plusSeconds(1)))
        val rows = build(lines) { copy(showJoinPart = false) }
        val shown = rows.filterIsInstance<ChatRow.Line>().map { it.line.event?.kind }
        assertEquals(listOf(IrcEventKind.MODE), shown)
    }

    @Test
    fun newMessagesDividerBeforeFirstUnreadChatLine() {
        val since = day1.plusSeconds(10)
        val lines = listOf(msg("a", day1), event(IrcEventKind.JOIN, "x", day1.plusSeconds(20)), msg("b", day1.plusSeconds(30)))
        val rows = build(lines) { copy(unreadSince = since) }
        val idx = rows.indexOfFirst { it is ChatRow.NewMessages }
        assertEquals(ChatRow.NewMessages(since), rows[idx])
        assertEquals("b", (rows[idx + 1] as ChatRow.Line).line.sender)
        assertTrue((rows[idx + 1] as ChatRow.Line).header)
    }

    @Test
    fun replyCollapseFoldsRepliesUnderParent() {
        val parent = msg("a", day1, msgid = "p1")
        val r1 = IrcLine(day1.plusSeconds(1), "b", "x", IrcLineKind.MESSAGE, msgid = "r1", replyTo = "p1")
        val r2 = IrcLine(day1.plusSeconds(2), "B", "y", IrcLineKind.MESSAGE, msgid = "r2", replyTo = "p1")
        val rows = build(listOf(parent, r1, r2)) { copy(replyCollapse = true) }
        assertEquals(ChatRow.Replies("p1", 2, listOf("b")), rows.last())
        assertEquals(1, rows.count { it is ChatRow.Line })
    }

    @Test
    fun diffAppendsReplacesOrReloads() {
        val a = ChatRow.Line(msg("a", day1, msgid = "1"), true)
        val b = ChatRow.Line(msg("b", day1, msgid = "2"), true)
        val b2 = ChatRow.Line(msg("b", day1, text = "edited", msgid = "2"), true)
        val c = ChatRow.Line(msg("c", day1, msgid = "3"), true)
        assertEquals(ChatRowDiff.Append(listOf(c)), ChatRowDiff.diff(listOf(a, b), listOf(a, b, c)))
        assertEquals(ChatRowDiff.ReplaceTail(1, listOf(b2, c)), ChatRowDiff.diff(listOf(a, b), listOf(a, b2, c)))
        assertEquals(ChatRowDiff.Reload, ChatRowDiff.diff(listOf(a, b), listOf(b, c)))
        assertEquals(ChatRowDiff.Reload, ChatRowDiff.diff(emptyList(), listOf(a)))
    }

    @Test
    fun anchorKeyUsesMsgidElseTimeKindSender() {
        assertEquals("l|m1", ChatRow.Line(msg("a", day1, msgid = "m1"), true).anchorKey)
        assertEquals("l|$day1|MESSAGE|a", ChatRow.Line(msg("a", day1), true).anchorKey)
    }

    @Test
    fun membershipSummaryPhrasing() {
        val events = listOf(
            IrcEvent(IrcEventKind.JOIN, "a"),
            IrcEvent(IrcEventKind.JOIN, "b"),
            IrcEvent(IrcEventKind.JOIN, "c"),
            IrcEvent(IrcEventKind.PART, "d", reason = "bye"),
        )
        assertEquals("→ a, b and c joined • ← d left (bye)", MembershipSummary.text(events))
    }
}
