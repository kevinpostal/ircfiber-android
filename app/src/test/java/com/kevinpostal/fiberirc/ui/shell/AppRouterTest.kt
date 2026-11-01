package com.kevinpostal.fiberirc.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `FiberIRCTests/ExternalLinksTests.swift`, plus the Android intent URIs [AppRouter.parse] reads. */
class AppRouterTest {
    private val site = "https://ircfiber.com"

    private fun web(s: String) = WebBufferRoute.parse(s)
    private fun irc(s: String) = IrcLinkRoute.parse(s)

    // Web routes

    @Test
    fun webRoutesReadTheThreeBufferPages() {
        assertEquals(WebBufferRoute("Libera", null), web("https://ircfiber.com/irc/Libera"))
        assertEquals(WebBufferRoute("Libera", null), web("https://ircfiber.com/irc/Libera/"))
        assertEquals(WebBufferRoute("Libera", "#ircfiber"), web("https://ircfiber.com/irc/Libera/channel/%23ircfiber"))
        assertEquals(WebBufferRoute("Libera", "alice"), web("https://ircfiber.com/irc/Libera/messages/alice"))
    }

    @Test
    fun webRoutesDecodeSegments() {
        assertEquals(WebBufferRoute("My Net", "##chat"), web("https://ircfiber.com/irc/My%20Net/channel/%23%23chat"))
        assertEquals(WebBufferRoute("Net", "&local"), web("https://ircfiber.com/irc/Net/channel/%26local"))
        assertEquals(WebBufferRoute("Net", "a/b"), web("https://ircfiber.com/irc/Net/messages/a%2Fb"))
    }

    @Test
    fun legacyChannelSegmentGetsAHash() {
        assertEquals(WebBufferRoute("Supernets", "#superbowl"), web("https://ircfiber.com/irc/Supernets/channel/superbowl"))
    }

    @Test
    fun otherPathsAreNotBufferRoutes() {
        assertNull(web("https://ircfiber.com/"))
        assertNull(web("https://ircfiber.com/irc"))
        assertNull(web("https://ircfiber.com/irc/"))
        assertNull(web("https://ircfiber.com/irc/Net/settings/x"))
        assertNull(web("https://ircfiber.com/irc/Net/channel"))
        assertNull(web("https://ircfiber.com/irc/Net/channel/%23a/extra"))
        assertNull(web("https://ircfiber.com/uploads/abc.png"))
    }

    @Test
    fun webUrlsFollowTheWebRouter() {
        assertEquals(
            "https://ircfiber.com/irc/Libera/channel/%23ircfiber",
            WebBufferRoute.url(site, "Libera", "#ircfiber", isChannel = true),
        )
        assertEquals(
            "https://ircfiber.com/irc/My%20Net/messages/bob%5Bm%5D",
            WebBufferRoute.url(site, "My Net", "bob[m]", isChannel = false),
        )
        assertEquals("https://ircfiber.com/irc/Libera", WebBufferRoute.url(site, "Libera", null, isChannel = false))
        assertEquals(
            "http://127.0.0.1:8090/irc/Net/channel/%23a",
            WebBufferRoute.url("http://127.0.0.1:8090", "Net", "#a", isChannel = true),
        )
    }

    @Test
    fun webUrlsRoundTrip() {
        val url = WebBufferRoute.url(site, "Net (test)", "#c++", isChannel = true)!!
        assertEquals(WebBufferRoute("Net (test)", "#c++"), web(url))
    }

    @Test
    fun encodeComponentMatchesEncodeURIComponent() {
        assertEquals("%23a%20b%2Fc%3Fd%26e", WebBufferRoute.encodeComponent("#a b/c?d&e"))
        assertEquals("A-z_0.9!~*'()", WebBufferRoute.encodeComponent("A-z_0.9!~*'()"))
    }

    // irc:// links

    @Test
    fun ircLinkWithChannelInTheFragment() {
        assertEquals(IrcLinkRoute("irc.ircfiber.com", null, true, listOf("#support")), irc("ircs://irc.ircfiber.com/#support"))
    }

    @Test
    fun ircLinkWithEncodedOrBareChannels() {
        assertEquals(IrcLinkRoute("irc.libera.chat", 6667, false, listOf("#a", "#b")), irc("irc://irc.libera.chat:6667/%23a,%23b"))
        assertEquals(IrcLinkRoute("irc.example.org", null, false, listOf("#chan")), irc("irc://IRC.Example.org/chan"))
        assertEquals(IrcLinkRoute("h", 6697, true, listOf("&local", "#news")), irc("ircs://h:6697/&local,news"))
    }

    @Test
    fun ircLinkWithoutChannels() {
        assertEquals(IrcLinkRoute("irc.ircfiber.com", null, true, emptyList()), irc("ircs://irc.ircfiber.com"))
        assertEquals(IrcLinkRoute("irc.ircfiber.com", null, false, emptyList()), irc("irc://irc.ircfiber.com/"))
    }

    @Test
    fun otherSchemesAreNotIrcLinks() {
        assertNull(irc("fiberirc://auth?code=x"))
        assertNull(irc("https://ircfiber.com/irc/Net"))
        assertNull(irc("irc:#chan"))
    }

    // Intent URIs

    @Test
    fun parseSortsIntentUris() {
        assertEquals(BufferLink.Buffer("n1", "#a b"), AppRouter.parse("fiberirc://buffer/n1/%23a%20b"))
        assertEquals(BufferLink.Irc("ircs://irc.ircfiber.com/#support"), AppRouter.parse("ircs://irc.ircfiber.com/#support"))
        assertEquals(
            BufferLink.Web("https://ircfiber.com/irc/Libera/channel/%23ircfiber"),
            AppRouter.parse("https://ircfiber.com/irc/Libera/channel/%23ircfiber"),
        )
        assertNull(AppRouter.parse("fiberirc://auth?code=x"))
        assertNull(AppRouter.parse("fiberirc://buffer/onlynetwork"))
        assertNull(AppRouter.parse("https://ircfiber.com/settings"))
        assertNull(AppRouter.parse(null as String?))
    }

    @Test
    fun bufferUriRoundTrips() {
        val uri = AppRouter.bufferUri("b1c2", "#chan/with space")
        assertEquals(BufferLink.Buffer("b1c2", "#chan/with space"), AppRouter.parse(uri))
    }

    @Test
    fun authRedirectIsToldApart() {
        assertTrue(AppRouter.isAuthRedirect("fiberirc://auth?code=abc"))
        assertFalse(AppRouter.isAuthRedirect("fiberirc://buffer/n1/%23a"))
        assertFalse(AppRouter.isAuthRedirect("https://ircfiber.com/auth"))
        assertFalse(AppRouter.isAuthRedirect(null))
    }

    @Test
    fun pendingLinkWaitsUntilConsumed() {
        val a = BufferLink.Key("n1:#a")
        val b = BufferLink.Key("n1:#b")
        AppRouter.open(a)
        AppRouter.open(b)
        AppRouter.consume(a)   // stale: the newer link stays
        assertEquals(b, AppRouter.pending.value)
        AppRouter.consume(b)
        assertNull(AppRouter.pending.value)
    }
}
