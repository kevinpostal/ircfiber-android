package com.kevinpostal.fiberirc.data.irc

import android.content.Context
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.data.api.SessionBootstrap
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.networks
import com.kevinpostal.fiberirc.data.e2ee.E2eeBridgeException
import com.kevinpostal.fiberirc.data.e2ee.E2eeIrcHost
import com.kevinpostal.fiberirc.data.e2ee.E2eeNetworkEncryption
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceObserver
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeWireRequest
import com.kevinpostal.fiberirc.data.e2ee.E2eeWireTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
 * Encryption: production sessions own the signed-in account's one [E2eeService] ([e2ee]), started and stopped
 * with the session; the manager is its [E2eeIrcHost] (network/member context, WHO) and tells it about membership,
 * nick and network changes. Every bouncer network's client gets [IrcEncryptionConfig.Encrypted] over that service
 * (sends decided and encrypted, ciphertext decrypted, its `transport.sendWire` written by that client); a network
 * without a site network id (direct IRC) is [IrcEncryptionConfig.Plaintext]. Test sessions (FakeTransport) pass
 * no factory: their clients are explicitly plaintext.
 *
 * @param clientId this install's `X-IRCFiber-Device` id, for [SessionBootstrap.bouncerSettings].
 * @param makeClient builds one network's client with the encryption configuration the session chose for it.
 * @param showLogin replaces the session with the sign-in screen showing `notice` (rejected credentials).
 * @param e2eeFactory builds the session's encryption service; null disables encryption (tests).
 */
class IrcSessionManager(
    settings: List<ConnectionSettings>,
    private val api: SiteApi,
    private val clientId: () -> String,
    private val makeClient: (ConnectionSettings, IrcEncryptionConfig) -> IrcClient,
    private val showLogin: (notice: String) -> Unit,
    private val scope: CoroutineScope = MainScope(),
    private val clock: () -> Instant = Instant::now,
    e2eeFactory: ((IrcSessionManager) -> E2eeService)? = null,
) : IrcClientListener, E2eeIrcHost {
    /** Production wiring: real sockets via [IrcClient.create]. */
    constructor(
        context: Context,
        settings: List<ConnectionSettings>,
        api: SiteApi,
        clientId: () -> String,
        showLogin: (notice: String) -> Unit,
        e2eeFactory: (IrcSessionManager) -> E2eeService,
    ) : this(
        settings,
        api,
        clientId,
        { s, encryption -> IrcClient.create(context.applicationContext, s, encryption) },
        showLogin,
        e2eeFactory = e2eeFactory,
    )

    /**
     * The signed-in account's encryption service (null in tests). Message paths reach its typed helpers through
     * each client's [IrcEncryptionConfig]; without a running service encrypted sends fail, never fall back.
     */
    val e2ee: E2eeService? = e2eeFactory?.invoke(this)?.also { service ->
        service.onSessionExpired = { leaveForLogin("Your sign-in has expired — sign in again.") }
    }

    private val _clients = MutableStateFlow(settings.map { makeClient(it, encryptionFor(it)) })

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
        e2ee?.let { service ->
            service.wireTransport = E2eeWireTransport { request -> writeWire(request) }
            service.addObserver(E2eeRelay())
        }
    }

    val isTornDown: Boolean get() = tornDown

    /** Adds [l] to the listeners told about every client's events (current and future clients). */
    fun addListener(l: IrcClientListener) {
        if (listeners.none { it === l }) listeners.add(l)
    }

    fun removeListener(l: IrcClientListener) {
        listeners.removeAll { it === l }
    }

    /** Connects every client (once) and starts the session's encryption service. */
    fun start() {
        if (started || tornDown) return
        started = true
        _clients.value.forEach { it.connect() }
        e2ee?.start()
    }

    var connectionsPaused = false
        private set
    /** IRC service loss is not account loss: retain buffers and independent voice ownership. */
    fun pauseConnections() {
        if (tornDown) return
        connectionsPaused = true
        _clients.value.forEach { it.disconnect() }
    }
    fun resumeConnections() {
        if (tornDown || !connectionsPaused) return
        connectionsPaused = false
        _clients.value.forEach { it.connect() }
    }

    /** Leaving for the sign-in screen: stops observing, disconnects every client and stops encryption. Terminal. */
    fun stop() {
        if (tornDown) return
        tornDown = true
        e2ee?.stop()
        _clients.value.forEach(::retire)
        listeners.clear()
    }

    /** Drops the client of [networkId] (its network was deleted): disconnects it and removes it from [clients]. */
    fun close(networkId: String) {
        val c = _clients.value.firstOrNull { it.settings.networkId == networkId } ?: return
        retire(c)
        _clients.value = _clients.value.filterNot { it === c }
        e2ee?.contextChanged()
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
        e2ee?.contextChanged()
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
        val added = settings.filter { s -> current.none { it.settings.networkId == s.networkId } }
            .map { makeClient(it, encryptionFor(it)) }
        if (removed.isEmpty() && added.isEmpty()) return emptyList()
        removed.forEach(::retire)
        val all = current + added
        _clients.value = settings.mapNotNull { s -> all.firstOrNull { it.settings.networkId == s.networkId } }
        for (c in added) {
            c.addObserver(this)
            configure(c)
        }
        if (started) added.forEach { it.connect() }
        e2ee?.contextChanged()
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

    override fun onStateChanged(client: IrcClient, state: IrcState, error: Throwable?) {
        if (state == IrcState.CONNECTED) e2ee?.contextChanged()
        forward { it.onStateChanged(client, state, error) }
    }

    override fun onBufferAdded(client: IrcClient, buffer: IrcBuffer) {
        e2ee?.contextChanged()
        forward { it.onBufferAdded(client, buffer) }
    }

    override fun onBufferRemoved(client: IrcClient, buffer: IrcBuffer) {
        e2ee?.contextChanged()
        forward { it.onBufferRemoved(client, buffer) }
    }

    override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) =
        forward { it.onLineAppended(client, buffer, line) }

    /** Membership, nick and IRC account changes: the encryption audience follows them. */
    override fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) {
        e2ee?.contextChanged()
        forward { it.onMembersUpdated(client, buffer) }
    }

    override fun onBufferUpdated(client: IrcClient, buffer: IrcBuffer) = forward { it.onBufferUpdated(client, buffer) }

    override fun onHistoryLoaded(client: IrcClient, buffer: IrcBuffer, added: Int) =
        forward { it.onHistoryLoaded(client, buffer, added) }

    override fun onAttachReplayFinished(client: IrcClient) = forward { it.onAttachReplayFinished(client) }

    override fun onTypingUpdated(client: IrcClient, buffer: IrcBuffer) = forward { it.onTypingUpdated(client, buffer) }

    override fun onLinesChanged(client: IrcClient, buffer: IrcBuffer) = forward { it.onLinesChanged(client, buffer) }

    /**
     * IRC credentials can rotate while the website login and voice remain healthy. The account owner
     * refreshes IRC alone after confirming website identity. Repeated IRC rejection pauses recovery;
     * only an actually expired website session returns to sign-in.
     */
    override fun onCredentialsRejected(client: IrcClient) {
        forward { it.onCredentialsRejected(client) }
        if (leavingForLogin) return   // every network's client reports the same rejection
        val now = clock()
        val repeated = lastCredentialRecovery?.let { Duration.between(it, now) < REPEAT_WINDOW } ?: false
        lastCredentialRecovery = now
        scope.launch {
            // IRC credential rotation is not website logout: recover IRC independently of voice.
            val me = api.me()
            if (tornDown) return@launch
            val account = me.getOrNull()
            if (account != null) {
                if (!repeated) leaveForLogin("Refreshing IRC credentials…")
            } else if (me.exceptionOrNull() is com.kevinpostal.fiberirc.data.api.SiteApiError.Unauthorized) {
                leaveForLogin("Your sign-in is no longer valid — sign in again.")
            }
        }
    }

    /** Request account-owner recovery once; it decides between IRC-only refresh and actual account loss. */
    private fun leaveForLogin(notice: String) {
        if (leavingForLogin || tornDown) return
        leavingForLogin = true
        showLogin(notice)
    }

    // endregion

    // region Encryption

    /** Bouncer networks encrypt through the session's service; a network without a site id can't (direct IRC). */
    private fun encryptionFor(s: ConnectionSettings): IrcEncryptionConfig {
        val service = e2ee ?: return IrcEncryptionConfig.Plaintext
        if (!s.viaBouncer || s.networkId.isEmpty()) return IrcEncryptionConfig.Plaintext
        return IrcEncryptionConfig.Encrypted(E2eeNetworkEncryption(service, s.networkId))
    }

    private fun encryptedClients(): List<IrcClient> =
        _clients.value.filter { it.encryption is IrcEncryptionConfig.Encrypted }

    /** `transport.sendWire` onto that network's link: all accepted, nothing written, or uncertain (never retried). */
    private fun writeWire(request: E2eeWireRequest) {
        val c = encryptedClients().firstOrNull { it.settings.networkId == request.networkId }
            ?: throw E2eeBridgeException("not_connected", "network not connected; nothing was sent")
        when (c.sendWire(request.target, request.command, request.lines, request.tags, request.label)) {
            IrcWireResult.SENT -> Unit
            IrcWireResult.NOT_WRITTEN -> throw E2eeBridgeException("not_connected", "nothing was sent")
            // The runtime chose plaintext for a target this device doesn't positively know to be off.
            IrcWireResult.PLAINTEXT_REFUSED ->
                throw E2eeBridgeException("plaintext_refused", "this target isn't known to be unencrypted; nothing was sent")
            IrcWireResult.UNCERTAIN ->
                throw E2eeBridgeException("uncertain_delivery", "the connection failed mid-send; delivery is uncertain")
        }
    }

    /** The service's state, row results, resubmissions and plaintext invalidation, routed to the network clients. */
    private inner class E2eeRelay : E2eeServiceObserver {
        /** Whether the last running status carried a policy decision (decryption refuses rows without one). */
        private var hadPolicy = false

        override fun onState(state: E2eeServiceState) {
            if (tornDown) return
            encryptedClients().forEach { it.encryptionStateChanged() }
            val policy = (state as? E2eeServiceState.Running)?.status?.policyAvailable == true
            // Rows refused while no policy decision existed are read as soon as one does.
            if (policy && !hadPolicy) encryptedClients().forEach { it.resubmitPendingEncrypted() }
            hadPolicy = policy
            bump()
        }

        override fun onReplaceRow(payload: JsonObject) {
            if (tornDown) return
            val networkId = (payload["networkId"] as? JsonPrimitive)?.content ?: return
            val id = (payload["id"] as? JsonPrimitive)?.content ?: return
            val message = payload["message"] as? JsonObject ?: return
            val buffer = (payload["buffer"] as? JsonPrimitive)?.content
            // The payload's stable id is authoritative; msgid stays the fallback.
            val row = JsonObject(message + ("id" to JsonPrimitive(id)))
            encryptedClients().firstOrNull { it.settings.networkId == networkId }?.applyReplaceRow(buffer, row)
        }

        override fun onResubmitPending() {
            if (tornDown) return
            encryptedClients().forEach { it.resubmitPendingEncrypted() }
        }

        override fun onPlaintextInvalidated() {
            encryptedClients().forEach { it.dropPlaintext() }
            bump()
        }
    }

    // endregion

    // region E2eeIrcHost

    override fun e2eeNetworks(): JsonArray = buildJsonArray {
        val infos = _networkInfos.value
        for (c in _clients.value) {
            val networkId = c.settings.networkId
            // A direct-ircd session has no site network id: encryption is unavailable there.
            if (networkId.isEmpty() || !c.settings.viaBouncer) continue
            add(
                buildJsonObject {
                    put("networkId", networkId)
                    // The upstream network's host (the bouncer's address isn't it); "" until /api/networks answers.
                    put("host", infos.firstOrNull { it.id == networkId }?.host.orEmpty())
                    put("nick", c.nick)
                    c.hostmask?.let { put("hostmask", it) }
                    if (c.isupport.isNotEmpty()) {
                        put("isupport", JsonObject(c.isupport.mapValues { JsonPrimitive(it.value) }))
                    }
                    put(
                        "members",
                        JsonObject(
                            c.buffers.filter { it.kind != IrcBufferKind.SERVER && it.members.isNotEmpty() }
                                .associate { b -> b.name.lowercase() to JsonArray(b.members.map { memberJson(it) }) },
                        ),
                    )
                },
            )
        }
    }

    override fun sendWho(networkId: String, channel: String): Boolean {
        val c = _clients.value.firstOrNull { it.settings.networkId == networkId } ?: return false
        return c.requestWhoAccounts(channel)
    }

    /** An `IrcMember` as the shared runtime's `Member` (account from the IRC account tag, never the nick). */
    private fun memberJson(m: IrcMember): JsonObject = buildJsonObject {
        val top = m.prefixes.maxByOrNull { PREFIX_RANK.indexOf(it).let { i -> if (i < 0) -1 else PREFIX_RANK.length - i } }
        put("nick", m.nick)
        put("prefix", top?.toString().orEmpty())
        put("category", categoryOf(top))
        put("ident", m.user)
        put("host", m.host)
        put("realname", m.realname)
        put("isAway", m.away)
        put("awayMessage", "")
        put("lastSpoke", 0)
        put("lastHighlighted", 0)
        put("account", m.account)
        put("isBot", false)
    }

    // endregion

    companion object {
        private const val RETIRE_DELAY_MS = 2_500L
        private val REPEAT_WINDOW: Duration = Duration.ofSeconds(60)

        /** When the last rejected-credentials recovery ran (process-wide, like the iOS static). */
        private var lastCredentialRecovery: Instant? = null

        /** Status prefix chars, highest first (InspIRCd `PREFIX=(Yyqaohv)!*~&@%+`). */
        private const val PREFIX_RANK = "!*~&@%+"

        /** The web's `ModeCategory` of a member's highest prefix. */
        private fun categoryOf(prefix: Char?): String = when (prefix) {
            '!', '*' -> "OPER"
            '~' -> "OWNER"
            '&' -> "ADMIN"
            '@' -> "OP"
            '%' -> "HALFOP"
            '+' -> "VOICED"
            else -> "MEMBER"
        }

        /** `networkId`, or the network name on the direct-ircd fallback (no id). */
        fun networkKey(c: IrcClient): String = c.settings.networkId.ifEmpty { c.settings.networkName }
    }
}
