package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieJarStoreTest {
    private val url = "https://ircfiber.com/api/me".toHttpUrl()
    private var now = 1_000_000L

    private fun cookie(value: String, maxAgeS: Long) = Cookie.parse(url, "vibe.session_id=$value; Max-Age=$maxAgeS; Path=/")!!

    @Test
    fun persistsAndSlidesExpiry() {
        val prefs = MemoryPrefs()
        val jar = CookieJarStore(prefs) { now }
        jar.saveFromResponse(url, listOf(cookie("a", 60)))
        jar.saveFromResponse(url, listOf(cookie("b", 3600)))
        assertEquals(1, prefs.map.size)
        // A new process reads the same file.
        val reloaded = CookieJarStore(prefs) { now }
        assertEquals(listOf("b"), reloaded.loadForRequest(url).map { it.value })
    }

    @Test
    fun expiredCookiesDropOut() {
        val prefs = MemoryPrefs()
        val jar = CookieJarStore(prefs) { now }
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("s").value("v").domain("ircfiber.com").expiresAt(now + 10).build()))
        now += 20
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun clearForgetsHostCookiesOnly() {
        val jar = CookieJarStore(MemoryPrefs()) { now }
        jar.saveFromResponse(url, listOf(cookie("a", 3600)))
        val other = "https://example.org/".toHttpUrl()
        jar.saveFromResponse(other, listOf(Cookie.parse(other, "x=1; Max-Age=3600")!!))
        jar.clear("ircfiber.com")
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertEquals(1, jar.loadForRequest(other).size)
    }
}
