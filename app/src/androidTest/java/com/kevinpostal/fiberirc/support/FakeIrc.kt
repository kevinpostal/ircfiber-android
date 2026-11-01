package com.kevinpostal.fiberirc.support

import com.kevinpostal.fiberirc.data.irc.AppForegroundMonitoring
import com.kevinpostal.fiberirc.data.irc.ConnectionEvent
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.irc.PathMonitoring
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Synchronous in-memory [com.kevinpostal.fiberirc.data.irc.IrcTransport]: records sent lines, [feed] injects a
 * received line, as the main thread would see it over a real socket. Ported from the unit tests'
 * `IrcClientTest.kt` `FakeTransport` so instrumented E2E tests drive the real [IrcClient] protocol state machine
 * without a real socket.
 */
class FakeTransport : com.kevinpostal.fiberirc.data.irc.IrcTransport {
    private val channel = Channel<ConnectionEvent>(Channel.UNLIMITED)
    override val events: Flow<ConnectionEvent> = channel.receiveAsFlow()
    val sentLines = ArrayList<String>()
    var connected = false
        private set

    override fun connect(host: String, port: Int, tls: Boolean) {
        connected = true
    }

    fun simulateConnected() {
        channel.trySend(ConnectionEvent.Connected)
    }

    override fun send(line: String) {
        sentLines.add(line)
    }

    override fun disconnect() {
        connected = false
        channel.trySend(ConnectionEvent.Disconnected(null))
    }

    /** Injects one line as if the server had sent it. */
    fun feed(line: String) {
        channel.trySend(ConnectionEvent.Line(line))
    }

    fun clearSent() = sentLines.clear()
}

/** Always-reachable network path, as most E2E scenarios need. */
class FakePathMonitor : PathMonitoring {
    override var isSatisfied = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
    fun set(satisfied: Boolean) {
        isSatisfied = satisfied
        onChange?.invoke(satisfied)
    }
}

/** Always-foreground app state, as most E2E scenarios need. */
class FakeForegroundMonitor : AppForegroundMonitoring {
    override var isForeground = true
    override var isActive = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
    fun set(foreground: Boolean) {
        isForeground = foreground
        isActive = foreground
        onChange?.invoke(foreground)
    }
}

/**
 * One connected [IrcClient] over a [FakeTransport]: `connect()` called, [FakeTransport.simulateConnected] already
 * fired, so the client has sent its registration lines (CAP/NICK/USER) and is ready for the test to [FakeTransport.feed]
 * a server reply (001, JOIN, PRIVMSG, …).
 */
fun fakeIrcClient(settings: ConnectionSettings, transport: FakeTransport = FakeTransport()): IrcClient {
    val client = IrcClient(settings, transport, FakePathMonitor(), FakeForegroundMonitor())
    client.connect()
    transport.simulateConnected()
    return client
}

/**
 * An [IrcSessionManager] whose clients are [FakeTransport]-backed [IrcClient]s (never a real socket), keyed by
 * [ConnectionSettings.networkId] so a test can reach each network's [FakeTransport] via [transports].
 */
class FakeSession(
    settings: List<ConnectionSettings>,
    api: SiteApi,
    clientId: () -> String = { "test-device" },
    showLogin: (notice: String) -> Unit = {},
) {
    val transports = LinkedHashMap<ConnectionSettings, FakeTransport>()
    val manager: IrcSessionManager = IrcSessionManager(
        settings,
        api,
        clientId,
        { s -> val t = FakeTransport(); transports[s] = t; fakeIrcClientFrom(s, t) },
        showLogin,
    )

    /** The lone client's transport, for a session with exactly one network. */
    val transport: FakeTransport get() = transports.values.single()
}

private fun fakeIrcClientFrom(settings: ConnectionSettings, transport: FakeTransport): IrcClient {
    val client = IrcClient(settings, transport, FakePathMonitor(), FakeForegroundMonitor())
    client.connect()
    transport.simulateConnected()
    return client
}
