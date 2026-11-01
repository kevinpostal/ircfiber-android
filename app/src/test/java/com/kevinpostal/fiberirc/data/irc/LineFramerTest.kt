package com.kevinpostal.fiberirc.data.irc

import org.junit.Assert.assertEquals
import org.junit.Test

/** `IRCConnection.drainLines` framing: CRLF/LF split, empty and oversize lines dropped, Latin-1 fallback. */
class LineFramerTest {
    private fun LineFramer.feed(s: String) = feed(s.toByteArray(Charsets.UTF_8))

    @Test
    fun splitsOnLfStripsCrAndDropsEmptyLines() {
        val f = LineFramer()
        assertEquals(listOf("PING :a", "b"), f.feed("PING :a\r\n\r\n\nb\n"))
    }

    @Test
    fun keepsPartialLineUntilItsNewlineArrives() {
        val f = LineFramer()
        assertEquals(emptyList<String>(), f.feed("PRIVMSG #c :héllo"))
        assertEquals(listOf("PRIVMSG #c :héllo wörld"), f.feed(" wörld\r\n"))
    }

    @Test
    fun invalidUtf8FallsBackToLatin1() {
        val f = LineFramer()
        val bytes = byteArrayOf('a'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())
        assertEquals(listOf("aé"), f.feed(bytes))
    }

    @Test
    fun dropsLinesOver8192Bytes() {
        val f = LineFramer()
        val long = "x".repeat(8193)
        assertEquals(listOf("ok", "x".repeat(8192)), f.feed("$long\nok\n${"x".repeat(8192)}\n"))
    }

    @Test
    fun discardsGarbageWithoutNewlineOver16384Bytes() {
        val f = LineFramer()
        assertEquals(emptyList<String>(), f.feed("y".repeat(16385)))
        assertEquals(listOf("tail"), f.feed("tail\n"))
    }
}
