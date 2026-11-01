package com.kevinpostal.fiberirc.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class UploadFileTest {
    @Test
    fun chatLineJoinsMessageAndUrl() {
        assertEquals("look https://x/u.png", UploadFile.message("look  \n", "https://x/u.png"))
        assertEquals("https://x/u.png", UploadFile.message("   ", "https://x/u.png"))
        assertEquals("  hi https://x/u.png", UploadFile.message("  hi", "https://x/u.png"))
    }

    @Test
    fun photosShrinkToTheLongestSideNeverGrow() {
        assertEquals(1024 to 768, UploadFile.resizedSize(4000, 3000, 1024))
        assertEquals(768 to 1024, UploadFile.resizedSize(3000, 4000, 1024))
        assertEquals(800 to 600, UploadFile.resizedSize(800, 600, 1024))
        assertEquals(4000 to 3000, UploadFile.resizedSize(4000, 3000, 0))
    }

    @Test
    fun sizeLimitAndNames() {
        assertFalse(UploadFile.isTooLarge(50L * 1024 * 1024))
        assertTrue(UploadFile.isTooLarge(50L * 1024 * 1024 + 1))
        val t = Instant.parse("2026-10-31T12:00:05Z")
        assertEquals("VID_20261031_120005.mov", UploadFile.defaultName("VID", "mov", t, ZoneOffset.UTC))
        assertEquals("IMG_20261031_120005.jpg", UploadFile.defaultName(date = t, zone = ZoneOffset.UTC))
        assertEquals("application/octet-stream", UploadFile.mimeType("noext", "application/octet-stream"))
    }

    @Test
    fun pasteCommand() {
        assertTrue(Snippet.isPasteCommand("/paste"))
        assertTrue(Snippet.isPasteCommand("  /PASTE some text"))
        assertFalse(Snippet.isPasteCommand("/pastebin"))
        assertFalse(Snippet.isPasteCommand("/past"))
        assertEquals(" hi", Snippet.pasteBody("/paste  hi"))
        assertEquals("", Snippet.pasteBody("/paste"))
    }

    @Test
    fun longOrMultiLineMessagesOfferASnippet() {
        assertTrue(Snippet.offersSnippet("a\nb", ask = true))
        assertFalse(Snippet.offersSnippet("a\n", ask = true))
        assertFalse(Snippet.offersSnippet("a\nb", ask = false))
        assertFalse(Snippet.offersSnippet("/msg x\ny", ask = true))
        assertTrue(Snippet.offersSnippet("x".repeat(1081), ask = true))
        assertFalse(Snippet.offersSnippet("x".repeat(1080), ask = true))
    }

    @Test
    fun sendAsMessagesSkipsBlankLinesAndEscapesCommands() {
        assertEquals(listOf("a", "//me waves", "b"), Snippet.messageLines("a\n\n/me waves\n  \r\nb\n"))
    }

    @Test
    fun pasteAndProxyUrls() {
        assertEquals("https://ircfiber.com/?/pastebin=a%2Fb", MediaUrls.pasteUrl("a/b", "https://ircfiber.com/"))
        assertEquals("https://ircfiber.com/api/pastebins/abc/raw", MediaUrls.pasteRawUrl("abc", "https://ircfiber.com"))
        assertEquals("%C3%A9-._~%20", MediaUrls.encodeUnreserved("é-._~ "))
    }
}
