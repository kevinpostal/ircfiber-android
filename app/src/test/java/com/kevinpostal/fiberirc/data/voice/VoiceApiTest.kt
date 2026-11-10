package com.kevinpostal.fiberirc.data.voice

import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class VoiceApiTest {
    private val server = MockWebServer().apply { start() }
    private val cookie = Cookie.Builder().name("vibe.session_id").value("owned-login").hostOnlyDomain(server.hostName).path("/").build()
    private val site = SiteApi(OkHttpClient.Builder().followRedirects(false).cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { }
        override fun loadForRequest(url: HttpUrl) = listOf(cookie)
    }).build(), server.url("/").toString())
    private val api = VoiceApi(site, "in-memory-instance")
    @After fun close() { server.shutdown() }
    private fun value(group: String, index: Int = 0): JsonElement {
        val root = javaClass.classLoader!!.getResourceAsStream("voice-v1.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject
        return root.getValue(group).jsonArray[index].jsonObject.getValue("value")
    }
    @Test fun errorsRetainStatusCodeParamsAndRetryAfter() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17")
            .setBody("""{"error":"Please wait","code":"voice_rate_limited","params":{"seconds":17}}"""))
        val error = runCatching { api.snapshot() }.exceptionOrNull() as VoiceApiError
        assertEquals(429, error.status); assertEquals("voice_rate_limited", error.code)
        assertEquals(17, error.params.jsonObject.getValue("seconds").jsonPrimitive.int)
        assertEquals("17", error.retryAfter)
    }
    @Test fun activeElsewhereAndConcurrentReplacementRemainDistinctErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Active elsewhere","code":"voice_active_elsewhere","params":{}}"""))
        assertEquals("voice_active_elsewhere", (runCatching { api.join("room", null, "request-one") }.exceptionOrNull() as VoiceApiError).code)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Changed","code":"voice_conflict","params":{}}"""))
        assertEquals("voice_conflict", (runCatching { api.join("room", "exact-old-session", "request-two") }.exceptionOrNull() as VoiceApiError).code)
    }
    @Test fun lostAccountFencesAllLaterControlRequests() = runTest {
        val api = VoiceApi(site, "old-instance") { false }
        assertTrue(runCatching { api.snapshot() }.exceptionOrNull() is CancellationException)
        assertNull(server.takeRequest(25, TimeUnit.MILLISECONDS))
    }
    @Test fun proxyHtmlErrorsStillPreserveStatusAndRetryAfter() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "3").setBody("<html>unavailable</html>"))
        val error = runCatching { api.snapshot() }.exceptionOrNull() as VoiceApiError
        assertEquals(503, error.status)
        assertEquals("3", error.retryAfter)
    }
    @Test fun eventsCannotSmuggleOwnerProjectionOrMixMlsIntoVoice() {
        assertTrue(voiceEventsOf("""{"type":"mls_welcome","token":"not-voice"}""").isEmpty())
        assertTrue(voiceEventsOf("""{"type":"voice_session","sessionId":"s","controlRevision":1,"ownedByThisClient":true}""").isEmpty())
        assertTrue(voiceEventsOf("""{"type":{"voice_bad":true}}""").isEmpty())
        val directory = value("events", 0)
        assertEquals(VoiceEventKind.DIRECTORY, voiceEventsOf("[$directory,{\"type\":\"stat_user\"}]").single().type)
    }
}
