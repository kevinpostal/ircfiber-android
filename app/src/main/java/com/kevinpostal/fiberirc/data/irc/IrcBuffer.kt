package com.kevinpostal.fiberirc.data.irc

import java.time.Instant

enum class IrcLineKind { MESSAGE, ACTION, NOTICE, EVENT, ERROR }
enum class IrcBufferKind { SERVER, CHANNEL, QUERY }

/**
 * What an `EVENT` row reports, structured so the chat can collapse and phrase it. Set on someone else's join,
 * part, quit, nick change or kick, and on every channel MODE line; your own joins, parts, nick changes and kicks
 * carry none and always show.
 */
enum class IrcEventKind { JOIN, PART, QUIT, NICK, KICK, MODE }

/**
 * One letter of a MODE line: `+o alice` is `(adding = true, mode = 'o', arg = "alice")`. `symbol` is the nick-list
 * prefix character a status mode carries (`o` → `@`, `v` → `+`, from ISUPPORT `PREFIX`); null for list modes
 * (`b`/`e`/`I`) and channel flags.
 */
data class IrcModeChange(val adding: Boolean, val mode: Char, val arg: String?, val symbol: Char? = null)

/**
 * `nick` is the actor: who joined, parted, quit or kicked, the new nick of a rename, the setter of a mode.
 * `oldNick` is a rename's old nick, `target` the kicked nick, `reason` a part/quit/kick reason.
 */
data class IrcEvent(
    val kind: IrcEventKind,
    val nick: String,
    val oldNick: String? = null,
    val target: String? = null,
    val reason: String? = null,
    val modes: List<IrcModeChange> = emptyList(),
)

/** Immutable (Swift value semantics): the buffer replaces a row with a `copy` when it is edited or redacted. */
data class IrcLine(
    val time: Instant,
    val sender: String?,
    /**
     * What the row shows. An IRCv3 `+draft/edit` replaces it (see [IrcBuffer.replaceText]). An encrypted row holds
     * its decrypted plaintext here only while [e2e] is readable; otherwise it is empty and the row shows a placeholder.
     */
    val text: String,
    val kind: IrcLineKind,
    /** IRCv3 `msgid` tag; dedupes paged CHATHISTORY rows against live/replayed ones. */
    val msgid: String? = null,
    /** Null on chat rows and on your own membership rows. */
    val event: IrcEvent? = null,
    /** The `msgid` this row answers (`+reply` / `+draft/reply`). */
    val replyTo: String? = null,
    /**
     * `+ircfiber.com/phase` from a bnc `*status` NOTICE, or `"motd"` for a real MOTD numeric: drives milestone
     * colour and the MOTD fold. Null for every ordinary line.
     */
    val serverLogTag: String? = null,
    /** Deleted by an IRCv3 `REDACT`; the row stays as a tombstone. */
    val redacted: Boolean = false,
    /** Replaced by an IRCv3 `+draft/edit`. */
    val edited: Boolean = false,
    /** End-to-end encryption state of an encrypted row (`!MLS1:`/`!PGP1:` body); null on every plaintext row. */
    val e2e: IrcE2e? = null,
    /** Your own encrypted send's progress; null once its bytes reached the IRC connection (and on every other row). */
    val send: IrcSendState? = null,
    /** Stable local row id: decryption results and send progress find the row by it (then by [msgid]). */
    val id: String = IrcLine.newId(),
) {
    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicLong()
        private val session = java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong())

        fun newId(): String = "r:$session:${nextId.incrementAndGet()}"
    }
}

/** The shared runtime's `E2eInfo.state` union. */
enum class IrcE2eState(val wire: String) {
    PENDING("pending"),
    VERIFIED("verified"),
    UNVERIFIED("unverified"),
    BEFORE_JOIN("before-join"),
    LOCKED("locked"),
    PARTIAL("partial"),
    EXPIRED("expired"),
    NOT_ADDRESSED("not-addressed"),
    ERROR("error"),
    ;

    /** The row's plaintext is available (verified, or decrypted from a sender whose identity isn't verified). */
    val readable: Boolean get() = this == VERIFIED || this == UNVERIFIED

    companion object {
        fun fromWire(s: String?): IrcE2eState? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Encryption details of one logical encrypted row. [wire] is this row's immutable ciphertext wire line (the final
 * chunk of a chunked message); [ciphertext] the complete logical ciphertext once the runtime reassembled it (what a
 * resubmission sends). [source] is what the runtime's normalized `IRCMessage` needs again on resubmission.
 */
data class IrcE2e(
    val state: IrcE2eState,
    val wire: String,
    val ciphertext: String? = null,
    val identity: String? = null,
    val epoch: Int? = null,
    val source: IrcE2eSource,
)

/** The wire facts of an encrypted row that its decryption depends on. */
data class IrcE2eSource(
    val command: String,
    val target: String,
    val nick: String,
    val account: String? = null,
    val prefix: String? = null,
    val hostmask: String? = null,
    val selfEcho: Boolean = false,
    val label: String? = null,
    val editOf: String? = null,
    /** Arrived live (attach replay included), so it counts toward unread/highlights; false on history pages. */
    val live: Boolean = true,
    /** Your own encrypted send's optimistic row: its plaintext came from the composer, not a decryption. */
    val own: Boolean = false,
)

/** Progress of your own encrypted send (shown on its optimistic row). */
sealed interface IrcSendState {
    /** Waiting for the encryption service, encrypting, or writing. */
    data object Sending : IrcSendState

    /** Nothing reached the wire; [IrcClient.retryEncryptedSend] tries again with fresh ciphertext. */
    data class Failed(val reason: String) : IrcSendState

    /** Some or all bytes may have been written before the link failed; never re-sent automatically. */
    data class Uncertain(val reason: String) : IrcSendState
}

data class IrcMember(
    val nick: String,
    /** e.g. `"@+"`. */
    val prefixes: String,
    /** From the JOIN prefix, `userhost-in-names`, `CHGHOST` or the site's member list; "" when unknown. */
    val user: String = "",
    val host: String = "",
    /** From `extended-join`, `SETNAME` or the site's member list; "" when unknown. */
    val realname: String = "",
    /** Services account (`extended-join`, `ACCOUNT`); "" when logged out or unknown. */
    val account: String = "",
    /** `away-notify`. */
    val away: Boolean = false,
) {
    /** Highest of `!`=7 `*`=6 `~`=5 `&`=4 `@`=3 `%`=2 `+`=1, else 0 (InspIRCd `PREFIX=(Yyqaohv)!*~&@%+`). */
    val rank: Int
        get() = prefixes.maxOfOrNull {
            when (it) {
                '!' -> 7
                '*' -> 6
                '~' -> 5
                '&' -> 4
                '@' -> 3
                '%' -> 2
                '+' -> 1
                else -> 0
            }
        } ?: 0
}

/** Ports `ios/IRC/IRCBuffer.swift`. Main-thread only, like the [IrcClient] that owns it. */
class IrcBuffer(val name: String, val kind: IrcBufferKind) {
    private val _lines = ArrayList<IrcLine>()
    private val _members = ArrayList<IrcMember>()

    /** Time-ordered. */
    val lines: List<IrcLine> get() = _lines

    /** Rank desc, then nick case-insensitive. */
    val members: List<IrcMember> get() = _members

    var topic: String? = null

    /** RPL_TOPICWHOTIME (333) or the last live `TOPIC`: who set the topic (nick only) and when. */
    var topicSetBy: String? = null
    var topicSetAt: Instant? = null

    /**
     * Channel mode letters without the `+` (RPL_CHANNELMODEIS 324 and live MODE lines), list and status modes
     * excluded; `channelModeArgs` holds the parameter of a set `k`/`l`-type mode.
     */
    var channelModes: String = ""
    val channelModeArgs: MutableMap<Char, String> = HashMap()

    /** RPL_CHANNELURL (328). */
    var channelURL: String? = null

    /** Unsent composer text, kept while another buffer is shown. */
    var draft: String = ""

    /** Chat rows from others newer than `readMarker` (see [IrcClient.recomputeUnread]). */
    var unreadCount: Int = 0

    /** The unread rows that mention you or a highlight word (never in a muted buffer). */
    var highlightCount: Int = 0

    /** Lower-cased nick → when their `+typing` state lapses. */
    val typing: MutableMap<String, Instant> = HashMap()

    /** URLs whose inline preview the user closed. */
    val hiddenMedia: MutableSet<String> = HashSet()

    /** Newest read timestamp (IRCv3 `draft/read-marker`, shared with the web UI via the bouncer). Null = never read. */
    var readMarker: Instant? = null
    var joined: Boolean = false

    /** A `CHATHISTORY` page request is in flight. */
    var historyLoading: Boolean = false

    /** The server has no rows older than `lines.first()`. */
    var historyExhausted: Boolean = false

    /**
     * Set by the buffer view while visible; the visible buffer isn't trimmed so paged-in history survives live
     * traffic. Trims on leaving the screen.
     */
    var onScreen: Boolean = false
        set(value) {
            field = value
            if (!value) trimToLimit()
        }

    /** Marks every held line with `msgid` deleted. Returns whether one was found. */
    fun redact(msgid: String): Boolean {
        var found = false
        for (i in _lines.indices) {
            if (_lines[i].msgid == msgid) {
                _lines[i] = _lines[i].copy(redacted = true)
                found = true
            }
        }
        return found
    }

    /** Replaces the text of the line with `msgid` and marks it edited. Returns whether one was found. */
    fun replaceText(msgid: String, text: String): Boolean {
        val i = _lines.indexOfLast { it.msgid == msgid }
        if (i < 0) return false
        _lines[i] = _lines[i].copy(text = text, edited = true)
        return true
    }

    /** The held row with stable [id], or null. */
    fun line(id: String): IrcLine? = _lines.firstOrNull { it.id == id }

    /** The newest held row with `msgid`, or null. */
    fun lineByMsgid(msgid: String): IrcLine? = _lines.lastOrNull { it.msgid == msgid }

    /** Replaces the row with stable [id] by `change(row)` in place. Returns the new row, or null when not held. */
    fun updateLine(id: String, change: (IrcLine) -> IrcLine): IrcLine? {
        val i = _lines.indexOfFirst { it.id == id }
        if (i < 0) return null
        val next = change(_lines[i])
        _lines[i] = next
        return next
    }

    /** Drops the row with stable [id]. Returns whether it was held. */
    fun removeLine(id: String): Boolean {
        val i = _lines.indexOfFirst { it.id == id }
        if (i < 0) return false
        _lines.removeAt(i)
        return true
    }

    /**
     * Drops every held line (`/clear`, Clear Backlog). Paging stays off until live lines arrive and trimming
     * re-opens it, so the cleared rows aren't fetched straight back.
     */
    fun clearLines() {
        _lines.clear()
        historyExhausted = true
    }

    /**
     * Inserts by `time`: bouncer playback can deliver server-time-tagged rows out of order (backfilled history
     * after newer lines), and the view assumes chronological order.
     */
    fun append(line: IrcLine) {
        insertSorted(line)
        if (!onScreen) trimToLimit()
    }

    /**
     * Merges a `CHATHISTORY` page, skipping rows already present (by `msgid`, else by time/sender/kind/text).
     * Never trims and leaves unread state alone: paged rows are old. Returns the number of rows inserted.
     */
    fun insertHistory(rows: List<IrcLine>): Int = insertHistoryRows(rows).size

    /** [insertHistory], returning the rows actually inserted (in page order). */
    fun insertHistoryRows(rows: List<IrcLine>): List<IrcLine> {
        val ids = HashSet<String>()
        val keys = HashSet<String>()
        for (l in _lines) {
            val id = l.msgid
            if (id != null) ids.add(id) else keys.add(dedupeKey(l))
        }
        val added = ArrayList<IrcLine>()
        for (row in rows) {
            val id = row.msgid
            if (id != null) {
                if (!ids.add(id)) continue
            } else if (keys.contains(dedupeKey(row))) {
                continue
            }
            insertSorted(row)
            added.add(row)
        }
        return added
    }

    /**
     * Whether a line with `msgid` is already held. Copies of one row carry its server time and `lines` is sorted by
     * time, so only the lines at `time` are compared.
     */
    fun containsLine(msgid: String, time: Instant): Boolean {
        var i = lineIndexAfter(time)
        while (i > 0 && _lines[i - 1].time == time) {
            i--
            if (_lines[i].msgid == msgid) return true
        }
        return false
    }

    /** Index of the first line newer than `time` (binary search over the time-sorted lines). */
    private fun lineIndexAfter(time: Instant): Int {
        var lo = 0
        var hi = _lines.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (_lines[mid].time <= time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun insertSorted(line: IrcLine) {
        val last = _lines.lastOrNull()
        if (last != null && line.time < last.time) _lines.add(lineIndexAfter(line.time), line) else _lines.add(line)
    }

    private fun trimToLimit() {
        if (_lines.size <= MAX_LINES) return
        _lines.subList(0, _lines.size - MAX_LINES).clear()
        historyExhausted = false // trimmed rows can be fetched again
    }

    private fun indexOf(nick: String): Int = _members.indexOfFirst { it.nick.equals(nick, ignoreCase = true) }

    fun member(nick: String): IrcMember? = indexOf(nick).let { if (it < 0) null else _members[it] }

    private fun sortMembers() {
        _members.sortWith { a, b ->
            if (a.rank != b.rank) b.rank.compareTo(a.rank) else String.CASE_INSENSITIVE_ORDER.compare(a.nick, b.nick)
        }
    }

    fun setMembers(list: List<IrcMember>) {
        _members.clear()
        _members.addAll(list)
        sortMembers()
    }

    fun addMember(m: IrcMember) {
        val i = indexOf(m.nick)
        if (i >= 0) _members[i] = m else _members.add(m)
        sortMembers()
    }

    fun removeMember(nick: String): Boolean {
        val i = indexOf(nick)
        if (i < 0) return false
        _members.removeAt(i)
        return true
    }

    fun renameMember(old: String, new: String): Boolean {
        val i = indexOf(old)
        if (i < 0) return false
        _members[i] = _members[i].copy(nick = new)
        sortMembers()
        return true
    }

    /**
     * Applies `change` to the member named `nick` (it must not change the nick or prefixes). Returns whether the
     * member exists and changed.
     */
    fun updateMember(nick: String, change: (IrcMember) -> IrcMember): Boolean {
        val i = indexOf(nick)
        if (i < 0) return false
        val m = change(_members[i])
        if (m == _members[i]) return false
        _members[i] = m
        return true
    }

    /**
     * Merges the site's member rows by nick: their non-empty user, host, real name and account fill in what IRC
     * hasn't told us. Prefixes stay IRC's.
     */
    fun applyDetails(rows: List<IrcMember>) {
        for (row in rows) {
            updateMember(row.nick) { m ->
                m.copy(
                    user = row.user.ifEmpty { m.user },
                    host = row.host.ifEmpty { m.host },
                    realname = row.realname.ifEmpty { m.realname },
                    account = row.account.ifEmpty { m.account },
                )
            }
        }
    }

    companion object {
        const val MAX_LINES = 2000

        /** Encrypted rows compare by their ciphertext: their shown text changes as decryption settles. */
        private fun dedupeKey(l: IrcLine): String = "${l.time}|${l.sender ?: ""}|${l.kind}|${l.e2e?.wire ?: l.text}"
    }
}

/** One compiled pattern per own nick (main thread only, like the views that ask). */
private var mentionPattern: Pair<String, Regex>? = null

/**
 * Web `checkHighlight` parity: someone else's chat line naming `nick` as a whole word, case-insensitively. Nick
 * characters (`A-Z a-z 0-9 _ [ ] { }`) don't count as word boundaries.
 */
fun IrcLine.mentions(nick: String): Boolean {
    if (nick.isEmpty() || (kind != IrcLineKind.MESSAGE && kind != IrcLineKind.ACTION && kind != IrcLineKind.NOTICE)) {
        return false
    }
    // Highlights come only from available plaintext: never from ciphertext or a placeholder.
    if (e2e != null && !e2e.state.readable) return false
    if (sender != null && sender.equals(nick, ignoreCase = true)) return false
    val cached = mentionPattern
    val regex = if (cached != null && cached.first == nick) {
        cached.second
    } else {
        val edge = "[a-zA-Z0-9_\\[\\]{}]"
        Regex("(?<!$edge)" + Regex.escape(nick) + "(?!$edge)", RegexOption.IGNORE_CASE).also { mentionPattern = nick to it }
    }
    return regex.containsMatchIn(IrcFormatting.strip(text))
}
