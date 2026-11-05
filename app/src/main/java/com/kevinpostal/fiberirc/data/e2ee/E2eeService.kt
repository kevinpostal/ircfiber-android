package com.kevinpostal.fiberirc.data.e2ee

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Base64
import androidx.annotation.MainThread
import com.kevinpostal.fiberirc.data.api.MLS_DEVICE_HEADER
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.api.originOf
import com.kevinpostal.fiberirc.data.api.lenientBufferPrefs
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import com.kevinpostal.fiberirc.data.irc.AppForegroundMonitoring
import com.kevinpostal.fiberirc.data.irc.ProcessForegroundMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

/** What the session's IRC side gives the encryption service ([com.kevinpostal.fiberirc.data.irc.IrcSessionManager]). */
interface E2eeIrcHost {
    /** The runtime context's `networks`: every connected-or-not bouncer network that has a site network id, as
     *  `{networkId, host, nick?, hostmask?, isupport?, members:{<lowercased buffer>: Member[]}}` with real IRC
     *  account tags. Direct-IRC networks without a network id are absent (encryption unavailable there). */
    fun e2eeNetworks(): JsonArray

    /** Sends `WHO <channel> %tna,152` (WHOX accounts; replies update members silently) on that network; false when
     *  it isn't connected (nothing sent). */
    fun sendWho(networkId: String, channel: String): Boolean
}

/** One `transport.sendWire` request: ciphertext lines to write verbatim (tags on the first line only). [label] is
 *  the `sendBufferLine` label (the native optimistic row's send label) when the runtime passed one. */
data class E2eeWireRequest(
    val networkId: String,
    val target: String,
    val command: String,
    val lines: List<String>,
    val tags: Map<String, String>,
    val label: String? = null,
)

/**
 * The IRC write path for encrypted lines (installed by the phase-5 message pipeline). Returns once the transport
 * accepted every line's bytes — not server delivery. Throws [E2eeBridgeException] `not_connected` when nothing was
 * written and `uncertain_delivery` after a partial or uncertain write (never retransmitted automatically).
 */
fun interface E2eeWireTransport {
    suspend fun sendWire(request: E2eeWireRequest)
}

/**
 * Native passkey PRF (phase 7, [E2eePasskeys]): `create` registers a PRF passkey for [account], `get` asserts one
 * (exactly [credentialId] when given). [E2eePrfResult.first] is the authenticator's actual 32-byte PRF output;
 * [E2eePrfResult.credentialId] is standard base64. Failures are [E2eeBridgeException]s (`passkey_cancelled`,
 * `passkey_unsupported`, …).
 */
fun interface E2eePasskeyProvider {
    suspend fun prf(mode: String, account: String, credentialId: String?): E2eePrfResult
}

class E2eePrfResult(val credentialId: String, val first: ByteArray)

/** The runtime's `{ui,ktAlarm,deviceId,policy}` status snapshot. */
class E2eeStatus(val json: JsonObject) {
    private val ui: JsonObject? get() = json["ui"] as? JsonObject
    private fun flag(name: String) = (ui?.get(name) as? JsonPrimitive)?.booleanOrNull == true

    val ready: Boolean get() = flag("ready")
    val requireUnlock: Boolean get() = flag("requireUnlock")
    val unlocked: Boolean get() = flag("unlocked")

    /** Unlock required and not done: sends, decrypt display and archive reads are blocked. */
    val locked: Boolean get() = requireUnlock && !unlocked
    val deviceId: String? get() = (json["deviceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val policyAvailable: Boolean get() = json["policy"] is JsonObject
    val ktAlarm: Boolean get() = ((json["ktAlarm"] as? JsonObject)?.get("active") as? JsonPrimitive)?.booleanOrNull == true

    /** The effective (policy or local) encrypt-DMs-by-default rule. */
    val dmDefault: Boolean get() = flag("dmDefault")

    /** A lock change of `<networkId>:<lower target>` still settling (device setup / policy lookup). */
    fun lockPending(bufferKey: String): Boolean =
        ((ui?.get("lockPending") as? JsonObject)?.get(bufferKey) as? JsonPrimitive)?.booleanOrNull == true

    /** The membership engine's lock override of `<networkId>:<lower target>` (`partial`, `nopeer`); null when none. */
    fun lockOverride(bufferKey: String): String? =
        ((ui?.get("overrides") as? JsonObject)?.get(bufferKey) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The service's lifecycle as the encryption UI and message pipeline see it. Only [Running] can encrypt. */
sealed interface E2eeServiceState {
    data object Idle : E2eeServiceState
    data object Starting : E2eeServiceState

    /** Bootstrapped runtime of [generation]; [status] is the latest snapshot. */
    data class Running(val generation: Long, val status: E2eeStatus) : E2eeServiceState

    /** The runtime can't run (WebView missing/outdated, resource invalid, renderer gone, site unreachable); sends
     *  stay blocked. [webViewUpdate]: show "Update Android System WebView to use encryption". */
    data class Unavailable(val message: String, val webViewUpdate: Boolean = false) : E2eeServiceState

    /** The account's store exists but can't be read; only an explicit user choice discards it. */
    data class RecoveryRequired(val detail: String) : E2eeServiceState

    /** Signed out, account switched or session expired: this service is done. */
    data object Stopped : E2eeServiceState
}

/** Phase-5/6 callbacks (main thread). */
interface E2eeServiceObserver {
    fun onState(state: E2eeServiceState) {}

    /** `replaceRow` `{networkId,buffer,id,msgid?,message}`: locate the row by stable `id`, then `msgid`. */
    fun onReplaceRow(payload: JsonObject) {}

    /** A rebuilt runtime, a reconnected event stream or a fresh unlock: resubmit every native-visible
     *  pending/before-join/error row (stable id, raw ciphertext) through [E2eeService.decryptBatch]. */
    fun onResubmitPending() {}

    /** Background with unlock required, renderer loss, sign-out: drop decrypted plaintext held natively. */
    fun onPlaintextInvalidated() {}
}

/**
 * The signed-in account's encryption service: the one [E2eeRuntime] of the app's main process, the account's
 * [E2eeStore], its [E2eeEvents] stream, and the runtime's host calls (native HTTP under the encryption allowlist,
 * the encrypted store, buffer/local preferences, WHO and the IRC write hook, passkey PRF).
 *
 * Owned by the session's [com.kevinpostal.fiberirc.data.irc.IrcSessionManager]: [start] with the session,
 * [stop] on sign-out/account switch (shuts the generation down, closes events, drops plaintext, closes the store).
 * Account identity comes from an authenticated `GET /api/me` (immutable user id + username) and the configured API
 * origin; the store is scoped by both. Foreground resume rebuilds a killed runtime from committed storage and
 * reconnects events (`setContext {reconnect:true}` + pending-row resubmission); background closes events, flushes,
 * and with unlock required destroys the runtime so plaintext and unlock state go with it. Nothing ever falls back
 * to plaintext: without a running service, sends fail.
 *
 * Main-thread confined; suspend helpers may be called from any coroutine.
 */
class E2eeService(
    context: Context,
    private val api: SiteApi,
    httpClient: OkHttpClient,
    private val settingsDeviceId: () -> String,
    private val irc: E2eeIrcHost,
    /** The account's buffer prefs: the `e2ee`/`e2eeMode` keys the runtime reads and saves live in this one store. */
    private val bufferPrefsStore: BufferPrefsStore,
    private val foreground: AppForegroundMonitoring = ProcessForegroundMonitor(),
    /** Native PRF passkeys; null where the device can't do them (API < 28) → `passkey_unsupported`. */
    private val passkeys: E2eePasskeyProvider? = null,
) : E2eeHostCallHandler, E2eeRuntimeListener {
    private val app = context.applicationContext

    /** The configured API origin the account's store and policy snapshot are scoped by. */
    val apiOrigin: String = originOf(api.baseUrl.toHttpUrl())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            // A bug here must fail encryption closed, never crash the app or fall back to plaintext.
            Log.e(TAG, "encryption service failure", e)
            setState(E2eeServiceState.Unavailable("Encryption failed — reopen the app to retry"))
        },
    )
    private val events = E2eeEvents(httpClient, api.baseUrl, settingsDeviceId, EventsListener())

    private val _state = MutableStateFlow<E2eeServiceState>(E2eeServiceState.Idle)
    val state: StateFlow<E2eeServiceState> = _state.asStateFlow()

    /** Called once when the site session is gone (401 from the encryption API or events handshake). */
    var onSessionExpired: (() -> Unit)? = null

    /** Phase-5 IRC write path; null → encrypted sends fail with `transport_unavailable` (nothing written). */
    var wireTransport: E2eeWireTransport? = null

    /** Whether passkey backup/unlock can be attempted here (the provider still decides per request). */
    val passkeysAvailable: Boolean get() = passkeys != null

    // A passkey sheet (or the user-verification UI behind it) can stop this app's activity: that's an app-owned
    // presentation, not leaving the app. Background handling waits until the passkey operation finishes, then runs
    // if the app is still in the background.
    private var foregroundHolds = 0
    private var backgroundDeferred = false

    private val observers = ArrayList<E2eeServiceObserver>()

    private var account: Account? = null
    private var store: E2eeStore? = null
    private var runtime: E2eeRuntime? = null
    private var generation = -1L
    private var started = false
    private var stopped = false
    private var sessionExpired = false
    private var eventQueue: Channel<JsonObject>? = null
    private var contextJob: Job? = null
    private val terminations = ArrayDeque<Long>()

    private var localPrefs: Map<String, String> = emptyMap()

    /** `deviceId` meta as last committed; the MLS device header is sent only when present. */
    private var mlsDeviceId: String? = null

    // Native lock enforcement on secret reads (mirrors the runtime's own check; see [secretReadsAllowed]).
    private var lockedByState = false
    private var unlockProven = false
    private var unlocksInFlight = 0

    private class Account(val username: String, val userId: String)

    /** The authenticated account's immutable id (from `/api/me`); null until verified. */
    val userId: String? get() = account?.userId

    /** The authenticated account's username; null until verified. */
    val username: String? get() = account?.username

    // ── lifecycle ──────────────────────────────────────────────────────────

    /** Starts the service for the signed-in account (main thread, once). */
    @MainThread
    fun start() {
        if (started || stopped) return
        started = true
        active?.takeIf { it !== this }?.stop()
        active = this
        foreground.onChange = { fg -> if (fg) onForeground() else onBackground() }
        foreground.start()
        scope.launch { boot() }
        // Buffer prefs changed anywhere (the web via `/api/me`, the composer, a failed save's rollback): the runtime's
        // context follows.
        scope.launch { bufferPrefsStore.values.drop(1).collect { contextChanged() } }
    }

    /** Sign-out / account switch / session end: terminal. Shuts the runtime generation down (flushing committed
     *  state), closes events, drops plaintext and closes the store. Keys stay unless `removeLocalKeys` ran. */
    @MainThread
    fun stop() {
        if (stopped) return
        stopped = true
        if (active === this) active = null
        foreground.cancel()
        foreground.onChange = null
        events.dispose()
        contextJob?.cancel()
        notifyPlaintextInvalidated()
        setState(E2eeServiceState.Stopped)
        val rt = runtime
        val gen = generation
        val st = store
        runtime = null
        eventQueue?.close()
        eventQueue = null
        localPrefs = emptyMap()
        observers.clear()
        // Teardown outlives this service's scope; the process-wide lock keeps the next account's runtime from
        // starting while this one still flushes.
        teardownScope.launch {
            processLifecycle.withLock {
                if (rt != null) shutdownRuntime(rt, gen)
                runCatching { st?.close() }
            }
            scope.cancel()
        }
    }

    fun addObserver(o: E2eeServiceObserver) {
        if (observers.none { it === o }) observers.add(o)
    }

    fun removeObserver(o: E2eeServiceObserver) {
        observers.removeAll { it === o }
    }

    private var booting = false

    private suspend fun boot() {
        if (booting) return
        booting = true
        try {
            bootOnce()
        } finally {
            booting = false
        }
    }

    private suspend fun bootOnce() {
        setState(E2eeServiceState.Starting)
        if (!isMainProcess(app)) {
            setState(E2eeServiceState.Unavailable("Encryption runs only in the app's main process"))
            return
        }
        val acct = account ?: fetchAccount() ?: return
        if (stopped) return
        account = acct
        val st = store ?: E2eeStore.forAccount(app, apiOrigin, acct.userId).also { store = it }
        try {
            st.open()
            mlsDeviceId = (st.getMeta(META_DEVICE_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content
            localPrefs = st.localPrefs()
        } catch (e: E2eeRecoveryRequired) {
            enterRecoveryRequired(e)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "encryption store open failed", e)
            setState(E2eeServiceState.Unavailable("Encryption storage is unavailable"))
            return
        }
        lockedByState = localDefaultsRequireUnlock()
        unlockProven = false
        startRuntime(rebuild = false)
        if (!stopped && foreground.isForeground && runtime != null) events.start()
    }

    /** `GET /api/me`: the authenticated account (immutable id + username) and its raw buffer prefs. */
    private suspend fun fetchAccount(): Account? {
        val me = api.getJson("api/me").getOrElse { e ->
            if (e is SiteApiError.Unauthorized) {
                sessionEnded()
            } else if (!stopped) {
                setState(E2eeServiceState.Unavailable("Can't reach ircfiber.com — encryption will start when online"))
            }
            return null
        } as? JsonObject
        val id = me?.str("id")?.takeIf(String::isNotEmpty)
        val username = me?.str("username")?.takeIf(String::isNotEmpty)
        if (me == null || id == null || username == null) {
            setState(E2eeServiceState.Unavailable("Unexpected response from ircfiber.com"))
            return null
        }
        account?.let { current ->
            if (current.userId != id) {
                // Another account answered on this session's cookie: this generation must not continue.
                Log.w(TAG, "account changed under the encryption session")
                sessionEnded()
                return null
            }
        }
        adoptBufferPrefs(me["bufferPrefs"] as? JsonObject)
        return Account(username, id)
    }

    private suspend fun startRuntime(rebuild: Boolean) {
        val acct = account ?: return
        processLifecycle.withLock {
            if (stopped || runtime != null) return
            val rt = E2eeRuntime(app, this, this)
            try {
                rt.start()
            } catch (e: E2eeRuntimeUnavailable) {
                rt.destroy()
                setState(E2eeServiceState.Unavailable(e.userMessage, e.reason.isWebViewProblem()))
                return
            } catch (e: IllegalStateException) {
                rt.destroy()
                Log.e(TAG, "encryption runtime refused to start", e)
                setState(E2eeServiceState.Unavailable("Encryption is unavailable"))
                return
            }
            if (stopped) {
                rt.destroy()
                return
            }
            val gen = rt.beginGeneration()
            runtime = rt
            generation = gen
            unlockProven = false
            startEventConsumer(rt, gen)
            try {
                val status = rt.dispatch(gen, E2eeOp.Bootstrap, contextArgs(acct, reconnect = false))
                // A rebuilt runtime's status isn't current until its reconnect context lands (below).
                contextSettled = !rebuild
                adoptStatus(gen, status)
            } catch (e: E2eeBridgeException) {
                if (runtime === rt) {
                    Log.w(TAG, "encryption bootstrap failed: ${e.code}")
                    runtime = null
                    rt.destroy()
                    setState(E2eeServiceState.Unavailable("Encryption failed to start"))
                }
                return
            }
        }
        // A rebuilt runtime missed events while it was gone: reconcile held groups, then resubmit pending rows.
        if (rebuild) scheduleContext(0, reconnect = true, resubmit = true)
    }

    /** Shuts [rt]'s generation down (bounded) and destroys it. */
    private suspend fun shutdownRuntime(rt: E2eeRuntime, gen: Long) {
        withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
            runCatching { rt.dispatch(gen, E2eeOp.Shutdown) }
                .onFailure { if (it !is E2eeBridgeException) throw it }
        }
        rt.destroy()
    }

    /** Destroys the live runtime (if any) without ending the service; plaintext goes with it. */
    private suspend fun dropRuntime() {
        val rt = runtime ?: return
        val gen = generation
        runtime = null
        eventQueue?.close()
        eventQueue = null
        notifyPlaintextInvalidated()
        processLifecycle.withLock { shutdownRuntime(rt, gen) }
    }

    @MainThread
    private fun onForeground() {
        backgroundDeferred = false
        if (stopped || sessionExpired) return
        // Whatever happened while away isn't reflected until a reconnect context commits: nothing is off till then.
        contextSettled = false
        scope.launch {
            if (account == null || store == null) {
                boot()
                return@launch
            }
            if (_state.value is E2eeServiceState.RecoveryRequired) return@launch
            // Same account still signed in? Also picks up buffer-pref changes made elsewhere. Offline keeps the
            // last verified account; a 401 or another account ends the service.
            fetchAccount()
            if (stopped || sessionExpired) return@launch
            terminations.clear()
            if (runtime == null) startRuntime(rebuild = true) else scheduleContext(0, reconnect = true)
            if (!stopped && runtime != null) events.start()
        }
    }

    @MainThread
    private fun onBackground() {
        if (stopped) return
        if (foregroundHolds > 0) {
            backgroundDeferred = true
            return
        }
        events.stop()
        val rt = runtime ?: return
        val gen = generation
        val relock = (state.value as? E2eeServiceState.Running)?.status?.requireUnlock == true || localDefaultsRequireUnlock()
        scope.launch {
            // Committed state first; an unlocked session that requires unlock is then discarded entirely.
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) { runCatching { rt.dispatch(gen, E2eeOp.Flush) } }
            if (relock && runtime === rt && !foreground.isForeground) {
                dropRuntime()
                setState(E2eeServiceState.Unavailable("Encryption is locked"))
            }
        }
    }

    /** Runs [block] (main thread) as an app-owned passkey presentation; see [foregroundHolds]. */
    private suspend fun <T> holdingForeground(block: suspend () -> T): T {
        foregroundHolds++
        try {
            return block()
        } finally {
            foregroundHolds--
            if (foregroundHolds == 0 && backgroundDeferred) {
                backgroundDeferred = false
                if (!foreground.isForeground) onBackground()
            }
        }
    }

    // ── runtime listener ───────────────────────────────────────────────────

    override fun onEvent(event: E2eeRuntimeEvent) {
        if (event.generation != generation || runtime == null) return
        when (event) {
            is E2eeRuntimeEvent.State -> adoptStatus(event.generation, event.payload)
            is E2eeRuntimeEvent.ReplaceRow -> observers.toTypedArray().forEach { it.onReplaceRow(event.payload) }
        }
    }

    override fun onTerminated(cause: E2eeRuntimeUnavailable) {
        if (stopped) return
        runtime = null
        eventQueue?.close()
        eventQueue = null
        notifyPlaintextInvalidated()
        setState(E2eeServiceState.Unavailable(cause.userMessage, cause.reason.isWebViewProblem()))
        val now = System.currentTimeMillis()
        terminations.addLast(now)
        while (terminations.isNotEmpty() && now - terminations.first() > REBUILD_WINDOW_MS) terminations.removeFirst()
        // Rebuild from committed storage while in foreground; a crash loop waits for the next foreground.
        if (foreground.isForeground && terminations.size <= MAX_REBUILDS_PER_WINDOW) {
            scope.launch { startRuntime(rebuild = true) }
        }
    }

    // ── events ─────────────────────────────────────────────────────────────

    private inner class EventsListener : E2eeEvents.Listener {
        override fun onOpen() {
            // Pub/sub events since the last connection are lost: reconcile held groups, then resubmit rows.
            scheduleContext(0, reconnect = true, resubmit = true)
        }

        override fun onEvents(events: List<JsonObject>) {
            val queue = eventQueue ?: return
            for (e in events) {
                if (queue.trySend(e).isFailure) {
                    Log.w(TAG, "MLS event backlog full; reconciling instead")
                    scheduleContext(0, reconnect = true, resubmit = true)
                    return
                }
            }
        }

        override fun onUnauthorized() = sessionEnded()
    }

    /** One consumer per generation: MLS events in arrival order (Welcome before the messages that need it). */
    private fun startEventConsumer(rt: E2eeRuntime, gen: Long) {
        val queue = Channel<JsonObject>(EVENT_BACKLOG)
        eventQueue = queue
        scope.launch {
            for (event in queue) {
                if (runtime !== rt) break
                try {
                    rt.dispatch(gen, E2eeOp.HandleWsEvent, buildJsonObject { put("event", event) })
                } catch (e: E2eeBridgeException) {
                    Log.w(TAG, "MLS event not applied: ${e.code}")
                }
            }
        }
    }

    // ── context ────────────────────────────────────────────────────────────

    /** Membership, account, nick or network changes: refresh the runtime's context (debounced, no group sweep). */
    @MainThread
    fun contextChanged() {
        scheduleContext(CONTEXT_DEBOUNCE_MS, reconnect = false)
    }

    /**
     * The user's "Try Again" after a setup or policy failure: a missing runtime is (re)started from committed
     * storage, a live one refreshes its policy and retries device setup (`setContext {reconnect:true}`). Never for
     * [E2eeServiceState.RecoveryRequired] (only the user's explicit discard leaves that).
     */
    @MainThread
    fun retry() {
        if (stopped || sessionExpired || _state.value is E2eeServiceState.RecoveryRequired) return
        if (runtime != null) {
            scheduleContext(0, reconnect = true, resubmit = true)
            return
        }
        scope.launch {
            if (account == null || store == null) boot() else startRuntime(rebuild = true)
            if (!stopped && runtime != null && foreground.isForeground) events.start()
        }
    }

    private fun scheduleContext(delayMs: Long, reconnect: Boolean, resubmit: Boolean = false) {
        if (stopped || runtime == null) return
        if (reconnect) contextSettled = false
        // A pending reconnect refresh is never downgraded by a later plain one.
        val pendingReconnect = pendingContextReconnect
        if (contextJob?.isActive == true && pendingReconnect && !reconnect) return
        contextJob?.cancel()
        pendingContextReconnect = reconnect
        contextJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            pushContext(reconnect)
            pendingContextReconnect = false
            if (resubmit) observers.toTypedArray().forEach { it.onResubmitPending() }
        }
    }

    private var pendingContextReconnect = false

    private suspend fun pushContext(reconnect: Boolean) {
        val rt = runtime ?: return
        val acct = account ?: return
        val gen = generation
        try {
            val status = rt.dispatch(gen, E2eeOp.SetContext, contextArgs(acct, reconnect))
            if (reconnect && gen == generation && runtime === rt) contextSettled = true
            adoptStatus(gen, status)
        } catch (e: E2eeBridgeException) {
            Log.w(TAG, "encryption context refresh failed: ${e.code}")
        }
    }

    /**
     * The live runtime's context is current: its bootstrap, or the reconnect `setContext` after the latest
     * foreground / event-stream reconnect / rebuild, committed. Until then its status may be stale and the message
     * pipeline treats no target as positively off.
     */
    var contextSettled = false
        private set

    private fun contextArgs(acct: Account, reconnect: Boolean): JsonObject = buildJsonObject {
        put("account", acct.username)
        put("userId", acct.userId)
        put("apiOrigin", apiOrigin)
        put("networks", irc.e2eeNetworks())
        put("bufferPrefs", bufferPrefsJson())
        put("localPrefs", JsonObject(localPrefs.mapValues { JsonPrimitive(it.value) }))
        if (reconnect) put("reconnect", true)
    }

    /** The account's buffer prefs (bool/string values) as the runtime's `bufferPrefs` context. */
    private fun bufferPrefsJson(): JsonObject = JsonObject(
        bufferPrefsStore.values.value
            .filterKeys { it.indexOf(':') > 0 }
            .mapValues { (_, values) -> JsonObject(values.mapValues { it.value.toJson() }) },
    )

    /** Server `bufferPrefs` of an authenticated `/api/me` into the account's one store (bool/string values; in-flight
     *  saves keep theirs). A missing or malformed `bufferPrefs` object leaves the prefs unknown (never read as
     *  "everything off"). */
    private fun adoptBufferPrefs(raw: JsonObject?) {
        raw ?: return
        bufferPrefsStore.load(lenientBufferPrefs(raw))
        bufferPrefsLoaded = true
    }

    // ── lock inputs for the message pipeline ───────────────────────────────

    /** Whether the account's server buffer prefs are known: an authenticated `/api/me` for this service's account
     *  answered with a `bufferPrefs` object. Until then no target is positively off. */
    val bufferPrefsKnown: Boolean get() = account != null && bufferPrefsLoaded

    private var bufferPrefsLoaded = false

    /** The account's server buffer pref [key] of `networkId:buffer` (case-insensitive buffer); null when unset. */
    fun bufferPref(networkId: String, buffer: String, key: String): JsonPrimitive? =
        bufferPrefsStore.raw(networkId, buffer, key)?.toJson()

    /** Device-local encryption default [name] (`dmDefault`, `requireUnlock`, …) from the encrypted account store. */
    fun localDefault(name: String): Boolean {
        val raw = localPrefs[DEFAULTS_PREF] ?: return false
        return runCatching {
            ((Json.parseToJsonElement(raw) as? JsonObject)?.get(name) as? JsonPrimitive)?.booleanOrNull == true
        }.getOrDefault(false)
    }

    /** All device-local encryption defaults (base values for anything unset). */
    fun localDefaults(): E2eeDefaults = E2eeDefaults.parse(localPrefs[DEFAULTS_PREF])

    // ── status / lock ──────────────────────────────────────────────────────

    private fun adoptStatus(gen: Long, json: JsonElement) {
        if (gen != generation || runtime == null || stopped) return
        val obj = json as? JsonObject ?: return
        val status = E2eeStatus(obj)
        val wasLocked = lockedByState && !unlockProven
        lockedByState = status.locked
        if (!status.locked && status.requireUnlock) unlockProven = true
        if (!status.requireUnlock) unlockProven = false
        setState(E2eeServiceState.Running(gen, status))
        if (wasLocked && !status.locked && status.ready) {
            // Events were refused while locked: reconcile and resubmit what the lock held back.
            scheduleContext(0, reconnect = true, resubmit = true)
        }
    }

    /**
     * Secret store reads (MLS state, archive, PGP keys, own-send plaintext, link staging) are refused while the
     * latest runtime status says unlock is required and not done, unless an unlock in this generation succeeded or
     * is in flight (the runtime verifies it before it reads). Bootstrap starts from the local require-unlock
     * default. The runtime enforces the same; this is the native backstop.
     */
    private fun secretReadsAllowed(): Boolean = !lockedByState || unlockProven || unlocksInFlight > 0

    private fun requireSecretAccess() {
        if (!secretReadsAllowed()) throw E2eeBridgeException("locked", "unlock encryption first")
    }

    private fun localDefaultsRequireUnlock(): Boolean {
        val raw = localPrefs[DEFAULTS_PREF] ?: return false
        return runCatching {
            ((Json.parseToJsonElement(raw) as? JsonObject)?.get("requireUnlock") as? JsonPrimitive)?.booleanOrNull == true
        }.getOrDefault(false)
    }

    // ── host calls ─────────────────────────────────────────────────────────

    override suspend fun handle(call: E2eeHostCall): JsonElement {
        if (call.generation != generation) throw E2eeBridgeException("stale_generation", "host call from a previous session")
        // While a generation shuts down (sign-out, relock, rebuild) its engine still flushes committed state:
        // storage stays reachable, everything else is refused.
        if ((stopped || runtime == null) && !call.op.wire.startsWith("storage.")) {
            throw E2eeBridgeException("shutdown", "encryption session closing")
        }
        val args = call.args
        return when (call.op) {
            E2eeHostOp.HttpRequest -> httpRequest(args)
            E2eeHostOp.PrefsSetBuffer -> setBufferPref(args)
            E2eeHostOp.PrefsSetLocal -> setLocalPref(args)
            E2eeHostOp.IrcSendWho -> sendWho(args)
            E2eeHostOp.TransportSendWire -> sendWire(args)
            E2eeHostOp.PasskeyPrf -> passkeyPrf(args)
            else -> storageCall(call.op, args, call.generation)
        }
    }

    private suspend fun storageCall(op: E2eeHostOp, a: JsonObject, gen: Long): JsonElement {
        val st = store ?: throw E2eeBridgeException("storage_failed", "encryption storage closed")
        try {
            return when (op) {
                E2eeHostOp.StorageGetMeta -> {
                    val key = a.req("key")
                    if (isSecretMeta(key)) requireSecretAccess()
                    st.getMeta(key) ?: JsonNull
                }
                E2eeHostOp.StorageSetMeta -> {
                    val key = a.req("key")
                    val value = a["value"] ?: throw bad("value is required")
                    st.setMeta(key, value)
                    if (key == META_DEVICE_ID) mlsDeviceId = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    JsonNull
                }
                E2eeHostOp.StorageDeleteMeta -> {
                    val key = a.req("key")
                    st.deleteMeta(key)
                    if (key == META_DEVICE_ID) mlsDeviceId = null
                    JsonNull
                }
                E2eeHostOp.StorageListMeta -> {
                    val prefix = a.str("prefix") ?: throw bad("prefix must be a string")
                    if (SECRET_META_PREFIXES.any { it.startsWith(prefix) || prefix.startsWith(it) }) requireSecretAccess()
                    buildJsonArray {
                        for ((key, value) in st.listMeta(prefix)) {
                            add(buildJsonObject { put("key", key); put("value", value) })
                        }
                    }
                }
                E2eeHostOp.StorageLoadMlsState -> {
                    requireSecretAccess()
                    st.loadMlsState()?.let { JsonPrimitive(it) } ?: JsonNull
                }
                E2eeHostOp.StorageSaveMlsState -> {
                    st.saveMlsState(a.req("state"))
                    JsonNull
                }
                E2eeHostOp.StorageArchiveGet -> {
                    requireSecretAccess()
                    st.archiveGet(a.req("msgid")) ?: JsonNull
                }
                E2eeHostOp.StorageArchivePut -> {
                    val row = a["row"] as? JsonObject ?: throw bad("row must be an object")
                    st.archivePut(a.req("msgid"), a.req("networkId"), a.req("buffer"), row)
                    JsonNull
                }
                E2eeHostOp.StorageArchiveExportAll -> {
                    requireSecretAccess()
                    JsonArray(st.archiveExportAll().map(E2eeArchiveRow::toJson))
                }
                E2eeHostOp.StorageArchiveImport -> {
                    val rows = (a["rows"] as? JsonArray ?: throw bad("rows must be an array")).map {
                        (it as? JsonObject)?.let { row -> E2eeStore.parseArchiveRow(row) } ?: throw bad("malformed archive row")
                    }
                    st.archiveImport(rows)
                    JsonNull
                }
                E2eeHostOp.StorageImportRecovery -> {
                    val payload = a["payload"] as? JsonObject ?: throw bad("payload must be an object")
                    val result = st.importRecovery(payload)
                    buildJsonObject {
                        put("archivedRows", result.archivedRows)
                        put("pgpConflicts", JsonArray(result.pgpConflicts.map { JsonPrimitive(it) }))
                    }
                }
                E2eeHostOp.StorageArchivePrune -> {
                    val keep = a["keep"]?.takeIf { it !is JsonNull }?.let {
                        (it as? JsonPrimitive)?.intOrNull?.takeIf { n -> n >= 0 } ?: throw bad("keep must be a count")
                    }
                    if (keep == null) {
                        st.archivePrune(a.req("networkId"), a.req("buffer"))
                    } else {
                        st.archivePrune(a.req("networkId"), a.req("buffer"), keep)
                    }
                    JsonNull
                }
                E2eeHostOp.StorageE2eeDbExists -> JsonPrimitive(st.hasData())
                E2eeHostOp.StorageDeleteE2eeDb -> {
                    deleteLocalStore(st, gen)
                    JsonNull
                }
                else -> throw E2eeBridgeException("unknown_op", "unknown host operation")
            }
        } catch (e: E2eeRecoveryRequired) {
            enterRecoveryRequired(e)
            throw E2eeBridgeException("recovery_required", e.message ?: "encryption storage needs recovery")
        } catch (e: E2eeStoreRejected) {
            throw E2eeBridgeException("bad_request", e.message ?: "storage request refused")
        } catch (e: E2eeBridgeException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${op.wire} failed", e)
            throw E2eeBridgeException("storage_failed", "${op.wire} failed")
        }
    }

    /**
     * `deleteE2eeDb` (explicit local-key removal; the runtime stopped its engine's writes first): deletes the
     * account's database and Keystore key, then opens a fresh empty store so this session can continue with a new
     * identity. Device-local encryption defaults survive: losing keys must not quietly drop require-unlock.
     */
    private suspend fun deleteLocalStore(st: E2eeStore, gen: Long) {
        val acct = account ?: throw E2eeBridgeException("storage_failed", "no account")
        val keptPrefs = localPrefs
        st.destroy()
        mlsDeviceId = null
        val fresh = E2eeStore.forAccount(app, apiOrigin, acct.userId)
        fresh.open()
        for ((k, v) in keptPrefs) fresh.setLocalPref(k, v)
        if (gen == generation && store === st) store = fresh else fresh.close()
    }

    private suspend fun httpRequest(a: JsonObject): JsonElement {
        val body = a["body"]?.takeIf { it !is JsonNull }
        val target = E2eeApiPolicy.check(a.req("method"), a.req("path"), body != null)
        val headers = mlsDeviceId?.let { mapOf(MLS_DEVICE_HEADER to it) } ?: emptyMap()
        val answer = api.rawApiRequest(target.method, target.encodedPath, target.encodedQuery, body, headers).getOrElse { e ->
            if (e is CancellationException) throw e
            throw E2eeBridgeException("network", if (e is IOException) "network request failed" else "request failed")
        }
        if (answer.code in 300..399) throw E2eeBridgeException("redirect_refused", "redirects are not followed", answer.code)
        if (answer.code == 401) sessionEnded()
        return buildJsonObject {
            put("status", answer.code)
            put("body", answer.body)
        }
    }

    /** `prefs.setBuffer`: the encryption keys of the account's buffer prefs (`e2ee` bool, `e2eeMode` string), saved
     *  through [bufferPrefsStore]. Applied at once; a failed save restores the previous value only when no newer edit
     *  happened, and the settled value reaches the runtime through the next `setContext`. */
    private suspend fun setBufferPref(a: JsonObject): JsonElement {
        val networkId = a.req("networkId")
        val buffer = a.req("buffer")
        val key = a.req("key")
        val value = a["value"] as? JsonPrimitive ?: throw bad("value must be a boolean or string")
        try {
            when (key) {
                BufferPrefsStore.E2EE -> {
                    val on = value.takeUnless { it.isString }?.booleanOrNull ?: throw bad("e2ee must be a boolean")
                    bufferPrefsStore.setE2ee(on, networkId, buffer)
                }
                BufferPrefsStore.E2EE_MODE -> {
                    val mode = value.takeIf { it.isString }?.let { BufferPrefsStore.E2eeMode.of(it.content) }
                        ?: throw bad("e2eeMode must be mls or pgp")
                    bufferPrefsStore.setE2eeMode(mode, networkId, buffer)
                }
                else -> throw bad("buffer preference $key is not an encryption key")
            }
        } catch (e: E2eeBridgeException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scheduleContext(0, reconnect = false)
            if (e is SiteApiError.Unauthorized) sessionEnded()
            throw E2eeBridgeException("pref_save_failed", "couldn't save the encryption setting")
        }
        return JsonNull
    }

    private suspend fun setLocalPref(a: JsonObject): JsonElement {
        val key = a.req("key")
        if (!key.startsWith(LOCAL_PREF_PREFIX)) throw bad("local preference $key is not an encryption key")
        val value = when (val v = a["value"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> v.takeIf { it.isString }?.content ?: throw bad("value must be a string or null")
            else -> throw bad("value must be a string or null")
        }
        if (value != null && value.length > MAX_LOCAL_PREF_CHARS) throw bad("local preference too large")
        val st = store ?: throw E2eeBridgeException("storage_failed", "encryption storage closed")
        try {
            st.setLocalPref(key, value)
        } catch (e: E2eeRecoveryRequired) {
            enterRecoveryRequired(e)
            throw E2eeBridgeException("recovery_required", e.message ?: "encryption storage needs recovery")
        }
        localPrefs = if (value == null) localPrefs - key else localPrefs + (key to value)
        return JsonNull
    }

    private fun sendWho(a: JsonObject): JsonElement {
        val networkId = a.req("networkId")
        val channel = a.req("channel")
        if (!(channel.startsWith('#') || channel.startsWith('&')) || channel.any { it == ' ' || it == ',' || it.code < 0x20 }) {
            throw bad("WHO needs one channel name")
        }
        if (!irc.sendWho(networkId, channel)) throw E2eeBridgeException("not_connected", "network not connected")
        return JsonNull
    }

    private suspend fun sendWire(a: JsonObject): JsonElement {
        val networkId = a.req("networkId")
        val target = a.req("target")
        val command = a.req("command")
        if (command != "PRIVMSG" && command != "NOTICE") throw bad("unsupported command $command")
        if (target.any { it == ' ' || it.code < 0x20 } || target.startsWith(':')) throw bad("bad target")
        val lines = (a["lines"] as? JsonArray ?: throw bad("lines must be an array")).map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw bad("lines must be strings")
        }
        if (lines.isEmpty() || lines.any { it.isEmpty() || it.any { c -> c == '\r' || c == '\n' || c == '\u0000' } }) {
            throw bad("lines must be non-empty single IRC lines")
        }
        val tags = when (val t = a["tags"]) {
            null, is JsonNull -> emptyMap()
            is JsonObject -> t.mapValues { (k, v) ->
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw bad("tags must map names to strings")
                if (k.isEmpty() || k.any { it == ' ' || it == ';' || it == '=' || it.code < 0x20 } || s.any { it.code < 0x20 }) {
                    throw bad("bad tag $k")
                }
                s
            }
            else -> throw bad("tags must be an object")
        }
        val label = when (val l = a["label"]) {
            null, is JsonNull -> null
            else -> (l as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                ?: throw bad("label must be a non-empty string")
        }
        val transport = wireTransport
            ?: throw E2eeBridgeException("transport_unavailable", "encrypted sending isn't connected to IRC; nothing was sent")
        transport.sendWire(E2eeWireRequest(networkId, target, command, lines, tags, label))
        return JsonNull
    }

    private suspend fun passkeyPrf(a: JsonObject): JsonElement {
        val mode = a.req("mode")
        if (mode != "create" && mode != "get") throw bad("mode must be create or get")
        val requested = a.req("account")
        val acct = account ?: throw E2eeBridgeException("context_mismatch", "no account")
        if (!requested.equals(acct.username, ignoreCase = true)) throw E2eeBridgeException("context_mismatch", "passkey request for another account")
        val provider = passkeys ?: throw E2eeBridgeException("passkey_unsupported", "passkeys with PRF aren't available on this device")
        val result = holdingForeground { provider.prf(mode, acct.username, a.str("credentialId")) }
        if (result.first.size != 32) throw E2eeBridgeException("passkey_unsupported", "no PRF output")
        if (account?.userId != acct.userId || stopped) throw E2eeBridgeException("context_mismatch", "account changed")
        return buildJsonObject {
            put("credentialId", result.credentialId)
            put("first", Base64.encodeToString(result.first, Base64.NO_WRAP))
        }
    }

    // ── helpers for the message pipeline (phase 5) and encryption UI (phase 6) ─────────────────────────

    /**
     * One user-facing runtime operation of the live generation (`status`, `setLock`, `listDevices`, `backup*`,
     * `unlock`, …). Lifecycle operations (`bootstrap`, `setContext`, `handleWsEvent`, `shutdown`) belong to the
     * service. Throws [E2eeBridgeException] (`unavailable` when no runtime is running).
     */
    suspend fun request(op: E2eeOp, args: JsonObject = JsonObject(emptyMap())): JsonElement = withContext(Dispatchers.Main.immediate) {
        require(op !in SERVICE_OWNED_OPS) { "${op.wire} is owned by the encryption service" }
        val rt = runtime ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        val gen = generation
        if (op == E2eeOp.Unlock) unlocksInFlight++
        try {
            // Passkey operations finish (and commit) before a background relock tears the runtime down.
            val result = if (op in PASSKEY_OPS) holdingForeground { rt.dispatch(gen, op, args) } else rt.dispatch(gen, op, args)
            if (op in STATUS_OPS) adoptStatus(gen, result)
            if (op == E2eeOp.RemoveLocalKeys) restartGeneration(rt, gen)
            result
        } finally {
            if (op == E2eeOp.Unlock) unlocksInFlight--
        }
    }

    /**
     * Registers a PRF passkey for the signed-in account (enabling require-unlock enrolls one before saving the
     * toggle) and returns its credential id (standard base64). Only the credential is kept; the caller proves it
     * with an unlock assertion before relying on it.
     */
    suspend fun enrollPasskey(): String = withContext(Dispatchers.Main.immediate) {
        val acct = account ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        val provider = passkeys ?: throw E2eeBridgeException("passkey_unsupported", "passkeys with PRF aren't available on this device")
        val result = holdingForeground { provider.prf("create", acct.username, null) }
        if (result.first.size != 32) throw E2eeBridgeException("passkey_unsupported", "no PRF output")
        if (account?.userId != acct.userId || stopped) throw E2eeBridgeException("context_mismatch", "account changed")
        result.credentialId
    }

    /** Encrypts and sends one logical line (`sendBufferLine`): the shared pipeline's ordering/retry, the
     *  persisted own-send mapping before any wire byte, and no plaintext fallback. Returns the `SendOutcome`. */
    suspend fun sendBufferLine(
        networkId: String,
        target: String,
        text: String,
        label: String? = null,
        tags: Map<String, String> = emptyMap(),
        command: String = "PRIVMSG",
    ): JsonElement = request(
        E2eeOp.SendBufferLine,
        buildJsonObject {
            put("networkId", networkId)
            put("target", target)
            put("text", text)
            if (label != null) put("label", label)
            if (tags.isNotEmpty()) put("tags", JsonObject(tags.mapValues { JsonPrimitive(it.value) }))
            put("command", command)
        },
    )

    /** Decrypts normalized `IRCMessage` rows (stable native row ids) of one network; results merge into those rows. */
    suspend fun decryptBatch(networkId: String, messages: List<JsonObject>): JsonElement = request(
        E2eeOp.DecryptBatch,
        buildJsonObject {
            put("networkId", networkId)
            put("messages", JsonArray(messages))
        },
    )

    suspend fun setLock(networkId: String, target: String, on: Boolean): JsonElement = request(
        E2eeOp.SetLock,
        buildJsonObject {
            put("networkId", networkId)
            put("target", target)
            put("on", on)
        },
    )

    /** The committed own-send record (`{networkId,target,command,text,label,t}`) for a reassembled self echo;
     *  null when absent. Plaintext: refused while locked. */
    suspend fun ownSend(networkId: String, target: String, ciphertext: String): JsonObject? {
        requireSecretAccess()
        val st = store ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        return st.getMeta(e2eeSendMappingKey(networkId, target, ciphertext)) as? JsonObject
    }

    /** Removes an own-send record after its echo/archive binding committed, or an explicitly discarded failed send. */
    suspend fun retireOwnSend(networkId: String, target: String, ciphertext: String) {
        val st = store ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        st.deleteMeta(e2eeSendMappingKey(networkId, target, ciphertext))
    }

    /** The user's explicit choice to discard an unreadable store (shown only for [E2eeServiceState.RecoveryRequired]):
     *  deletes it and its Keystore key, then starts over with a new identity. */
    suspend fun discardUnrecoverableStore() = withContext(Dispatchers.Main.immediate) {
        check(_state.value is E2eeServiceState.RecoveryRequired) { "store is not in recovery" }
        if (account == null) return@withContext
        dropRuntime()
        store?.destroy()
        store = null
        mlsDeviceId = null
        boot()
    }

    /** After the server deleted the account: removes only this account's local encrypted store, then ends the
     *  service. Never called for a failed deletion. */
    suspend fun deleteLocalStateForDeletedAccount() = destroyLocalStateAndStop()

    /**
     * Sign-out with "Remove this device's keys": the runtime revokes this device on the server, then deletes the
     * account's local encrypted state; the service ends. Throws [E2eeBridgeException] when encryption isn't running
     * or revocation failed — nothing was deleted then (the user may confirm [removeLocalStateOnlyAndStop]).
     */
    suspend fun revokeAndRemoveKeysAndStop() = withContext(Dispatchers.Main.immediate) {
        val rt = runtime ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        rt.dispatch(generation, E2eeOp.RemoveLocalKeys)
        destroyLocalStateAndStop()
    }

    /** The user's separately confirmed local-only removal (revocation failed or impossible): deletes this account's
     *  local encrypted state without telling the server, then ends the service. */
    suspend fun removeLocalStateOnlyAndStop() = destroyLocalStateAndStop()

    /**
     * Staying signed in, the user's separately confirmed local-only key removal (the server couldn't revoke this
     * device): deletes the account's local encrypted state and starts over with a new identity. Device-local
     * encryption defaults survive (losing keys must not quietly drop require-unlock).
     */
    suspend fun removeLocalStateOnlyAndRestart() = withContext(Dispatchers.Main.immediate) {
        val acct = account ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        val keptPrefs = localPrefs
        events.stop()
        dropRuntime()
        val st = store ?: E2eeStore.forAccount(app, apiOrigin, acct.userId)
        store = null
        mlsDeviceId = null
        st.destroy()
        val fresh = E2eeStore.forAccount(app, apiOrigin, acct.userId)
        fresh.open()
        for ((k, v) in keptPrefs) fresh.setLocalPref(k, v)
        store = fresh
        boot()
    }

    private suspend fun destroyLocalStateAndStop() = withContext(Dispatchers.Main.immediate) {
        events.stop()
        dropRuntime()
        val st = store ?: account?.let { E2eeStore.forAccount(app, apiOrigin, it.userId) }
        store = null
        mlsDeviceId = null
        st?.destroy()
        stop()
    }

    /** Whether this account has an encrypted store on this device (sign-out offers keep/remove only then). */
    fun hasLocalStore(): Boolean {
        val id = account?.userId ?: return store != null
        return store != null || E2eeStore.exists(app, apiOrigin, id)
    }

    /** The PRF credential require-unlock was enrolled with (meta `unlockCredentialId`, standard base64); null when
     *  none. Not secret: needed to unlock. */
    suspend fun unlockCredentialId(): String? {
        val st = store ?: return null
        return (st.getMeta(META_UNLOCK_CREDENTIAL) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Records the credential a successful PRF unlock just proved, before require-unlock is turned on. */
    suspend fun setUnlockCredentialId(credentialId: String) {
        val st = store ?: throw E2eeBridgeException("unavailable", "encryption isn't running")
        st.setMeta(META_UNLOCK_CREDENTIAL, JsonPrimitive(credentialId))
    }

    /** After `removeLocalKeys`: a fresh generation over the fresh store (the old engine stopped writing). */
    private suspend fun restartGeneration(rt: E2eeRuntime, gen: Long) {
        val acct = account ?: return
        if (runtime !== rt || generation != gen) return
        notifyPlaintextInvalidated()
        eventQueue?.close()
        runCatching { rt.dispatch(gen, E2eeOp.Shutdown) }
        val next = rt.beginGeneration()
        generation = next
        unlockProven = false
        lockedByState = localDefaultsRequireUnlock()
        startEventConsumer(rt, next)
        try {
            val status = rt.dispatch(next, E2eeOp.Bootstrap, contextArgs(acct, reconnect = false))
            contextSettled = true
            adoptStatus(next, status)
        } catch (e: E2eeBridgeException) {
            Log.w(TAG, "encryption restart failed: ${e.code}")
            dropRuntime()
            setState(E2eeServiceState.Unavailable("Encryption failed to start"))
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun setState(next: E2eeServiceState) {
        if (stopped && next !is E2eeServiceState.Stopped) return
        _state.value = next
        observers.toTypedArray().forEach { it.onState(next) }
    }

    private fun notifyPlaintextInvalidated() = observers.toTypedArray().forEach { it.onPlaintextInvalidated() }

    private fun enterRecoveryRequired(e: E2eeRecoveryRequired) {
        Log.e(TAG, "encryption store needs recovery: ${e.message}")
        events.stop()
        setState(E2eeServiceState.RecoveryRequired(e.message ?: "encryption storage needs recovery"))
        scope.launch { dropRuntime() }
    }

    /** The site session is gone (401) or another account answered: stop retrying, end this service, tell the session. */
    private fun sessionEnded() {
        if (sessionExpired) return
        sessionExpired = true
        events.stop()
        val callback = onSessionExpired
        scope.launch {
            callback?.invoke()
            stop()
        }
    }

    companion object {
        private const val TAG = "E2eeService"
        private const val META_DEVICE_ID = "deviceId"
        private const val META_UNLOCK_CREDENTIAL = "unlockCredentialId"
        private const val DEFAULTS_PREF = "ircfiber:e2ee:defaults"
        private const val LOCAL_PREF_PREFIX = "ircfiber:e2ee:"
        private const val MAX_LOCAL_PREF_CHARS = 64 * 1024
        private const val EVENT_BACKLOG = 256
        private const val CONTEXT_DEBOUNCE_MS = 2_000L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val FLUSH_TIMEOUT_MS = 5_000L
        private const val REBUILD_WINDOW_MS = 60_000L
        private const val MAX_REBUILDS_PER_WINDOW = 3

        /** Meta records holding key material or plaintext (the runtime's `SECRET_META_PREFIXES`). */
        private val SECRET_META_PREFIXES = listOf("pgp:", "sent:", "linkRx:")

        private val SERVICE_OWNED_OPS = setOf(E2eeOp.Bootstrap, E2eeOp.SetContext, E2eeOp.HandleWsEvent, E2eeOp.Shutdown)
        private val STATUS_OPS = setOf(E2eeOp.Status, E2eeOp.SetDefaults, E2eeOp.Unlock)

        /** Operations that may present a passkey sheet (`passkey.prf`). */
        private val PASSKEY_OPS = setOf(E2eeOp.Unlock, E2eeOp.BackupWithPasskey, E2eeOp.RestoreBackup)

        /** Serializes runtime creation/destruction across services: one live runtime per process. */
        private val processLifecycle = Mutex()
        private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** The process's live service (main-thread confined): starting another stops this one. */
        private var active: E2eeService? = null

        private fun isSecretMeta(key: String) = SECRET_META_PREFIXES.any { key.startsWith(it) }

        private fun E2eeRuntimeUnavailable.Reason.isWebViewProblem() =
            this == E2eeRuntimeUnavailable.Reason.WebViewUpdateRequired || this == E2eeRuntimeUnavailable.Reason.WebViewMissing

        private fun bad(why: String) = E2eeBridgeException("bad_request", why)

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.req(key: String): String =
            str(key)?.takeIf { it.isNotEmpty() } ?: throw bad("$key must be a non-empty string")

        /** The encryption runtime and its secrets live only in the app's main process. */
        fun isMainProcess(context: Context): Boolean {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
            return name == context.packageName
        }
    }
}
