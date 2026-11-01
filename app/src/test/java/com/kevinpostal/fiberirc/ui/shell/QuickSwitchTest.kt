package com.kevinpostal.fiberirc.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports `FiberIRCTests/QuickSwitchTests.swift`. */
class QuickSwitchTest {
    private val names = listOf("#general", "#ircfiber", "alice", "#fiber-dev", "Libera", "#Fiberglass")

    private fun match(q: String): List<String> = QuickSwitch.matches(names, q) { it }

    @Test
    fun blankQueryKeepsEveryBufferInOrder() {
        assertEquals(names, match(""))
        assertEquals(names, match("  "))
    }

    @Test
    fun prefixMatchesComeFirstIgnoringTheChannelPrefix() {
        assertEquals(listOf("#fiber-dev", "#Fiberglass", "#ircfiber"), match("fiber"))
        assertEquals(listOf("#fiber-dev", "#Fiberglass"), match("#fib"))
    }

    @Test
    fun substringAndCaseInsensitive() {
        assertEquals(listOf("alice"), match("LIC"))
        assertEquals(listOf("#general", "#ircfiber", "#fiber-dev", "Libera", "#Fiberglass"), match("er"))
        assertEquals(emptyList<String>(), match("zzz"))
    }
}
