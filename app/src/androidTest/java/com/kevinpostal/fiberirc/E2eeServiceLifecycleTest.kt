package com.kevinpostal.fiberirc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import com.kevinpostal.fiberirc.data.api.CookieJarStore
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.fiberIrcHttpClient
import com.kevinpostal.fiberirc.data.e2ee.E2eeDefaults
import com.kevinpostal.fiberirc.data.e2ee.E2eePasskeyProvider
import com.kevinpostal.fiberirc.data.e2ee.E2eePrfResult
import com.kevinpostal.fiberirc.data.e2ee.E2eeWireTransport
import com.kevinpostal.fiberirc.data.e2ee.backupWithPasskey
import com.kevinpostal.fiberirc.data.e2ee.setDefaults
import com.kevinpostal.fiberirc.data.e2ee.unlockWithPasskey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import com.kevinpostal.fiberirc.data.api.originOf
import com.kevinpostal.fiberirc.data.e2ee.E2EE_FILES_DIR
import com.kevinpostal.fiberirc.data.e2ee.E2eeBridgeException
import com.kevinpostal.fiberirc.data.e2ee.E2eeIrcHost
import com.kevinpostal.fiberirc.data.e2ee.E2eeOp
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeStore
import com.kevinpostal.fiberirc.data.e2ee.E2eeWireRequest
import com.kevinpostal.fiberirc.data.e2ee.e2eeAccountScope
import com.kevinpostal.fiberirc.data.irc.AppForegroundMonitoring
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.fakeMeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The account encryption service end to end on this device: real packaged runtime in the real WebView, real
 * Keystore/SQLite store, real OkHttp to a local site stand-in. One service per account; sign-out shuts the
 * generation down and the next account gets its own store; a missing Keystore key is recovery-required (store
 * kept); a 401 ends the session; the runtime's HTTP stays inside the encryption allowlist; no wire transport
 * means no encrypted send (nothing written, no plaintext fallback).
 *
 * Run: `ANDROID_SERIAL=<device> ./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.kevinpostal.fiberirc.E2eeServiceLifecycleTest` after the
 * site's `npm run build:mobile-e2ee` generated `assets/e2ee/`.
 */
@RunWith(AndroidJUnit4::class)
class E2eeServiceLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var site: FakeSiteServer
    private lateinit var api: SiteApi
    private lateinit var http: OkHttpClient
    private val services = ArrayList<E2eeService>()
    private val whoSent = ArrayList<Pair<String, String>>()

    private val origin get() = originOf(api.baseUrl.toHttpUrl())

    private class Foreground : AppForegroundMonitoring {
        override var isForeground = true
        override val isActive get() = isForeground
        override var onChange: ((Boolean) -> Unit)? = null
        override fun start() {}
        override fun cancel() {}
    }

    private val irc = object : E2eeIrcHost {
        override fun e2eeNetworks(): JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("networkId", "n1")
                    put("host", "irc.example.net")
                    put("nick", "alice")
                    put(
                        "members",
                        buildJsonObject {
                            put(
                                "#enc",
                                buildJsonArray {
                                    add(buildJsonObject { put("nick", "alice"); put("account", "alice") })
                                    add(buildJsonObject { put("nick", "bobby"); put("account", "bob") })
                                },
                            )
                        },
                    )
                },
            )
        }

        override fun sendWho(networkId: String, channel: String): Boolean {
            whoSent.add(networkId to channel)
            return true
        }
    }

    @Before
    fun setUp() {
        site = FakeSiteServer(context)
        // The production client and SiteApi (as Deps wires them), pointed at the local stand-in.
        val jar = CookieJarStore(context.getSharedPreferences("e2ee_life_cookies_${System.nanoTime()}", Context.MODE_PRIVATE))
        http = fiberIrcHttpClient(jar) { "settings-device" }
        api = SiteApi(http, site.baseUrl)
        site.on("GET", "/api/mls/policy", body = "{}")
    }

    @After
    fun tearDown() {
        runBlocking { withContext(Dispatchers.Main) { services.forEach { it.stop() } } }
        Thread.sleep(1_500) // teardown flush (shutdown is bounded at 5 s; idle stands-ins answer at once)
        for (user in listOf("life-a", "life-b")) wipe(user)
        site.shutdown()
    }

    private fun wipe(user: String) {
        File(File(context.filesDir, E2EE_FILES_DIR), e2eeAccountScope(origin, user)).deleteRecursively()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "fiberirc.e2ee." + e2eeAccountScope(origin, user)
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun signIn(user: String) = site.on("GET", "/api/me", body = fakeMeJson(id = user, username = user.replace("-", "")))

    /** The account's buffer prefs store, as Deps wires it (saves to the stand-in), on a fresh preferences file. */
    private fun prefsStore() = BufferPrefsStore(
        context.getSharedPreferences("e2ee_life_prefs_${System.nanoTime()}", Context.MODE_PRIVATE),
    ) { network, channel, prefs ->
        api.postJson(
            "api/me/buffer-prefs",
            buildJsonObject {
                put("network", network)
                put("channel", channel)
                put("prefs", JsonObject(prefs.mapValues { it.value.toJson() }))
            },
        ).getOrThrow()
    }

    private suspend fun startService(): E2eeService = withContext(Dispatchers.Main) {
        E2eeService(context, api, http, { "settings-device" }, irc, prefsStore(), Foreground()).also {
            services.add(it)
            it.start()
        }
    }

    private suspend fun startService(fg: Foreground, passkeys: E2eePasskeyProvider): E2eeService = withContext(Dispatchers.Main) {
        E2eeService(context, api, http, { "settings-device" }, irc, prefsStore(), fg, passkeys).also {
            services.add(it)
            it.start()
        }
    }

    /** The shared backup key: HKDF-SHA-256(PRF output, salt "ircfiber-e2ee-prf", info "ircfiber-e2ee-backup-v1"). */
    private fun backupKey(prf: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("ircfiber-e2ee-prf".toByteArray(), "HmacSHA256")) }.doFinal(prf)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
            .doFinal("ircfiber-e2ee-backup-v1".toByteArray() + byteArrayOf(1))
    }

    private suspend fun E2eeService.settled(): E2eeServiceState = withTimeout(60_000) {
        state.first { it !is E2eeServiceState.Idle && it !is E2eeServiceState.Starting }
    }

    @Test
    fun oneServicePerAccountWithSeparateStores() = runBlocking {
        signIn("life-a")
        val a = startService()
        val state = a.settled()
        assertTrue("A runs: $state", state is E2eeServiceState.Running)
        assertTrue(E2eeStore.exists(context, origin, "life-a"))
        // The runtime asked for its policy through the native allowlisted HTTP path.
        withTimeout(30_000) {
            while (site.requests().none { it.method == "GET" && it.path == "/api/mls/policy" }) delay(10)
        }
        // Every runtime HTTP call stayed in the encryption API.
        val runtimePaths = site.requests().map { it.path!!.substringBefore('?') }
            .filter { it != "/api/me" && it != "/ws" }
        assertTrue(runtimePaths.toString(), runtimePaths.all { p -> listOf("/api/mls/", "/api/kt/", "/api/me/e2ee/").any { p.startsWith(it) } })
        val status = a.request(E2eeOp.Status) as JsonObject
        assertTrue(status.containsKey("ui"))

        // Sign-out: A stops; B signs in on the same device and gets its own store.
        withContext(Dispatchers.Main) { a.stop() }
        assertEquals(E2eeServiceState.Stopped, a.state.value)
        try {
            a.request(E2eeOp.Status)
            fail("stopped service answered")
        } catch (e: E2eeBridgeException) {
            assertEquals("unavailable", e.code)
        }
        signIn("life-b")
        val b = startService()
        assertTrue(b.settled() is E2eeServiceState.Running)
        assertTrue(E2eeStore.exists(context, origin, "life-b"))
        assertTrue(E2eeStore.exists(context, origin, "life-a"))
        assertFalse(e2eeAccountScope(origin, "life-a") == e2eeAccountScope(origin, "life-b"))
    }

    @Test
    fun lockedBufferSendNeverWritesPlaintext() = runBlocking {
        // The account's buffer prefs lock #enc; this fresh device has no MLS identity on the server.
        site.on("GET", "/api/me", body = """{"id":"life-a","username":"lifea","bufferPrefs":{"n1:#enc":{"e2ee":true}}}""")
        val a = startService()
        assertTrue(a.settled() is E2eeServiceState.Running)
        val written = ArrayList<E2eeWireRequest>()
        withContext(Dispatchers.Main) { a.wireTransport = E2eeWireTransport { written.add(it) } }
        val marker = "PLAINTEXT-MARKER-91ab"
        // Encryption can't complete against this stand-in (no real device/group): the shared pipeline retries and
        // fails. It never downgrades to plaintext: only ciphertext could ever reach the write hook.
        withTimeoutOrNull(15_000) { runCatching { a.sendBufferLine("n1", "#enc", marker) } }
        assertTrue(written.toString(), written.none { req -> req.lines.any { marker in it } })
        assertTrue(written.toString(), written.all { req -> req.lines.all { it.startsWith("!MLS1:") || it.startsWith("!PGP1:") } })
        assertFalse(site.requests().any { it.body.clone().readUtf8().contains(marker) })
    }

    @Test
    fun noTransportMeansNothingSent() = runBlocking {
        signIn("life-a")
        val a = startService()
        assertTrue(a.settled() is E2eeServiceState.Running)
        // #plain is off (policy known, no lock): the runtime's plaintext path still goes through the IRC write hook,
        // and without one the send fails instead of claiming success.
        val outcome = withTimeoutOrNull(15_000) { runCatching { a.sendBufferLine("n1", "#plain", "hello") } }
        assertTrue("$outcome", outcome == null || outcome.isFailure)
    }

    @Test
    fun missingKeystoreKeyIsRecoveryRequired() = runBlocking {
        signIn("life-a")
        val first = startService()
        assertTrue(first.settled() is E2eeServiceState.Running)
        withContext(Dispatchers.Main) { first.stop() }
        Thread.sleep(500)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry("fiberirc.e2ee." + e2eeAccountScope(origin, "life-a"))
        val again = startService()
        val state = again.settled()
        assertTrue("$state", state is E2eeServiceState.RecoveryRequired)
        assertTrue(E2eeStore.exists(context, origin, "life-a"))
    }

    @Test
    fun expiredSessionEndsTheService() = runBlocking {
        site.on("GET", "/api/me", code = 401, body = """{"error":"Unauthorized"}""")
        var expired = false
        val a = withContext(Dispatchers.Main) {
            E2eeService(context, api, http, { "settings-device" }, irc, prefsStore(), Foreground()).also {
                services.add(it)
                it.onSessionExpired = { expired = true }
                it.start()
            }
        }
        withTimeout(30_000) { a.state.first { it is E2eeServiceState.Stopped } }
        assertTrue(expired)
        assertFalse(E2eeStore.exists(context, origin, "life-a"))
    }

    @Test
    fun passkeyBackupIsSealedWithTheProvidersRawPrfOutput() = runBlocking {
        signIn("life-a")
        site.on("PUT", "/api/me/e2ee/backup", body = "{}")
        val prf = ByteArray(32) { (it * 3 + 1).toByte() }
        val calls = ArrayList<Triple<String, String, String?>>()
        var output = prf
        val a = startService(Foreground()) { mode, account, credentialId ->
            calls.add(Triple(mode, account, credentialId))
            E2eePrfResult("Y3JlZC1hbmRyb2lk", output)
        }
        assertTrue(a.settled() is E2eeServiceState.Running)
        // Key-reading operations fail closed until the account's policy decision exists: wait for the runtime's
        // fetched policy (the stand-in's `{}`) rather than racing it.
        withTimeout(30_000) { a.state.first { (it as? E2eeServiceState.Running)?.status?.policyAvailable == true } }
        a.backupWithPasskey()
        assertEquals(listOf(Triple("create", "lifea", null)), calls)
        val put = site.requests().last { it.method == "PUT" && it.path == "/api/me/e2ee/backup" }
        val body = Json.parseToJsonElement(put.body.clone().readUtf8()).jsonObject
        assertEquals("passkey", body["wrap"]!!.jsonPrimitive.content)
        assertEquals("Y3JlZC1hbmRyb2lk", body["credentialId"]!!.jsonPrimitive.content)
        // An independent opener: the blob is AES-GCM under HKDF of exactly the bytes the provider returned.
        val sealed = android.util.Base64.decode(body["blob"]!!.jsonPrimitive.content, android.util.Base64.NO_WRAP)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(backupKey(prf), "AES"), GCMParameterSpec(128, sealed, 0, 12))
        }.doFinal(sealed, 12, sealed.size - 12)
        val payload = Json.parseToJsonElement(String(plain)).jsonObject
        assertEquals(2, payload["v"]!!.jsonPrimitive.int)
        assertEquals("lifea", payload["account"]!!.jsonPrimitive.content)

        // A provider without real 32-byte PRF output is unsupported: nothing is sealed or uploaded.
        output = ByteArray(16)
        val puts = site.requests().count { it.method == "PUT" && it.path == "/api/me/e2ee/backup" }
        try {
            a.backupWithPasskey()
            fail("backup sealed without PRF output")
        } catch (e: E2eeBridgeException) {
            assertEquals("passkey_unsupported", e.code)
        }
        assertEquals(puts, site.requests().count { it.method == "PUT" && it.path == "/api/me/e2ee/backup" })
    }

    @Test
    fun passkeySheetStoppingTheAppIsNotABackgroundRelock() = runBlocking {
        signIn("life-a")
        val fg = Foreground()
        val prf = ByteArray(32) { 7 }
        val a = startService(fg) { _, _, credentialId ->
            // The provider's sheet stops the activity: the process reports background mid-ceremony.
            withContext(Dispatchers.Main) {
                fg.isForeground = false
                fg.onChange?.invoke(false)
            }
            delay(2_000) // longer than a background flush: an immediate relock would have torn the runtime down
            E2eePrfResult(credentialId ?: "Y3JlZA==", prf)
        }
        assertTrue(a.settled() is E2eeServiceState.Running)
        a.setDefaults(E2eeDefaults(requireUnlock = true))
        // The unlock completes against the same runtime generation the sheet was opened for.
        val status = a.unlockWithPasskey("Y3JlZA==")
        assertTrue(status.json.toString(), status.unlocked)
        // The app is still in the background once the passkey operation ends: the relock happens then.
        val after = withTimeout(15_000) { a.state.first { it is E2eeServiceState.Unavailable } }
        assertEquals(E2eeServiceState.Unavailable("Encryption is locked"), after)
    }
}
