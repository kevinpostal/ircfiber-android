package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.api.CookieJarStore
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The native `http.request` allowlist, and the raw pass-through it feeds. */
class E2eeApiPolicyTest {
    private fun refused(method: String, path: String, hasBody: Boolean = false) {
        try {
            E2eeApiPolicy.check(method, path, hasBody)
            fail("$method $path should be refused")
        } catch (e: E2eeBridgeException) {
            assertEquals("bad_request", e.code)
        }
    }

    @Test
    fun encryptionApiPathsPass() {
        assertEquals(E2eeApiTarget("GET", "/kt/key", null), E2eeApiPolicy.check("GET", "/kt/key", false))
        assertEquals(E2eeApiTarget("GET", "/mls/policy", null), E2eeApiPolicy.check("GET", "/mls/policy", false))
        assertEquals(
            E2eeApiTarget("GET", "/mls/groups/0f3a/handshakes", "since=4"),
            E2eeApiPolicy.check("GET", "/mls/groups/0f3a/handshakes?since=4", false),
        )
        assertEquals(
            E2eeApiTarget("GET", "/kt/lookup", "account=a%2Fb"),
            E2eeApiPolicy.check("GET", "/kt/lookup?account=a%2Fb", false),
        )
        assertEquals(E2eeApiTarget("PUT", "/me/e2ee/backup", null), E2eeApiPolicy.check("PUT", "/me/e2ee/backup", true))
        assertEquals(E2eeApiTarget("DELETE", "/mls/devices/d-1", null), E2eeApiPolicy.check("DELETE", "/mls/devices/d-1", false))
    }

    @Test
    fun everythingElseIsRefused() {
        refused("PATCH", "/mls/policy")
        refused("GET", "/me")
        refused("GET", "/me/bouncer")
        refused("POST", "/login")
        refused("GET", "/mls")
        refused("GET", "/mls/")
        refused("GET", "/kt")
        refused("GET", "mls/policy")
        refused("GET", "https://evil.example/mls/policy")
        refused("GET", "//evil.example/mls/policy")
        refused("GET", "/mls/../me")
        refused("GET", "/mls/./policy")
        refused("GET", "/mls/%2e%2e/me")
        refused("GET", "/mls/%2E./me")
        refused("GET", "/mls/a%2fb")
        refused("GET", "/mls/a%5Cb")
        refused("GET", "/mls/a\\b")
        refused("GET", "/mls//policy")
        refused("GET", "/mls/policy#x")
        refused("GET", "/mls/pol icy")
        refused("GET", "/mls/policy\r\nX: y")
        refused("GET", "/mls/pölicy")
        refused("GET", "/mls/policy?a=%00")
        refused("GET", "/mls/policy", hasBody = true)
    }

    @Test
    fun rawRequestPassesStatusAndBodyAndNeverFollowsRedirects() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .cookieJar(CookieJarStore(MemoryPrefs())).build()
            val api = SiteApi(client, server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"group_superseded"}"""))
            val target = E2eeApiPolicy.check("POST", "/mls/groups/g1/commit?x=1", true)
            val answer = api.rawApiRequest(
                target.method,
                target.encodedPath,
                target.encodedQuery,
                buildJsonObject { put("commit", "AAAA") },
                mapOf("X-IRCFiber-MLS-Device" to "dev-1"),
            ).getOrThrow()
            assertEquals(409, answer.code)
            assertEquals("""{"error":"group_superseded"}""", answer.body)
            val recorded = server.takeRequest()
            assertEquals("/api/mls/groups/g1/commit?x=1", recorded.path)
            assertEquals("dev-1", recorded.getHeader("X-IRCFiber-MLS-Device"))
            assertEquals("""{"commit":"AAAA"}""", recorded.body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
            val redirect = api.rawApiRequest("GET", "/kt/key", null, null, emptyMap()).getOrThrow()
            assertEquals(302, redirect.code)
            assertEquals(2, server.requestCount)
            assertNull(server.takeRequest().getHeader("X-IRCFiber-MLS-Device"))
        } finally {
            server.shutdown()
        }
    }
}
