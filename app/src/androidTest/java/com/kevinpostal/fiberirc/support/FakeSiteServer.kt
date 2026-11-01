package com.kevinpostal.fiberirc.support

import android.content.Context
import com.kevinpostal.fiberirc.data.api.CookieJarStore
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.fiberIrcHttpClient
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest

/**
 * A local ircfiber.com stand-in for instrumented E2E tests: a real [MockWebServer] (reachable at `127.0.0.1` from
 * the emulator the test runs on) in front of a route table keyed by `"<METHOD> <path>"`. Unmatched requests get a
 * 200 with an empty JSON object, so a test only has to stub the endpoints its scenario actually exercises — call
 * [on]/[onEach] before the call happens, or use [defaultsSignedOut]/[defaultsPasswordLogin] for a whole bootstrap
 * chain.
 *
 * [siteApi] is the production [SiteApi] class pointed at this server's `127.0.0.1` URL: the app's real HTTP calls
 * run unmodified, only the network destination changes.
 */
class FakeSiteServer(private val context: Context) {
    val server = MockWebServer().also { it.start() }
    private val routes = LinkedHashMap<String, () -> MockResponse>()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()

    init {
        server.dispatcher = object : QueueDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                val key = "${request.method} ${request.path?.substringBefore('?')}"
                routes[key]?.let { return it() }
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
    }

    val baseUrl: String get() = server.url("/").toString()

    /** Requests received so far, in order. */
    fun requests(): List<RecordedRequest> = seen.toList()

    /** Routes `method path` (no query string) to a fixed JSON [body]/[code] every time it's hit. */
    fun on(method: String, path: String, code: Int = 200, body: String = "{}") {
        routes["$method $path"] = { MockResponse().setResponseCode(code).setBody(body) }
    }

    /** Routes `method path` to a freshly computed response each time (e.g. a counter, or a redirect `Location`). */
    fun onEach(method: String, path: String, respond: () -> MockResponse) {
        routes["$method $path"] = respond
    }

    /** The real [SiteApi], HTTP client and cookie jar wired to this server, as `Deps` wires them to production. */
    fun siteApi(deviceId: () -> String = { "test-device" }): SiteApi {
        val cookieJar = CookieJarStore(context.getSharedPreferences("test_cookies_${System.nanoTime()}", Context.MODE_PRIVATE))
        val client = fiberIrcHttpClient(cookieJar, deviceId)
        return SiteApi(client, baseUrl)
    }

    fun shutdown() {
        server.shutdown()
    }

    /** `GET /api/me` → 401 and no social providers: the sign-in form shows empty, nothing stored. */
    fun defaultsSignedOut() {
        on("GET", "/api/me", code = 401, body = """{"error":"Unauthorized"}""")
        on("GET", "/api/auth/providers", body = """{"providers":[]}""")
    }

    /** `GET /api/me` is 401 (no stored session, so [com.kevinpostal.fiberirc.ui.login.LoginViewModel.resumeSession]
     * shows the form) until `POST /login` succeeds, then answers [me]; a disabled bouncer (direct-ircd fallback). */
    fun defaultsPasswordLogin(me: String) {
        on("GET", "/api/auth/providers", body = """{"providers":[]}""")
        var loggedIn = false
        onEach("POST", "/login") { loggedIn = true; MockResponse().setResponseCode(302).setHeader("Location", "/") }
        onEach("GET", "/api/me") {
            if (loggedIn) MockResponse().setResponseCode(200).setBody(me)
            else MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}""")
        }
        on("GET", "/api/me/bouncer", body = """{"enabled":false,"host":"","port":0,"tls":false,"username":""}""")
        // "ready" with an account/password: a "none"/"pending" status makes the real app poll up to 8× every
        // 2.5s (SessionBootstrap.ircAccountWaitingForReady) before connectDirect finishes.
        on("GET", "/api/me/irc-account", body = """{"status":"ready","account":"tester","password":"x"}""")
        on("POST", "/api/me/irc-account/retry")
    }
}

/** `Me` JSON with the given username/id, every list/map field empty — the smallest valid `/api/me` answer. */
fun fakeMeJson(id: String = "u1", username: String = "tester", email: String = "tester@example.com") = """
    {"id":"$id","username":"$username","email":"$email","ignores":[],"pinnedChannels":[],"archivedChannels":[],
     "collapsed":{},"networkOrder":[],"bufferPrefs":{},"highlightWords":[]}
""".trimIndent()
