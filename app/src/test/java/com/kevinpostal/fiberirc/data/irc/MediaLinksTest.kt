package com.kevinpostal.fiberirc.data.irc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class MediaLinksTest {
    private val site = "https://ircfiber.com"

    private fun items(text: String) = MediaLinks.items(text, site)

    @Test
    fun imageLinksTrimSentencePunctuationAndLowercaseTheHost() {
        assertEquals(listOf(MediaItem.Image("https://example.com/A.png")), items("look at https://EXAMPLE.com/A.png."))
        assertEquals(listOf(MediaItem.Image("https://example.com/a.png")), items("(see https://example.com/a.png)"))
        assertEquals(
            listOf(MediaItem.Image("https://en.example.org/wiki/Foo_(bar).png")),
            items("https://en.example.org/wiki/Foo_(bar).png"),
        )
    }

    @Test
    fun bareAndProtocolRelativeLinksBecomeHttps() {
        assertEquals(listOf(MediaItem.Image("https://example.com/pic.JPG")), items("example.com/pic.JPG"))
        assertEquals(listOf(MediaItem.Image("https://example.com/p.gif")), items("//example.com/p.gif"))
        assertEquals(emptyList<MediaItem>(), items("example.com"))
        assertEquals(emptyList<MediaItem>(), items("https://example.com/page.html"))
    }

    @Test
    fun imageExtensionMayBeInQueryOrFragment() {
        assertEquals(listOf(MediaItem.Image("https://example.com/x?f=a.webp")), items("https://example.com/x?f=a.webp"))
    }

    @Test
    fun anotherSchemesLinkIsNotReadAsHttps() {
        assertEquals(emptyList<MediaItem>(), items("ftp://host.example/a.png"))
    }

    @Test
    fun repeatsAreDropped() {
        assertEquals(1, items("https://example.com/a.png https://example.com/a.png").size)
    }

    @Test
    fun youtubeLinksBecomeCanonicalWatchUrlsWithStart() {
        assertEquals(
            listOf(MediaItem.YouTube("dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90s")),
            items("https://youtu.be/dQw4w9WgXcQ?t=1m30s"),
        )
        assertEquals(
            listOf(MediaItem.YouTube("dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3723s")),
            items("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5#t=1h2m3s"),
        )
        assertEquals(
            listOf(MediaItem.YouTube("dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")),
            items("https://m.youtube.com/shorts/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun startSecondsParsesYtStartForms() {
        assertEquals(90, MediaLinks.startSeconds("90"))
        assertEquals(90, MediaLinks.startSeconds("90s"))
        assertEquals(90, MediaLinks.startSeconds("1m30s"))
        assertEquals(3723, MediaLinks.startSeconds("1h2m3s"))
        assertNull(MediaLinks.startSeconds("0"))
        assertNull(MediaLinks.startSeconds("abc"))
    }

    @Test
    fun privateAndLoopbackHostsAreRejected() {
        for (host in listOf("192.168.1.1", "10.0.0.1", "127.1", "0x7f.0.0.1", "localhost", "printer.local", "172.16.0.9", "100.64.1.1", "169.254.1.1")) {
            assertEquals(host, emptyList<MediaItem>(), items("http://$host/a.png"))
        }
        assertTrue(MediaLinks.isPrivateHost("224.0.0.1"))
        assertFalse(MediaLinks.isPrivateHost("8.8.8.8"))
        assertEquals(listOf(MediaItem.Image("https://8.8.8.8/a.png")), items("http://8.8.8.8/a.png"))
    }

    @Test
    fun hostRewrites() {
        assertEquals(
            listOf(MediaItem.Image("https://dl.dropboxusercontent.com/s/abc123/photo.jpg")),
            items("https://www.dropbox.com/s/abc123/photo.jpg?dl=0"),
        )
        assertEquals(
            listOf(MediaItem.Image("https://pbs.twimg.com/media/ABC123?format=jpg&name=small")),
            items("https://pbs.twimg.com/media/ABC123:large"),
        )
        assertEquals(listOf(MediaItem.Image("https://d.pr/i/xyz+")), items("https://d.pr/i/xyz"))
        assertEquals(listOf(MediaItem.Image("https://lh3.googleusercontent.com/abc")), items("https://lh3.googleusercontent.com/abc"))
    }

    @Test
    fun siteLinksAreSnippetsUploadsOrImages() {
        assertEquals(
            listOf(MediaItem.Paste("abcdefgh", "https://ircfiber.com/?/pastebin=abcdefgh")),
            items("https://ircfiber.com/?/pastebin=abcdefgh"),
        )
        assertEquals(emptyList<MediaItem>(), items("https://ircfiber.com/?/pastebin=abc"))
        assertEquals(
            listOf(MediaItem.File("https://ircfiber.com/uploads/my%20report.pdf", "my report.pdf")),
            items("https://ircfiber.com/uploads/my%20report.pdf"),
        )
        assertEquals(listOf(MediaItem.Image("https://ircfiber.com/uploads/x.png")), items("http://ircfiber.com/uploads/x.png"))
        assertEquals(emptyList<MediaItem>(), items("https://ircfiber.com/irc/net"))
    }

    @Test
    fun policyFiltersByKindNetworkAndClosedPreviews() {
        val upload = MediaItem.Image("https://ircfiber.com/uploads/x.png")
        val external = MediaItem.Image("https://example.com/a.png")
        val yt = MediaItem.YouTube("dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val paste = MediaItem.Paste("abcdefgh", "https://ircfiber.com/?/pastebin=abcdefgh")
        val all = listOf(upload, external, yt, paste)
        val filesOnly = InlineMedia.Policy.of(images = false, files = true, pastes = false, embedOnCellular = true, pathExpensive = true)
        assertEquals(listOf(upload), InlineMedia.visible(all, site, emptySet(), filesOnly))
        val offCellular = InlineMedia.Policy.of(images = true, files = true, pastes = true, embedOnCellular = false, pathExpensive = true)
        assertEquals(emptyList<MediaItem>(), InlineMedia.visible(all, site, emptySet(), offCellular))
        val everything = InlineMedia.Policy.of(images = true, files = true, pastes = true, embedOnCellular = false, pathExpensive = false)
        assertEquals(listOf(upload, yt, paste), InlineMedia.visible(all, site, setOf(external.url), everything))
    }

    @Test
    fun sourceUrlsProxyExternalImagesOnly() {
        assertEquals("https://ircfiber.com/uploads/x.png", InlineMedia.sourceUrl(MediaItem.Image("https://ircfiber.com/uploads/x.png"), site))
        assertEquals(
            "https://ircfiber.com/api/image-proxy?url=https%3A%2F%2Fexample.com%2Fa.png",
            InlineMedia.sourceUrl(MediaItem.Image("https://example.com/a.png"), site),
        )
        assertEquals(
            "https://ircfiber.com/api/image-proxy?url=https%3A%2F%2Fi.ytimg.com%2Fvi%2FdQw4w9WgXcQ%2Fhqdefault.jpg",
            InlineMedia.sourceUrl(MediaItem.YouTube("dQw4w9WgXcQ", "u"), site),
        )
        assertEquals("https://ircfiber.com/api/pastebins/abcdefgh/raw", InlineMedia.sourceUrl(MediaItem.Paste("abcdefgh", "u"), site))
        assertNull(InlineMedia.sourceUrl(MediaItem.File("https://ircfiber.com/uploads/a.pdf", "a.pdf"), site))
    }

    @Test
    fun imageBoxFitsWithoutEnlarging() {
        assertEquals(300f to 200f, InlineMedia.imageSize(600f, 400f, 300f))
        assertEquals(100f to 50f, InlineMedia.imageSize(100f, 50f, 300f))
        assertEquals(100f to 200f, InlineMedia.imageSize(400f, 800f, 300f))
        assertEquals(0f to 0f, InlineMedia.imageSize(0f, 10f, 300f))
    }

    @Test
    fun captionsAndPastePreview() {
        assertEquals("a b.png", InlineMedia.caption(MediaItem.Image("https://example.com/x/a%20b.png"), null))
        assertEquals("YouTube", InlineMedia.caption(MediaItem.YouTube("id", "u"), null))
        assertEquals("Text snippet", InlineMedia.caption(MediaItem.Paste("id", "u"), null))
        assertEquals("Text snippet · 1 line", InlineMedia.caption(MediaItem.Paste("id", "u"), 1))
        assertEquals("Text snippet · 7 lines", InlineMedia.caption(MediaItem.Paste("id", "u"), 7))
        assertEquals("a\nb" to 2, InlineMedia.pastePreview("a\nb\n"))
        assertEquals("1\n2\n3\n4\n5" to 7, InlineMedia.pastePreview("1\r\n2\n3\n4\n5\n6\n7"))
    }

    @Test
    fun logExportFormatsLinesAndFileName() {
        val t = Instant.parse("2026-10-31T12:34:56Z")
        val lines = listOf(
            IrcLine(t, "alice", "\u0002hi\u0002", IrcLineKind.MESSAGE),
            IrcLine(t, "bob", "waves", IrcLineKind.ACTION),
            IrcLine(t, "srv", "note", IrcLineKind.NOTICE),
            IrcLine(t, "carol", "gone", IrcLineKind.MESSAGE, redacted = true),
            IrcLine(t, null, "dave joined", IrcLineKind.EVENT),
        )
        assertEquals(
            "[2026-10-31 12:34:56] <alice> hi\n[2026-10-31 12:34:56] * bob waves\n" +
                "[2026-10-31 12:34:56] -srv- note\n[2026-10-31 12:34:56] *** dave joined\n",
            LogExport.text(lines, ZoneOffset.UTC),
        )
        assertEquals("net_x-#a_b-20261031.txt", LogExport.filename("net/x", "#a:b", t, ZoneOffset.UTC))
    }
}
