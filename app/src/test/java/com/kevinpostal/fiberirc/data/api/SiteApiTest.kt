package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

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
        // Booleans and strings stay (a string such as `e2eeMode` or a future key survives); other JSON types drop out.
        assertEquals(
            mapOf("n1:#a" to mapOf("mute" to BufferPrefValue.Bool(true), "bad" to BufferPrefValue.Text("x"))),
            me.bufferPrefs,
        )
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

    @Test
    fun meDecodesNotificationPolicy() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"u1","username":"kev","muteAll":false,"notificationSound":true,"highlightWords":["outage","deploy"],
                "notificationPolicy":${policyViewJson()}}""",
            ),
        )
        val me = api.me().getOrThrow()
        val view = me.notificationPolicy!!
        assertEquals(12L, view.rev)
        assertEquals("focused", view.preset)
        assertEquals("a1b2c3", view.selfRef)
        assertEquals(listOf(PolicyHighlightWord("outage", true), PolicyHighlightWord("deploy", false)), view.policy.highlight.words)
        assertEquals(mapOf("dm.sound" to false), view.policy.clients["a1b2c3"]!!.cells)
        assertNull(view.policy.clients["a1b2c3"]!!.snoozeUntil)
        assertNull(view.capabilities.webPush)
        assertTrue(view.capabilities.email.available)
        assertEquals(3, view.presets.size)
        assertEquals(3, view.quietTemplates["nightsWeekends"]!!.size)
        assertEquals("android", view.clientsMeta[1].kind)
        // resolveCell: the install's override wins; otherwise the account cell.
        assertEquals(false, view.policy.resolveCell("a1b2c3", "dm", "sound"))
        assertEquals(true, view.policy.resolveCell("a1b2c3", "mention", "sound"))
        assertEquals(true, view.policy.resolveCell("web", "dm", "sound"))
        assertEquals(false, view.effective!!.matrix["dm"]!!.sound)
        assertEquals(false, me.muteAll)
    }

    @Test
    fun malformedNotificationPolicyKeepsMeDecodable() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"u1","username":"kev","notificationPolicy":{"policy":5,"rev":"x"}}"""))
        val me = api.me().getOrThrow()
        assertEquals("kev", me.username)
        assertNull(me.notificationPolicy)
        server.enqueue(MockResponse().setBody("""{"id":"u1","username":"kev","notificationPolicy":null}"""))
        assertNull(api.me().getOrThrow().notificationPolicy)
        server.enqueue(MockResponse().setBody("""{"id":"u1","username":"kev","notificationPolicy":[]}"""))
        assertNull(api.me().getOrThrow().notificationPolicy)
    }

    @Test
    fun policyPatchSendsIfMatchAndDecodesTheView() = runTest {
        server.enqueue(MockResponse().setBody(policyViewJson(rev = 13, preset = "everything")))
        val patch = buildJsonObject { put("applyPreset", "everything") }
        val view = api.patchNotificationPolicy(patch, 12).getOrThrow()
        assertEquals(13L, view.rev)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/notification-policy", req.path)
        assertEquals("\"np-12\"", req.getHeader("If-Match"))
        assertEquals("""{"applyPreset":"everything"}""", req.body.readUtf8())

        server.enqueue(MockResponse().setBody(policyViewJson(rev = 14)))
        api.patchNotificationClient("self", buildJsonObject { put("snoozeUntil", -1) }, 13).getOrThrow()
        val client = server.takeRequest()
        assertEquals("PATCH", client.method)
        assertEquals("/api/me/notification-policy/clients/self", client.path)
        assertEquals("\"np-13\"", client.getHeader("If-Match"))

        server.enqueue(MockResponse().setBody(policyViewJson(rev = 15)))
        api.snoozeNotifications(buildJsonObject { put("seconds", 3600); put("client", "self") }).getOrThrow()
        val snooze = server.takeRequest()
        assertEquals("POST", snooze.method)
        assertEquals("/api/me/notifications/snooze", snooze.path)
        assertNull(snooze.getHeader("If-Match"))
    }

    @Test
    fun policyConflictCarriesTheCurrentView() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":"The notification settings changed elsewhere; reload and retry","code":"rev_conflict",
                "current":${policyViewJson(rev = 20)}}""",
            ),
        )
        val error = api.patchNotificationPolicy(buildJsonObject { put("snoozeUntil", 0) }, 12).exceptionOrNull()
        assertTrue(error is SiteApiError.Conflict)
        error as SiteApiError.Conflict
        assertEquals("The notification settings changed elsewhere; reload and retry", error.message)
        assertEquals(20L, error.currentPolicy()!!.rev)
        // A 409 without a JSON body stays a plain failure.
        assertEquals(SiteApiError.Server(409), SiteApiError.failure(409, "nope"))
    }

    // MARK: Signup

    private fun formFields(body: String): Map<String, String> = body.split('&').associate { pair ->
        val (k, v) = pair.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun watchSet(value: String) = "${SiteApi.SIGNUP_WATCH_COOKIE}=$value; Max-Age=86400; Path=/; HttpOnly; SameSite=Lax"

    @Test
    fun registerWatchThenVerifiedStartsTheSession() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).addHeader("Set-Cookie", watchSet("w1"))
                .setBody("""{"status":"verification_sent","email":"k@e.x"}"""),
        )
        val password = " pw +é& "
        val r = api.register("kev", "k@e.x", password, "en-US,fr")
        assertEquals(SignupResult.VerificationSent("k@e.x"), r)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/register", post.path)
        // Sent as typed: the server, not the client, trims and validates.
        assertEquals(
            mapOf("username" to "kev", "email" to "k@e.x", "password" to password, "langs" to "en-US,fr"),
            formFields(post.body.readUtf8()),
        )
        assertTrue(api.hasSignupWatch())

        server.enqueue(MockResponse().setBody("""{"state":"pending"}"""))
        assertEquals(SignupWatchState.Pending, api.signupStatus().getOrThrow())
        val poll = server.takeRequest()
        assertEquals("GET", poll.method)
        assertEquals("/register/status", poll.path)
        assertEquals("${SiteApi.SIGNUP_WATCH_COOKIE}=w1", poll.getHeader("Cookie"))
        assertTrue(api.hasSignupWatch())

        server.enqueue(
            MockResponse().setBody("""{"state":"verified","next":"/register/continue"}""")
                .addHeader("Set-Cookie", "${SiteApi.SIGNUP_WATCH_COOKIE}=; Max-Age=0; Path=/")
                .addHeader("Set-Cookie", "vibe.session_id=s1; Max-Age=3600; Path=/; HttpOnly"),
        )
        assertEquals(SignupWatchState.Verified, api.signupStatus().getOrThrow())
        server.takeRequest()
        assertFalse(api.hasSignupWatch())
        // `next` is never navigated: the next call is the caller's own /api/me with the new session.
        server.enqueue(MockResponse().setBody(meJson))
        assertEquals("kev", api.me().getOrThrow().username)
        val me = server.takeRequest()
        assertEquals("/api/me", me.path)
        assertEquals("vibe.session_id=s1", me.getHeader("Cookie"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun registerRedirectSignsInThroughMe() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "/app")
                .addHeader("Set-Cookie", "vibe.session_id=s2; Max-Age=3600; Path=/; HttpOnly"),
        )
        server.enqueue(MockResponse().setBody(meJson))
        val r = api.register("kev", "k@e.x", "password1", "en")
        assertEquals("kev", (r as SignupResult.SignedIn).me.username)
        assertEquals("/register", server.takeRequest().path)
        val me = server.takeRequest()
        assertEquals("/api/me", me.path)
        assertEquals("vibe.session_id=s2", me.getHeader("Cookie"))
        assertEquals(2, server.requestCount)
        assertFalse(api.hasSignupWatch())
    }

    @Test
    fun registerRedirectWithoutSessionIsNotSignedIn() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        val r = api.register("kev", "k@e.x", "password1", "en")
        assertEquals(SignupResult.Failure(SiteApiError.Unauthorized), r)
    }

    @Test
    fun registerRejectionsKeepCodeAndNickAndNeverConflict() = runTest {
        val taken = """{"error":"The nickname \"kev\" is already registered","code":"nick_registered","params":{"nick":"kev"}}"""
        server.enqueue(MockResponse().setResponseCode(409).setBody(taken))
        val nick = (api.register("kev", "k@e.x", "password1", "en") as SignupResult.Failure).error
        assertEquals(SiteApiError.Rejected(409, "nick_registered", "The nickname \"kev\" is already registered", "kev"), nick)
        assertFalse(nick is SiteApiError.Conflict)
        assertEquals("The nickname \"kev\" is already registered", nick.message)

        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Taken","code":"username_taken"}"""))
        assertEquals(
            SiteApiError.Rejected(409, "username_taken", "Taken", null),
            (api.register("kev", "k@e.x", "password1", "en") as SignupResult.Failure).error,
        )
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Wait","code":"verification_cooldown"}"""))
        assertEquals(
            SiteApiError.Rejected(429, "verification_cooldown", "Wait", null),
            (api.register("kev", "k@e.x", "password1", "en") as SignupResult.Failure).error,
        )
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>down</html>"))
        assertEquals(SiteApiError.Server(503), (api.register("kev", "k@e.x", "password1", "en") as SignupResult.Failure).error)
        // No rejection bootstraps a session: only the four POSTs reached the server.
        assertEquals(4, server.requestCount)
        assertEquals(SiteApiError.Server(409), SiteApiError.registration(409, """{"error":""}"""))
    }

    @Test
    fun registerAcceptanceIsStrict() = runTest {
        for (body in listOf(
            """{"status":"ok","email":"k@e.x"}""",
            """{"status":"verification_sent","email":""}""",
            """{"status":"verification_sent"}""",
            """{"email":"k@e.x"}""",
            "not json",
        )) {
            server.enqueue(MockResponse().setResponseCode(202).setBody(body))
            assertEquals(body, SignupResult.Failure(SiteApiError.Malformed), api.register("kev", "k@e.x", "password1", "en"))
        }
    }

    @Test
    fun registerForgetsOnlyThePreviousWatch() = runTest {
        val site = server.url("/")
        jar.saveFromResponse(site, listOfNotNull(Cookie.parse(site, watchSet("old")), Cookie.parse(site, "vibe.session_id=s; Max-Age=3600; Path=/")))
        assertTrue(api.hasSignupWatch())
        // Accepted, but the server could not start a watch: no stale watch may pass for this attempt's.
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"verification_sent","email":"k@e.x"}"""))
        assertEquals(SignupResult.VerificationSent("k@e.x"), api.register("kev", "k@e.x", "password1", "en"))
        assertEquals("vibe.session_id=s", server.takeRequest().getHeader("Cookie"))
        assertFalse(api.hasSignupWatch())
        assertEquals(listOf("vibe.session_id"), jar.loadForRequest(site).map { it.name })
    }

    @Test
    fun signupStatusIsStrict() = runTest {
        server.enqueue(MockResponse().setBody("""{"state":"gone"}"""))
        assertEquals(SignupWatchState.Gone, api.signupStatus().getOrThrow())
        for (body in listOf("""{"state":"done"}""", """{"state":null}""", "{}", "[]", "nope")) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(body, SiteApiError.Malformed, api.signupStatus().exceptionOrNull())
        }
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Bad gateway"}"""))
        assertEquals(SiteApiError.Server(502), api.signupStatus().exceptionOrNull())
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(SiteApiError.Server(429), api.signupStatus().exceptionOrNull())
    }

    @Test
    fun forgetSignupWatchKeepsSessionAndOtherCookies() {
        val prefs = MemoryPrefs()
        val store = CookieJarStore(prefs)
        val https = SiteApi(OkHttpClient.Builder().followRedirects(false).cookieJar(store).build(), "https://ircfiber.com")
        val site = "https://ircfiber.com/register".toHttpUrl()
        val other = "https://example.org/".toHttpUrl()
        store.saveFromResponse(
            site,
            listOfNotNull(
                Cookie.parse(site, "${SiteApi.SIGNUP_WATCH_COOKIE}=w; Max-Age=86400; Path=/; Secure; HttpOnly; SameSite=Lax"),
                Cookie.parse(site, "vibe.session_id=s; Max-Age=3600; Path=/; Secure; HttpOnly"),
            ),
        )
        store.saveFromResponse(other, listOfNotNull(Cookie.parse(other, "${SiteApi.SIGNUP_WATCH_COOKIE}=x; Max-Age=3600")))
        assertTrue(https.hasSignupWatch())

        https.forgetSignupWatch()
        assertFalse(https.hasSignupWatch())
        assertEquals(listOf("vibe.session_id"), store.loadForRequest(site).map { it.name })
        assertEquals(listOf("x"), store.loadForRequest(other).map { it.value })
        // The deletion is persisted: a new process cannot resurrect the watch.
        assertEquals(listOf("vibe.session_id"), CookieJarStore(prefs).loadForRequest(site).map { it.name })
        // Nothing to forget is a no-op.
        https.forgetSignupWatch()
        assertEquals(listOf("vibe.session_id"), store.loadForRequest(site).map { it.name })
    }

    @Test
    fun registerTransportFailureIsThrownNotWrapped() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val thrown = try {
            api.register("kev", "k@e.x", "password1", "en")
            null
        } catch (e: IOException) {
            e
        }
        assertNotNull(thrown)
    }

    @Test
    fun cancelledRegisterIsNotAFailure() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var outcome: SignupResult? = null
        val job = launch(Dispatchers.IO) { outcome = api.register("kev", "k@e.x", "password1", "en") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(outcome)
    }

    @Test
    fun cancelledStatusPollThrowsCancellation() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var outcome: Result<SignupWatchState>? = null
        val job = launch(Dispatchers.IO) { outcome = api.signupStatus() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(outcome)
    }
}
