package com.kevinpostal.fiberirc.data.irc

import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synchronous in-memory transport: records sent lines, `feed` injects a received line. */
private class FakeTransport : IrcTransport {
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

    fun feed(line: String) {
        channel.trySend(ConnectionEvent.Line(line))
    }

    fun clearSent() = sentLines.clear()
}

/** Scriptable reachability: `set` flips the path and reports it like the real monitor would. */
private class FakePathMonitor : PathMonitoring {
    override var isSatisfied = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
    fun set(satisfied: Boolean) {
        isSatisfied = satisfied
        onChange?.invoke(satisfied)
    }
}

private class FakeForegroundMonitor : AppForegroundMonitoring {
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

/** Ports the protocol cases of `FiberIRCTests/IRCClientTests.swift`. */
@OptIn(ExperimentalCoroutinesApi::class)
class IrcClientTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var settings = ConnectionSettings(nick = "n", realName = "", autoJoinChannels = "#ircfiber")
    private var t = FakeTransport()
    private val pm = FakePathMonitor()
    private val fg = FakeForegroundMonitor()
    private var c = IrcClient(settings, t, pm, fg, dispatcher)

    /** A fresh client and transport (the iOS tests rebuild `c` after changing `settings`). */
    private fun rebuild(clock: () -> java.time.Instant = java.time.Instant::now, change: (ConnectionSettings) -> ConnectionSettings = { it }) {
        c.dispose()
        settings = change(settings)
        t = FakeTransport()
        c = IrcClient(settings, t, pm, fg, dispatcher, clock)
    }

    @After
    fun tearDown() {
        c.dispose()
    }

    private fun connectAndOpen() {
        c.connect()
        assertTrue(t.connected)
        t.simulateConnected()
    }

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    private fun serverBufferHas(fragment: String) = c.serverBuffer.lines.any { it.text.contains(fragment) }

    @Test
    fun saslRegistration() {
        rebuild { it.copy(saslAccount = "a", saslPassword = "p") }
        connectAndOpen()
        assertEquals(listOf("CAP LS 302", "NICK n", "USER n 0 * n"), t.sentLines)
        assertEquals(IrcState.REGISTERING, c.state)
        t.clearSent()

        t.feed(":s CAP * LS :sasl server-time foo")
        assertEquals(listOf("CAP REQ :sasl server-time"), t.sentLines)
        t.clearSent()

        t.feed(":s CAP n ACK :sasl server-time")
        assertEquals(listOf("AUTHENTICATE PLAIN"), t.sentLines)
        t.clearSent()

        t.feed("AUTHENTICATE +")
        val expected = Base64.getEncoder().encodeToString(byteArrayOf('a'.code.toByte(), 0, 'a'.code.toByte(), 0, 'p'.code.toByte()))
        assertEquals(listOf("AUTHENTICATE $expected"), t.sentLines)
        t.clearSent()

        t.feed(":s 903 n :ok")
        assertEquals(listOf("CAP END"), t.sentLines)
        t.clearSent()

        t.feed(":s 001 n :hi")
        assertEquals(IrcState.CONNECTED, c.state)
        assertEquals("n", c.nick)
        assertEquals(listOf("JOIN #ircfiber"), t.sentLines)
        assertNotNull(c.buffer("#IRCFIBER"))
    }

    @Test
    fun saslPayloadSplitsIn400CharChunksWithTrailingPlusOnExactMultiple() {
        // 300 bytes of payload → exactly 400 base64 characters.
        val account = "x".repeat(99)
        val password = "y".repeat(300 - 2 * 99 - 2)
        rebuild { it.copy(saslAccount = account, saslPassword = password) }
        connectAndOpen()
        t.feed(":s CAP * LS :sasl")
        t.feed(":s CAP n ACK :sasl")
        t.clearSent()
        t.feed("AUTHENTICATE +")
        assertEquals(2, t.sentLines.size)
        assertEquals(400, t.sentLines[0].removePrefix("AUTHENTICATE ").length)
        assertEquals("AUTHENTICATE +", t.sentLines[1])
    }

    @Test
    fun saslFailureStillEndsCapNegotiation() {
        rebuild { it.copy(saslAccount = "a", saslPassword = "p") }
        connectAndOpen()
        t.clearSent()
        t.feed(":s 904 n :SASL authentication failed")
        assertEquals(listOf("CAP END"), t.sentLines)
        assertTrue(serverBufferHas("SASL failed: SASL authentication failed"))
    }

    @Test
    fun noSaslGoesStraightToCapEnd() {
        connectAndOpen()
        t.clearSent()
        t.feed(":s CAP * LS :sasl server-time echo-message")
        assertEquals(listOf("CAP REQ :server-time echo-message"), t.sentLines)
        t.clearSent()
        t.feed(":s CAP n ACK :server-time echo-message")
        assertEquals(listOf("CAP END"), t.sentLines)
    }

    @Test
    fun capReqKeepsWantedOrderAndNakEndsNegotiation() {
        connectAndOpen()
        t.clearSent()
        t.feed(":s CAP * LS :standard-replies batch draft/chathistory labeled-response message-tags server-time")
        assertEquals(listOf("CAP REQ :server-time message-tags batch standard-replies"), t.sentLines)
        t.clearSent()
        t.feed(":s CAP n NAK :server-time message-tags batch standard-replies")
        assertEquals(listOf("CAP END"), t.sentLines)
    }

    @Test
    fun multilineCapLs() {
        connectAndOpen()
        t.clearSent()
        t.feed(":s CAP * LS * :sasl=PLAIN server-time")
        assertEquals(emptyList<String>(), t.sentLines)
        t.feed(":s CAP * LS :multi-prefix")
        assertEquals(listOf("CAP REQ :server-time multi-prefix"), t.sentLines)
    }

    @Test
    fun nickInUseDuringRegistrationRetriesThreeTimesThenGivesUp() {
        connectAndOpen()
        t.clearSent()
        t.feed(":s 433 * n :Nickname is already in use.")
        assertEquals(listOf("NICK n_"), t.sentLines)
        t.feed(":s 433 * n_ :Nickname is already in use.")
        t.feed(":s 432 * n__ :Erroneous nickname")
        assertEquals(listOf("NICK n_", "NICK n__", "NICK n___"), t.sentLines)
        t.feed(":s 433 * n___ :Nickname is already in use.")
        assertEquals(IrcState.DISCONNECTED, c.state)
        assertFalse(serverBufferHas("Reconnecting in"))
    }

    @Test
    fun pingPong() {
        connectAndOpen()
        t.clearSent()
        t.feed("PING :x")
        assertEquals(listOf("PONG x"), t.sentLines)
    }

    @Test
    fun idleLinkIsPingedAt60sAndDroppedAt150s() {
        var now = java.time.Instant.parse("2026-09-27T10:00:00Z")
        rebuild(clock = { now })
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.clearSent()
        now = now.plusSeconds(60)
        advance(10_000)
        assertEquals(listOf("PING fiberirc"), t.sentLines)
        now = now.plusSeconds(90)
        advance(10_000)
        assertTrue(serverBufferHas("Connection timed out"))
        assertEquals(IrcState.DISCONNECTED, c.state)
    }

    @Test
    fun bouncerAttachSendsPassAndNeverJoins() {
        rebuild { it.copy(serverPassword = "n/irc-fiber@android-abc:deadbeef", viaBouncer = true) }
        connectAndOpen()
        assertEquals(listOf("CAP LS 302", "PASS n/irc-fiber@android-abc:deadbeef", "NICK n", "USER n 0 * n"), t.sentLines)
        t.clearSent()
        t.feed(":s 001 n :hi")
        assertEquals(listOf("PING fiberirc-sync"), t.sentLines) // engine session owns membership: no JOIN
        t.feed(":n!u@h NICK Zodiac")
        assertEquals("Zodiac", c.nick)
        t.feed("@time=2026-09-27T10:00:00.000Z :Zodiac!u@h JOIN #support")
        val b = c.buffer("#support")!!
        assertTrue(b.joined)
        // Reattach after a drop must not JOIN either.
        t.disconnect(); t.clearSent()
        c.connect(); t.simulateConnected(); t.feed(":s 001 Zodiac :hi")
        assertFalse(t.sentLines.any { it.startsWith("JOIN") })
    }

    private fun useBouncer() = rebuild {
        it.copy(nick = "Zodiac", serverPassword = "n/supernets@android-abc:deadbeef", viaBouncer = true)
    }

    /** Mirrors bnc `dumpChannels`: JOIN → 332 → 353 → 366 on every attach. */
    private fun feedAttachBurst(topic: String = "SUPERBOWL") {
        t.feed(":Zodiac!u@h JOIN #superbowl")
        t.feed(":s 332 Zodiac #superbowl :$topic")
        t.feed(":s 353 Zodiac = #superbowl :Zodiac @lulzy")
        t.feed(":s 366 Zodiac #superbowl :End of /NAMES list.")
    }

    @Test
    fun bouncerReattachResumesSilently() {
        useBouncer()
        connectAndOpen()
        t.feed(":s 001 Zodiac :hi")
        feedAttachBurst()
        val b = c.buffer("#superbowl")!!
        fun count(prefix: String) = b.lines.count { it.text.startsWith(prefix) }
        assertEquals(0, count("You joined"))
        assertEquals(1, count("Topic: SUPERBOWL"))
        assertTrue(b.joined)
        assertEquals(2, b.members.size)
        assertEquals("lulzy", b.members.first().nick)

        t.disconnect()
        c.connect(); t.simulateConnected(); t.feed(":s 001 Zodiac :hi")
        feedAttachBurst()
        assertEquals(0, count("You joined"))
        assertEquals(1, count("Topic:"))

        t.disconnect()
        c.connect(); t.simulateConnected(); t.feed(":s 001 Zodiac :hi")
        feedAttachBurst(topic = "NEW")
        assertEquals(1, count("Topic: NEW"))
        assertEquals(0, count("You joined"))
    }

    @Test
    fun bouncerAnnouncesUserJoinAndRejoinAfterPart() {
        useBouncer()
        connectAndOpen()
        t.feed(":s 001 Zodiac :hi")
        c.joinChannel("#new")
        t.feed(":Zodiac!u@h JOIN #new")
        val b = c.buffer("#new")!!
        assertEquals(1, b.lines.count { it.text == "You joined #new" })
        t.feed(":Zodiac!u@h PART #new :bye")
        t.feed(":Zodiac!u@h JOIN #new")
        assertEquals(2, b.lines.count { it.text == "You joined #new" })
    }

    @Test
    fun rejectedBouncerPasswordStopsReconnectAndNotifies() {
        var rejections = 0
        rebuild { it.copy(serverPassword = "gone/irc-fiber@android-abc:deadbeef", viaBouncer = true) }
        c.addObserver(object : IrcClientListener {
            override fun onCredentialsRejected(client: IrcClient) {
                rejections++
            }
        })
        connectAndOpen()
        t.feed(":bnc.ircfiber.com 464 n :Invalid username or bouncer password")
        t.feed("ERROR :Closing link: Invalid password")
        t.disconnect()
        assertEquals(1, rejections)
        assertEquals(IrcState.DISCONNECTED, c.state)
        assertFalse(serverBufferHas("Reconnecting"))
        fg.set(false); fg.set(true)
        assertEquals(IrcState.DISCONNECTED, c.state)
        assertFalse(t.connected)
    }

    @Test
    fun markReadFromServerRecomputesUnreadAndOnlyAdvances() {
        rebuild { it.copy(serverPassword = "n/irc-fiber@android-abc:deadbeef", viaBouncer = true) }
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed("@time=2026-09-27T10:00:00.000Z :a!u@h PRIVMSG #c :one")
        t.feed("@time=2026-09-27T10:00:01.000Z :a!u@h PRIVMSG #c :two")
        t.feed("@time=2026-09-27T10:00:02.000Z :a!u@h PRIVMSG #c :three")
        val b = c.buffer("#c")!!
        t.feed(":bnc MARKREAD #c timestamp=2026-09-27T10:00:01.000Z")
        assertEquals(1, b.unreadCount)
        assertEquals(IrcMessage.parseTime("2026-09-27T10:00:01.000Z"), b.readMarker)
        t.feed(":bnc MARKREAD #c timestamp=2026-09-27T09:00:00.000Z")
        assertEquals(1, b.unreadCount)
        t.feed(":bnc MARKREAD #nope timestamp=2026-09-27T10:00:01.000Z")
        assertNull(c.buffer("#nope"))
    }

    private fun bouncerWithReadMarker() {
        rebuild { it.copy(serverPassword = "n/irc-fiber@android-abc:deadbeef", viaBouncer = true) }
        connectAndOpen()
        t.feed(":s CAP * LS :server-time draft/read-marker")
        assertEquals("CAP REQ :server-time draft/read-marker", t.sentLines.last())
        t.feed(":s CAP n ACK :server-time draft/read-marker")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed("@time=2026-09-27T10:00:02.500Z :a!u@h PRIVMSG #c :three")
    }

    @Test
    fun markReadSendsMarkreadAfterTwoSecondDebounce() {
        bouncerWithReadMarker()
        val b = c.buffer("#c")!!
        b.unreadCount = 1
        t.clearSent()
        c.markRead(b)
        assertEquals(0, b.unreadCount)
        assertEquals(emptyList<String>(), t.sentLines) // coalesced, not sent synchronously
        advance(1_999)
        assertEquals(emptyList<String>(), t.sentLines)
        advance(1)
        assertEquals(listOf("MARKREAD #c timestamp=2026-09-27T10:00:02.500Z"), t.sentLines)
    }

    @Test
    fun markReadSkipsServerBuffer() {
        bouncerWithReadMarker()
        c.buffer("#c")!!.unreadCount = 1
        t.clearSent()
        for (buf in c.buffers) c.markRead(buf)
        c.flushMarkRead()
        assertEquals(listOf("MARKREAD #c timestamp=2026-09-27T10:00:02.500Z"), t.sentLines)
    }

    @Test
    fun markReadStaysLocalWithoutCap() {
        rebuild { it.copy(serverPassword = "n/irc-fiber@android-abc:deadbeef", viaBouncer = true) }
        connectAndOpen()
        t.feed(":s CAP * LS :server-time")
        t.feed(":s CAP n ACK :server-time")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed("@time=2026-09-27T10:00:02.000Z :a!u@h PRIVMSG #c :x")
        val b = c.buffer("#c")!!
        b.unreadCount = 1
        t.clearSent()
        c.markRead(b)
        c.flushMarkRead()
        assertEquals(0, b.unreadCount)
        assertEquals(emptyList<String>(), t.sentLines)
    }

    @Test
    fun clientTagFeatureGating() {
        connectAndOpen()
        t.feed(":s CAP * LS :message-tags draft/message-redaction")
        t.feed(":s CAP n ACK :message-tags draft/message-redaction")
        // Unknown upstream: reply/typing allowed, edit not.
        assertEquals(ClientTagFeatures(reply = true, typing = true, edit = false, redact = true), c.clientTagFeatures)
        c.upstreamCaps = setOf("message-tags", "draft/edit-message")
        assertEquals(ClientTagFeatures(reply = true, typing = true, edit = true, redact = true), c.clientTagFeatures)
        c.upstreamCaps = emptySet()
        assertEquals(ClientTagFeatures(reply = false, typing = false, edit = false, redact = true), c.clientTagFeatures)
        c.upstreamCaps = null
        t.feed(":s 005 n CLIENTTAGDENY=typing :are supported")
        assertEquals(ClientTagFeatures(reply = true, typing = false, edit = false, redact = true), c.clientTagFeatures)
        t.feed(":s 005 n CLIENTTAGDENY=*,-reply :are supported")
        assertEquals(ClientTagFeatures(reply = true, typing = false, edit = false, redact = true), c.clientTagFeatures)
    }

    @Test
    fun clientTagsNeedMessageTagsAcked() {
        connectAndOpen()
        t.feed(":s CAP * LS :server-time")
        t.feed(":s CAP n ACK :server-time")
        c.upstreamCaps = setOf("message-tags", "draft/edit-message")
        assertEquals(ClientTagFeatures(reply = false, typing = false, edit = false, redact = false), c.clientTagFeatures)
    }

    @Test
    fun userQuitSendsQuitAndNoReconnect() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.clearSent()
        c.sendInput("/quit bye", c.serverBuffer)
        assertEquals(listOf("QUIT bye"), t.sentLines)
        assertEquals(IrcState.CONNECTED, c.state)
        t.feed("ERROR :Closing link: (Quit: bye)")
        t.disconnect()
        assertEquals(IrcState.DISCONNECTED, c.state)
        assertFalse(serverBufferHas("Reconnecting"))
    }

    private fun connectRegisteredAndForeground() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.clearSent()
        c.resumeProbeTimeout = Duration.ofMillis(100)
        fg.set(true)
    }

    @Test
    fun foregroundProbeKeepsLiveLink() {
        connectRegisteredAndForeground()
        assertEquals(listOf("PING fiberirc-resume"), t.sentLines)
        t.feed(":s PONG s :fiberirc-resume")
        advance(300)
        assertEquals(IrcState.CONNECTED, c.state)
        assertTrue(t.connected)
        assertFalse(serverBufferHas("No reply from the server"))
    }

    @Test
    fun foregroundProbeTimeoutReconnectsImmediately() {
        connectRegisteredAndForeground()
        advance(100)
        assertTrue(serverBufferHas("No reply from the server after returning to the app — reconnecting"))
        assertEquals(IrcState.CONNECTING, c.state)
        assertTrue(t.connected)
        assertFalse(serverBufferHas("Reconnecting in"))
    }

    @Test
    fun dropRightAfterForegroundReconnectsWithoutBackoff() {
        connectRegisteredAndForeground()
        t.disconnect()
        assertEquals(IrcState.CONNECTING, c.state)
        assertTrue(t.connected)
        assertFalse(serverBufferHas("Reconnecting in"))
    }

    @Test
    fun backgroundCancelsPendingProbe() {
        connectRegisteredAndForeground()
        fg.set(false)
        advance(300)
        assertEquals(IrcState.CONNECTED, c.state)
        assertFalse(serverBufferHas("No reply from the server"))
    }

    @Test
    fun dropWithoutNetworkWaitsForPath() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        pm.isSatisfied = false
        t.disconnect()
        assertEquals(IrcState.DISCONNECTED, c.state)
        assertFalse(t.connected)
        assertTrue(serverBufferHas("Waiting for network…"))
        assertFalse(serverBufferHas("Reconnecting in"))
    }

    @Test
    fun pathRestoredReconnectsImmediately() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        pm.isSatisfied = false
        t.disconnect()
        pm.set(true)
        assertEquals(IrcState.CONNECTING, c.state)
        assertTrue(t.connected)
        assertTrue(serverBufferHas("Network available — reconnecting…"))
    }

    @Test
    fun pathLossCancelsPendingBackoffAndBackoffReconnects() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.disconnect()
        assertTrue(serverBufferHas("Reconnecting in"))
        pm.set(false)
        assertTrue(serverBufferHas("Waiting for network…"))
        advance(10_000)
        assertFalse(t.connected) // the cancelled backoff never fires
        pm.set(true)
        assertEquals(IrcState.CONNECTING, c.state)
        assertTrue(t.connected)
    }

    @Test
    fun backoffReconnectsAfterJitteredDelay() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.disconnect()
        assertFalse(t.connected)
        advance(1_599) // 2 s × 0.8 minimum jitter
        assertFalse(t.connected)
        advance(1_000) // 2 s × 1.2 maximum jitter
        assertTrue(t.connected)
        assertEquals(IrcState.CONNECTING, c.state)
    }

    /** Bouncer client with `batch` acked, registered, in `#c`, attach replay synced. */
    private fun connectBouncerWithBatch() {
        rebuild { it.copy(viaBouncer = true) }
        connectAndOpen()
        t.feed(":s CAP * LS :server-time message-tags echo-message batch")
        t.feed(":s CAP n ACK :server-time message-tags echo-message batch")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed(":s PONG s :fiberirc-sync")
    }

    private class HistorySpy : IrcClientListener {
        var appends = 0
        var synced = 0
        val history = ArrayList<Pair<String, Int>>()
        override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) {
            appends++
        }

        override fun onAttachReplayFinished(client: IrcClient) {
            synced++
        }

        override fun onHistoryLoaded(client: IrcClient, buffer: IrcBuffer, added: Int) {
            history.add(buffer.name to added)
        }
    }

    @Test
    fun scrollbackBeforeOldestInsertsOlderRowsSilently() {
        connectBouncerWithBatch()
        t.feed("@time=2026-09-27T10:00:00.000Z;msgid=m3 :a!u@h PRIVMSG #c :newest")
        t.clearSent()
        val b = c.buffer("#c")!!
        val spy = HistorySpy()
        c.addObserver(spy)
        assertTrue(c.requestOlderHistory(b))
        assertEquals(listOf("CHATHISTORY BEFORE #c timestamp=2026-09-27T10:00:00.000Z 100"), t.sentLines)
        assertFalse(c.requestOlderHistory(b)) // one page in flight at a time
        t.clearSent()
        t.feed(":s BATCH +h1 chathistory #c")
        t.feed("@batch=h1;time=2026-09-26T09:00:00.000Z;msgid=m1 :b!u@h PRIVMSG #c :older")
        t.feed("@batch=h1;time=2026-09-26T09:00:01.000Z;msgid=m2 :b!u@h PRIVMSG #c :\u0001ACTION waves\u0001")
        t.feed("@batch=h1;time=2026-09-26T09:00:02.000Z :b!u@h PRIVMSG #c :\u0001VERSION\u0001")
        t.feed(":s BATCH -h1")
        val chat = b.lines.filter { it.kind == IrcLineKind.MESSAGE || it.kind == IrcLineKind.ACTION }
        assertEquals(listOf("older", "waves", "newest"), chat.map { it.text })
        assertEquals(listOf(IrcLineKind.MESSAGE, IrcLineKind.ACTION, IrcLineKind.MESSAGE), chat.map { it.kind })
        assertEquals(0, spy.appends)
        assertEquals(listOf("#c" to 2), spy.history)
        assertEquals(emptyList<String>(), t.sentLines) // replayed CTCP is never answered
        assertFalse(b.historyLoading)
        assertFalse(b.historyExhausted)
        assertFalse(serverBufferHas("BATCH"))
    }

    @Test
    fun emptyPageMarksExhausted() {
        connectBouncerWithBatch()
        val b = c.buffer("#c")!!
        val spy = HistorySpy()
        c.addObserver(spy)
        t.clearSent()
        assertTrue(c.requestOlderHistory(b))
        assertEquals(listOf("CHATHISTORY LATEST #c * 100"), t.sentLines)
        t.feed(":s BATCH +h2 chathistory #c")
        t.feed(":s BATCH -h2")
        assertEquals(listOf("#c" to 0), spy.history)
        assertTrue(b.historyExhausted)
        t.clearSent()
        assertFalse(c.requestOlderHistory(b))
        assertEquals(emptyList<String>(), t.sentLines)
    }

    @Test
    fun attachSyncSeparatesReplayFromPages() {
        rebuild { it.copy(viaBouncer = true) }
        connectAndOpen()
        t.feed(":s CAP * LS :server-time message-tags batch")
        t.feed(":s CAP n ACK :server-time message-tags batch")
        t.clearSent()
        t.feed(":s 001 n :hi")
        assertEquals(listOf("PING fiberirc-sync"), t.sentLines)
        t.feed(":n!u@h JOIN #c")
        val b = c.buffer("#c")!!
        val spy = HistorySpy()
        c.addObserver(spy)
        assertFalse(c.requestOlderHistory(b))
        t.feed(":s BATCH +r1 chathistory #c")
        t.feed("@batch=r1;time=2026-09-26T09:00:00.000Z;msgid=m1 :b!u@h PRIVMSG #c :older")
        t.feed("@batch=r1;time=2026-09-27T10:00:00.000Z;msgid=m3 :a!u@h PRIVMSG #c :newest")
        t.feed(":s BATCH -r1")
        assertEquals(2, spy.appends)
        assertEquals(0, spy.synced)
        t.clearSent()
        t.feed(":s PONG s :fiberirc-sync")
        assertEquals(1, spy.synced)
        assertTrue(c.requestOlderHistory(b))
        assertEquals(listOf("CHATHISTORY BEFORE #c timestamp=2026-09-26T09:00:00.000Z 100"), t.sentLines)
        t.feed(":s BATCH +h4 chathistory #c")
        t.feed("@batch=h4;time=2026-09-25T08:00:00.000Z;msgid=m0 :b!u@h PRIVMSG #c :oldest")
        t.feed("@batch=h4;time=2026-09-26T09:00:00.000Z;msgid=m1 :b!u@h PRIVMSG #c :older")
        t.feed(":s BATCH -h4")
        assertEquals(2, spy.appends)
        assertEquals(listOf("#c" to 1), spy.history)
        assertEquals(listOf("oldest", "older", "newest"), b.lines.filter { it.kind == IrcLineKind.MESSAGE }.map { it.text })
        assertFalse(b.historyExhausted)
    }

    @Test
    fun liveDuplicateByMsgidIsDropped() {
        connectBouncerWithBatch()
        t.feed("@time=2026-09-27T10:00:00.000Z;msgid=m3 :a!u@h PRIVMSG #c :newest")
        val b = c.buffer("#c")!!
        val spy = HistorySpy()
        c.addObserver(spy)
        t.feed("@time=2026-09-27T10:00:00.000Z;msgid=m3 :a!u@h PRIVMSG #c :newest")
        assertEquals(0, spy.appends)
        assertEquals(1, b.lines.count { it.text == "newest" })
    }

    @Test
    fun attachSyncFallsBackOnTimeout() {
        rebuild { it.copy(viaBouncer = true) }
        c.attachSyncTimeout = Duration.ofMillis(50)
        connectAndOpen()
        t.feed(":s CAP * LS :batch")
        t.feed(":s CAP n ACK :batch")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        val b = c.buffer("#c")!!
        val spy = HistorySpy()
        c.addObserver(spy)
        assertFalse(c.requestOlderHistory(b))
        advance(50)
        assertEquals(1, spy.synced)
        assertTrue(c.requestOlderHistory(b))
    }

    @Test
    fun chathistoryFail() {
        connectBouncerWithBatch()
        val b = c.buffer("#c")!!
        assertTrue(c.requestOlderHistory(b))
        t.feed("FAIL CHATHISTORY MESSAGE_ERROR BEFORE #c :History lookup failed")
        assertFalse(b.historyLoading)
        assertFalse(b.historyExhausted)
        t.clearSent()
        assertTrue(c.requestOlderHistory(b))
        assertEquals(1, t.sentLines.size)
        t.feed("FAIL CHATHISTORY INVALID_TARGET BEFORE #c :Unknown target")
        assertFalse(b.historyLoading)
        assertTrue(b.historyExhausted)
    }

    @Test
    fun disconnectClearsInFlightRequest() {
        connectBouncerWithBatch()
        val b = c.buffer("#c")!!
        val spy = HistorySpy()
        c.addObserver(spy)
        assertTrue(c.requestOlderHistory(b))
        t.disconnect()
        assertFalse(b.historyLoading)
        assertEquals(listOf("#c" to 0), spy.history)
    }

    @Test
    fun noPagingWithoutBouncer() {
        connectAndOpen()
        t.feed(":s CAP * LS :server-time batch")
        t.feed(":s CAP n ACK :server-time batch")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        assertEquals(IrcState.CONNECTED, c.state)
        assertFalse(c.requestOlderHistory(c.buffer("#c")!!))
    }

    @Test
    fun banMaskAndKickAndBanCommands() {
        assertEquals("bob!*@*", IrcClient.banMask("bob", ""))
        assertEquals("*!*@host.example", IrcClient.banMask("bob", "host.example"))
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed(":bob!b@host.example JOIN #c")
        val b = c.buffer("#c")!!
        t.clearSent()
        c.sendInput("/ban bob", b)
        c.sendInput("/ban stranger", b)
        c.sendInput("/kick bob go away", b)
        c.sendInput("/kick bob", b)
        c.sendInput("/kick", b)
        c.sendInput("/ban", b)
        assertEquals(
            listOf("MODE #c +b *!*@host.example", "MODE #c +b stranger!*@*", "KICK #c bob :go away", "KICK #c bob"),
            t.sentLines,
        )
        assertTrue(b.lines.any { it.kind == IrcLineKind.ERROR && it.text == "Usage: /kick nick [reason]" })
        assertTrue(b.lines.any { it.kind == IrcLineKind.ERROR && it.text == "Usage: /ban nick" })
        c.sendInput("/kick bob", c.serverBuffer)
        assertTrue(c.serverBuffer.lines.any { it.kind == IrcLineKind.ERROR && it.text == "Not a channel" })
    }

    @Test
    fun kickWhileDisconnectedAppendsNotConnected() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        val b = c.buffer("#c")!!
        c.disconnect()
        t.disconnect()
        c.kick("bob", b, "x")
        assertTrue(b.lines.any { it.kind == IrcLineKind.ERROR && it.text == "Not connected" })
    }

    @Test
    fun longMessageSplitsOnSpaceBoundaryUnder400Bytes() {
        connectAndOpen()
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.clearSent()
        val word = "abcdefghi " // 10 bytes
        c.sendInput(word.repeat(50).trim(), c.buffer("#c")!!)
        assertEquals(2, t.sentLines.size)
        val first = t.sentLines[0].removePrefix("PRIVMSG #c :")
        assertTrue(first.toByteArray().size <= 400)
        assertFalse(first.endsWith(" "))
        assertEquals(word.repeat(50).trim(), first + " " + t.sentLines[1].removePrefix("PRIVMSG #c :"))
    }

    @Test
    fun channelModeUpdatesMemberPrefixesInRankOrder() {
        connectAndOpen()
        t.feed(":s 005 n PREFIX=(qaohv)~&@%+ CHANMODES=beI,k,l,imnpst :are supported")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed(":s 353 n = #c :n alice")
        t.feed(":s 366 n #c :End")
        t.clearSent()
        t.feed(":op!u@h MODE #c +vo alice alice")
        val b = c.buffer("#c")!!
        assertEquals("@+", b.member("alice")!!.prefixes)
        assertEquals(emptyList<String>(), t.sentLines) // placed without a NAMES refresh
        t.feed(":op!u@h MODE #c +kl secret 10")
        assertEquals("kl", b.channelModes)
        assertEquals("secret", b.channelModeArgs['k'])
        t.feed(":op!u@h MODE #c +X")
        assertEquals(listOf("NAMES #c"), t.sentLines)
    }
}
