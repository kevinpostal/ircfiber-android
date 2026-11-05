package com.kevinpostal.fiberirc.data.irc

import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every written line; [acceptLines] lets a write fail part-way (a link dropping mid-send). */
private class WireTransport : IrcTransport {
    private val channel = Channel<ConnectionEvent>(Channel.UNLIMITED)
    override val events: Flow<ConnectionEvent> = channel.receiveAsFlow()
    val sentLines = ArrayList<String>()
    var acceptLines = Int.MAX_VALUE

    override fun connect(host: String, port: Int, tls: Boolean) = Unit

    override fun send(line: String) {
        trySend(line)
    }

    override fun trySend(line: String): Boolean {
        if (acceptLines <= 0) return false
        acceptLines--
        sentLines.add(line)
        return true
    }

    override fun disconnect() {
        channel.trySend(ConnectionEvent.Disconnected(null))
    }

    fun simulateConnected() = channel.trySend(ConnectionEvent.Connected)
    fun feed(line: String) = channel.trySend(ConnectionEvent.Line(line))
}

private object StillPath : PathMonitoring {
    override val isSatisfied = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
}

private object Foreground : AppForegroundMonitoring {
    override val isForeground = true
    override val isActive = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
}

/**
 * Stands in for the shared runtime behind [IrcEncryption]: "encrypts" by base64 (no real MLS — the wire, framing and
 * row semantics are what's under test), writes through [IrcClient.sendWire] like `transport.sendWire`, and decrypts
 * like `decryptBatch` (chunk reassembly, final-chunk logical row, own sends recovered from their mapping).
 */
private class FakeRuntime : IrcEncryption {
    lateinit var client: IrcClient
    val decisions = HashMap<String, IrcLockDecision>()
    var defaultDecision = IrcLockDecision.OFF
    var sendFailure: IrcEncryptedSendException? = null

    /** The runtime's own lock view says off (stale/divergent): it hands the plaintext line to `transport.sendWire`. */
    var runtimeSaysOff = false
    var decryptAvailable = true
    var slice = 400
    var decryptBatches = 0

    /** Every logical plaintext the pipeline was asked to encrypt. */
    val encrypted = ArrayList<String>()

    /** Complete ciphertext → plaintext: peers' keys and the own-send mapping in one. */
    val plaintextOf = HashMap<String, String>()
    private val partial = HashMap<String, Array<String?>>()
    private val finalRow = HashMap<String, JsonObject>()
    private var nextId = 0x1a00

    override fun lockDecision(target: String) = decisions[target.lowercase()] ?: defaultDecision

    fun encrypt(text: String): List<String> {
        val b64 = Base64.getEncoder().encodeToString(text.toByteArray())
        val complete = "!MLS1:$b64"
        plaintextOf[complete] = text
        if (b64.length <= slice) return listOf(complete)
        val parts = b64.chunked(slice)
        val id = "%04x".format(nextId++)
        return parts.mapIndexed { i, s -> "!MLS1:$id:${i + 1}/${parts.size}:$s" }
    }

    override suspend fun send(target: String, command: String, text: String, tags: Map<String, String>, label: String): Boolean {
        sendFailure?.let { throw it }
        val lines = if (runtimeSaysOff) listOf(text) else encrypt(text).also { encrypted.add(text) }
        return when (client.sendWire(target, command, lines, tags, label)) {
            IrcWireResult.SENT -> !runtimeSaysOff
            IrcWireResult.NOT_WRITTEN -> throw IrcEncryptedSendException("not_connected", "Not connected — nothing was sent")
            IrcWireResult.PLAINTEXT_REFUSED -> throw IrcEncryptedSendException("plaintext_refused", "nothing was sent")
            IrcWireResult.UNCERTAIN -> throw IrcEncryptedSendException("uncertain_delivery", "dropped mid-send", uncertain = true)
        }
    }

    override suspend fun decrypt(rows: List<JsonObject>): List<JsonObject> {
        decryptBatches++
        if (!decryptAvailable) throw IllegalStateException("encryption isn't running")
        val out = ArrayList<JsonObject>()
        for (row in rows) {
            val text = (row["text"] as JsonPrimitive).content
            val chunk = E2eeWire.chunk(text)
            var logical = row
            val complete = if (chunk != null && chunk.total > 1) {
                val key = "${(row["nick"] as JsonPrimitive).content}:${chunk.id}"
                val slices = partial.getOrPut(key) { arrayOfNulls(chunk.total) }
                slices[chunk.n - 1] = chunk.slice
                if (chunk.n == chunk.total) finalRow[key] = row
                if (slices.any { it == null }) {
                    if (chunk.n == chunk.total) out.add(result(row, null, "partial", text))
                    continue
                }
                partial.remove(key)
                logical = finalRow.remove(key) ?: row
                chunk.prefix + slices.joinToString("")
            } else {
                text
            }
            val plain = plaintextOf[complete]
            out.add(if (plain != null) result(logical, plain, "verified", complete) else result(logical, null, "not-addressed", complete))
        }
        return out
    }

    private fun result(row: JsonObject, text: String?, state: String, raw: String) = JsonObject(
        row + buildMap<String, JsonElement> {
            if (text != null) put("text", JsonPrimitive(text))
            put("e2e", buildJsonObject { put("state", state); put("raw", raw) })
        },
    )
}

/** Phase-5 message paths over [IrcEncryptionConfig.Encrypted]: lock before wire, ciphertext framing, collapse. */
@OptIn(ExperimentalCoroutinesApi::class)
class IrcClientEncryptionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val t = WireTransport()
    private val runtime = FakeRuntime()
    private val c = IrcClient(
        ConnectionSettings(nick = "n", realName = "", autoJoinChannels = "", viaBouncer = true, networkId = "net"),
        t, StillPath, Foreground, dispatcher, encryption = IrcEncryptionConfig.Encrypted(runtime),
    ).also { runtime.client = it }

    @After
    fun tearDown() = c.dispose()

    private fun connect(caps: String = "server-time message-tags echo-message batch") {
        c.connect()
        t.simulateConnected()
        t.feed(":s CAP * LS :$caps")
        t.feed(":s CAP n ACK :$caps")
        t.feed(":s 001 n :hi")
        t.feed(":n!u@h JOIN #c")
        t.feed(":s PONG s :fiberirc-sync")
        t.sentLines.clear()
    }

    private fun sent(): List<IrcMessage> = t.sentLines.mapNotNull { IrcMessage.parse(it) }
    private fun chat(b: IrcBuffer) = b.lines.filter { it.kind != IrcLineKind.EVENT && it.kind != IrcLineKind.ERROR }

    private val marker = "MARKER-7f3c"

    @Test
    fun lockOnSendsOnlyCiphertext() {
        connect()
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        val b = c.buffer("#c")!!
        c.sendInput("secret $marker", b)
        assertEquals(1, t.sentLines.size)
        val m = sent().single()
        assertEquals("PRIVMSG", m.command)
        assertTrue(E2eeWire.isEncrypted(m.params[1]))
        assertFalse(t.sentLines.any { it.contains(marker) })
        assertEquals(listOf("secret $marker"), runtime.encrypted)
        val row = chat(b).single()
        assertEquals("secret $marker", row.text) // optimistic plaintext, memory only
        assertNull(row.send)
        assertEquals(m.params[1], row.e2e?.wire)
    }

    @Test
    fun lockOffKeepsPlaintextPath() {
        connect()
        c.sendInput("hello", c.buffer("#c")!!)
        assertEquals(listOf("PRIVMSG #c hello"), t.sentLines)
        assertTrue(runtime.encrypted.isEmpty())
    }

    @Test
    fun pendingDecisionHoldsThenEncrypts() {
        connect()
        runtime.decisions["#c"] = IrcLockDecision.PENDING
        val b = c.buffer("#c")!!
        c.sendInput("first $marker", b)
        assertTrue(t.sentLines.isEmpty())
        assertEquals(IrcSendState.Sending, chat(b).single().send)
        c.encryptionStateChanged() // still pending
        assertTrue(t.sentLines.isEmpty())
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        c.encryptionStateChanged()
        assertTrue(E2eeWire.isEncrypted(sent().single().params[1]))
        assertFalse(t.sentLines.any { it.contains(marker) })
        assertNull(chat(b).single().send)
    }

    @Test
    fun pendingDecisionResolvedOffSendsPlaintextOnce() {
        connect("server-time message-tags batch") // no echo-message: the plaintext path shows its own row
        runtime.decisions["#c"] = IrcLockDecision.PENDING
        val b = c.buffer("#c")!!
        c.sendInput("hello", b)
        runtime.decisions["#c"] = IrcLockDecision.OFF
        c.encryptionStateChanged()
        assertEquals(listOf("PRIVMSG #c hello"), t.sentLines)
        assertEquals(listOf("hello"), chat(b).map { it.text })
        assertNull(chat(b).single().e2e)
    }

    @Test
    fun targetBearingShortcutsUseTheTargetsLock() {
        connect()
        runtime.decisions["bob"] = IrcLockDecision.ENCRYPT
        val open = c.buffer("#c")!! // off
        c.sendInput("/msg bob hi $marker", open)
        c.sendInput("/notice bob note $marker", open)
        c.sendInput("/raw PRIVMSG bob :raw $marker", open)
        c.sendInput("/raw NOTICE bob :rawn $marker", open)
        val bob = c.buffer("bob")!!
        c.sendInput("/me waves $marker", bob)
        val m = sent()
        assertEquals(listOf("PRIVMSG", "NOTICE", "PRIVMSG", "NOTICE", "PRIVMSG"), m.map { it.command })
        assertTrue(m.all { it.params[0] == "bob" && E2eeWire.isEncrypted(it.params[1]) })
        assertFalse(t.sentLines.any { it.contains(marker) })
        // /me encrypts its CTCP ACTION body inside the ciphertext.
        assertEquals("\u0001ACTION waves $marker\u0001", runtime.encrypted.last())
        val action = bob.lines.last()
        assertEquals(IrcLineKind.ACTION, action.kind)
        assertEquals("waves $marker", action.text)
        // Plaintext shortcuts to an off target stay ordinary.
        t.sentLines.clear()
        c.sendInput("/msg carol hi", open)
        assertEquals(listOf("PRIVMSG carol hi"), t.sentLines)
        // An encrypted message has one audience.
        t.sentLines.clear()
        c.sendInput("/msg bob,carol hi", open)
        assertTrue(t.sentLines.isEmpty())
    }

    @Test
    fun longMessageChunksVerbatimWithTagsOnFirstLineOnly() {
        connect()
        c.upstreamCaps = setOf("message-tags", "draft/edit-message")
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        runtime.slice = 300
        val b = c.buffer("#c")!!
        val long = "x".repeat(1200) + " $marker"
        c.sendInput(long, b, replyTo = "p1")
        val m = sent()
        assertTrue(m.size > 1)
        assertEquals("p1", m[0].tags["+reply"])
        assertEquals("p1", m[0].tags["+draft/reply"])
        assertTrue(m.drop(1).all { it.tags.isEmpty() })
        assertTrue(t.sentLines.all { it.toByteArray().size <= 512 })
        // Exactly the runtime's lines, never re-split by the 400-byte plaintext splitter.
        assertEquals(runtime.encrypt(long).size, m.size)
        assertFalse(t.sentLines.any { it.contains(marker) })
        assertEquals(1, chat(b).size)
        assertEquals("p1", chat(b).single().replyTo)
    }

    @Test
    fun failedSendWritesNothingAndRetriesWithFreshCiphertext() {
        connect()
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        runtime.sendFailure = IrcEncryptedSendException("nopeer", "No one here can read encrypted messages yet")
        val b = c.buffer("#c")!!
        c.sendInput("hi $marker", b)
        assertTrue(t.sentLines.isEmpty())
        val failed = chat(b).single()
        assertTrue(failed.send is IrcSendState.Failed)
        runtime.sendFailure = null
        assertTrue(c.retryEncryptedSend(failed, b))
        assertTrue(E2eeWire.isEncrypted(sent().single().params[1]))
        assertNull(chat(b).single().send)
        assertEquals(1, chat(b).size)
    }

    @Test
    fun partialWriteIsUncertainAndNeverRetried() {
        connect()
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        runtime.slice = 100
        t.acceptLines = 1
        val b = c.buffer("#c")!!
        c.sendInput("y".repeat(500), b)
        assertEquals(1, t.sentLines.size)
        val row = chat(b).single()
        assertTrue(row.send is IrcSendState.Uncertain)
        assertFalse(c.retryEncryptedSend(row, b))
        assertEquals(1, t.sentLines.size)
    }

    @Test
    fun liveChunksCollapseIntoOneLogicalRow() {
        connect()
        val lines = run {
            runtime.slice = 30
            runtime.encrypt("a long encrypted message for n that spans several chunks")
        }
        assertTrue(lines.size > 2)
        val b = c.buffer("#c")!!
        lines.forEachIndexed { i, l ->
            val reply = if (i == 0) "+reply=p9;" else ""
            t.feed("@${reply}time=2026-09-27T10:00:0$i.000Z;msgid=c$i;account=alice :alice!a@h PRIVMSG #c :$l")
        }
        val rows = chat(b)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("a long encrypted message for n that spans several chunks", row.text)
        assertEquals("c${lines.lastIndex}", row.msgid)
        assertEquals("p9", row.replyTo)
        assertEquals(IrcE2eState.VERIFIED, row.e2e?.state)
        assertEquals(1, b.unreadCount)
        assertEquals(1, b.highlightCount) // names `n` once the plaintext is known
        // A replayed copy (same msgids and server times) adds nothing and decrypts nothing again.
        val batches = runtime.decryptBatches
        lines.forEachIndexed { i, l -> t.feed("@time=2026-09-27T10:00:0$i.000Z;msgid=c$i :alice!a@h PRIVMSG #c :$l") }
        assertEquals(1, chat(b).size)
        assertEquals(1, b.unreadCount)
        assertEquals(1, b.highlightCount)
        assertEquals(batches, runtime.decryptBatches)
    }

    @Test
    fun placeholdersAreDistinctAndNeverHighlight() {
        connect()
        val b = c.buffer("#c")!!
        t.feed("@msgid=x1 :alice!a@h PRIVMSG #c :!MLS1:bm90LWZvci11cw==")
        val row = chat(b).single()
        assertEquals(IrcE2eState.NOT_ADDRESSED, row.e2e?.state)
        assertEquals("", row.text)
        assertEquals(0, b.highlightCount)
        assertEquals(1, b.unreadCount)
    }

    @Test
    fun selfEchoCollapsesIntoTheOptimisticRow() {
        connect()
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        runtime.slice = 30
        val b = c.buffer("#c")!!
        c.sendInput("my own message that is chunked", b)
        val wire = sent().map { it.params[1] }
        assertTrue(wire.size > 1)
        wire.forEachIndexed { i, l -> t.feed("@msgid=e$i :n!u@h PRIVMSG #c :$l") }
        val row = chat(b).single()
        assertEquals("my own message that is chunked", row.text)
        assertEquals("e${wire.lastIndex}", row.msgid)
        assertEquals(0, b.unreadCount)
    }

    @Test
    fun ownMessageReplayedAfterRestartIsRecoveredOnce() {
        connect()
        val b = c.buffer("#c")!!
        // A previous process sent it: only the runtime's own-send mapping knows the plaintext.
        val line = runtime.encrypt("sent before restart").single()
        t.feed("@time=2026-09-27T09:00:00.000Z;msgid=o1 :n!u@h PRIVMSG #c :$line")
        t.feed("@time=2026-09-27T09:00:00.000Z;msgid=o1 :n!u@h PRIVMSG #c :$line")
        val rows = chat(b)
        assertEquals(1, rows.size)
        assertEquals("sent before restart", rows.single().text)
        assertEquals(0, b.unreadCount)
    }

    @Test
    fun historyPageDecryptsAndDedupesAgainstHeldRows() {
        connect()
        val b = c.buffer("#c")!!
        val held = runtime.encrypt("held").single()
        t.feed("@time=2026-09-27T10:00:00.000Z;msgid=m3 :a!u@h PRIVMSG #c :$held")
        runtime.slice = 20
        val older = runtime.encrypt("an older chunked message")
        assertTrue(c.requestOlderHistory(b))
        t.feed(":s BATCH +h1 chathistory #c")
        older.forEachIndexed { i, l -> t.feed("@batch=h1;time=2026-09-26T09:00:0$i.000Z;msgid=h$i :b!u@h PRIVMSG #c :$l") }
        t.feed("@batch=h1;time=2026-09-27T10:00:00.000Z;msgid=m3 :a!u@h PRIVMSG #c :$held")
        t.feed(":s BATCH -h1")
        assertEquals(listOf("an older chunked message", "held"), chat(b).map { it.text })
        assertEquals(1, b.unreadCount) // paged rows are old
        assertFalse(b.historyExhausted)
    }

    @Test
    fun incomingEditFoldsIntoTheEditedRow() {
        connect()
        val b = c.buffer("#c")!!
        t.feed("@msgid=m1 :alice!a@h PRIVMSG #c :${runtime.encrypt("first").single()}")
        t.feed("@msgid=m2;+draft/edit=m1 :alice!a@h PRIVMSG #c :${runtime.encrypt("second").single()}")
        val row = chat(b).single()
        assertEquals("second", row.text)
        assertTrue(row.edited)
        assertEquals("m1", row.msgid)
    }

    @Test
    fun unavailableDecryptStaysPendingAndResubmits() {
        connect()
        val b = c.buffer("#c")!!
        runtime.decryptAvailable = false
        t.feed("@msgid=p1 :alice!a@h PRIVMSG #c :${runtime.encrypt("later n").single()}")
        val pending = chat(b).single()
        assertEquals(IrcE2eState.PENDING, pending.e2e?.state)
        assertEquals("", pending.text)
        assertEquals(0, b.highlightCount)
        runtime.decryptAvailable = true
        c.resubmitPendingEncrypted()
        assertEquals("later n", chat(b).single().text)
        assertEquals(1, b.highlightCount)
    }

    @Test
    fun droppedPlaintextComesBackWithoutRecountingHighlights() {
        connect()
        val b = c.buffer("#c")!!
        t.feed("@msgid=q1 :alice!a@h PRIVMSG #c :${runtime.encrypt("hey n").single()}")
        assertEquals(1, b.highlightCount)
        c.dropPlaintext()
        assertEquals("", chat(b).single().text)
        assertEquals(IrcE2eState.PENDING, chat(b).single().e2e?.state)
        c.resubmitPendingEncrypted()
        assertEquals("hey n", chat(b).single().text)
        assertEquals(1, b.highlightCount)
        assertEquals(1, b.unreadCount)
    }

    @Test
    fun typingIsSuppressedUnlessPositivelyOff() {
        connect()
        val b = c.buffer("#c")!!
        for (d in listOf(IrcLockDecision.ENCRYPT, IrcLockDecision.PENDING)) {
            runtime.decisions["#c"] = d
            c.sendTyping(TypingState.ACTIVE, b)
            assertTrue(t.sentLines.isEmpty())
        }
        runtime.decisions["#c"] = IrcLockDecision.OFF
        c.sendTyping(TypingState.ACTIVE, b)
        assertEquals(listOf("@+typing=active TAGMSG #c"), t.sentLines)
    }

    @Test
    fun notConnectedSendsNothing() {
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        val b = c.queryBuffer("bob")
        runtime.decisions["bob"] = IrcLockDecision.ENCRYPT
        c.sendInput("hi", b)
        assertTrue(t.sentLines.isEmpty())
        assertNotNull(b.lines.lastOrNull { it.kind == IrcLineKind.ERROR })
    }

    @Test
    fun runtimePlaintextForANotOffTargetIsRefusedAtTheWire() {
        connect()
        val b = c.buffer("#c")!!
        // Native says encrypt (or still pending); the runtime's view says off and hands over plaintext.
        runtime.runtimeSaysOff = true
        for (d in listOf(IrcLockDecision.ENCRYPT, IrcLockDecision.PENDING)) {
            runtime.decisions["#c"] = d
            assertEquals(IrcWireResult.PLAINTEXT_REFUSED, c.sendWire("#c", "PRIVMSG", listOf("raw $marker"), emptyMap(), null))
        }
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        c.sendInput("secret $marker", b)
        assertTrue(t.sentLines.isEmpty())
        val row = chat(b).single()
        assertTrue(row.send is IrcSendState.Failed)
        assertNotNull(row.e2e) // still an encrypted row, never relabelled plaintext
        assertFalse(t.sentLines.any { it.contains(marker) })
        // Only a target positively off on this device takes the runtime's plaintext line.
        runtime.decisions["#c"] = IrcLockDecision.OFF
        assertEquals(IrcWireResult.SENT, c.sendWire("#c", "PRIVMSG", listOf("plain"), emptyMap(), null))
        assertEquals(listOf("PRIVMSG #c plain"), t.sentLines)
    }

    @Test
    fun channelHeldWhilePrefsUnknownThenLockedOnEncrypts() {
        connect()
        val b = c.buffer("#c")!!
        // Prefs/policy not known yet: a channel is held, not treated as off.
        runtime.decisions["#c"] = IrcLockDecision.PENDING
        c.sendInput("early $marker", b)
        c.sendTyping(TypingState.ACTIVE, b)
        assertTrue(t.sentLines.isEmpty())
        assertTrue(c.isHeldSend(chat(b).single()))
        // The prefs arrive with the channel locked on.
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        c.encryptionStateChanged()
        val m = sent().single()
        assertTrue(E2eeWire.isEncrypted(m.params[1]))
        assertFalse(t.sentLines.any { it.contains(marker) })
        assertFalse(c.isHeldSend(chat(b).single()))
    }

    @Test
    fun heldSendCanBeDiscardedBeforeAnythingIsSent() {
        connect()
        val b = c.buffer("#c")!!
        runtime.decisions["#c"] = IrcLockDecision.PENDING
        c.sendInput("never mind", b)
        c.discardEncryptedSend(chat(b).single(), b)
        assertTrue(chat(b).isEmpty())
        runtime.decisions["#c"] = IrcLockDecision.ENCRYPT
        c.encryptionStateChanged()
        assertTrue(t.sentLines.isEmpty())
    }

    @Test
    fun whoxAccountsUpdateMembersSilently() {
        connect()
        val b = c.buffer("#c")!!
        t.feed(":alice!a@h JOIN #c")
        t.sentLines.clear()
        val serverRows = c.serverBuffer.lines.size
        assertTrue(c.requestWhoAccounts("#c"))
        assertEquals(listOf("WHO #c %tna,152"), t.sentLines)
        t.feed(":s 354 n 152 alice AliceAcct")
        t.feed(":s 354 n 152 n 0")
        t.feed(":s 315 n #c :End of /WHO list.")
        assertEquals("AliceAcct", b.member("alice")?.account)
        assertEquals(serverRows, c.serverBuffer.lines.size) // nothing printed
    }

    @Test
    fun purgedBufferKeepsSendsAndRestoresOnlyItselfOnReopen() {
        connect()
        val b = c.buffer("#c")!!
        val other = c.queryBuffer("bob")
        t.feed("@msgid=r1 :alice!a@h PRIVMSG #c :${runtime.encrypt("from alice").single()}")
        t.feed("@msgid=r2 :bob!b@h PRIVMSG n :${runtime.encrypt("from bob").single()}")
        runtime.decisions["#c"] = IrcLockDecision.PENDING
        c.sendInput("held reply", b)
        c.purgeDecrypted(b)
        assertEquals(listOf("", "held reply"), chat(b).map { it.text })
        assertEquals(IrcE2eState.PENDING, chat(b).first().e2e?.state)
        assertTrue(c.isHeldSend(chat(b).last()))
        assertEquals("from bob", other.lines.last().text)
        c.resubmitPendingEncrypted(b)
        assertEquals("from alice", chat(b).first().text)
        assertEquals("from bob", other.lines.last().text)
    }
}
