package com.kevinpostal.fiberirc.ui.settings

import com.kevinpostal.fiberirc.data.api.BouncerClient
import com.kevinpostal.fiberirc.data.api.BouncerDevice
import com.kevinpostal.fiberirc.data.api.LoginSession
import com.kevinpostal.fiberirc.data.api.MeDevice
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SettingsLogicTest {
    @Test
    fun userAgentLabels() {
        assertEquals("IRC Fiber for Android", UserAgentSummary.label("FiberIRC Android"))
        assertEquals("IRC Fiber for iOS", UserAgentSummary.label("  FiberIRC iOS 1.2"))
        assertEquals("Unknown client", UserAgentSummary.label(""))
        assertEquals(
            "Chrome 152 on macOS",
            UserAgentSummary.label("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"),
        )
        assertEquals(
            "Safari 18.4 on iOS 18",
            UserAgentSummary.label("Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Mobile/15E148 Safari/604.1"),
        )
        assertEquals(
            "Safari 18 on macOS",
            UserAgentSummary.label("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"),
        )
        assertEquals(
            "Firefox 140 on Android 15",
            UserAgentSummary.label("Mozilla/5.0 (Android 15; Mobile; rv:140.0) Gecko/140.0 Firefox/140.0"),
        )
        assertEquals("curl 8.7.1", UserAgentSummary.label("curl/8.7.1"))
        // A token without a version: "this client, version unknown".
        assertEquals("Playwright on Linux", UserAgentSummary.label("Playwright X11; Linux"))
        val bot = "Mozilla/5.0 (compatible; Googlebot/2.1) Chrome/1"
        assertEquals(bot, UserAgentSummary.label(bot))
        val long = "x".repeat(100)
        assertEquals("x".repeat(79) + "…", UserAgentSummary.label(long))
    }

    @Test
    fun changePasswordChecksInWebOrder() {
        assertEquals("Please fill in all fields", changePasswordProblem("", "a", "a"))
        assertEquals("New passwords do not match", changePasswordProblem("o", "abcdefgh", "abcdefgX"))
        assertEquals("Password must be at least 8 characters", changePasswordProblem("o", "short", "short"))
        assertNull(changePasswordProblem("o", "abcdefgh", "abcdefgh"))
    }

    @Test
    fun highlightWordRules() {
        assertNull(highlightWordProblem("fiber", listOf("irc")))
        assertEquals("“FIBER” is already a highlight word.", highlightWordProblem("FIBER", listOf("fiber")))
        assertEquals("Highlight words can be at most 64 bytes.", highlightWordProblem("é".repeat(33), emptyList()))
        assertNull(highlightWordProblem("é".repeat(32), emptyList()))
        assertEquals("You can have at most 100 highlight words.", highlightWordProblem("new", List(100) { "w$it" }))
    }

    @Test
    fun rowTexts() {
        val s = LoginSession("r", 0, 0, 0, clientIp = "", userAgent = "", current = false, clientCount = 2)
        assertEquals("unknown address · active now · 2 tabs", sessionDetail(s, "now"))
        val c = BouncerClient(sid = "s", networkName = "", clientId = "", nick = "kev", peer = "1.2.3.4", tls = true)
        assertEquals("kev", clientName(c))
        assertEquals("bouncer · as kev · 1.2.3.4 · TLS · connected 1 min. ago", clientDetail(c, "1 min. ago"))
        assertEquals("anonymous", clientName(c.copy(nick = "")))
        assertEquals("k (no client id) · online", deviceName(BouncerDevice(id = "1", clientId = "k", anonymous = true, online = true)))
        assertTrue(isExternal(null))
        assertTrue(isExternal("external"))
        assertFalse(isExternal("app"))
        assertEquals("Not Registered", pushStatus(MeDevice.PushStatus(on = false)))
        assertEquals("Registered", pushStatus(MeDevice.PushStatus(on = true, environment = "production")))
        assertEquals("Registered (Sandbox)", pushStatus(MeDevice.PushStatus(on = true, environment = "sandbox")))
    }

    @Test
    fun fontScaleSnapsToTenPercent() {
        assertEquals(1.1, snapFontScale(107f), 1e-9)
        assertEquals(0.8, snapFontScale(80f), 1e-9)
        assertEquals("140%", percent(1.4))
    }

    @Test
    fun settingsSearchIgnoresCaseAndDiacritics() {
        assertTrue(foldedContains("Notifications", "NOTIF"))
        assertTrue(foldedContains("Déconnexion", "deconn"))
        assertFalse(foldedContains("Bouncer", "sessions"))
    }

    @Test
    fun diagnosticsReport() {
        val report = Diagnostics.report(listOf("App version" to "1.0.0", "Build" to "1"), emptyList())
        assertEquals("== FiberIRC diagnostics ==\nApp version: 1.0.0\nBuild: 1\n\n== Log (this launch) ==\n(none)\n", report)
        assertEquals("20261031T120000Z", Diagnostics.stamp(ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, ZoneOffset.UTC)))
    }

    @Test
    fun logRingKeepsNewestAppLines() {
        AppLog.clear()
        AppLog.record(Instant.EPOCH, AppLog.INFO, "OtherLib", "ignored")
        repeat(AppLog.CAPACITY + 5) { AppLog.record(Instant.EPOCH, AppLog.WARN, "FiberIRC.api", "line $it") }
        val lines = AppLog.lines()
        assertEquals(AppLog.CAPACITY, lines.size)
        assertEquals("1970-01-01T00:00:00Z [warn] FiberIRC.api: line 5", lines.first())
        assertTrue(lines.last().endsWith("line ${AppLog.CAPACITY + 4}"))
        AppLog.clear()
    }
}
