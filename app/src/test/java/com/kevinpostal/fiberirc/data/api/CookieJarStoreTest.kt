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

    @Test
    fun expiredCopyDeletesOnlyThatCookie() {
        val prefs = MemoryPrefs()
        val jar = CookieJarStore(prefs) { now }
        val other = "https://example.org/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("s", 3600), Cookie.parse(url, "watch=w; Max-Age=3600; Path=/; Secure; HttpOnly")!!))
        jar.saveFromResponse(other, listOf(Cookie.parse(other, "watch=x; Max-Age=3600")!!))
        // Same name, domain and path, already expired: what SiteApi.forgetSignupWatch saves.
        val watch = jar.loadForRequest(url).single { it.name == "watch" }
        val expired = Cookie.Builder().name(watch.name).value("").expiresAt(0L).path(watch.path)
            .hostOnlyDomain(watch.domain).secure().httpOnly().build()
        jar.saveFromResponse(url, listOf(expired))
        assertEquals(listOf("vibe.session_id"), jar.loadForRequest(url).map { it.name })
        assertEquals(listOf("x"), jar.loadForRequest(other).map { it.value })
        assertEquals(listOf("vibe.session_id"), CookieJarStore(prefs) { now }.loadForRequest(url).map { it.name })
    }
}
