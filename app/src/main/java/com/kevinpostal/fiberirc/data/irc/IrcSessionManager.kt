package com.kevinpostal.fiberirc.data.irc

import android.content.Context
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.data.api.SessionBootstrap
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.networks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * The signed-in session's IRC connections: one [IrcClient] per network (the bouncer's networks, or the single
 * direct-ircd fallback), plus the session-level duties `SessionViewController.swift` has that aren't UI —
 * following `GET /api/networks` (upstream caps), adding/removing clients when the account's networks change
 * ([syncNetworks]) and leaving for the login screen when the bouncer rejects the credentials.
 *
 * It is the one observer on every client and fans each event out to [addListener] listeners, bumping [revision]
 * so Compose can recompose over the clients' plain mutable buffers. Main-thread confined, like [IrcClient].
 *
 * Lifecycle: construct with the login's settings → [start] (connects) → [stop] (sign-out / back to login; the
 * manager is done afterwards).
 *
 * @param clientId this install's `X-IRCFiber-Device` id, for [SessionBootstrap.bouncerSettings].
 * @param showLogin replaces the session with the sign-in screen showing `notice` (rejected credentials).
 */
class IrcSessionManager(
    settings: List<ConnectionSettings>,
    private val api: SiteApi,
    private val clientId: () -> String,
    private val makeClient: (ConnectionSettings) -> IrcClient,
    private val showLogin: (notice: String) -> Unit,
    private val scope: CoroutineScope = MainScope(),
    private val clock: () -> Instant = Instant::now,
) : IrcClientListener {
    /** Production wiring: real sockets via [IrcClient.create]. */
    constructor(
        context: Context,
        settings: List<ConnectionSettings>,
        api: SiteApi,
        clientId: () -> String,
        showLogin: (notice: String) -> Unit,
    ) : this(settings, api, clientId, { IrcClient.create(context.applicationContext, it) }, showLogin)

    private val _clients = MutableStateFlow(settings.map(makeClient))

    /** One client per network, in the bouncer's order (managed networks first). */
    val clients: StateFlow<List<IrcClient>> = _clients.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /** Bumped on every client event (lines, buffers, members, state…) and on client-list changes. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _networkInfos = MutableStateFlow<List<NetworkInfo>>(emptyList())

    /** `GET /api/networks`, refreshed by [refreshNetworks]; empty until the first answer. */
    val networkInfos: StateFlow<List<NetworkInfo>> = _networkInfos.asStateFlow()

    private val _networksLoaded = MutableStateFlow(false)

    /** [networkInfos] holds an answer (an `irc://` link waits for it). */
    val networksLoaded: StateFlow<Boolean> = _networksLoaded.asStateFlow()

    /**
     * The session's rules on a client (highlight words, mute, Mark As Read Automatically — `wire(_:)` in iOS),
     * applied to every client now and to each one [syncNetworks] adds. Set before [start].
     */
    var configure: (IrcClient) -> Unit = {}
        set(value) {
            field = value
            _clients.value.forEach(value)
        }

    private val listeners = ArrayList<IrcClientListener>()
    private var started = false
    private var tornDown = false
    private var leavingForLogin = false

    init {
        _clients.value.forEach { it.addObserver(this) }
    }

    val isTornDown: Boolean get() = tornDown

    /** Adds [l] to the listeners told about every client's events (current and future clients). */
    fun addListener(l: IrcClientListener) {
        if (listeners.none { it === l }) listeners.add(l)
    }

    fun removeListener(l: IrcClientListener) {
        listeners.removeAll { it === l }
    }

    /** Connects every client (once). */
    fun start() {
        if (started || tornDown) return
        started = true
        _clients.value.forEach { it.connect() }
    }

    /** Leaving for the sign-in screen: stops observing and disconnects every client. Terminal. */
    fun stop() {
        if (tornDown) return
        tornDown = true
        _clients.value.forEach(::retire)
        listeners.clear()
    }

    /** Drops the client of [networkId] (its network was deleted): disconnects it and removes it from [clients]. */
    fun close(networkId: String) {
        val c = _clients.value.firstOrNull { it.settings.networkId == networkId } ?: return
        retire(c)
        _clients.value = _clients.value.filterNot { it === c }
        bump()
    }

    /** The client whose network key (`networkId`, or the network name without one) is [networkKey]. */
    fun client(networkKey: String): IrcClient? = _clients.value.firstOrNull { networkKey(it) == networkKey }

    /** [name] (case-insensitive) on the network keyed [networkId] (or the network name on the direct fallback). */
    fun buffer(networkId: String, name: String): IrcBuffer? = client(networkId)?.buffer(name)

    /** Upstream caps per network (reply/typing/edit support) and the network configs. Failures keep the last rows. */
    suspend fun refreshNetworks() {
        val infos = api.networks().getOrNull() ?: return
        if (tornDown) return
        _networkInfos.value = infos
        _networksLoaded.value = true
        for (c in _clients.value) {
            val info = infos.firstOrNull { it.id == c.settings.networkId } ?: continue
            c.upstreamCaps = info.caps?.toSet() ?: emptySet()
        }
        bump()
    }

    /**
     * Brings the clients in line with the account's networks (after one is added, edited or deleted, and on
     * foreground): connects new ones, disconnects removed ones. The direct-ircd fallback has no list to follow.
     * Returns the clients removed (the shell replaces a shown buffer of theirs).
     */
    suspend fun syncNetworks(me: Me): List<IrcClient> {
        if (tornDown || !_clients.value.all { it.settings.viaBouncer }) return emptyList()
        val settings = SessionBootstrap.bouncerSettings(me, api, clientId()).getOrNull()
        if (settings.isNullOrEmpty() || tornDown) return emptyList()
        val current = _clients.value
        val wanted = settings.mapTo(HashSet()) { it.networkId }
        val removed = current.filter { it.settings.networkId !in wanted }
        val added = settings.filter { s -> current.none { it.settings.networkId == s.networkId } }.map(makeClient)
        if (removed.isEmpty() && added.isEmpty()) return emptyList()
        removed.forEach(::retire)
        val all = current + added
        _clients.value = settings.mapNotNull { s -> all.firstOrNull { it.settings.networkId == s.networkId } }
        for (c in added) {
            c.addObserver(this)
            configure(c)
        }
        if (started) added.forEach { it.connect() }
        bump()
        return removed
    }

    private fun retire(c: IrcClient) {
        c.removeObserver(this)
        c.disconnect()
        // The QUIT goes out and the server closes the link (the client force-closes after 2 s); then release it.
        scope.launch {
            delay(RETIRE_DELAY_MS)
            c.dispose()
        }
    }

    private fun bump() {
        _revision.value++
    }

    private inline fun forward(body: (IrcClientListener) -> Unit) {
        bump()
        for (l in listeners.toTypedArray()) body(l)
    }

    // region IrcClientListener (fan-out)

    override fun onStateChanged(client: IrcClient, state: IrcState, error: Throwable?) =
        forward { it.onStateChanged(client, state, error) }

    override fun onBufferAdded(client: IrcClient, buffer: IrcBuffer) = forward { it.onBufferAdded(client, buffer) }

    override fun onBufferRemoved(client: IrcClient, buffer: IrcBuffer) = forward { it.onBufferRemoved(client, buffer) }

    override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) =
        forward { it.onLineAppended(client, buffer, line) }

    override fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) = forward { it.onMembersUpdated(client, buffer) }

    override fun onBufferUpdated(client: IrcClient, buffer: IrcBuffer) = forward { it.onBufferUpdated(client, buffer) }

    override fun onHistoryLoaded(client: IrcClient, buffer: IrcBuffer, added: Int) =
        forward { it.onHistoryLoaded(client, buffer, added) }

    override fun onAttachReplayFinished(client: IrcClient) = forward { it.onAttachReplayFinished(client) }

    override fun onTypingUpdated(client: IrcClient, buffer: IrcBuffer) = forward { it.onTypingUpdated(client, buffer) }

    override fun onLinesChanged(client: IrcClient, buffer: IrcBuffer) = forward { it.onLinesChanged(client, buffer) }

    /**
     * Bouncer/ircd refused the password (token rotated from the web, or the account was deleted). The login screen
     * re-probes the site session: a live one reconnects with a freshly fetched bouncer token, a dead one shows the
     * sign-in form with the notice. A second rejection within a minute signs out instead of bouncing.
     */
    override fun onCredentialsRejected(client: IrcClient) {
        forward { it.onCredentialsRejected(client) }
        if (leavingForLogin) return   // every network's client reports the same rejection
        leavingForLogin = true
        val now = clock()
        val repeated = lastCredentialRecovery?.let { Duration.between(it, now) < REPEAT_WINDOW } ?: false
        lastCredentialRecovery = now
        scope.launch {
            if (repeated) api.logout()
            showLogin("Your sign-in is no longer valid — sign in again.")
        }
    }

    // endregion

    companion object {
        private const val RETIRE_DELAY_MS = 2_500L
        private val REPEAT_WINDOW: Duration = Duration.ofSeconds(60)

        /** When the last rejected-credentials recovery ran (process-wide, like the iOS static). */
        private var lastCredentialRecovery: Instant? = null

        /** `networkId`, or the network name on the direct-ircd fallback (no id). */
        fun networkKey(c: IrcClient): String = c.settings.networkId.ifEmpty { c.settings.networkName }
    }
}
