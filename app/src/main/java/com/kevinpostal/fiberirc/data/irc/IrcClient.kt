package com.kevinpostal.fiberirc.data.irc

import android.content.Context
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import java.text.BreakIterator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class IrcState { DISCONNECTED, CONNECTING, REGISTERING, CONNECTED }

/** Observer of one [IrcClient]'s events (ports `IRCClientDelegate`). Every method defaults to a no-op. */
interface IrcClientListener {
    fun onStateChanged(client: IrcClient, state: IrcState, error: Throwable?) {}
    fun onBufferAdded(client: IrcClient, buffer: IrcBuffer) {}
    fun onBufferRemoved(client: IrcClient, buffer: IrcBuffer) {}
    fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) {}
    fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) {}

    /**
     * Unread/read-marker state or channel details (modes, topic setter, URL) changed without a new line (server
     * MARKREAD, a local read, 324/333/328).
     */
    fun onBufferUpdated(client: IrcClient, buffer: IrcBuffer) {}

    /**
     * The server refused our credentials (`464`). Auto-reconnect is off for this client: retrying the same password
     * cannot succeed, so the owner must fetch fresh credentials or sign out.
     */
    fun onCredentialsRejected(client: IrcClient) {}

    /** A `CHATHISTORY` page for `buffer` finished (`added` rows inserted; 0 on empty page, FAIL or drop). */
    fun onHistoryLoaded(client: IrcClient, buffer: IrcBuffer, added: Int) {}
    fun onAttachReplayFinished(client: IrcClient) {}

    /** `buffer.typing` changed (a `+typing` TAGMSG, or a typer's message, part, quit or rename). */
    fun onTypingUpdated(client: IrcClient, buffer: IrcBuffer) {}

    /** Held lines of `buffer` changed in place or went away (REDACT, `+draft/edit`, `/clear`). */
    fun onLinesChanged(client: IrcClient, buffer: IrcBuffer) {}
}

/** `+typing` states (IRCv3 typing client tag). */
enum class TypingState(val wire: String) {
    ACTIVE("active"), PAUSED("paused"), DONE("done");

    companion object {
        fun fromWire(s: String): TypingState? = entries.firstOrNull { it.wire == s }
    }
}

/** `WHOIS` answer, collected from 311/312/313/317/319/301/330/338/378/671 until 318. */
data class WhoisInfo(
    val nick: String,
    val user: String = "",
    val host: String = "",
    val realname: String = "",
    val server: String = "",
    val serverInfo: String = "",
    val operator: Boolean = false,
    /** Seconds idle (317). */
    val idle: Double? = null,
    val signon: Instant? = null,
    val channels: List<String> = emptyList(),
    /** Away message (301). */
    val away: String? = null,
    val account: String? = null,
    /** 338 / 378: the real host or IP behind a cloak. */
    val actualHost: String? = null,
    val secure: Boolean = false,
    /** Set when the nick doesn't exist (401/402) or the server never finished the reply. */
    val error: String? = null,
)

/** One row of a channel's ban (367), exception (348) or invite-exception (346) list. */
data class ModeListEntry(val mask: String, val setter: String?, val setAt: Instant?)

/** One `LIST` row (322). */
data class ChannelListEntry(val name: String, val users: Int, val topic: String)

/** Which IRCv3 client-tag features this network carries (see [IrcClient.clientTagFeatures]). */
data class ClientTagFeatures(val reply: Boolean, val typing: Boolean, val edit: Boolean, val redact: Boolean)

/**
 * IRC protocol state machine (ports `ios/IRC/IRCClient.swift`). Confined to [dispatcher] (the main thread in the
 * app): call every method there; listeners and callbacks run there too.
 */
class IrcClient(
    val settings: ConnectionSettings,
    private val transport: IrcTransport,
    private val pathMonitor: PathMonitoring,
    private val foregroundMonitor: AppForegroundMonitoring,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Instant = Instant::now,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val observers = ArrayList<IrcClientListener>()

    /** Whether a line from someone else counts as a highlight for our `nick`. The session adds highlight words. */
    var isHighlight: (IrcLine, String) -> Boolean = { line, nick -> line.mentions(nick) }

    /** A line in an `onScreen` buffer is read at once only while the app is active. */
    var isAppActive: () -> Boolean = { foregroundMonitor.isActive }

    /** Muted buffers count unread rows but never highlights. */
    var isMuted: (IrcBuffer) -> Boolean = { false }

    /** Whether showing a buffer marks it read (buffer pref "Mark As Read Automatically"). */
    var autoMarksRead: (IrcBuffer) -> Boolean = { true }

    /** The upstream network's acked caps (`GET /api/networks` `caps`), set by the session; null = unknown. */
    var upstreamCaps: Set<String>? = null

    private val _state = MutableStateFlow(IrcState.DISCONNECTED)
    val stateFlow: StateFlow<IrcState> = _state.asStateFlow()
    val state: IrcState get() = _state.value

    var nick: String = settings.nick
        private set
    private val _buffers = ArrayList<IrcBuffer>()
    val buffers: List<IrcBuffer> get() = _buffers
    val serverBuffer = IrcBuffer(settings.networkName, IrcBufferKind.SERVER)

    private val capsAdvertised = HashSet<String>()
    private val _capsAcked = HashSet<String>()
    val capsAcked: Set<String> get() = _capsAcked

    /** Buffer name → marker to push as `MARKREAD` on the next flush (coalesced, bouncer only). */
    private val pendingMarkRead = HashMap<String, Instant>()
    private var markReadTimer: Job? = null
    private val pendingNames = HashMap<String, MutableList<IrcMember>>()

    /**
     * Bouncer mode: lower-cased channels whose next self-JOIN must print "You joined" — the user asked for it
     * (/join) or we lost the channel this session (PART/KICK/join error). Every other self-JOIN via the bouncer is
     * its attach replay of a session that never left.
     */
    private val announceJoins = HashSet<String>()

    /** Lower-cased channels joined silently whose replayed 332 is swallowed if the topic is unchanged. */
    private val quietTopic = HashSet<String>()

    /**
     * Lower-cased nick → channel and send time of an `INVITE` awaiting its answer, so an ERR_NOSUCHNICK (which names
     * no channel) reaches the channel the invite came from.
     */
    private val pendingInvites = HashMap<String, Pair<String, Instant>>()
    private var nickTries = 0
    private var wantedNick: String = settings.nick

    private var idleTimer: Job? = null
    private var lastReceived: Instant = clock()
    private var userDisconnected = true
    private var reconnectAttempt = 0
    private var reconnectTimer: Job? = null

    /** Between entering the background and returning to the foreground; path changes are ignored meanwhile. */
    private var inBackground = false

    /**
     * After returning to the foreground the link must show life (any inbound line, or the TCP connect completing)
     * within this window, else it is presumed dead and replaced at once. `var` so unit tests can shorten it.
     */
    var resumeProbeTimeout: Duration = Duration.ofSeconds(8)
    private var resumeProbeTimer: Job? = null

    /** True from foregrounding until the link proves alive or drops. */
    private var resuming = false

    /** Rows of a requested `CHATHISTORY` batch, held until `BATCH -ref` so they merge as one page. */
    private class PendingHistory(val buffer: IrcBuffer, val lines: MutableList<IrcLine> = ArrayList())

    /** Batch ref → page being collected. Only batches answering our own request are recorded. */
    private val historyBatches = HashMap<String, PendingHistory>()

    /**
     * Bouncer: the bnc writes its attach replay (JOIN/NAMES/history batches) before it reads the next client line, so
     * a `PING` sent on 001 is answered only after the replay. Until that `PONG`, every `chathistory` batch is replay
     * (live rows, unread); after it, batches for a buffer with a request in flight are pages. Paging waits for it.
     */
    var attachSynced = false
        private set
    private var attachSyncTimer: Job? = null

    /**
     * No `PONG` within this window opens the gate anyway (a bouncer that never answers PING). Long enough for a large
     * first-attach playback. `var` so unit tests can shorten it.
     */
    var attachSyncTimeout: Duration = Duration.ofSeconds(30)

    private var prefixModes: Map<Char, Char> = DEFAULT_PREFIX_MODES
    private var prefixOrder: List<Char> = DEFAULT_PREFIX_ORDER
    private var chanModeTypes: Map<Char, Char> = DEFAULT_CHAN_MODE_TYPES

    /** ISUPPORT `TOPICLEN`; null until the server sends it. */
    var topicLength: Int? = null
        private set

    /** ISUPPORT `EXCEPTS` (ban-exception mode, `e` when bare); null until the server sends it. */
    var exceptsMode: Char? = null
        private set

    /** ISUPPORT `INVEX` (invite-exception mode, `I` when bare); null until the server sends it. */
    var invexMode: Char? = null
        private set

    /** ISUPPORT `CLIENTTAGDENY` entries: client-only tags the server drops (see [clientTagFeatures]). */
    private var clientTagDeny: List<String> = emptyList()

    /** Lower-cased buffer name → the last `+typing` state we sent and when. */
    private val typingSent = HashMap<String, Pair<TypingState, Instant>>()

    private class PendingWhois(var info: WhoisInfo, val completions: MutableList<(WhoisInfo) -> Unit>, var timer: Job?)

    /** Lower-cased nick → its `WHOIS` in flight. */
    private val pendingWhois = HashMap<String, PendingWhois>()

    private class PendingModeList(
        val entries: MutableList<ModeListEntry>,
        val completions: MutableList<(List<ModeListEntry>) -> Unit>,
        var timer: Job?,
    )

    /** `"<lower-cased channel> <mode>"` → its list request in flight. */
    private val pendingModeLists = HashMap<String, PendingModeList>()

    /** Lower-cased channel → when [refreshChannelModes] last sent `MODE <chan>` on this link. */
    private val modesRefreshedAt = HashMap<String, Instant>()

    private class PendingList(
        val page: MutableList<ChannelListEntry>,
        var total: Int,
        val onPage: (List<ChannelListEntry>) -> Unit,
        val completion: () -> Unit,
    )

    private var pendingList: PendingList? = null

    private class HistoryLoader(var pagesLeft: Int, val completion: () -> Unit)

    /** Buffers being walked back by [loadAllHistory]: pages left, and what to call when done. */
    private val historyLoaders = HashMap<IrcBuffer, HistoryLoader>()

    init {
        _buffers.add(serverBuffer)
        inBackground = !foregroundMonitor.isForeground
        foregroundMonitor.onChange = { foreground -> if (foreground) willEnterForeground() else didEnterBackground() }
        foregroundMonitor.start()
        pathMonitor.onChange = { satisfied -> pathChanged(satisfied) }
        pathMonitor.start()
        scope.launch { transport.events.collect { onTransportEvent(it) } }
    }

    /** Stops timers, monitors and the link (iOS `deinit`). The client is unusable afterwards. */
    fun dispose() {
        userDisconnected = true
        foregroundMonitor.onChange = null
        foregroundMonitor.cancel()
        pathMonitor.onChange = null
        pathMonitor.cancel()
        transport.disconnect()
        scope.cancel()
        observers.clear()
    }

    // region Observers

    /** Adds `o` to the objects told about this client's events; adding twice is a no-op. */
    fun addObserver(o: IrcClientListener) {
        if (observers.none { it === o }) observers.add(o)
    }

    fun removeObserver(o: IrcClientListener) {
        observers.removeAll { it === o }
    }

    /** Calls `body` once per observer; observers added or removed meanwhile take effect next time. */
    private inline fun notify(body: (IrcClientListener) -> Unit) {
        for (o in observers.toTypedArray()) body(o)
    }

    // endregion

    // region Public

    fun connect() {
        if (state != IrcState.DISCONNECTED) return
        reconnectTimer?.cancel(); reconnectTimer = null
        userDisconnected = false
        capsAdvertised.clear()
        _capsAcked.clear()
        pendingNames.clear()
        attachSynced = false
        attachSyncTimer?.cancel(); attachSyncTimer = null
        prefixModes = DEFAULT_PREFIX_MODES
        prefixOrder = DEFAULT_PREFIX_ORDER
        chanModeTypes = DEFAULT_CHAN_MODE_TYPES
        topicLength = null
        exceptsMode = null
        invexMode = null
        clientTagDeny = emptyList()
        typingSent.clear()
        nickTries = 0
        wantedNick = settings.nick
        setState(IrcState.CONNECTING, null)
        appendEvent(
            "Connecting to ${settings.host}:${settings.port}${if (settings.useTLS) " (TLS)" else ""}…",
            serverBuffer,
        )
        transport.connect(settings.host, settings.port, settings.useTLS)
    }

    /** User-initiated: QUIT, no auto-reconnect. */
    fun disconnect(quitMessage: String = "FiberIRC") {
        userDisconnected = true
        reconnectTimer?.cancel(); reconnectTimer = null
        stopIdleTimer()
        clearResumeProbe()
        if (state == IrcState.DISCONNECTED) return
        if (state == IrcState.CONNECTING) {
            transport.disconnect()
            return
        }
        // Send QUIT and let the server close the link so the quit reason is preserved; force-cancel if it hasn't
        // closed within 2 s.
        send("QUIT", listOf(quitMessage))
        scope.launch {
            delay(2_000)
            if (state != IrcState.DISCONNECTED) transport.disconnect()
        }
    }

    fun buffer(name: String): IrcBuffer? = _buffers.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The user has seen everything in `b`: clear the badge locally and, when attached through the bouncer with
     * `draft/read-marker`, tell it so every other client (web tabs, other phones) clears theirs too. Sends are
     * coalesced 2 s so scrolling through buffers is one burst.
     */
    fun markRead(b: IrcBuffer) {
        // Newest chat row (events like JOIN carry the local clock and would post-date replayed history).
        val marker = b.lines.lastOrNull { isChat(it) }?.time ?: clock()
        val m = b.readMarker
        if (m != null && m >= marker && b.unreadCount == 0 && b.highlightCount == 0) return
        b.readMarker = if (m != null && m > marker) m else marker
        b.unreadCount = 0
        b.highlightCount = 0
        notify { it.onBufferUpdated(this, b) }
        // The server/status buffer's name is the network's free-text display name, not an IRC token (e.g.
        // "IRC Fiber" — a space in it shifts MARKREAD's params and the bouncer FAILs with INVALID_PARAMS);
        // read-marker sync is for channels/queries only.
        if (b.kind == IrcBufferKind.SERVER || !settings.viaBouncer || !_capsAcked.contains("draft/read-marker")) return
        pendingMarkRead[b.name] = b.readMarker!!
        if (markReadTimer == null) {
            markReadTimer = scope.launch {
                delay(2_000)
                flushMarkRead()
            }
        }
    }

    /** Sends one `MARKREAD <buffer> timestamp=<iso>` per pending buffer. */
    fun flushMarkRead() {
        val timer = markReadTimer
        markReadTimer = null
        timer?.cancel()
        val pending = HashMap(pendingMarkRead)
        pendingMarkRead.clear()
        if (state != IrcState.CONNECTED) return
        for ((name, t) in pending.entries.sortedBy { it.key }) {
            send("MARKREAD", listOf(name, "timestamp=" + IrcMessage.formatTime(t)))
        }
    }

    fun joinChannel(name: String, key: String? = null) {
        if (name.isEmpty()) return
        val chan = if (isChannelName(name)) name else "#$name"
        channelBuffer(chan)
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", serverBuffer)
            return
        }
        announceJoins.add(chan.lowercase())
        send("JOIN", if (key != null) listOf(chan, key) else listOf(chan))
    }

    fun partBuffer(b: IrcBuffer, message: String = "FiberIRC") {
        if (b.kind != IrcBufferKind.CHANNEL) return
        if (b.joined && state == IrcState.CONNECTED) send("PART", listOf(b.name, message))
        b.joined = false
    }

    fun removeBuffer(b: IrcBuffer) {
        if (b === serverBuffer) return
        val i = _buffers.indexOfFirst { it === b }
        if (i < 0) return
        _buffers.removeAt(i)
        notify { it.onBufferRemoved(this, b) }
    }

    /**
     * Leaves a channel (PART) or closes a conversation, and drops its buffer. Through the bouncer a PART leaves the
     * channel for every client of the account. The server buffer never closes.
     */
    fun close(b: IrcBuffer) {
        if (b === serverBuffer) return
        if (b.kind == IrcBufferKind.CHANNEL) partBuffer(b)
        removeBuffer(b)
    }

    /** Drops every held line of `b` (`/clear`, Clear Backlog) and tells observers to rebuild its rows. */
    fun clearLines(b: IrcBuffer) {
        b.clearLines()
        notify { it.onLinesChanged(this, b) }
    }

    /**
     * Sets the channel topic; an empty `text` clears it. The server's `TOPIC` echo updates `topic`; a refusal shows
     * in the channel as an error.
     */
    fun setTopic(text: String, b: IrcBuffer) {
        if (b.kind != IrcBufferKind.CHANNEL) return
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", b)
            return
        }
        send("TOPIC", listOf(b.name, text))
    }

    /**
     * Deletes your message `msgid` (`REDACT <buffer> <msgid>`, draft/message-redaction). The server's relayed REDACT
     * turns the row into a tombstone; a refusal (`FAIL REDACT`) shows in the buffer.
     */
    fun redact(msgid: String, b: IrcBuffer) {
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", b)
            return
        }
        send("REDACT", listOf(b.name, msgid))
    }

    /**
     * Invites `nick` to the channel. The answer shows in the channel: "Invited …" (RPL_INVITING), or why not.
     */
    fun invite(nick: String, b: IrcBuffer) {
        if (b.kind != IrcBufferKind.CHANNEL || !isValidNick(nick)) return
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", b)
            return
        }
        val now = clock()
        pendingInvites.entries.removeAll { secondsBetween(it.value.second, now) >= INVITE_ANSWER_WINDOW }
        pendingInvites[nick.lowercase()] = b.name to now
        send("INVITE", listOf(nick, b.name))
    }

    /**
     * Whether [requestOlderHistory] would send a page request for `b` now: through the bouncer, after the attach
     * replay, with `batch`, while connected, no page in flight, and older rows left.
     */
    fun canRequestOlderHistory(b: IrcBuffer): Boolean =
        b.kind != IrcBufferKind.SERVER && !b.historyLoading && !b.historyExhausted && state == IrcState.CONNECTED &&
            attachSynced && settings.viaBouncer && _capsAcked.contains("batch")

    /**
     * Asks the bouncer for the page of chat rows before `b`'s oldest one (`LATEST` when it has none). Attach replay
     * is kept as is (`draft/chathistory` is not requested: it disables replay); `CHATHISTORY` is answered regardless.
     * Direct connections never page. Returns false when nothing was sent.
     */
    fun requestOlderHistory(b: IrcBuffer): Boolean {
        if (!canRequestOlderHistory(b)) return false
        val oldest = b.lines.firstOrNull { isChat(it) }
        if (oldest != null) {
            send(
                "CHATHISTORY",
                listOf("BEFORE", b.name, "timestamp=" + IrcMessage.formatTime(oldest.time), "$HISTORY_PAGE_SIZE"),
            )
        } else {
            send("CHATHISTORY", listOf("LATEST", b.name, "*", "$HISTORY_PAGE_SIZE"))
        }
        b.historyLoading = true
        return true
    }

    /**
     * Walks `b` back to its first row, or `maxPages` pages, through [requestOlderHistory] (Export Log). `completion`
     * runs once no further page can be fetched: exhausted, unsupported, offline, or the page budget spent.
     */
    fun loadAllHistory(b: IrcBuffer, maxPages: Int = 20, completion: () -> Unit) {
        val running = historyLoaders[b]
        if (running != null) {
            historyLoaders[b] = HistoryLoader(running.pagesLeft) { running.completion(); completion() }
            return
        }
        historyLoaders[b] = HistoryLoader(maxPages, completion)
        continueHistoryLoad(b)
    }

    /** Next step of a [loadAllHistory] walk: at the start and after each page lands. */
    private fun continueHistoryLoad(b: IrcBuffer) {
        val loader = historyLoaders[b] ?: return
        if (b.historyLoading) return // a page in flight continues it
        if (loader.pagesLeft > 0 && requestOlderHistory(b)) {
            loader.pagesLeft -= 1
        } else {
            historyLoaders.remove(b)
            loader.completion()
        }
    }

    /**
     * Recounts `b` from `readMarker` (all rows when null): `unreadCount` = chat rows from others newer than it,
     * `highlightCount` = those [isHighlight] accepts (none in a muted buffer).
     */
    fun recomputeUnread(b: IrcBuffer) {
        var unread = 0
        var highlights = 0
        val muted = isMuted(b)
        val m = b.readMarker
        for (l in b.lines) {
            if (!isChat(l) || isOwn(l.sender)) continue
            if (m != null && l.time <= m) continue
            unread++
            if (!muted && isHighlight(l, nick)) highlights++
        }
        b.unreadCount = unread
        b.highlightCount = highlights
    }

    /**
     * Which IRCv3 client-tag features this network carries (the web's `lib/clientTags.ts`). Reply and typing need
     * `message-tags` acked here and upstream (unknown upstream allows them) and `CLIENTTAGDENY` to let the tag
     * through; edit needs upstream `draft/edit-message`; delete needs `draft/message-redaction` acked here.
     */
    val clientTagFeatures: ClientTagFeatures
        get() {
            val tags = _capsAcked.contains("message-tags")
            val carried = tags && (upstreamCaps?.contains("message-tags") ?: true)
            // Under a leading `*` only `-name` exemptions pass; otherwise bare names are the blocklist.
            val deny = clientTagDeny
            val allowed = { tag: String ->
                if (deny.firstOrNull() == "*") deny.contains("-$tag") else !deny.contains(tag)
            }
            return ClientTagFeatures(
                reply = carried && allowed("reply"),
                typing = carried && allowed("typing"),
                edit = tags && (upstreamCaps?.contains("draft/edit-message") ?: false),
                redact = _capsAcked.contains("draft/message-redaction"),
            )
        }

    /**
     * Sends `+typing` for `b` as a TAGMSG: `active` at most every 3 s, `paused` and `done` only after an `active`.
     * Nothing is sent while `clientTagFeatures.typing` is off.
     */
    fun sendTyping(typing: TypingState, b: IrcBuffer) {
        if (b.kind == IrcBufferKind.SERVER || state != IrcState.CONNECTED || !clientTagFeatures.typing) return
        val key = b.name.lowercase()
        val now = clock()
        val last = typingSent[key]
        when (typing) {
            TypingState.ACTIVE -> {
                if (last != null && last.first == TypingState.ACTIVE &&
                    secondsBetween(last.second, now) < TYPING_ACTIVE_INTERVAL
                ) {
                    return
                }
                typingSent[key] = TypingState.ACTIVE to now
            }
            TypingState.PAUSED -> {
                if (last?.first != TypingState.ACTIVE) return
                typingSent[key] = TypingState.PAUSED to now
            }
            TypingState.DONE -> {
                if (last == null) return
                typingSent.remove(key)
            }
        }
        send(mapOf("+typing" to typing.wire), "TAGMSG", listOf(b.name))
    }

    /**
     * `WHOIS nick nick` (the second nick asks the user's own server, which knows the idle time). Its numerics are
     * collected instead of printed; `completion` runs on 318, on 401/402 with `error` set, or after 10 s with
     * whatever arrived.
     */
    fun whois(nick: String, completion: (WhoisInfo) -> Unit) {
        val key = nick.lowercase()
        val pending = pendingWhois[key]
        if (pending != null) {
            pending.completions.add(completion)
            return
        }
        if (state != IrcState.CONNECTED) {
            completion(WhoisInfo(nick = nick, error = "Not connected"))
            return
        }
        val timer = after(COLLECTOR_TIMEOUT_MS) { finishWhois(key, null) }
        pendingWhois[key] = PendingWhois(WhoisInfo(nick = nick), mutableListOf(completion), timer)
        send("WHOIS", listOf(nick, nick))
    }

    /**
     * Asks for a channel's list mode (`b`, the `EXCEPTS` mode, the `INVEX` mode); its 367/348/346 rows are collected
     * until 368/349/347, or for 10 s, instead of printed.
     */
    fun requestModeList(mode: Char, b: IrcBuffer, completion: (List<ModeListEntry>) -> Unit) {
        if (b.kind != IrcBufferKind.CHANNEL || state != IrcState.CONNECTED) {
            completion(emptyList())
            return
        }
        val key = "${b.name.lowercase()} $mode"
        val pending = pendingModeLists[key]
        if (pending != null) {
            pending.completions.add(completion)
            return
        }
        val timer = after(COLLECTOR_TIMEOUT_MS) { finishModeList(key) }
        pendingModeLists[key] = PendingModeList(ArrayList(), mutableListOf(completion), timer)
        send("MODE", listOf(b.name, mode.toString()))
    }

    /**
     * `MODE <chan>` (Channel Info on appear), at most once per 60 s per channel on this link; the 324 answer
     * replaces `channelModes`/`channelModeArgs` without printing.
     */
    fun refreshChannelModes(b: IrcBuffer, now: Instant = clock()) {
        if (b.kind != IrcBufferKind.CHANNEL || state != IrcState.CONNECTED) return
        val key = b.name.lowercase()
        val last = modesRefreshedAt[key]
        if (last != null && secondsBetween(last, now) < MODE_REFRESH_INTERVAL) return
        modesRefreshedAt[key] = now
        send("MODE", listOf(b.name))
    }

    /**
     * `LIST`: the 322 rows arrive in `onPage` batches of 50 (5000 rows at most; later ones are dropped) and
     * `completion` runs on 323 or when the link drops. A new request ends the previous one.
     */
    fun listChannels(onPage: (List<ChannelListEntry>) -> Unit, completion: () -> Unit) {
        finishList()
        if (state != IrcState.CONNECTED) {
            completion()
            return
        }
        pendingList = PendingList(ArrayList(), 0, onPage, completion)
        send("LIST", emptyList())
    }

    /** `MODE <chan> <modes> <args…>`, e.g. `setMode("+oo", listOf("a", "b"), chan)`. */
    fun setMode(modes: String, args: List<String>, b: IrcBuffer) {
        if (b.kind != IrcBufferKind.CHANNEL) return
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", b)
            return
        }
        send("MODE", listOf(b.name, modes) + args)
    }

    fun kick(nick: String, b: IrcBuffer, reason: String? = null) {
        if (b.kind != IrcBufferKind.CHANNEL || !isValidNick(nick)) return
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", b)
            return
        }
        if (!reason.isNullOrEmpty()) send("KICK", listOf(b.name, nick, reason)) else send("KICK", listOf(b.name, nick))
    }

    /** Bans [banMask] of the member: their host when it is known. */
    fun ban(nick: String, b: IrcBuffer) {
        setMode("+b", listOf(banMask(nick, b.member(nick)?.host ?: "")), b)
    }

    // endregion

    // region Input

    /**
     * Sends what the composer holds: each non-empty line on its own, through the slash commands and PRIVMSG
     * chunking. `replyTo` tags every plain or `/me` line; `editOf` edits with the first line only.
     */
    fun sendInput(text: String, buffer: IrcBuffer, replyTo: String? = null, editOf: String? = null) {
        var edit = editOf
        for (line in text.split(NEWLINES)) {
            if (line.isEmpty()) continue
            sendInputLine(line, buffer, replyTo, edit)
            edit = null
        }
    }

    private fun sendInputLine(text: String, buffer: IrcBuffer, replyTo: String?, editOf: String?) {
        if (!text.startsWith("/") || text.startsWith("//")) {
            val msg = if (text.startsWith("//")) text.substring(1) else text
            if (buffer.kind == IrcBufferKind.SERVER) {
                appendError("Not a channel", buffer)
                return
            }
            sendMessage(msg, buffer, action = false, replyTo = replyTo, editOf = editOf)
            return
        }
        val body = text.substring(1)
        val sp = body.indexOf(' ')
        val cmd: String
        val arg: String
        if (sp >= 0) {
            cmd = body.substring(0, sp).lowercase()
            arg = body.substring(sp + 1).trim { it == ' ' || it == '\t' || (it.isWhitespace() && it != '\n' && it != '\r') }
        } else {
            cmd = body.lowercase()
            arg = ""
        }
        val words = if (arg.isEmpty()) emptyList() else arg.split(' ').filter { it.isNotEmpty() }

        when (cmd) {
            "join", "j" -> {
                val chan = words.firstOrNull() ?: return appendError("Usage: /join #channel [key]", buffer)
                joinChannel(chan, if (words.size > 1) words[1] else null)
            }
            "part", "leave" -> {
                var target = buffer
                var msg = arg
                val first = words.firstOrNull()
                if (first != null && isChannelName(first)) {
                    target = buffer(first) ?: return appendError("No such channel", buffer)
                    msg = words.drop(1).joinToString(" ")
                }
                if (target.kind != IrcBufferKind.CHANNEL) return appendError("Not a channel", buffer)
                partBuffer(target, msg.ifEmpty { "FiberIRC" })
            }
            "nick" -> {
                val n = words.firstOrNull() ?: return appendError("Usage: /nick name", buffer)
                wantedNick = n
                send("NICK", listOf(n))
            }
            "msg" -> {
                if (words.size < 2) return appendError("Usage: /msg target text", buffer)
                val target = words[0]
                val b = buffer(target) ?: queryBuffer(target)
                sendMessage(words.drop(1).joinToString(" "), b, action = false)
            }
            "me" -> {
                if (buffer.kind == IrcBufferKind.SERVER) return appendError("Not a channel", buffer)
                sendMessage(arg, buffer, action = true, replyTo = replyTo, editOf = editOf)
            }
            "query" -> {
                val n = words.firstOrNull() ?: return appendError("Usage: /query nick", buffer)
                queryBuffer(n)
            }
            "topic" -> {
                if (buffer.kind != IrcBufferKind.CHANNEL) return appendError("Not a channel", buffer)
                send("TOPIC", if (arg.isEmpty()) listOf(buffer.name) else listOf(buffer.name, arg))
            }
            "invite" -> {
                val n = words.firstOrNull()
                if (n == null || !isValidNick(n)) return appendError("Usage: /invite nick [#channel]", buffer)
                var target = buffer
                if (words.size > 1) {
                    val b = buffer(words[1])
                    if (b == null || b.kind != IrcBufferKind.CHANNEL) return appendError("No such channel", buffer)
                    target = b
                }
                if (target.kind != IrcBufferKind.CHANNEL) return appendError("Not a channel", buffer)
                invite(n, target)
            }
            "quit" -> disconnect(arg.ifEmpty { "FiberIRC" })
            "raw", "quote" -> {
                if (arg.isEmpty()) return appendError("Usage: /raw line", buffer)
                transport.send(arg)
            }
            "close" -> {
                if (buffer.kind == IrcBufferKind.SERVER) return appendError("Cannot close the server buffer", buffer)
                close(buffer)
            }
            "whois" -> {
                val n = words.firstOrNull() ?: return appendError("Usage: /whois nick", buffer)
                whois(n) { info ->
                    val error = info.error
                    if (error != null) {
                        appendError("${info.nick}: $error", buffer)
                    } else {
                        for (line in whoisLines(info)) appendEvent(line, buffer)
                    }
                }
            }
            "op", "deop", "voice", "devoice" -> {
                if (buffer.kind != IrcBufferKind.CHANNEL) return appendError("Not a channel", buffer)
                if (words.isEmpty()) return appendError("Usage: /$cmd nick [nick…]", buffer)
                val letter = if (cmd.endsWith("op")) "o" else "v"
                setMode((if (cmd.startsWith("de")) "-" else "+") + letter.repeat(words.size), words, buffer)
            }
            "kick" -> {
                if (buffer.kind != IrcBufferKind.CHANNEL) return appendError("Not a channel", buffer)
                val n = words.firstOrNull() ?: return appendError("Usage: /kick nick [reason]", buffer)
                kick(n, buffer, words.drop(1).joinToString(" "))
            }
            "ban" -> {
                if (buffer.kind != IrcBufferKind.CHANNEL) return appendError("Not a channel", buffer)
                val n = words.firstOrNull() ?: return appendError("Usage: /ban nick", buffer)
                ban(n, buffer)
            }
            "mode" -> {
                if (state != IrcState.CONNECTED) return appendError("Not connected", buffer)
                val params = words.toMutableList()
                // "/mode" or "/mode +m" means this channel (your own user modes in other buffers).
                val first = params.firstOrNull()
                if (first == null || first.startsWith("+") || first.startsWith("-")) {
                    params.add(0, if (buffer.kind == IrcBufferKind.CHANNEL) buffer.name else nick)
                }
                send("MODE", params)
            }
            "notice" -> {
                if (words.size < 2) return appendError("Usage: /notice target text", buffer)
                if (state != IrcState.CONNECTED) return appendError("Not connected", buffer)
                val noticeText = words.drop(1).joinToString(" ")
                send("NOTICE", listOf(words[0], noticeText))
                if (!_capsAcked.contains("echo-message")) {
                    append(IrcLine(clock(), nick, noticeText, IrcLineKind.NOTICE), buffer(words[0]) ?: buffer)
                }
            }
            "away" -> {
                if (state != IrcState.CONNECTED) return appendError("Not connected", buffer)
                send("AWAY", if (arg.isEmpty()) emptyList() else listOf(arg))
            }
            "list" -> {
                if (state != IrcState.CONNECTED) return appendError("Not connected", buffer)
                send("LIST", words)
            }
            "clear" -> clearLines(buffer)
            else -> appendError("Unknown command: /$cmd", buffer)
        }
    }

    /**
     * PRIVMSG `text` to `buffer`, split into ≤ 400-byte pieces. `replyTo` adds `+reply`/`+draft/reply` and `editOf`
     * adds `+draft/edit` (to the first piece) where [clientTagFeatures] allows them. Without echo-message the pieces
     * show at once (an edit replaces the held line).
     */
    fun sendMessage(
        text: String,
        buffer: IrcBuffer,
        action: Boolean,
        replyTo: String? = null,
        editOf: String? = null,
    ) {
        if (state != IrcState.CONNECTED) {
            appendError("Not connected", buffer)
            return
        }
        val features = clientTagFeatures
        val chunks = if (action) listOf(text) else chunk(text, PRIVMSG_CHUNK_BYTES)
        val echo = !_capsAcked.contains("echo-message")
        val reply = if (features.reply) replyTo else null
        typingSent.remove(buffer.name.lowercase()) // a message ends our typing state
        chunks.forEachIndexed { i, c ->
            val tags = HashMap<String, String>()
            if (reply != null) {
                tags["+reply"] = reply
                tags["+draft/reply"] = reply
            }
            val edit = if (i == 0 && features.edit) editOf else null
            if (edit != null) tags["+draft/edit"] = edit
            send(tags, "PRIVMSG", listOf(buffer.name, if (action) "\u0001ACTION $c\u0001" else c))
            if (!echo) return@forEachIndexed
            if (edit != null && buffer.replaceText(edit, c)) {
                notify { it.onLinesChanged(this, buffer) }
            } else {
                append(
                    IrcLine(clock(), nick, c, if (action) IrcLineKind.ACTION else IrcLineKind.MESSAGE, replyTo = reply),
                    buffer,
                )
            }
        }
    }

    private fun send(command: String, params: List<String>) = send(emptyMap(), command, params)

    private fun send(tags: Map<String, String>, command: String, params: List<String>) {
        transport.send(IrcMessage.line(tags, command, params))
    }

    // endregion

    // region Reconnect / liveness

    private fun scheduleReconnect() {
        // No route: retrying would only burn attempts; `pathChanged` reconnects when one appears.
        if (!pathMonitor.isSatisfied) {
            Log.i(TAG, "reconnect deferred: no network path")
            appendEvent("Waiting for network…", serverBuffer)
            return
        }
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "giving up after $MAX_RECONNECT_ATTEMPTS reconnect attempts")
            appendError("Giving up after $MAX_RECONNECT_ATTEMPTS reconnect attempts", serverBuffer)
            userDisconnected = true
            return
        }
        // ±20% jitter so clients dropped together (server restart) don't reconnect in lockstep.
        val delays = doubleArrayOf(2.0, 4.0, 8.0, 16.0, 30.0)
        val delaySeconds = delays[minOf(reconnectAttempt, delays.size - 1)] * Random.nextDouble(0.8, 1.2)
        reconnectAttempt += 1
        Log.i(TAG, "reconnect attempt $reconnectAttempt in ${"%.1f".format(delaySeconds)} s")
        appendEvent("Reconnecting in ${Math.round(delaySeconds)} s…", serverBuffer)
        reconnectTimer?.cancel()
        reconnectTimer = after((delaySeconds * 1000).toLong()) {
            reconnectTimer = null
            reconnectNow()
        }
    }

    private fun reconnectNow() {
        if (state == IrcState.DISCONNECTED && !userDisconnected) connect()
    }

    private fun willEnterForeground() {
        inBackground = false
        if (userDisconnected) return
        if (state == IrcState.DISCONNECTED) {
            reconnectAttempt = 0
            reconnectTimer?.cancel(); reconnectTimer = null
            reconnectNow()
        } else {
            armResumeProbe()
        }
    }

    /** A probe armed just before switching away must not fire late on the next resume; that resume arms a fresh one. */
    private fun didEnterBackground() {
        inBackground = true
        clearResumeProbe()
    }

    /**
     * Reachability changed while in the foreground (Wi-Fi ↔ cellular, airplane mode, captive network). Backgrounded,
     * the foreground handler takes over on return.
     */
    private fun pathChanged(satisfied: Boolean) {
        Log.i(TAG, "network path ${if (satisfied) "satisfied" else "unsatisfied"} while $state")
        if (userDisconnected || inBackground) return
        if (satisfied) {
            if (state == IrcState.DISCONNECTED) {
                reconnectAttempt = 0
                reconnectTimer?.cancel(); reconnectTimer = null
                appendEvent("Network available — reconnecting…", serverBuffer)
                connect()
            } else {
                armResumeProbe() // new interface: the socket must prove it still works
            }
        } else if (reconnectTimer != null) {
            reconnectTimer?.cancel(); reconnectTimer = null
            appendEvent("Waiting for network…", serverBuffer)
        }
    }

    private fun armResumeProbe() {
        resumeProbeTimer?.cancel()
        resuming = true
        // CONNECTING gets no PING (lines are dropped before the link is ready); the connect completing is its proof
        // of life.
        if (state == IrcState.CONNECTED || state == IrcState.REGISTERING) send("PING", listOf("fiberirc-resume"))
        resumeProbeTimer = after(resumeProbeTimeout.toMillis()) {
            if (!resuming) return@after
            resumeProbeTimer = null
            Log.i(TAG, "resume probe: no reply within ${resumeProbeTimeout.seconds} s, reconnecting")
            appendError("No reply from the server after returning to the app — reconnecting", serverBuffer)
            transport.disconnect()
        }
    }

    private fun clearResumeProbe() {
        resuming = false
        resumeProbeTimer?.cancel()
        resumeProbeTimer = null
    }

    private fun startIdleTimer() {
        stopIdleTimer()
        idleTimer = scope.launch {
            while (isActive) {
                delay(10_000)
                idleTick()
            }
        }
    }

    private fun stopIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }

    private fun idleTick() {
        val idle = secondsBetween(lastReceived, clock())
        if (idle >= IDLE_TIMEOUT_AFTER) {
            appendError("Connection timed out", serverBuffer)
            stopIdleTimer()
            transport.disconnect()
        } else if (idle >= IDLE_PING_AFTER) {
            send("PING", listOf("fiberirc"))
        }
    }

    // endregion

    // region Message handling

    private fun handle(m: IrcMessage) {
        val p = m.params
        val ref = m.tags["batch"]
        if (ref != null && historyBatches.containsKey(ref)) {
            collectHistory(m, ref)
            return
        }
        val cmd = m.command
        when {
            cmd == "PING" -> send("PONG", p.lastOrNull()?.let { listOf(it) } ?: emptyList())
            cmd == "PONG" -> if (p.lastOrNull() == ATTACH_SYNC_TOKEN) finishAttachSync()
            cmd == "005" -> handleIsupport(m)
            cmd == "CAP" -> handleCap(m)
            cmd == "MARKREAD" -> handleMarkRead(m)
            cmd == "AUTHENTICATE" -> if (p.firstOrNull() == "+") sendSaslPayload()
            cmd == "PRIVMSG" || cmd == "NOTICE" -> handlePrivmsg(m)
            cmd == "JOIN" -> handleJoin(m)
            cmd == "PART" -> handlePart(m)
            cmd == "KICK" -> handleKick(m)
            cmd == "QUIT" -> handleQuit(m)
            cmd == "NICK" -> handleNick(m)
            cmd == "MODE" -> handleMode(m)
            cmd == "TOPIC" -> {
                val chan = p.firstOrNull() ?: return
                val b = buffer(chan) ?: return
                b.topic = if (p.size > 1) p[1] else ""
                b.topicSetBy = m.nick
                b.topicSetAt = m.time
                appendEvent("${m.nick ?: "?"} set the topic: ${b.topic ?: ""}", b)
            }
            cmd == "TAGMSG" -> handleTagmsg(m)
            cmd == "REDACT" -> handleRedact(m)
            cmd == "INVITE" -> handleInvite(m)
            cmd == "AWAY" || cmd == "ACCOUNT" || cmd == "CHGHOST" || cmd == "SETNAME" -> handleMemberDetail(m)
            cmd == "ERROR" -> appendError("ERROR: ${p.lastOrNull() ?: ""}", serverBuffer)
            cmd == "BATCH" -> handleBatch(m)
            cmd == "FAIL" && p.firstOrNull() == "CHATHISTORY" -> handleChatHistoryFail(m)
            cmd == "FAIL" && p.firstOrNull() == "REDACT" -> {
                // FAIL REDACT <code> <target> [<msgid>] :<description> (the web's FAIL_REDACT_PREFIX).
                val b = if (p.size > 2) buffer(p[2]) ?: serverBuffer else serverBuffer
                appendError("Could not delete message: ${p.lastOrNull() ?: ""}", b)
            }
            cmd == "464" -> handlePasswordRejected(m)
            cmd == "001" -> handleWelcome(m)
            cmd == "433" || cmd == "432" -> handleNickInUse(m)
            cmd == "332" -> {
                if (p.size > 1) {
                    val b = buffer(p[1]) ?: return
                    val quiet = quietTopic.remove(b.name.lowercase()) && b.topic == p.lastOrNull()
                    b.topic = p.lastOrNull()
                    if (!quiet) appendEvent("Topic: ${b.topic ?: ""}", b)
                }
            }
            cmd == "333" -> { // RPL_TOPICWHOTIME <me> <chan> <setter> <ts>
                if (p.size >= 4) {
                    val b = buffer(p[1]) ?: return
                    b.topicSetBy = p[2].takeWhile { it != '!' }
                    b.topicSetAt = p[3].toDoubleOrNull()?.let { epoch(it) }
                    notify { it.onBufferUpdated(this, b) }
                }
            }
            // RPL_CHANNELMODEIS <me> <chan> <modes> [args]
            cmd == "324" && p.size >= 3 && buffer(p[1]) != null -> handleChannelModeIs(m)
            cmd == "328" && p.size >= 3 && buffer(p[1]) != null -> { // RPL_CHANNELURL <me> <chan> <url>
                val b = buffer(p[1]) ?: return
                b.channelURL = p[2]
                notify { it.onBufferUpdated(this, b) }
            }
            cmd == "329" -> Unit // RPL_CREATIONTIME
            cmd == "353" -> handleNames(m)
            cmd == "366" -> {
                if (p.size > 1) {
                    val b = buffer(p[1]) ?: return
                    // A NAMES refresh keeps what JOIN, AWAY, ACCOUNT, SETNAME and the site told us per member.
                    val names = pendingNames.remove(b.name.lowercase()) ?: emptyList()
                    b.setMembers(names.map { merge(it, b.member(it.nick)) })
                    quietTopic.remove(b.name.lowercase())
                    notifyMembers(b)
                }
            }
            cmd == "903" -> {
                appendEvent("SASL authentication successful", serverBuffer)
                send("CAP", listOf("END"))
            }
            cmd == "904" || cmd == "905" || cmd == "906" || cmd == "907" -> {
                appendError("SASL failed: ${p.lastOrNull() ?: ""}", serverBuffer)
                send("CAP", listOf("END"))
            }
            cmd == "900" || cmd == "901" -> appendEvent(p.lastOrNull() ?: "", serverBuffer)
            cmd.length == 3 && cmd.all { it.isDigit() } -> {
                if (collectWhois(cmd, p) || collectModeList(cmd, p) || collectList(cmd, p)) return
                appendEvent(p.drop(1).joinToString(" "), serverBuffer, serverLogTag = m.tags["+ircfiber.com/phase"])
                if (cmd in JOIN_ERRORS && p.size > 1) {
                    val b = buffer(p[1])
                    if (b != null) {
                        announceJoins.remove(b.name.lowercase())
                        appendError(p.lastOrNull() ?: cmd, b)
                    }
                }
                routeChannelReply(cmd, p)
            }
            else -> appendEvent("${m.command} ${p.joinToString(" ")}", serverBuffer)
        }
    }

    /** Answers to the chat's channel commands (invite, topic) also show in that channel; the server log keeps its copy. */
    private fun routeChannelReply(code: String, p: List<String>) {
        when {
            code == "341" && p.size >= 3 -> { // RPL_INVITING <me> <nick> <channel>
                pendingInvites.remove(p[1].lowercase())
                buffer(p[2])?.let { appendEvent("Invited ${p[1]} to ${it.name}", it) }
            }
            code == "443" && p.size >= 3 -> { // ERR_USERONCHANNEL <me> <nick> <channel> :is already on channel
                pendingInvites.remove(p[1].lowercase())
                buffer(p[2])?.let { appendError("${p[1]} is already in ${it.name}", it) }
            }
            code == "442" || code == "482" -> { // ERR_NOTONCHANNEL / ERR_CHANOPRIVSNEEDED <me> <channel> :reason
                if (p.size >= 3) buffer(p[1])?.let { appendError(p.lastOrNull() ?: code, it) }
            }
            code == "401" && p.size >= 2 -> { // ERR_NOSUCHNICK <me> <nick> :No such nick/channel
                val invite = pendingInvites.remove(p[1].lowercase()) ?: return
                if (secondsBetween(invite.second, clock()) < INVITE_ANSWER_WINDOW) {
                    buffer(invite.first)?.let { appendError("No such nick: ${p[1]}", it) }
                }
            }
        }
    }

    private fun handleCap(m: IrcMessage) {
        val p = m.params
        if (p.size < 2) return
        when (p[1].uppercase()) {
            "LS" -> {
                val more = p.size >= 4 && p[2] == "*"
                for (tok in tokens(p.lastOrNull() ?: "", ' ')) {
                    tokens(tok, '=', limit = 2).firstOrNull()?.let { capsAdvertised.add(it) }
                }
                if (more) return
                val wanted = ArrayList(WANTED_CAPS)
                if (!settings.saslAccount.isNullOrEmpty()) wanted.add(0, "sasl")
                val req = wanted.filter { capsAdvertised.contains(it) }
                if (req.isEmpty()) send("CAP", listOf("END")) else send("CAP", listOf("REQ", req.joinToString(" ")))
            }
            "ACK" -> {
                for (tok in tokens(p.lastOrNull() ?: "", ' ')) {
                    _capsAcked.add(if (tok.startsWith("-")) tok.substring(1) else tok)
                }
                if (state == IrcState.REGISTERING) {
                    if (_capsAcked.contains("sasl") && !settings.saslAccount.isNullOrEmpty()) {
                        send("AUTHENTICATE", listOf("PLAIN"))
                    } else {
                        send("CAP", listOf("END"))
                    }
                }
            }
            "NAK" -> if (state == IrcState.REGISTERING) send("CAP", listOf("END"))
            else -> Unit // NEW / DEL ignored (cap-notify not requested)
        }
    }

    /**
     * `BATCH +ref chathistory <target>` / `BATCH -ref`. Only a batch for a buffer with our request in flight is
     * collected; others (attach replay) flow through [handlePrivmsg] as live lines.
     */
    private fun handleBatch(m: IrcMessage) {
        val p = m.params
        val first = p.firstOrNull() ?: return
        if (first.length <= 1) return
        val ref = first.substring(1)
        if (first.startsWith("+")) {
            if (p.size < 3 || p[1] != "chathistory") return
            val b = buffer(p[2]) ?: return
            if (!b.historyLoading || historyBatches.values.any { it.buffer === b }) return
            historyBatches[ref] = PendingHistory(b)
        } else if (first.startsWith("-")) {
            val h = historyBatches.remove(ref) ?: return
            val added = h.buffer.insertHistory(h.lines)
            h.buffer.historyLoading = false
            // Only an empty page proves there is nothing older. A full page that dedupes away (`LATEST` overlapping
            // the attach replay) still leaves `BEFORE <oldest>` to walk.
            if (h.lines.isEmpty()) h.buffer.historyExhausted = true
            notifyHistory(h.buffer, added)
        }
    }

    /** A line inside a requested history batch. Replayed CTCP is dropped, never answered. */
    private fun collectHistory(m: IrcMessage, ref: String) {
        if ((m.command != "PRIVMSG" && m.command != "NOTICE") || m.params.size < 2) return
        val sender = m.nick ?: "server"
        val msgid = m.tags["msgid"]
        val action = m.ctcpActionText
        val line = when {
            action != null ->
                IrcLine(m.time, sender, action, IrcLineKind.ACTION, msgid = msgid, replyTo = replyTo(m))
            m.command == "PRIVMSG" && m.params[1].startsWith("\u0001") -> return
            else -> IrcLine(
                m.time, sender, m.params[1],
                if (m.command == "NOTICE") IrcLineKind.NOTICE else IrcLineKind.MESSAGE,
                msgid = msgid, replyTo = replyTo(m),
            )
        }
        historyBatches[ref]?.lines?.add(line)
    }

    /**
     * `FAIL CHATHISTORY <code> <subcommand> <target> :<text>`. `MESSAGE_ERROR` is the bouncer's transient store
     * failure (retry on the next scroll); any other code stops paging.
     */
    private fun handleChatHistoryFail(m: IrcMessage) {
        val p = m.params
        val b = if (p.size >= 4) buffer(p[3]) else null
        if (b == null || !b.historyLoading) {
            appendEvent("${m.command} ${p.joinToString(" ")}", serverBuffer)
            return
        }
        b.historyLoading = false
        Log.i(TAG, "CHATHISTORY failed: ${p[1]}")
        if (p[1] != "MESSAGE_ERROR") b.historyExhausted = true
        notifyHistory(b, 0)
    }

    /**
     * `MARKREAD <target> timestamp=<server-time>|*` from the bouncer: another client (or the web UI) read `target` up
     * to that time. Advance-only; unknown buffers are ignored (never created).
     */
    private fun handleMarkRead(m: IrcMessage) {
        val p = m.params
        if (p.size < 2) return
        val b = buffer(p[0]) ?: return
        if (b.kind == IrcBufferKind.SERVER) return
        if (!p[1].startsWith("timestamp=")) return
        val t = IrcMessage.parseTime(p[1].substring("timestamp=".length)) ?: return
        val marker = b.readMarker
        if (marker != null && marker >= t) return
        b.readMarker = t
        recomputeUnread(b)
        notify { it.onBufferUpdated(this, b) }
    }

    private fun sendSaslPayload() {
        val acct = (settings.saslAccount ?: "").toByteArray(Charsets.UTF_8)
        val nul = byteArrayOf(0)
        val raw = acct + nul + acct + nul + (settings.saslPassword ?: "").toByteArray(Charsets.UTF_8)
        val b64 = Base64.getEncoder().encodeToString(raw)
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + SASL_CHUNK, b64.length)
            transport.send("AUTHENTICATE " + b64.substring(i, end))
            i = end
        }
        if (b64.length % SASL_CHUNK == 0) transport.send("AUTHENTICATE +")
    }

    /**
     * ERR_PASSWDMISMATCH: the bouncer token was revoked/rotated or the account no longer exists. The server closes
     * the link next; suppress the reconnect loop that would follow.
     */
    private fun handlePasswordRejected(m: IrcMessage) {
        Log.e(TAG, "server rejected the connection password")
        appendError(m.params.lastOrNull() ?: "Password rejected", serverBuffer)
        userDisconnected = true
        reconnectTimer?.cancel(); reconnectTimer = null
        notify { it.onCredentialsRejected(this) }
    }

    private fun handleWelcome(m: IrcMessage) {
        m.params.firstOrNull()?.let { nick = it }
        reconnectAttempt = 0
        nickTries = 0
        appendEvent(m.params.lastOrNull() ?: "Connected", serverBuffer)
        setState(IrcState.CONNECTED, null)
        // Attached to the bouncer: the engine session owns channel membership and replays JOIN/NAMES/history itself;
        // never JOIN on its behalf. The PING is answered after that replay (see `attachSynced`).
        if (settings.viaBouncer) {
            send("PING", listOf(ATTACH_SYNC_TOKEN))
            attachSyncTimer?.cancel()
            attachSyncTimer = after(attachSyncTimeout.toMillis()) { finishAttachSync() }
            return
        }
        attachSynced = true
        val toJoin = settings.autoJoinChannelList.toMutableList()
        // Channel buffers from a previous session (or pre-created via /join) are rejoined.
        for (b in _buffers) {
            if (b.kind == IrcBufferKind.CHANNEL && toJoin.none { it.equals(b.name, ignoreCase = true) }) toJoin.add(b.name)
        }
        for (chan in toJoin) {
            channelBuffer(chan)
            send("JOIN", listOf(chan))
        }
    }

    private fun handleNickInUse(m: IrcMessage) {
        val tried = if (m.params.size > 1) m.params[1] else wantedNick
        if (state != IrcState.REGISTERING) {
            appendError("Nickname $tried is already in use", serverBuffer)
            return
        }
        if (nickTries >= 3) {
            appendError("Nickname in use", serverBuffer)
            userDisconnected = true
            transport.disconnect()
            return
        }
        nickTries += 1
        wantedNick = tried + "_"
        send("NICK", listOf(wantedNick))
    }

    private fun handlePrivmsg(m: IrcMessage) {
        val p = m.params
        if (p.size < 2) return
        val target = p[0]
        val text = p[1]
        val sender = m.nick ?: "server"
        val notice = m.command == "NOTICE"
        val fromServer = m.prefix == null || (m.user == null && m.prefix.contains('.'))
        val b = when {
            isChannelName(target) -> channelBuffer(target)
            fromServer || (notice && m.user == null) || sender.startsWith("*") -> serverBuffer // *status etc. (bouncer)
            sender.equals(nick, ignoreCase = true) -> queryBuffer(target) // echo of our own PM
            else -> queryBuffer(sender)
        }
        // The bouncer answers a `CHATHISTORY` page and streams its attach replay independently; the second copy of a
        // row we already hold (same msgid) is noise, not a new message.
        val msgid = m.tags["msgid"]
        if (msgid != null && b.containsLine(msgid, m.time)) return
        val action = m.ctcpActionText
        val line = when {
            action != null -> IrcLine(m.time, sender, action, IrcLineKind.ACTION, msgid = msgid, replyTo = replyTo(m))
            !notice && text.startsWith("\u0001") -> {
                handleCtcp(text, m.nick)
                return
            }
            else -> IrcLine(
                m.time, sender, text, if (notice) IrcLineKind.NOTICE else IrcLineKind.MESSAGE,
                msgid = msgid, replyTo = replyTo(m),
                serverLogTag = if (notice) m.tags["+ircfiber.com/phase"] else null,
            )
        }
        clearTyping(sender, b)
        // `+draft/edit=<msgid>` replaces the held row; an edit of a row we don't hold shows as a new one.
        val original = m.tags["+draft/edit"]
        if (!original.isNullOrEmpty() && b.replaceText(original, line.text)) {
            notify { it.onLinesChanged(this, b) }
            return
        }
        append(line, b)
    }

    private fun handleCtcp(text: String, from: String?) {
        if (from == null) return
        val inner = text.trim('\u0001')
        if (inner == "VERSION") {
            send("NOTICE", listOf(from, "\u0001VERSION FiberIRC Android\u0001"))
        } else if (inner.startsWith("PING")) {
            send("NOTICE", listOf(from, "\u0001$inner\u0001"))
        }
        appendEvent("CTCP $inner from $from", serverBuffer)
    }

    /**
     * `+typing` from someone else (IRCv3 typing): `active` lapses in 6 s, `paused` in 30 s, `done` clears it. The
     * buffer is the channel, or the sender's open conversation for a TAGMSG to us; anything else is ignored.
     */
    private fun handleTagmsg(m: IrcMessage) {
        val sender = m.nick ?: return
        if (isOwn(sender)) return
        val target = m.params.firstOrNull() ?: return
        val typing = m.tags["+typing"]?.let { TypingState.fromWire(it) } ?: return
        val b = when {
            isChannelName(target) -> buffer(target)
            isOwn(target) -> buffer(sender)?.takeIf { it.kind == IrcBufferKind.QUERY }
            else -> null
        } ?: return
        val key = sender.lowercase()
        when (typing) {
            TypingState.ACTIVE -> b.typing[key] = clock().plusSeconds(TYPING_ACTIVE_LAPSE)
            TypingState.PAUSED -> b.typing[key] = clock().plusSeconds(TYPING_PAUSED_LAPSE)
            TypingState.DONE -> if (b.typing.remove(key) == null) return
        }
        notify { it.onTypingUpdated(this, b) }
    }

    /** A typer who speaks, leaves or is renamed stops typing. */
    private fun clearTyping(nick: String, b: IrcBuffer) {
        if (b.typing.remove(nick.lowercase()) == null) return
        notify { it.onTypingUpdated(this, b) }
    }

    /** `REDACT <target> <msgid> [reason]` (draft/message-redaction): the row stays as a tombstone. */
    private fun handleRedact(m: IrcMessage) {
        val p = m.params
        if (p.size < 2) return
        val b = if (!isChannelName(p[0]) && isOwn(p[0])) {
            m.nick?.let { buffer(it) } // a message to us, deleted by its sender
        } else {
            buffer(p[0]) // a channel, or our own message in a conversation
        }
        if (b == null || !b.redact(p[1])) return
        notify { it.onLinesChanged(this, b) }
    }

    /**
     * `INVITE <nick> <channel>`: an invite to us shows in the server buffer, and in the channel when it is open;
     * `invite-notify` copies of others' invites show in the channel only.
     */
    private fun handleInvite(m: IrcMessage) {
        val p = m.params
        if (p.size < 2) return
        val from = m.nick ?: "?"
        val chan = p[1]
        if (isOwn(p[0])) {
            val text = "$from invited you to $chan"
            appendEvent(text, serverBuffer)
            buffer(chan)?.let { appendEvent(text, it) }
        } else {
            buffer(chan)?.let { appendEvent("$from invited ${p[0]} to $chan", it) }
        }
    }

    /**
     * away-notify `AWAY [:message]`, account-notify `ACCOUNT <name|*>`, `CHGHOST <user> <host>` and
     * `SETNAME :<realname>` update the sender in every channel holding them; no line is shown.
     */
    private fun handleMemberDetail(m: IrcMessage) {
        val who = m.nick ?: return
        val p = m.params
        val change: (IrcMember) -> IrcMember
        when (m.command) {
            "AWAY" -> {
                val away = (p.firstOrNull() ?: "").isNotEmpty()
                change = { it.copy(away = away) }
            }
            "ACCOUNT" -> {
                val account = p.firstOrNull() ?: return
                change = { it.copy(account = if (account == "*") "" else account) }
            }
            "CHGHOST" -> {
                if (p.size < 2) return
                change = { it.copy(user = p[0], host = p[1]) }
            }
            "SETNAME" -> {
                val realname = p.lastOrNull() ?: return
                change = { it.copy(realname = realname) }
            }
            else -> return
        }
        for (b in _buffers.toTypedArray()) {
            if (b.kind == IrcBufferKind.CHANNEL && b.updateMember(who, change)) notifyMembers(b)
        }
    }

    private fun isOwn(n: String?): Boolean = n != null && n.equals(nick, ignoreCase = true)

    private fun handleJoin(m: IrcMessage) {
        val chan = m.params.firstOrNull() ?: return
        val who = m.nick ?: return
        val b = channelBuffer(chan)
        if (isOwn(who)) {
            val key = b.name.lowercase()
            b.joined = true
            b.setMembers(emptyList())
            if (!settings.viaBouncer || announceJoins.remove(key)) {
                appendEvent("You joined ${b.name}", b)
            } else {
                quietTopic.add(key)
            }
        } else {
            var member = IrcMember(nick = who, prefixes = "", user = m.user ?: "", host = m.host ?: "")
            if (m.params.size >= 3) { // extended-join: JOIN <channel> <account|*> :<realname>
                member = member.copy(
                    account = if (m.params[1] == "*") "" else m.params[1],
                    realname = m.params[2],
                )
            }
            b.addMember(member)
            appendEvent("$who joined", b, event = IrcEvent(IrcEventKind.JOIN, who))
            notifyMembers(b)
        }
    }

    private fun handlePart(m: IrcMessage) {
        val chan = m.params.firstOrNull() ?: return
        val b = buffer(chan) ?: return
        val who = m.nick ?: return
        val reason = if (m.params.size > 1) m.params[1] else ""
        if (isOwn(who)) {
            b.joined = false
            b.setMembers(emptyList())
            announceJoins.add(b.name.lowercase())
            quietTopic.remove(b.name.lowercase())
            appendEvent("You left ${b.name}", b)
        } else {
            b.removeMember(who)
            clearTyping(who, b)
            appendEvent(
                if (reason.isEmpty()) "$who left" else "$who left ($reason)", b,
                event = IrcEvent(IrcEventKind.PART, who, reason = reason.ifEmpty { null }),
            )
        }
        notifyMembers(b)
    }

    private fun handleKick(m: IrcMessage) {
        if (m.params.size < 2) return
        val b = buffer(m.params[0]) ?: return
        val victim = m.params[1]
        val reason = if (m.params.size > 2) m.params[2] else ""
        if (isOwn(victim)) {
            b.joined = false
            b.setMembers(emptyList())
            announceJoins.add(b.name.lowercase())
            quietTopic.remove(b.name.lowercase())
            appendError("You were kicked by ${m.nick ?: "?"} ($reason)", b)
        } else {
            b.removeMember(victim)
            clearTyping(victim, b)
            appendEvent(
                "$victim was kicked by ${m.nick ?: "?"} ($reason)", b,
                event = IrcEvent(IrcEventKind.KICK, m.nick ?: "?", target = victim, reason = reason.ifEmpty { null }),
            )
        }
        notifyMembers(b)
    }

    private fun handleQuit(m: IrcMessage) {
        val who = m.nick ?: return
        val reason = m.params.lastOrNull() ?: ""
        val event = IrcEvent(IrcEventKind.QUIT, who, reason = reason.ifEmpty { null })
        for (b in _buffers.toTypedArray()) {
            if (b.kind == IrcBufferKind.CHANNEL && b.removeMember(who)) {
                clearTyping(who, b)
                appendEvent("$who quit ($reason)", b, event = event)
                notifyMembers(b)
            } else if (b.kind == IrcBufferKind.QUERY && b.name.equals(who, ignoreCase = true)) {
                clearTyping(who, b)
                appendEvent("$who quit ($reason)", b, event = event)
            }
        }
    }

    /**
     * `005`: `PREFIX=(modes)chars` and `CHANMODES=A,B,C,D` drive [handleMode]; `TOPICLEN`, `EXCEPTS`, `INVEX` and
     * `CLIENTTAGDENY` are kept for the channel screens and [clientTagFeatures].
     */
    private fun handleIsupport(m: IrcMessage) {
        for (tok in m.params.drop(1)) {
            val kv = tokens(tok, '=', limit = 2)
            val key = kv.firstOrNull() ?: continue
            val value = if (kv.size == 2) kv[1] else ""
            when (key) {
                "EXCEPTS" -> exceptsMode = value.firstOrNull() ?: 'e'
                "INVEX" -> invexMode = value.firstOrNull() ?: 'I'
                "TOPICLEN" -> topicLength = value.toIntOrNull()
                "CLIENTTAGDENY" -> clientTagDeny = tokens(value, ',').map { it.trim(' ', '\t') }.filter { it.isNotEmpty() }
                "PREFIX" -> {
                    // "(ov)@+" — modes and chars pair up by position, highest rank first.
                    val close = value.indexOf(')')
                    if (!value.startsWith("(") || close < 0) continue
                    val modes = value.substring(1, close).toList()
                    val chars = value.substring(close + 1).toList()
                    if (modes.size != chars.size || modes.isEmpty()) continue
                    // A malformed or hostile 005 must not trap: a repeated mode letter keeps its first char.
                    val map = LinkedHashMap<Char, Char>()
                    for ((mode, ch) in modes.zip(chars)) map.putIfAbsent(mode, ch)
                    prefixModes = map
                    prefixOrder = chars
                }
                "CHANMODES" -> {
                    val groups = value.split(',')
                    if (groups.size < 4) continue
                    val types = HashMap<Char, Char>()
                    for ((group, type) in groups.zip(listOf('A', 'B', 'C', 'D'))) {
                        for (mode in group) types[mode] = type
                    }
                    chanModeTypes = types
                }
            }
        }
    }

    /**
     * Channel MODE: status changes (`+o nick`) are applied to the member list in place, `k`/`l`/flag changes to
     * `channelModes`, and the line carries every change as `event.modes`. Only a mode letter or nick we cannot place
     * falls back to a NAMES refresh.
     */
    private fun handleMode(m: IrcMessage) {
        val p = m.params
        val target = p.firstOrNull()
        val b = if (target != null && isChannelName(target)) buffer(target) else null
        if (b == null) {
            appendEvent("Mode: ${p.joinToString(" ")}", serverBuffer)
            return
        }
        val changes = ArrayList<IrcModeChange>()
        var changed = false
        var resync = false
        val args = ArrayDeque(p.drop(2))
        var adding = true
        letters@ for (mode in if (p.size >= 2) p[1] else "") {
            if (mode == '+') { adding = true; continue }
            if (mode == '-') { adding = false; continue }
            val prefix = prefixModes[mode]
            if (prefix != null) {
                val who = args.removeFirstOrNull()
                changes.add(IrcModeChange(adding, mode, who, prefix))
                val member = who?.let { b.member(it) }
                if (member == null) {
                    resync = true
                    continue
                }
                val want = member.prefixes.toHashSet()
                if (adding) want.add(prefix) else want.remove(prefix)
                // Server rank order first; any char outside PREFIX keeps its place after them.
                val prefixes = String(prefixOrder.filter { it in want }.toCharArray()) +
                    member.prefixes.filter { it !in prefixOrder && it in want }
                if (prefixes != member.prefixes) {
                    b.addMember(member.copy(prefixes = prefixes))
                    changed = true
                }
                continue
            }
            val type = chanModeTypes[mode]
            val arg: String? = when (type) {
                'A', 'B' -> args.removeFirstOrNull()
                'C' -> if (adding) args.removeFirstOrNull() else null
                'D' -> null
                else -> {
                    // An unknown letter's parameter count is unknown, so nothing after it can be placed.
                    changes.add(IrcModeChange(adding, mode, null))
                    resync = true
                    break@letters
                }
            }
            changes.add(IrcModeChange(adding, mode, arg))
            if (type != 'A') setChannelMode(mode, adding, arg, b)
        }
        appendEvent(
            "${m.nick ?: "server"} sets mode ${p.drop(1).joinToString(" ")}", b,
            event = IrcEvent(IrcEventKind.MODE, m.nick ?: "server", modes = changes),
        )
        if (resync) send("NAMES", listOf(b.name)) else if (changed) notifyMembers(b)
    }

    /** Records a type B/C/D channel mode in `channelModes`, and a set mode's parameter in `channelModeArgs`. */
    private fun setChannelMode(mode: Char, adding: Boolean, arg: String?, b: IrcBuffer) {
        b.channelModes = b.channelModes.filter { it != mode }
        b.channelModeArgs.remove(mode)
        if (!adding) return
        b.channelModes += mode
        if (arg != null) b.channelModeArgs[mode] = arg
    }

    /** RPL_CHANNELMODEIS `<me> <channel> +<modes> [args…]` replaces the channel's B/C/D modes. */
    private fun handleChannelModeIs(m: IrcMessage) {
        val p = m.params
        if (p.size < 3) return
        val b = buffer(p[1]) ?: return
        val args = ArrayDeque(p.drop(3))
        b.channelModes = ""
        b.channelModeArgs.clear()
        for (mode in p[2]) {
            if (mode == '+') continue
            val takesArg = chanModeTypes[mode] == 'B' || chanModeTypes[mode] == 'C'
            setChannelMode(mode, true, if (takesArg) args.removeFirstOrNull() else null, b)
        }
        notify { it.onBufferUpdated(this, b) }
    }

    private fun handleNick(m: IrcMessage) {
        val old = m.nick ?: return
        val new = m.params.lastOrNull() ?: return
        val own = isOwn(old)
        if (own) {
            nick = new
            appendEvent("You are now known as $new", serverBuffer)
        }
        for (b in _buffers.toTypedArray()) clearTyping(old, b)
        for (b in _buffers.toTypedArray()) {
            if (b.kind == IrcBufferKind.CHANNEL && b.renameMember(old, new)) {
                appendEvent(
                    "$old is now known as $new", b,
                    event = if (own) null else IrcEvent(IrcEventKind.NICK, new, oldNick = old),
                )
                notifyMembers(b)
            }
        }
    }

    private fun handleNames(m: IrcMessage) {
        val p = m.params
        if (p.size < 4) return
        val b = channelBuffer(p[2])
        val key = b.name.lowercase()
        val list = pendingNames.getOrPut(key) { ArrayList() }
        for (tok in tokens(p[3], ' ')) {
            val prefixes = tok.takeWhile { it in NAMES_PREFIX_CHARS }
            var n = tok.substring(prefixes.length)
            var user = ""
            var host = ""
            val bang = n.indexOf('!')
            if (bang >= 0) { // userhost-in-names: nick!user@host
                val uh = n.substring(bang + 1)
                val at = uh.indexOf('@')
                if (at >= 0) {
                    user = uh.substring(0, at)
                    host = uh.substring(at + 1)
                }
                n = n.substring(0, bang)
            }
            if (n.isNotEmpty()) list.add(IrcMember(nick = n, prefixes = prefixes, user = user, host = host))
        }
    }

    // endregion

    // region Collectors

    /** A numeric answering a pending `WHOIS`; false when none is pending for its nick. */
    private fun collectWhois(code: String, p: List<String>): Boolean {
        if (p.size < 2) return false
        val key = p[1].lowercase()
        val pending = pendingWhois[key] ?: return false
        var info = pending.info
        val last = p.lastOrNull() ?: ""
        when {
            code == "318" -> {
                finishWhois(key, null)
                return true
            }
            code == "401" || code == "402" -> { // `WHOIS nick nick` for a missing nick may name it as a server
                finishWhois(key, last)
                return true
            }
            code == "311" && p.size >= 6 -> // <me> <nick> <user> <host> * :<realname>
                info = info.copy(nick = p[1], user = p[2], host = p[3], realname = last)
            code == "312" && p.size >= 3 -> // <me> <nick> <server> :<info>
                info = info.copy(server = p[2], serverInfo = if (p.size > 3) last else "")
            code == "313" -> info = info.copy(operator = true)
            code == "317" && p.size >= 3 -> { // <me> <nick> <idle> [<signon>] :seconds idle…
                info = info.copy(idle = p[2].toDoubleOrNull())
                if (p.size >= 5) p[3].toDoubleOrNull()?.let { info = info.copy(signon = epoch(it)) }
            }
            code == "319" -> info = info.copy(channels = info.channels + tokens(last, ' '))
            code == "301" -> info = info.copy(away = last)
            code == "330" && p.size >= 3 -> info = info.copy(account = p[2])
            code == "338" && p.size >= 3 -> // <me> <nick> <host> [<ip>] :actually using host
                info = info.copy(actualHost = if (p.size > 3) p.subList(2, p.size - 1).joinToString(" ") else p[2])
            code == "378" -> { // <me> <nick> :is connecting from *@host ip
                if (info.actualHost == null) {
                    val prefix = "is connecting from "
                    info = info.copy(actualHost = if (last.startsWith(prefix)) last.substring(prefix.length) else last)
                }
            }
            code == "671" -> info = info.copy(secure = true)
            code == "276" -> Unit // client certificate fingerprint: consumed, not shown
            else -> return false
        }
        pending.info = info
        return true
    }

    private fun finishWhois(key: String, error: String?) {
        val w = pendingWhois.remove(key) ?: return
        w.timer?.cancel()
        if (error != null) {
            w.info = w.info.copy(error = error)
        } else if (w.info.user.isEmpty()) {
            w.info = w.info.copy(error = "No reply from the server")
        }
        for (c in w.completions) c(w.info)
    }

    /** The list mode a 367/368, 348/349 or 346/347 numeric belongs to. */
    private fun listMode(code: String): Char? = when (code) {
        "367", "368" -> 'b'
        "348", "349" -> exceptsMode ?: 'e'
        "346", "347" -> invexMode ?: 'I'
        else -> null
    }

    /** A numeric answering a pending [requestModeList]; false when none is pending for it. */
    private fun collectModeList(code: String, p: List<String>): Boolean {
        if (p.size < 3) return false
        val mode = listMode(code) ?: return false
        val key = "${p[1].lowercase()} $mode"
        val pending = pendingModeLists[key] ?: return false
        if (code == "368" || code == "349" || code == "347") {
            finishModeList(key)
            return true
        }
        // <me> <channel> <mask> [<setter> <set-at>]
        val setter = if (p.size >= 4) p[3].takeWhile { it != '!' } else null
        val setAt = if (p.size >= 5) p[4].toDoubleOrNull()?.let { epoch(it) } else null
        pending.entries.add(ModeListEntry(p[2], setter, setAt))
        return true
    }

    private fun finishModeList(key: String) {
        val l = pendingModeLists.remove(key) ?: return
        l.timer?.cancel()
        val entries = l.entries.toList()
        for (c in l.completions) c(entries)
    }

    /** A numeric answering a pending [listChannels]; false when none is pending. */
    private fun collectList(code: String, p: List<String>): Boolean {
        val l = pendingList ?: return false
        return when {
            code == "321" -> true // RPL_LISTSTART
            code == "323" -> { // RPL_LISTEND
                finishList()
                true
            }
            code == "322" && p.size >= 3 -> { // <me> <channel> <users> :<topic>
                if (l.total >= LIST_MAX_ENTRIES) return true
                l.page.add(ChannelListEntry(p[1], p[2].toIntOrNull() ?: 0, if (p.size > 3) p[3] else ""))
                l.total += 1
                if (l.page.size == LIST_PAGE_SIZE) {
                    l.onPage(l.page.toList())
                    l.page.clear()
                }
                true
            }
            else -> false
        }
    }

    /** Delivers the last partial page and completes the pending [listChannels]. */
    private fun finishList() {
        val l = pendingList ?: return
        pendingList = null
        if (l.page.isNotEmpty()) l.onPage(l.page.toList())
        l.completion()
    }

    // endregion

    // region Buffers

    private fun isChannelName(s: String): Boolean = s.startsWith("#") || s.startsWith("&")

    private fun channelBuffer(name: String): IrcBuffer {
        buffer(name)?.let { return it }
        val b = IrcBuffer(name, IrcBufferKind.CHANNEL)
        val at = _buffers.indexOfFirst { it.kind == IrcBufferKind.QUERY }.let { if (it < 0) _buffers.size else it }
        _buffers.add(at, b) // channels before queries
        notify { it.onBufferAdded(this, b) }
        return b
    }

    /** The conversation with `name`, opened (and announced to observers) when there is none yet. */
    fun queryBuffer(name: String): IrcBuffer {
        buffer(name)?.let { return it }
        val b = IrcBuffer(name, IrcBufferKind.QUERY)
        _buffers.add(b)
        notify { it.onBufferAdded(this, b) }
        return b
    }

    private fun appendEvent(text: String, b: IrcBuffer, event: IrcEvent? = null, serverLogTag: String? = null) {
        append(IrcLine(clock(), null, text, IrcLineKind.EVENT, event = event, serverLogTag = serverLogTag), b)
    }

    private fun appendError(text: String, b: IrcBuffer) {
        append(IrcLine(clock(), null, text, IrcLineKind.ERROR), b)
    }

    /**
     * A live line (never a history page): someone else's chat row is read at once when its buffer is on screen in
     * the active app (and reads automatically), else counted as unread, and as a highlight when [isHighlight] says
     * so and the buffer isn't muted.
     */
    private fun append(line: IrcLine, b: IrcBuffer) {
        b.append(line)
        if (isChat(line) && !isOwn(line.sender)) {
            val marker = b.readMarker
            if (b.onScreen && isAppActive() && autoMarksRead(b)) {
                markRead(b)
            } else if (marker == null || line.time > marker) {
                b.unreadCount += 1
                if (!isMuted(b) && isHighlight(line, nick)) b.highlightCount += 1
            }
        }
        notify { it.onLineAppended(this, b, line) }
    }

    private fun notifyMembers(b: IrcBuffer) {
        notify { it.onMembersUpdated(this, b) }
    }

    private fun notifyHistory(b: IrcBuffer, added: Int) {
        notify { it.onHistoryLoaded(this, b, added) }
        continueHistoryLoad(b)
    }

    private fun finishAttachSync() {
        attachSyncTimer?.cancel()
        attachSyncTimer = null
        if (attachSynced || state != IrcState.CONNECTED) return
        attachSynced = true
        notify { it.onAttachReplayFinished(this) }
    }

    private fun setState(s: IrcState, error: Throwable?) {
        val old = state
        _state.value = s
        Log.i(TAG, "state $old → $s${error?.let { ": ${describe(it)}" } ?: ""}")
        notify { it.onStateChanged(this, s, error) }
    }

    // endregion

    // region Transport events

    private fun onTransportEvent(e: ConnectionEvent) {
        when (e) {
            ConnectionEvent.Connected -> transportDidConnect()
            is ConnectionEvent.Line -> transportDidReceive(e.text)
            is ConnectionEvent.Disconnected -> transportDidDisconnect(e.cause)
        }
    }

    private fun transportDidConnect() {
        clearResumeProbe()
        setState(IrcState.REGISTERING, null)
        lastReceived = clock()
        startIdleTimer()
        send("CAP", listOf("LS", "302"))
        if (settings.serverPassword.isNotEmpty()) send("PASS", listOf(settings.serverPassword))
        send("NICK", listOf(wantedNick))
        send("USER", listOf(wantedNick, "0", "*", settings.realName.ifEmpty { wantedNick }))
    }

    private fun transportDidReceive(line: String) {
        lastReceived = clock()
        if (resuming) clearResumeProbe()
        IrcMessage.parse(line, clock())?.let { handle(it) }
    }

    private fun transportDidDisconnect(error: Throwable?) {
        val resumeDrop = resuming
        clearResumeProbe()
        stopIdleTimer()
        for (b in _buffers) if (b.kind == IrcBufferKind.CHANNEL) b.joined = false
        historyBatches.clear()
        // Nothing more will answer pending requests or refresh typing state on this link.
        for (key in pendingWhois.keys.toList()) finishWhois(key, "Disconnected")
        for (key in pendingModeLists.keys.toList()) finishModeList(key)
        finishList()
        modesRefreshedAt.clear()
        typingSent.clear()
        val loaders = historyLoaders.values.toList() // ended before the in-flight pages report, which would page on
        historyLoaders.clear()
        for (l in loaders) l.completion()
        for (b in _buffers.toTypedArray()) {
            if (b.typing.isNotEmpty()) {
                b.typing.clear()
                notify { it.onTypingUpdated(this, b) }
            }
        }
        for (b in _buffers.toTypedArray()) {
            if (b.historyLoading) {
                b.historyLoading = false
                notifyHistory(b, 0)
            }
        }
        appendEvent(error?.let { "Disconnected: ${describe(it)}" } ?: "Disconnected", serverBuffer)
        setState(IrcState.DISCONNECTED, error)
        if (!userDisconnected) {
            // A drop found right after foregrounding: the user is looking at the app, so retry at once. `resuming`
            // is already cleared, so a failed retry falls back to backoff.
            if (resumeDrop) {
                reconnectAttempt = 0
                connect()
            } else {
                scheduleReconnect()
            }
        }
    }

    // endregion

    // region Helpers

    /** One-shot timer on the client's dispatcher (iOS `Timer.scheduledTimer(repeats: false)`). */
    private fun after(millis: Long, block: () -> Unit): Job = scope.launch {
        delay(millis)
        block()
    }

    /** Splits at a space boundary so each piece is ≤ maxBytes of UTF-8 (never inside a grapheme cluster). */
    private fun chunk(text: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (utf8Length(rest, 0, rest.length) > maxBytes) {
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(rest)
            // The longest grapheme-aligned prefix that fits.
            var cut = 0
            var bytes = 0
            var next = bi.next()
            while (next != BreakIterator.DONE) {
                val add = utf8Length(rest, cut, next)
                if (bytes + add > maxBytes) break
                bytes += add
                cut = next
                next = bi.next()
            }
            val sp = rest.lastIndexOf(' ', cut - 1)
            val splitAt = if (sp > 0) sp else cut
            if (splitAt == 0) break // a single grapheme over the limit: send it whole
            out.add(rest.substring(0, splitAt))
            rest = rest.substring(splitAt).trimStart(' ')
            if (rest.isEmpty()) break
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    // endregion

    companion object {
        private const val TAG = "FiberIRC/irc"

        private val WANTED_CAPS = listOf(
            "server-time", "message-tags", "echo-message", "multi-prefix", "draft/read-marker", "batch",
            "away-notify", "account-notify", "extended-join", "userhost-in-names", "chghost", "setname",
            "invite-notify", "account-tag", "draft/message-redaction", "standard-replies",
        )

        /** An invite's ERR_NOSUCHNICK counts for this long (s); later ones are someone else's. */
        private const val INVITE_ANSWER_WINDOW = 30.0
        private const val IDLE_PING_AFTER = 60.0
        private const val IDLE_TIMEOUT_AFTER = 150.0
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val PRIVMSG_CHUNK_BYTES = 400
        private const val SASL_CHUNK = 400
        private const val HISTORY_PAGE_SIZE = 100
        private const val ATTACH_SYNC_TOKEN = "fiberirc-sync"
        private const val TYPING_ACTIVE_INTERVAL = 3.0
        private const val TYPING_ACTIVE_LAPSE = 6L
        private const val TYPING_PAUSED_LAPSE = 30L
        private const val MODE_REFRESH_INTERVAL = 60.0
        private const val COLLECTOR_TIMEOUT_MS = 10_000L
        private const val LIST_PAGE_SIZE = 50
        private const val LIST_MAX_ENTRIES = 5000

        /**
         * ISUPPORT `PREFIX=(modes)chars`: status mode letter → prefix char, and the prefix chars in rank order
         * (highest first). Defaults are the RFC pair; the server's 005 replaces them.
         */
        private val DEFAULT_PREFIX_MODES = mapOf('o' to '@', 'v' to '+')
        private val DEFAULT_PREFIX_ORDER = listOf('@', '+')

        /**
         * ISUPPORT `CHANMODES=A,B,C,D`: which channel modes take a parameter (A/B always, C only when set, D never).
         * Defaults are the RFC 1459 set.
         */
        private val DEFAULT_CHAN_MODE_TYPES = mapOf(
            'b' to 'A', 'k' to 'B', 'l' to 'C',
            'i' to 'D', 'm' to 'D', 'n' to 'D', 'p' to 'D', 's' to 'D', 't' to 'D',
        )

        private val JOIN_ERRORS = setOf("403", "404", "471", "473", "474", "475")
        private val NAMES_PREFIX_CHARS = setOf('!', '*', '~', '&', '@', '%', '+')

        /** Swift `Character.isNewline`: CRLF, LF, CR, VT, FF, NEL, LS, PS. */
        private val NEWLINES = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

        private val SIGNON_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

        /** App-wide production client: real socket, `ConnectivityManager` path monitor, process lifecycle. Main thread. */
        fun create(context: Context, settings: ConnectionSettings): IrcClient =
            IrcClient(settings, IrcConnection(), NetworkPathMonitor(context), ProcessForegroundMonitor())

        /** Whether `s` can be sent as a nickname: no spaces, no IRC delimiters or wildcards, and not starting like a
         *  channel, a mask or a trailing parameter. */
        fun isValidNick(s: String): Boolean {
            val first = s.firstOrNull() ?: return false
            if (first in "#&$:+~%@!" || s.length > 64) return false
            return s.none { it.isWhitespace() || it in ",*?!@\u0001" }
        }

        /** `*!*@<host>` when the host is known, else `<nick>!*@*`. */
        fun banMask(nick: String, host: String): String = if (host.isEmpty()) "$nick!*@*" else "*!*@$host"

        private fun isChat(l: IrcLine): Boolean =
            l.kind == IrcLineKind.MESSAGE || l.kind == IrcLineKind.ACTION || l.kind == IrcLineKind.NOTICE

        /** `+reply` (or the older `+draft/reply`) on a chat line. */
        private fun replyTo(m: IrcMessage): String? =
            (m.tags["+reply"] ?: m.tags["+draft/reply"])?.takeIf { it.isNotEmpty() }

        /** A NAMES row over what was known of the member: its nick, prefixes and any user/host it carries win. */
        private fun merge(fresh: IrcMember, old: IrcMember?): IrcMember {
            if (old == null) return fresh
            return old.copy(
                nick = fresh.nick,
                prefixes = fresh.prefixes,
                user = fresh.user.ifEmpty { old.user },
                host = fresh.host.ifEmpty { old.host },
            )
        }

        /** `/whois` output, one row per known detail. */
        private fun whoisLines(i: WhoisInfo): List<String> {
            val out = ArrayList<String>()
            if (i.user.isNotEmpty()) {
                out.add("${i.nick} is ${i.user}@${i.host}" + if (i.realname.isEmpty()) "" else " (${i.realname})")
            }
            i.account?.let { out.add("${i.nick} is logged in as $it") }
            if (i.channels.isNotEmpty()) out.add("${i.nick} is on ${i.channels.joinToString(" ")}")
            if (i.server.isNotEmpty()) {
                out.add("${i.nick} is connected to ${i.server}" + if (i.serverInfo.isEmpty()) "" else " (${i.serverInfo})")
            }
            if (i.operator) out.add("${i.nick} is an IRC operator")
            i.away?.let { out.add("${i.nick} is away: $it") }
            i.actualHost?.let { out.add("${i.nick} is connecting from $it") }
            if (i.secure) out.add("${i.nick} is using a secure connection")
            i.idle?.let { out.add("${i.nick} has been idle ${it.toInt()} s") }
            i.signon?.let { out.add("${i.nick} signed on ${SIGNON_FORMAT.format(it.atZone(ZoneId.systemDefault()))}") }
            return out.ifEmpty { listOf("${i.nick}: no details") }
        }

        /** Swift `split(separator:maxSplits:)` with empty pieces omitted. */
        private fun tokens(s: String, sep: Char, limit: Int = 0): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                while (i < s.length && s[i] == sep) i++
                if (i >= s.length) break
                if (limit > 0 && out.size == limit - 1) {
                    out.add(s.substring(i))
                    break
                }
                val j = s.indexOf(sep, i).let { if (it < 0) s.length else it }
                out.add(s.substring(i, j))
                i = j
            }
            return out
        }

        private fun epoch(seconds: Double): Instant = Instant.ofEpochMilli((seconds * 1000).toLong())

        private fun secondsBetween(from: Instant, to: Instant): Double = Duration.between(from, to).toMillis() / 1000.0

        private fun utf8Length(s: String, from: Int, to: Int): Int {
            var n = 0
            var i = from
            while (i < to) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }

        private fun describe(e: Throwable): String = e.message ?: e.toString()
    }
}
