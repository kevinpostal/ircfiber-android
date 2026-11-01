package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SiteApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().followRedirects(false).cookieJar(CookieJarStore(MemoryPrefs())).build()
        api = SiteApi(client, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun loginSessionsDecode() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"sessions":[{"ref":"r1","createdAt":1,"lastAccess":2,"expiresAt":3,"clientIp":"1.2.3.4",
                "userAgent":"FiberIRC Android","current":true,"clientCount":0}],"now":99}""",
            ),
        )
        val answer = api.loginSessions().getOrThrow()
        assertEquals(99L, answer.now)
        assertEquals("r1", answer.sessions.single().ref)
        assertTrue(answer.sessions.single().current)
        assertEquals("/api/me/sessions", server.takeRequest().path)
    }

    @Test
    fun revokeSessionEncodesRefAsOneSegment() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.revokeSession("a/b").isSuccess)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/me/sessions/a%2Fb", request.path)
    }

    @Test
    fun wrongCurrentPasswordIsTheServersMessageNotSignOut() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Current password is incorrect"}"""))
        assertEquals(SiteApiError.Message("Current password is incorrect"), api.changePassword("a", "bbbbbbbb").exceptionOrNull())
        server.enqueue(MockResponse().setBody("""{"changed":true,"sessionsEnded":3}"""))
        assertEquals(3, api.changePassword("a", "bbbbbbbb").getOrThrow())
        server.takeRequest()
        assertEquals("""{"oldPassword":"a","newPassword":"bbbbbbbb"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun bouncerAccessSendsOnlyGivenKeys() = runTest {
        server.enqueue(MockResponse().setBody("""{"requireTls":true,"maxClients":3,"maxClientsCeiling":10}"""))
        val saved = api.updateBouncerAccess(requireTls = true).getOrThrow()
        assertEquals(BouncerAccessSettings(true, 3, 10), saved)
        val request = server.takeRequest()
        assertEquals("/api/me/bouncer/settings", request.path)
        assertEquals("""{"requireTls":true}""", request.body.readUtf8())
    }

    @Test
    fun bouncerClientsAndActions() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"clients":[{"sid":"s1","networkId":"n","networkName":"Libera","clientId":"weechat","nick":"k",
                "peer":"1.2.3.4","tls":true,"attachedAt":5,"lastRecvMs":6,"lastSendMs":7}],"now":10}""",
            ),
        )
        val clients = api.bouncerClients().getOrThrow()
        assertNull(clients.clients.single().kind)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.disconnectBouncerClient("s1").isSuccess)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.resetBouncerDevice("weechat").isSuccess)
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.revokeBouncerPassword().isSuccess)
        server.takeRequest()
        assertEquals("POST /api/me/bouncer/clients/s1/disconnect", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /api/me/bouncer/devices/weechat", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /api/me/bouncer", server.takeRequest().let { "${it.method} ${it.path}" })
    }

    @Test
    fun myDeviceNullAndPresent() = runTest {
        server.enqueue(MockResponse().setBody("""{"device":null,"now":1}"""))
        assertNull(api.myDevice().getOrThrow().device)
        server.enqueue(
            MockResponse().setBody(
                """{"device":{"ref":"d","model":"Pixel_8","osVersion":"15","appVersion":"1.0.0 (1)","lastIp":"x",
                "createdAt":1,"updatedAt":2,"lastSeenAt":3,"push":{"on":false},"settings":{},
                "bouncer":{"online":true,"networks":[{"networkId":"n","networkName":"Libera"}]}},"now":4}""",
            ),
        )
        val d = api.myDevice().getOrThrow().device!!
        assertEquals("Pixel_8", d.model)
        assertFalse(d.push.on)
        assertEquals("Libera", d.bouncer!!.networks.single().networkName)
    }
}
