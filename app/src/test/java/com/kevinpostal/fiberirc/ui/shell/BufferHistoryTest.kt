package com.kevinpostal.fiberirc.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ports `FiberIRCTests/BufferHistoryTests.swift`. */
class BufferHistoryTest {
    private fun key(name: String, network: String = "net") = BufferKey(network, name)

    @Test
    fun backAndForwardWalkTheVisitedBuffers() {
        val h = BufferHistory()
        h.visit(key("#a"))   // #a → #b
        h.visit(key("#b"))   // #b → #c
        assertEquals(key("#b"), h.goBack(key("#c")))
        assertEquals(key("#a"), h.goBack(key("#b")))
        assertNull("nothing before the first buffer", h.goBack(key("#a")))
        assertEquals(key("#b"), h.goForward(key("#a")))
        assertEquals(key("#c"), h.goForward(key("#b")))
        assertNull("nothing after the newest buffer", h.goForward(key("#c")))
    }

    @Test
    fun visitClearsForward() {
        val h = BufferHistory()
        h.visit(key("#a"))
        assertEquals(key("#a"), h.goBack(key("#b")))
        h.visit(key("#a"))   // #a → #c instead of forward to #b
        assertTrue(h.forward.isEmpty())
        assertNull(h.goForward(key("#c")))
        assertEquals(key("#a"), h.goBack(key("#c")))
    }

    @Test
    fun repeatedVisitOfTheSameBufferIsOneEntry() {
        val h = BufferHistory()
        h.visit(key("#a"))
        h.visit(key("#A"))
        assertEquals(1, h.back.size)
    }

    @Test
    fun popBackTakesThePreviousBufferWithoutGoingForward() {
        val h = BufferHistory()
        h.visit(key("#a"))
        h.visit(key("#b"))   // #c closes
        assertEquals(key("#b"), h.popBack())
        assertTrue(h.forward.isEmpty())
        assertEquals(key("#a"), h.popBack())
        assertNull(h.popBack())
    }

    @Test
    fun keepsAtMostFiftyEntries() {
        val h = BufferHistory()
        for (i in 0 until 60) h.visit(key("#c$i"))
        assertEquals(BufferHistory.LIMIT, h.back.size)
        assertEquals("the oldest entries go first", key("#c10"), h.back.first())
        assertEquals(key("#c59"), h.back.last())
        var current = key("#now")
        repeat(BufferHistory.LIMIT) {
            current = h.goBack(current) ?: return fail("history ended early")
        }
        assertEquals(BufferHistory.LIMIT, h.forward.size)
    }

    @Test
    fun keyStringRoundTrip() {
        val k = key("#chan:with:colons", "b1c2")
        assertEquals("b1c2:#chan:with:colons", k.string)
        assertEquals(k, BufferKey.parse(k.string))
        assertNull(BufferKey.parse("nocolon"))
        assertNull(BufferKey.parse(":#a"))
    }

    @Test
    fun keysMatchIgnoringBufferCase() {
        assertTrue(key("#Chan").matches(key("#chan")))
        assertFalse(key("#chan", "a").matches(key("#chan", "b")))
    }
}
