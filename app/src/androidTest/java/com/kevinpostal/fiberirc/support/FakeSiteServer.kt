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

    /**
     * Notification Policy v2: GET `/api/me/notification-policy` answers [view]; the policy and `clients/self`
     * PATCHes and POST `/api/me/notifications/snooze` answer [onWrite] for the request (by default [view]).
     */
    fun defaultsNotificationPolicy(view: String, onWrite: (RecordedRequest) -> MockResponse = { MockResponse().setBody(view) }) {
        on("GET", "/api/me/notification-policy", body = view)
        for ((method, path) in listOf(
            "PATCH" to "/api/me/notification-policy",
            "PATCH" to "/api/me/notification-policy/clients/self",
            "POST" to "/api/me/notifications/snooze",
        )) {
            routes["$method $path"] = { onWrite(seen.last { it.method == method && it.path?.substringBefore('?') == path }) }
        }
    }
}

/**
 * A `NotificationPolicyView` (gateway `policyView` with `X-IRCFiber-Device`) at [rev]: the `focused` or `everything`
 * matrix per [preset], this install known as `self01` without overrides, quiet hours off.
 */
fun fakePolicyViewJson(rev: Long, preset: String = "focused"): String {
    fun cells(d: Boolean, s: Boolean, m: Boolean) = """{"desktop":$d,"sound":$s,"mobile":$m,"email":false}"""
    fun p(d: Boolean, s: Boolean, m: Boolean) = """{"desktop":$d,"sound":$s,"mobile":$m}"""
    val matrix = if (preset == "everything") {
        """{"dm":${cells(true, true, true)},"mention":${cells(true, true, true)},"all":${cells(true, true, true)},
           "invite":${cells(true, true, true)},"notice":${cells(true, true, true)},"wallops":${cells(true, false, true)}}"""
    } else {
        """{"dm":${cells(true, true, true)},"mention":${cells(true, true, true)},"all":${cells(true, false, false)},
           "invite":${cells(true, false, true)},"notice":${cells(false, false, false)},"wallops":${cells(false, false, false)}}"""
    }
    return """
    {"policy":{"v":2,"rev":$rev,"matrix":$matrix,"snoozeUntil":0,
      "quiet":{"enabled":false,"tz":"","tzMode":"account","windows":[{"days":[0,1,2,3,4,5,6],"start":"22:00","end":"08:00"}],
               "breakthrough":{"vip":[],"repeatSec":180,"urgentWords":true},"summary":true},
      "highlight":{"words":[{"w":"outage","urgent":true}],"wholeWord":true,"caseSensitive":false},
      "presentation":{"preview":"full","coalesceSec":30,"autoDismiss":true},"email":{"mode":"delay15","dailyHour":8},
      "networks":{},"clients":{},"migratedAt":0,"bannerDismissed":false},
     "rev":$rev,"prefVersion":${100 + rev},"preset":"$preset",
     "presets":{
       "everything":{"dm":${p(true, true, true)},"mention":${p(true, true, true)},"all":${p(true, true, true)},
                     "invite":${p(true, true, true)},"notice":${p(true, true, true)},"wallops":${p(true, false, true)}},
       "focused":{"dm":${p(true, true, true)},"mention":${p(true, true, true)},"all":${p(true, false, false)},
                  "invite":${p(true, false, true)},"notice":${p(false, false, false)},"wallops":${p(false, false, false)}},
       "quiet":{"dm":${p(true, false, true)},"mention":${p(true, false, false)},"all":${p(false, false, false)},
                "invite":${p(false, false, false)},"notice":${p(false, false, false)},"wallops":${p(false, false, false)}}},
     "quietTemplates":{"nights":[{"days":[0,1,2,3,4,5,6],"start":"22:00","end":"08:00"}]},
     "clientsMeta":[{"ref":"web","kind":"web","label":"Web browsers"},
                    {"ref":"self01","kind":"android","label":"Test phone","appVersion":"1.0 (1)","lastSeenAt":0,"push":true,"tz":"UTC"}],
     "status":{"snoozedUntil":0,"quietNow":false,"quietEndsAt":0,"nextQuietStartsAt":0},
     "capabilities":{"webPush":null,"email":{"available":true,"address":"t•••@example.com"}},
     "selfRef":"self01"}
    """.trimIndent()
}

/** `Me` JSON with the given username/id, every list/map field empty — the smallest valid `/api/me` answer. */
fun fakeMeJson(id: String = "u1", username: String = "tester", email: String = "tester@example.com") = """
    {"id":"$id","username":"$username","email":"$email","ignores":[],"pinnedChannels":[],"archivedChannels":[],
     "collapsed":{},"networkOrder":[],"bufferPrefs":{},"highlightWords":[]}
""".trimIndent()
