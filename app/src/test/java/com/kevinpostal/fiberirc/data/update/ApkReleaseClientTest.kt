package com.kevinpostal.fiberirc.data.update

import com.kevinpostal.fiberirc.data.api.USER_AGENT
import com.kevinpostal.fiberirc.data.update.ApkReleaseClient.Companion.APK_ASSET
import com.kevinpostal.fiberirc.data.update.ApkReleaseClient.Companion.MANIFEST_ASSET
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApkReleaseClientTest {
    private lateinit var server: MockWebServer
    private val recorded = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /**
     * The production plain client, with validated `https://github.com` asset destinations mapped onto the
     * MockWebServer. Production destination checks stay untouched: only the transport target changes.
     */
    private fun client(deadlineMillis: Long = 5_000) = ApkReleaseClient(
        client = ApkReleaseClient.plainHttpClient().newBuilder()
            .addInterceptor { chain ->
                val url = chain.request().url
                val target = if (url.scheme == "https" && url.host == "github.com") server.url(url.encodedPath) else url
                chain.proceed(chain.request().newBuilder().url(target).build())
            }
            .build(),
        releasesUrl = server.url(RELEASES_PATH),
        deadlineMillis = deadlineMillis,
    )

    private fun takeAll(): List<RecordedRequest> {
        while (true) recorded += server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
        return recorded
    }

    private suspend fun unavailable(target: ApkReleaseClient = client()): ApkReleaseUnavailableException {
        val release = try {
            target.latest()
        } catch (e: ApkReleaseUnavailableException) {
            return e
        }
        throw AssertionError("expected unavailable, got $release")
    }

    // ── Selection ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun selectsHighestNumericTagRegardlessOfApiOrStringOrder() = runTest {
        server.enqueue(page(complete("1.9.0"), complete("1.10.0"), complete("1.2")))
        server.enqueue(json(manifest(code = 12, name = "1.10.0", minSdk = 28)))

        val release = client().latest()

        assertEquals(
            ApkRelease(12, "1.10.0", 28, "https://github.com/kevinpostal/ircfiber-pages/releases/download/android-v1.10.0/ircfiber-android.apk"),
            release,
        )
        val requests = takeAll()
        assertEquals("/kevinpostal/ircfiber-pages/releases/download/android-v1.10.0/android-update.json", requests[1].path)
    }

    @Test
    fun comparesMissingComponentsAsZero() = runTest {
        server.enqueue(page(complete("1.99.99"), complete("2")))
        server.enqueue(json(manifest(code = 5, name = "2")))

        assertEquals("2", client().latest().versionName)
    }

    @Test
    fun numericallyEqualTagsAreAmbiguous() = runTest {
        server.enqueue(page(complete("2"), complete("2.0"), complete("1.0")))

        unavailable()
        assertEquals("no manifest is read for an ambiguous pick", 1, takeAll().size)
    }

    @Test
    fun ignoresUnrelatedPrereleaseDraftUnpublishedAndLegacyReleases() = runTest {
        server.enqueue(
            page(
                complete("1.0.1"),
                complete("9.0.0", draft = true),
                complete("8.0.0", prerelease = true),
                complete("7.0.0", publishedAt = null),
                release("android-v6.0.0", asset("android-v6.0.0", APK_ASSET)), // legacy: APK only, no manifest
                release("android-v99999999999", asset("android-v99999999999", APK_ASSET)), // legacy, never a candidate
                release("android-v3.5.0", asset("android-v3.5.0", "other.apk"), asset("android-v3.5.0", MANIFEST_ASSET)),
                release("android-v5.0.0-beta", asset("android-v5.0.0-beta", APK_ASSET), asset("android-v5.0.0-beta", MANIFEST_ASSET)),
                release("android-v5.", asset("android-v5.", APK_ASSET), asset("android-v5.", MANIFEST_ASSET)),
                release("v20.0.0", asset("v20.0.0", APK_ASSET), asset("v20.0.0", MANIFEST_ASSET)),
                release("ios-v30.0", asset("ios-v30.0", APK_ASSET), asset("ios-v30.0", MANIFEST_ASSET)),
                """{"tag_name":"android-v3.6.0","draft":"false","prerelease":false,"published_at":"2026-11-01T00:00:00Z","assets":[${asset("android-v3.6.0", APK_ASSET)},${asset("android-v3.6.0", MANIFEST_ASSET)}]}""",
            ),
        )
        server.enqueue(json(manifest(code = 2, name = "1.0.1")))

        val release = client().latest()

        assertEquals(ApkRelease(2, "1.0.1", 26, "$DOWNLOAD/android-v1.0.1/$APK_ASSET"), release)
        assertEquals("$MANIFEST_PREFIX/android-v1.0.1/$MANIFEST_ASSET", takeAll()[1].path)
    }

    @Test
    fun invalidNewestCompleteReleaseNeverFallsBackToAnOlderValidOne() = runTest {
        val tag = "android-v3.0.0"
        val invalidNewest = listOf(
            release(tag, asset(tag, APK_ASSET), asset(tag, MANIFEST_ASSET, state = "open")),
            release(tag, asset(tag, APK_ASSET, state = "starter"), asset(tag, MANIFEST_ASSET)),
            release(tag, asset(tag, APK_ASSET), asset(tag, MANIFEST_ASSET, url = "$DOWNLOAD/android-v1.0.1/$MANIFEST_ASSET")),
            release(tag, asset(tag, APK_ASSET, url = "https://github.com/evil/ircfiber-pages/releases/download/$tag/$APK_ASSET"), asset(tag, MANIFEST_ASSET)),
            release(tag, asset(tag, APK_ASSET, url = "http://github.com/kevinpostal/ircfiber-pages/releases/download/$tag/$APK_ASSET"), asset(tag, MANIFEST_ASSET)),
            release(tag, asset(tag, APK_ASSET, url = "https://user@github.com/kevinpostal/ircfiber-pages/releases/download/$tag/$APK_ASSET"), asset(tag, MANIFEST_ASSET)),
            release(tag, asset(tag, APK_ASSET, url = "$DOWNLOAD/$tag/$APK_ASSET?x=1"), asset(tag, MANIFEST_ASSET)),
            release(tag, asset(tag, APK_ASSET, url = "$DOWNLOAD/$tag/$APK_ASSET#x"), asset(tag, MANIFEST_ASSET)),
            release(tag, asset(tag, APK_ASSET), """{"name":"$MANIFEST_ASSET","state":"uploaded"}"""),
            // A complete numeric tag whose component overflows can't be ranked, so it can't be skipped either.
            complete("1.99999999999"),
        )
        for (newest in invalidNewest) {
            server.enqueue(page(complete("1.0.1"), newest))
            unavailable()
            assertEquals("no manifest fetched for $newest", 1, takeAll().size)
            recorded.clear()
        }
    }

    @Test
    fun onlyLegacyReleasesAreUnavailableWithoutReadingAManifest() = runTest {
        server.enqueue(page(release("android-v1.0.0", asset("android-v1.0.0", APK_ASSET))))

        unavailable()
        assertEquals(1, takeAll().size)
    }

    @Test
    fun noReleasesAtAllIsUnavailable() = runTest {
        server.enqueue(json("[]"))

        unavailable()
    }

    // ── Pagination ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun readsEveryPageUntilExhaustion() = runTest {
        val fullPage = (1..99).map { release("web-v$it", asset("web-v$it", "site.zip")) } + complete("1.9")
        server.enqueue(page(*fullPage.toTypedArray()))
        server.enqueue(page(complete("1.10"), complete("1.8")))
        server.enqueue(json(manifest(code = 3, name = "1.10")))

        assertEquals("1.10", client().latest().versionName)

        val requests = takeAll()
        assertEquals(
            listOf(
                "$RELEASES_PATH?per_page=100&page=1",
                "$RELEASES_PATH?per_page=100&page=2",
                "$MANIFEST_PREFIX/android-v1.10/$MANIFEST_ASSET",
            ),
            requests.map { it.path },
        )
    }

    @Test
    fun aFullLastPageIsFollowedByAnEmptyOne() = runTest {
        server.enqueue(page(*((1..99).map { release("web-v$it") } + complete("1.0.1")).toTypedArray()))
        server.enqueue(json("[]"))
        server.enqueue(json(manifest(code = 2, name = "1.0.1")))

        assertEquals(2, client().latest().versionCode)
        assertEquals(3, takeAll().size)
    }

    @Test
    fun failedLaterPageIsIncompleteEvenWithACandidateAlreadySeen() = runTest {
        server.enqueue(page(*((1..99).map { release("web-v$it") } + complete("1.0.1")).toTypedArray()))
        server.enqueue(MockResponse().setResponseCode(502))

        unavailable()
        assertEquals("no manifest after incomplete pagination", 2, takeAll().size)
    }

    @Test
    fun malformedLaterPageIsIncomplete() = runTest {
        server.enqueue(page(*((1..99).map { release("web-v$it") } + complete("1.0.1")).toTypedArray()))
        server.enqueue(json("""{"message":"Server Error"}"""))

        unavailable()
        assertEquals(2, takeAll().size)
    }

    @Test
    fun droppedConnectionOnLaterPageIsIncomplete() = runTest {
        server.enqueue(page(*((1..99).map { release("web-v$it") } + complete("1.0.1")).toTypedArray()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY).setBody("[" + "x".repeat(4096)))

        unavailable()
    }

    // ── Manifest strictness ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun rejectsManifestsThatDoNotMatchTheSelectedRelease() = runTest {
        val bad = listOf(
            """{"applicationId":"com.example.other","versionCode":12,"versionName":"1.10.0","minSdk":26}""",
            """{"versionCode":12,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":"12","versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12.0,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":1.2e1,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":2147483648,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":0,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":-12,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":null,"versionName":"1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.10.0"}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.10.0","minSdk":"26"}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.10.0","minSdk":26.5}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.10","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.9.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"android-v1.10.0","minSdk":26}""",
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":1.10,"minSdk":26}""",
            """[{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.10.0","minSdk":26}]""",
            """{"applicationId":"com.kevinpostal.fiberirc",""",
            "",
        )
        for (body in bad) {
            server.enqueue(page(complete("1.9.0"), complete("1.10.0")))
            server.enqueue(json(body))
            unavailable()
        }
        takeAll().forEach(::assertNoIrcCredentials)
    }

    @Test
    fun ignoresUnknownManifestFields() = runTest {
        server.enqueue(page(complete("1.10.0")))
        server.enqueue(json("""{"applicationId":"com.kevinpostal.fiberirc","versionCode":12,"versionName":"1.10.0","minSdk":26,"notes":"x"}"""))

        assertEquals(ApkRelease(12, "1.10.0", 26, "$DOWNLOAD/android-v1.10.0/$APK_ASSET"), client().latest())
    }

    @Test
    fun missingManifestAssetIsUnavailable() = runTest {
        server.enqueue(page(complete("1.10.0")))
        server.enqueue(MockResponse().setResponseCode(404))

        unavailable()
    }

    // ── Network failures and deadline ──────────────────────────────────────────────────────────────────────

    @Test
    fun rateLimitIsUnavailable() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0")
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )
        assertTrue(unavailable().message!!.contains("rate limited"))

        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(unavailable().message!!.contains("rate limited"))
    }

    @Test
    fun offlineIsUnavailable() = runTest {
        val offline = MockWebServer().apply { start() }
        val releases = offline.url(RELEASES_PATH)
        offline.shutdown()

        unavailable(ApkReleaseClient(ApkReleaseClient.plainHttpClient(), releases, deadlineMillis = 5_000))
    }

    @Test
    fun deadlineSpansEveryRequestNotEachOne() = runTest {
        // Each response alone fits the deadline; together they exceed it.
        server.enqueue(page(complete("1.10.0")).setHeadersDelay(400, TimeUnit.MILLISECONDS))
        server.enqueue(json(manifest(code = 12, name = "1.10.0")).setHeadersDelay(400, TimeUnit.MILLISECONDS))

        val started = System.nanoTime()
        unavailable(client(deadlineMillis = 600))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }

    @Test
    fun deadlineCoversASlowResponseBody() = runTest {
        server.enqueue(page(complete("1.10.0")))
        server.enqueue(json(manifest(code = 12, name = "1.10.0")).throttleBody(1, 1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        unavailable(client(deadlineMillis = 500))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }

    // ── Privacy and redirects ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun followsDownloadRedirectWithoutCookiesOrCredentialsOnAnyRequest() = runTest {
        server.enqueue(page(complete("1.10.0")).addHeader("Set-Cookie", "fiber_session=secret; Path=/"))
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/cdn/objects/android-update.json"))
                .addHeader("Set-Cookie", "gh_sess=secret; Path=/"),
        )
        server.enqueue(json(manifest(code = 12, name = "1.10.0")))

        assertEquals(12, client().latest().versionCode)

        val requests = takeAll()
        assertEquals("/cdn/objects/android-update.json", requests[2].path)
        assertEquals(3, requests.size)
        requests.forEach(::assertNoIrcCredentials)
    }

    private fun assertNoIrcCredentials(request: RecordedRequest) {
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))
        val leaked = request.headers.names().filter { it.startsWith("X-IRCFiber", ignoreCase = true) }
        assertTrue("device headers sent: $leaked", leaked.isEmpty())
        assertFalse(request.getHeader("User-Agent").orEmpty().contains(USER_AGENT))
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────────────

    private companion object {
        const val RELEASES_PATH = "/repos/kevinpostal/ircfiber-pages/releases"
        const val DOWNLOAD = "https://github.com/kevinpostal/ircfiber-pages/releases/download"
        const val MANIFEST_PREFIX = "/kevinpostal/ircfiber-pages/releases/download"

        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

        fun page(vararg releases: String) = json(releases.joinToString(",", "[", "]"))

        fun asset(tag: String, name: String, state: String = "uploaded", url: String = "$DOWNLOAD/$tag/$name") =
            """{"name":"$name","state":"$state","browser_download_url":"$url"}"""

        fun release(
            tag: String,
            vararg assets: String,
            draft: Boolean = false,
            prerelease: Boolean = false,
            publishedAt: String? = "2026-11-01T00:00:00Z",
        ): String {
            val published = publishedAt?.let { "\"$it\"" } ?: "null"
            return """{"tag_name":"$tag","name":"Android $tag","body":"versionCode 99","draft":$draft,""" +
                """"prerelease":$prerelease,"published_at":$published,"assets":[${assets.joinToString(",")}]}"""
        }

        fun complete(
            version: String,
            draft: Boolean = false,
            prerelease: Boolean = false,
            publishedAt: String? = "2026-11-01T00:00:00Z",
        ): String {
            val tag = "android-v$version"
            return release(tag, asset(tag, APK_ASSET), asset(tag, MANIFEST_ASSET), draft = draft, prerelease = prerelease, publishedAt = publishedAt)
        }

        fun manifest(code: Int, name: String, minSdk: Int = 26) =
            """{"applicationId":"com.kevinpostal.fiberirc","versionCode":$code,"versionName":"$name","minSdk":$minSdk}"""
    }
}
