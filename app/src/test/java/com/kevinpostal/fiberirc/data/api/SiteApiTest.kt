package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SiteApiTest {
    private lateinit var server: MockWebServer
    private lateinit var jar: CookieJarStore
    private lateinit var api: SiteApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        jar = CookieJarStore(MemoryPrefs())
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).cookieJar(jar).build()
        api = SiteApi(client, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    private val meJson = """{"id":"u1","username":"kev","email":"k@e.x","prefVersion":7,"membersCollapsed":true,
        "pinnedChannels":["n1:#a"],"bufferPrefs":{"n1:#a":{"mute":true,"bad":"x","n":1},"n1:#b":"nope","n1:#c":{}}}"""

    @Test
    fun segmentsAreEncodedWhole() {
        val url = api.url("api", "me", "pins", "net", "#chan/x?y")
        assertEquals("/api/me/pins/net/%23chan%2Fx%3Fy", url.encodedPath)
    }

    @Test
    fun meDecodesLenientlyAndIgnoresUnknownFields() = runTest {
        server.enqueue(MockResponse().setBody(meJson))
        val me = api.me().getOrThrow()
        assertEquals("kev", me.username)
        assertEquals(listOf("n1:#a"), me.pinnedChannels)
        assertEquals(mapOf("n1:#a" to mapOf("mute" to true)), me.bufferPrefs)
        assertNull(me.displayPrefs)
    }

    @Test
    fun meWithoutSessionIsUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        assertEquals(SiteApiError.Unauthorized, api.me().exceptionOrNull())
    }

    @Test
    fun loginRedirectStoresCookieThenLoadsMe() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/")
                .addHeader("Set-Cookie", "vibe.session_id=abc; Max-Age=3600; Path=/; HttpOnly"),
        )
        server.enqueue(MockResponse().setBody(meJson))
        val r = api.login("kev", "pw")
        assertTrue(r is LoginResult.Success)
        val form = server.takeRequest()
        assertEquals("POST", form.method)
        assertEquals("username=kev&password=pw", form.body.readUtf8())
        assertEquals("vibe.session_id=abc", server.takeRequest().getHeader("Cookie"))

        server.enqueue(MockResponse().setBody("<html>"))
        api.logout()
        assertTrue(jar.loadForRequest(server.url("/api/me")).isEmpty())
    }

    @Test
    fun loginFailuresMapLikeIos() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(SiteApiError.BadCredentials, (api.login("a", "b") as LoginResult.Failure).error)
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(SiteApiError.BadCredentials, (api.login("a", "b") as LoginResult.Failure).error)
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(SiteApiError.Server(429), (api.login("a", "b") as LoginResult.Failure).error)
    }

    @Test
    fun failureMapping() {
        assertEquals(SiteApiError.Unauthorized, SiteApiError.failure(401, """{"error":"Unauthorized"}"""))
        assertEquals(SiteApiError.Unauthorized, SiteApiError.failure(401, ""))
        assertEquals(SiteApiError.Message("Wrong password"), SiteApiError.failure(401, """{"error":"Wrong password"}"""))
        assertEquals(SiteApiError.Message("Nope"), SiteApiError.failure(403, """{"error":"Nope"}"""))
        assertEquals(SiteApiError.Server(500), SiteApiError.failure(500, "oops"))
        assertEquals(SiteApiError.Server(400), SiteApiError.failure(400, """{"error":""}"""))
    }

    @Test
    fun postJsonSendsBodyAndMapsErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val ok = api.postJson("api/me/pins", buildJsonObject { put("network", "n"); put("channel", "#c") })
        assertTrue(ok.isSuccess)
        val req = server.takeRequest()
        assertEquals("/api/me/pins", req.path)
        assertEquals("""{"network":"n","channel":"#c"}""", req.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Invalid channel."}"""))
        assertEquals(SiteApiError.Message("Invalid channel."), api.deletePath(listOf("api", "me", "pins", "n", "#c")).exceptionOrNull())
        assertEquals("/api/me/pins/n/%23c", server.takeRequest().path)
    }

    @Test
    fun appSessionRejectsBadCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(SiteApiError.Unauthorized, api.appSession("x").exceptionOrNull())
        server.enqueue(MockResponse().setBody("""{"id":"1","username":"kev","email":"e"}"""))
        assertEquals("kev", api.appSession("good").getOrThrow().username)
    }
}
