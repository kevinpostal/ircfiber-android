package com.kevinpostal.fiberirc.data.irc

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `FiberIRCTests/IRCMessageTests.swift`, plus tag-escape round trips and server-time formatting. */
class IrcMessageTest {
    @Test
    fun fullTaggedPrivmsg() {
        val m = IrcMessage.parse("@time=2026-09-27T10:00:00.123Z;msgid=abc :n!u@h PRIVMSG #c :hi there")!!
        assertEquals("2026-09-27T10:00:00.123Z", m.tags["time"])
        assertEquals("abc", m.tags["msgid"])
        assertEquals("n", m.nick)
        assertEquals("u", m.user)
        assertEquals("h", m.host)
        assertEquals("PRIVMSG", m.command)
        assertEquals(listOf("#c", "hi there"), m.params)
        assertEquals(1790503200123L, m.time.toEpochMilli())
    }

    @Test
    fun pingNoPrefix() {
        val m = IrcMessage.parse("PING :srv")!!
        assertNull(m.prefix)
        assertNull(m.nick)
        assertEquals("PING", m.command)
        assertEquals(listOf("srv"), m.params)
        assertTrue(Duration.between(m.time, Instant.now()).abs().seconds < 5)
    }

    @Test
    fun tagUnescape() {
        val m = IrcMessage.parse("@k=a\\:b\\s;flag :x CAP * LS :sasl")!!
        assertEquals("a;b ", m.tags["k"])
        assertEquals("", m.tags["flag"])
        assertEquals("x", m.nick)
        assertNull(m.user)
        assertEquals(listOf("*", "LS", "sasl"), m.params)
    }

    @Test
    fun unescapeKeepsUnknownEscapesLiterallyAndDropsLoneTrailingBackslash() {
        assertEquals("\\", IrcMessage.unescapeTagValue("\\\\"))
        assertEquals("\r\n", IrcMessage.unescapeTagValue("\\r\\n"))
        assertEquals("ab", IrcMessage.unescapeTagValue("\\ab"))
        assertEquals("ab", IrcMessage.unescapeTagValue("ab\\"))
        assertEquals("plain", IrcMessage.unescapeTagValue("plain"))
    }

    @Test
    fun tagEscapeRoundTrips() {
        for (value in listOf("a;b c", "back\\slash", "line\r\nbreak", ";;  \\\\", "emoji 😀;x", "")) {
            val line = IrcMessage.line(mapOf("+k" to value), "TAGMSG", listOf("#c"))
            val parsed = IrcMessage.parse(line)!!
            assertEquals(value, parsed.tags["+k"])
        }
        assertEquals("a\\:b\\sc\\\\d\\r\\n", IrcMessage.escapeTagValue("a;b c\\d\r\n"))
    }

    @Test
    fun serializedTagsAreSortedWithBareKeysForEmptyValues() {
        assertEquals(
            "@+draft/reply=m1;+reply=m1;flag TAGMSG #c",
            IrcMessage.line(mapOf("flag" to "", "+reply" to "m1", "+draft/reply" to "m1"), "TAGMSG", listOf("#c")),
        )
    }

    @Test
    fun namesTrailing() {
        val m = IrcMessage.parse(":s 353 me = #c :@op +v plain")!!
        assertEquals("353", m.command)
        assertEquals(4, m.params.size)
        assertEquals("@op +v plain", m.params[3])
    }

    @Test
    fun serialize() {
        assertEquals("PRIVMSG #c :hi there", IrcMessage.line(command = "PRIVMSG", params = listOf("#c", "hi there")))
        assertEquals("TOPIC #c :", IrcMessage.line(command = "TOPIC", params = listOf("#c", "")))
        assertEquals("JOIN #c", IrcMessage.line(command = "JOIN", params = listOf("#c")))
        assertEquals("PRIVMSG #c ::x", IrcMessage.line(command = "PRIVMSG", params = listOf("#c", ":x")))
    }

    @Test
    fun ctcpAction() {
        assertEquals("waves", IrcMessage.parse(":n!u@h PRIVMSG #c :\u0001ACTION waves\u0001")!!.ctcpActionText)
        assertNull(IrcMessage.parse(":n!u@h PRIVMSG #c :hello")!!.ctcpActionText)
    }

    @Test
    fun emptyAndInvalid() {
        assertNull(IrcMessage.parse(""))
        assertNull(IrcMessage.parse(":only-prefix"))
        assertNull(IrcMessage.parse("@tags-only"))
    }

    @Test
    fun serverTimeFormatsWithMillisecondsAndParsesBack() {
        val t = IrcMessage.parseTime("2026-09-27T10:00:02.500Z")
        assertNotNull(t)
        assertEquals("2026-09-27T10:00:02.500Z", IrcMessage.formatTime(t!!))
        assertEquals(IrcMessage.parseTime("2026-09-27T10:00:02.5Z"), t)
        assertNull(IrcMessage.parseTime("*"))
    }
}
