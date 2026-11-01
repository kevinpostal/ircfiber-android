package com.kevinpostal.fiberirc.data.irc

import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModerationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SiteApi
    private val time = Instant.parse("2026-10-31T12:34:56.789Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = SiteApi(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun confirmTextMatchesIos() {
        assertEquals("Block bob?", Moderation.blockTitle("bob"))
        assertEquals("You won't see messages from bob in this app or on ircfiber.com.", Moderation.blockMessage("bob"))
        assertEquals("Report sent. Moderators review reports within 24 hours.", Moderation.REPORT_SENT_MESSAGE)
    }

    @Test
    fun payloadCarriesContextAndTrimmedNote() {
        val p = Moderation.reportPayload("bob", "spam", "#chan", "IRC Fiber", time, "  rude  ", appVersion = "FiberIRC Android 1.0.0 (1)")
        assertEquals("other", p["kind"]!!.jsonPrimitive.content)
        assertEquals("Report: bob in #chan", p["title"]!!.jsonPrimitive.content)
        assertEquals(
            "Reported message from bob in #chan on IRC Fiber at 2026-10-31T12:34:56Z:\n\nspam\n\nReporter note: rude",
            p["body"]!!.jsonPrimitive.content,
        )
        val ctx = p["context"]!!.jsonObject
        assertEquals("FiberIRC Android 1.0.0 (1)", ctx["appVersion"]!!.jsonPrimitive.content)
        assertEquals("#chan", ctx["bufferName"]!!.jsonPrimitive.content)
        assertEquals("FiberIRC Android", ctx["userAgent"]!!.jsonPrimitive.content)
    }

    @Test
    fun payloadCapsTitleAndBodyAndDefaultsTheNote() {
        val p = Moderation.reportPayload("n".repeat(200), "x".repeat(6000), "#c", "net", time, "   ")
        assertEquals(120, p["title"]!!.jsonPrimitive.content.length)
        assertEquals(5000, p["body"]!!.jsonPrimitive.content.length)
        val short = Moderation.reportPayload("bob", "hi", "#c", "net", time, null)
        assertTrue(short["body"]!!.jsonPrimitive.content.endsWith("Reporter note: (none)"))
        assertTrue(short["context"]!!.jsonObject["appVersion"]!!.jsonPrimitive.content.startsWith("FiberIRC Android "))
    }

    @Test
    fun reportPostsToSupportIssues() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"i1"}"""))
        val r = Moderation.reportMessage(api, "bob", "spam", "#chan", "net", time, "")
        assertTrue(r.isSuccess)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/support/issues", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("Report: bob in #chan", body["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun reportThrottleMapsLikeIos() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(SiteApiError.RateLimited, Moderation.reportMessage(api, "b", "t", "#c", "n", time, null).exceptionOrNull())
        // The server's own explanation wins when it sends one.
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many reports"}"""))
        assertEquals(
            SiteApiError.Message("Too many reports"),
            Moderation.reportMessage(api, "b", "t", "#c", "n", time, null).exceptionOrNull(),
        )
    }

    @Test
    fun blockAppliesOnlyAfterTheSaveSucceeds() = runTest {
        var fail = true
        val saved = ArrayList<List<String>>()
        val store = BlockListStore { masks -> if (fail) throw IOException("offline") else saved += masks }
        assertTrue(Moderation.block(store, "bob").isFailure)
        assertEquals(emptyList<String>(), store.masks.value)
        fail = false
        assertTrue(Moderation.block(store, "bob").isSuccess)
        assertEquals(listOf("bob!*@*"), store.masks.value)
        assertTrue(Moderation.unblock(store, "BOB").isSuccess)
        assertEquals(emptyList<String>(), store.masks.value)
        assertEquals(listOf(listOf("bob!*@*"), emptyList()), saved)
    }
}
