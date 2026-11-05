package com.kevinpostal.fiberirc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.kevinpostal.fiberirc.data.e2ee.E2EE_ORIGIN
import com.kevinpostal.fiberirc.data.e2ee.E2EE_PATH_PREFIX
import com.kevinpostal.fiberirc.data.e2ee.E2eeBridgeException
import com.kevinpostal.fiberirc.data.e2ee.E2eeHostCall
import com.kevinpostal.fiberirc.data.e2ee.E2eeHostCallHandler
import com.kevinpostal.fiberirc.data.e2ee.E2eeHostOp
import com.kevinpostal.fiberirc.data.e2ee.E2eeOp
import com.kevinpostal.fiberirc.data.e2ee.E2eeRuntime
import com.kevinpostal.fiberirc.data.e2ee.E2eeRuntimeEvent
import com.kevinpostal.fiberirc.data.e2ee.E2eeRuntimeListener
import com.kevinpostal.fiberirc.data.e2ee.E2eeRuntimeUnavailable
import com.kevinpostal.fiberirc.data.e2ee.RUNTIME_DOCUMENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The encryption runtime on this device's actual Android System WebView: the packaged `runtime.html` (hash-checked
 * against `runtime.json`) passes the capability gate and answers the closed registry over the web-message bridge,
 * navigation/popups stay refused, and the debug-only `smoke.html` (same build, same CSP shape) runs real
 * WebCrypto/WASM/hybrid-suite MLS work whose durable saves go through this native bridge's host calls — including a
 * forced commit failure that must reject the operation. Nothing here is mocked on the JavaScript side.
 *
 * Run: `ANDROID_SERIAL=<device> ./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.kevinpostal.fiberirc.E2eeRuntimeTest` after the site's
 * `npm run build:mobile-e2ee` generated `assets/e2ee/` (and `src/debug/assets/e2ee/smoke.*`).
 */
@RunWith(AndroidJUnit4::class)
class E2eeRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var runtime: E2eeRuntime? = null

    @After
    fun tearDown() {
        runtime?.destroy()
        instrumentation.runOnMainSync { } // destroy() posted from this thread has run before the next test starts one
    }

    @Test
    fun packagedRuntimePassesCapabilityGate(): Unit = runBlocking {
        assertTrue("WebView lacks WEB_MESSAGE_LISTENER", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val ready = start(RUNTIME_DOCUMENT, SmokeHost(failMlsSaves = false))
        assertEquals(1, ready.abi)
        for (name in listOf("secureContext", "webCrypto", "random", "wasm")) {
            assertTrue("capability $name: ${ready.capabilities}", ready.capabilities[name] == true)
        }
        assertFalse(ready.smoke)
    }

    @Test
    fun packagedRuntimeAnswersOnlyItsClosedRegistry(): Unit = runBlocking {
        val rt = runtimeFor(RUNTIME_DOCUMENT, SmokeHost(failMlsSaves = false))
        val gen = rt.beginGeneration()
        assertBridgeError("not_bootstrapped") { rt.dispatch(gen, E2eeOp.Status) }
        assertBridgeError("unknown_op") { rt.dispatchWire(gen, "eval", JsonObject(emptyMap())) }
        rt.beginGeneration()
        assertBridgeError("stale_generation") { rt.dispatch(gen, E2eeOp.Status) }
    }

    @Test
    fun navigationAndPopupsAreRefused(): Unit = runBlocking {
        val rt = runtimeFor(RUNTIME_DOCUMENT, SmokeHost(failMlsSaves = false))
        val documentUrl = E2EE_ORIGIN + E2EE_PATH_PREFIX + RUNTIME_DOCUMENT
        withContext(Dispatchers.Main) {
            val view = rt.webViewForTest() ?: error("no runtime WebView")
            view.evaluateJavascript(
                "location.assign('https://example.com/');" +
                    "window.open('https://example.com/popup');" +
                    "document.body.appendChild(Object.assign(document.createElement('iframe'),{src:'https://example.com/frame'}));",
                null,
            )
        }
        delay(2_000)
        withContext(Dispatchers.Main) { assertEquals(documentUrl, rt.webViewForTest()?.url) }
        // Same document, same bridge: still answering.
        val gen = rt.beginGeneration()
        assertBridgeError("not_bootstrapped") { rt.dispatch(gen, E2eeOp.Status) }
    }

    @Test
    fun smokeSelfTestRunsCryptoWasmMlsAndHostStorage(): Unit = runBlocking {
        val host = SmokeHost(failMlsSaves = false)
        val rt = runtimeFor(SMOKE_DOCUMENT, host, expectSmoke = true)
        val gen = rt.beginGeneration()
        val checks = checks(withTimeout(SELF_TEST_TIMEOUT_MS) { rt.dispatchWire(gen, "selfTest", JsonObject(emptyMap())) })
        for (name in listOf("secureContext", "randomValues", "aesGcm", "hkdf", "wasm", "hybridSuite", "mlsRoundTrip", "hostStorage")) {
            assertTrue("missing check $name in ${checks.keys}", name in checks)
        }
        val failed = checks.filterValues { !it.first }
        assertTrue("failed checks: $failed", failed.isEmpty())
        val ops = withContext(Dispatchers.Main) { host.calls.toSet() }
        for (op in listOf(
            E2eeHostOp.StorageSetMeta,
            E2eeHostOp.StorageGetMeta,
            E2eeHostOp.StorageDeleteMeta,
            E2eeHostOp.StorageSaveMlsState,
            E2eeHostOp.StorageLoadMlsState,
        )) {
            assertTrue("host never saw ${op.wire}: $ops", op in ops)
        }
    }

    @Test
    fun smokeDurableSaveFailureRejectsAndRollsBack(): Unit = runBlocking {
        val host = SmokeHost(failMlsSaves = true)
        val rt = runtimeFor(SMOKE_DOCUMENT, host, expectSmoke = true)
        val gen = rt.beginGeneration()
        val checks = checks(
            withTimeout(SELF_TEST_TIMEOUT_MS) { rt.dispatchWire(gen, "selfTestPersistFailure", JsonObject(emptyMap())) },
        )
        for (name in listOf("durableRejects", "rolledBack")) {
            val check = checks[name]
            assertTrue("missing check $name in ${checks.keys}", check != null)
            assertTrue("$name: ${check!!.second}", check.first)
        }
        assertTrue(
            "the failed commit never reached the native host",
            E2eeHostOp.StorageSaveMlsState in withContext(Dispatchers.Main) { host.calls.toSet() },
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private suspend fun start(document: String, host: E2eeHostCallHandler) =
        E2eeRuntime(context, host, Listener, document).also { runtime = it }.start()

    private suspend fun runtimeFor(document: String, host: E2eeHostCallHandler, expectSmoke: Boolean = false): E2eeRuntime {
        val rt = E2eeRuntime(context, host, Listener, document)
        runtime = rt
        val ready = try {
            rt.start()
        } catch (e: E2eeRuntimeUnavailable) {
            throw AssertionError("$document unavailable (${e.reason}); run the site's build:mobile-e2ee", e)
        }
        assertEquals(expectSmoke, ready.smoke)
        return rt
    }

    private suspend fun assertBridgeError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected bridge error $code")
        } catch (e: E2eeBridgeException) {
            assertEquals(e.message, code, e.code)
        }
    }

    /** `{checks:[{name,ok,detail?}]}` → name → (ok, detail). */
    private fun checks(result: JsonElement): Map<String, Pair<Boolean, String?>> {
        val list = (result as? JsonObject)?.get("checks") as? JsonArray ?: error("malformed self-test result: $result")
        return list.associate { entry ->
            val check = entry as JsonObject
            val name = (check["name"] as JsonPrimitive).content
            val ok = (check["ok"] as? JsonPrimitive)?.booleanOrNull == true
            val detail = (check["detail"] as? JsonPrimitive)?.content
            name to (ok to detail)
        }
    }

    private object Listener : E2eeRuntimeListener {
        override fun onEvent(event: E2eeRuntimeEvent) = Unit
        override fun onTerminated(cause: E2eeRuntimeUnavailable) = Unit
    }

    /**
     * The native side of the storage host calls the smoke exercises, in memory (phase 3 brings the encrypted SQLite
     * store); [failMlsSaves] answers every `storage.saveMlsState` with a failed commit. Main-thread confined.
     */
    private class SmokeHost(private val failMlsSaves: Boolean) : E2eeHostCallHandler {
        val calls = mutableListOf<E2eeHostOp>()
        private val meta = HashMap<String, JsonElement>()
        private var mlsState: JsonElement = JsonNull

        override suspend fun handle(call: E2eeHostCall): JsonElement {
            calls += call.op
            val key = (call.args["key"] as? JsonPrimitive)?.content
            return when (call.op) {
                E2eeHostOp.StorageSetMeta -> {
                    meta[requireNotNull(key)] = call.args["value"] ?: JsonNull
                    JsonNull
                }
                E2eeHostOp.StorageGetMeta -> meta[requireNotNull(key)] ?: JsonNull
                E2eeHostOp.StorageDeleteMeta -> {
                    meta.remove(requireNotNull(key))
                    JsonNull
                }
                E2eeHostOp.StorageSaveMlsState -> {
                    if (failMlsSaves) throw E2eeBridgeException("storage_failed", "smoke: forced commit failure")
                    mlsState = call.args["state"] ?: JsonNull
                    JsonNull
                }
                E2eeHostOp.StorageLoadMlsState -> mlsState
                else -> throw E2eeBridgeException("unsupported", "smoke host does not serve ${call.op.wire}")
            }
        }
    }

    private companion object {
        const val SMOKE_DOCUMENT = "smoke.html"
        const val SELF_TEST_TIMEOUT_MS = 120_000L
    }
}
