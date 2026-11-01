package com.kevinpostal.fiberirc.ui.share

import com.kevinpostal.fiberirc.data.prefs.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareFormTest {
    private val fiber = ShareTarget("n1", "IRC Fiber", "#IRCFiber", ShareTarget.CHANNEL)
    private val cafe = ShareTarget("n2", "Café Net", "#lounge", ShareTarget.CHANNEL)
    private val alice = ShareTarget("n1", "IRC Fiber", "Alice", ShareTarget.QUERY)
    private val targets = listOf(fiber, cafe, alice)

    @Test
    fun preselectsShortcutIdCaseInsensitivelyAtFirstColon() {
        assertEquals(fiber, ShareForm.preselected(targets, "n1:#ircfiber"))
        assertEquals(alice, ShareForm.preselected(targets, "n1:alice"))
        assertNull(ShareForm.preselected(targets, "N1:#ircfiber"))
        assertNull(ShareForm.preselected(targets, "n1"))
        assertNull(ShareForm.preselected(targets, null))
        val colonBuffer = ShareTarget("n3", "X", "a:b", ShareTarget.QUERY)
        assertEquals(colonBuffer, ShareForm.preselected(listOf(colonBuffer), "n3:A:B"))
    }

    @Test
    fun messageTextTrimsDropsEmptiesAndRepeats() {
        assertEquals("hi https://x.y", ShareForm.messageText(listOf("  hi \n", "", "https://x.y", "hi")))
        assertEquals("", ShareForm.messageText(emptyList()))
    }

    @Test
    fun filterMatchesBufferOrNetworkIgnoringCaseAndDiacritics() {
        assertEquals(targets, ShareForm.filter(targets, "  "))
        assertEquals(listOf(fiber, alice), ShareForm.filter(targets, "fiber"))
        assertEquals(listOf(cafe), ShareForm.filter(targets, "cafe"))
        assertEquals(listOf(alice), ShareForm.filter(targets, " ALI "))
    }

    @Test
    fun postNeedsTargetContentAndIdle() {
        assertTrue(ShareForm.canPost(fiber, hasFile = false, message = "x", loading = false, sending = false))
        assertTrue(ShareForm.canPost(fiber, hasFile = true, message = " ", loading = false, sending = false))
        assertFalse(ShareForm.canPost(null, hasFile = true, message = "x", loading = false, sending = false))
        assertFalse(ShareForm.canPost(fiber, hasFile = false, message = " \n", loading = false, sending = false))
        assertFalse(ShareForm.canPost(fiber, hasFile = true, message = "x", loading = true, sending = false))
        assertFalse(ShareForm.canPost(fiber, hasFile = true, message = "x", loading = false, sending = true))
    }
}
