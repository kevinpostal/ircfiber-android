package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.ui.graphics.Color
import com.kevinpostal.fiberirc.data.irc.ArtRow
import com.kevinpostal.fiberirc.data.irc.IrcEvent
import com.kevinpostal.fiberirc.data.irc.IrcEventKind
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.irc.MediaItem
import com.kevinpostal.fiberirc.ui.theme.FiberIrcDarkColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Port of iOS `UI/Chat/ChatRows.swift`: the pure row pipeline behind the chat's LazyColumn.

/** A run of joins, parts, quits, nick changes and modes shown as one row (IRCCloud `CollapsedEvents`). */
data class MembershipGroup(
    /** The first line's msgid, else its time and nick: the key of [ChatRowsInput.expanded]. */
    val id: String,
    val lines: List<IrcLine>,
) {
    val events: List<IrcEvent> get() = lines.mapNotNull { it.event }
}

/** A run of consecutive MOTD lines (tagged `"motd"`: 375/372/376/422) shown as one foldable row. */
data class MotdFoldGroup(val id: String, val lines: List<IrcLine>)

/** One row of the chat list (IRCCloud `EventsTableView`). */
sealed class ChatRow {
    /** A line; [header] starts a group (avatar and author line). */
    data class Line(val line: IrcLine, val header: Boolean) : ChatRow()

    /** Comes before the first row of each local calendar day. */
    data class Day(val date: LocalDate) : ChatRow()

    /**
     * The last-seen position: before the first message newer than the read marker when the chat opened.
     * [since] is that read marker.
     */
    data class NewMessages(val since: Instant) : ChatRow()

    /** A collapsed run; when [expanded], its lines follow as [Line] rows. */
    data class Membership(val group: MembershipGroup, val expanded: Boolean) : ChatRow()

    /** A collapsed run of MOTD lines; when [expanded], its lines follow as [Line] rows. */
    data class MotdFold(val group: MotdFoldGroup, val expanded: Boolean) : ChatRow()

    data class Media(val item: MediaItem, val parent: IrcLine) : ChatRow()

    /** "N replies: a, b" under a parent whose replies are collapsed. */
    data class Replies(val parentMsgid: String, val count: Int, val nicks: List<String>) : ChatRow()

    /** One row of a block-art picture. */
    data class Art(val line: IrcLine, val layout: ArtLayout) : ChatRow()

    /**
     * What keeps a row recognisable across rebuilds (history pages inserted above): the line's msgid, else its
     * time, kind and sender; a separator's date or group. Use as the LazyColumn item key.
     */
    val anchorKey: String
        get() = when (this) {
            is Line -> "l|" + lineKey(line)
            is Art -> "l|" + lineKey(line)
            is Day -> "d|$date"
            is NewMessages -> "new"
            is Membership -> "m|" + group.id
            is MotdFold -> "mf|" + group.id
            is Media -> "media|${lineKey(parent)}|$item"
            is Replies -> "r|$parentMsgid"
        }

    companion object {
        /** A line's identity: its msgid, else `"$time|$kind|$sender"`. */
        fun lineKey(l: IrcLine): String = l.msgid ?: "${l.time}|${l.kind}|${l.sender ?: ""}"
    }
}

/** [ChatRow.anchorKey] as a function (LazyColumn `key = ::anchorKey`). */
fun anchorKey(row: ChatRow): String = row.anchorKey

/** What [ChatRows.build] turns into rows: the buffer's lines and the chat's settings. */
data class ChatRowsInput(
    val lines: List<IrcLine>,
    /** The read marker when the chat opened with unread rows; null: no "New messages" row. */
    val unreadSince: Instant? = null,
    val openedAt: Instant = Instant.now(),
    /** Show Joins, Parts, Quits: off drops joins, parts, quits and nick changes (kicks and modes stay). */
    val showJoinPart: Boolean = true,
    /** Collapse Joins, Parts, Quits. */
    val collapse: Boolean = true,
    /** Ids of the groups the user expanded. */
    val expanded: Set<String> = emptySet(),
    val isBlocked: (String) -> Boolean = { false },
    /** Show deleted messages (as tombstones). */
    val showDeleted: Boolean = false,
    /** Collapse Replies: replies to a visible parent become its count row. */
    val replyCollapse: Boolean = false,
    /** A thread's parent msgid: only the parent and its replies. */
    val thread: String? = null,
    /** Inline previews under a message or notice (filtered for closed previews by the caller). */
    val media: (IrcLine) -> List<MediaItem> = { emptyList() },
    /** Nicknames on Separate Line: off makes every row a non-header (the nick leads each line instead). */
    val separateNickLine: Boolean = true,
    /** Calendar zone for day rows (iOS `Calendar.current`). */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Default art text colour (the theme's `chatText`): uncoloured/reversed art cells resolve against it. */
    val artChatText: Color = FiberIrcDarkColors.chatText,
)

/** Builds the chat's rows from its lines (pure; IRCCloud `EventsTableView` grouping, day rows and collapsed events). */
object ChatRows {
    private val membershipKinds = setOf(IrcEventKind.JOIN, IrcEventKind.PART, IrcEventKind.QUIT, IrcEventKind.NICK)
    private val collapsibleKinds =
        setOf(IrcEventKind.JOIN, IrcEventKind.PART, IrcEventKind.QUIT, IrcEventKind.NICK, IrcEventKind.MODE)

    private class ReplyEntry(var count: Int = 0, val nicks: MutableList<String> = ArrayList())

    fun build(input: ChatRowsInput): List<ChatRow> {
        var lines = input.lines
        val thread = input.thread
        if (thread != null) lines = lines.filter { it.msgid == thread || it.replyTo == thread }
        lines = lines.filter { l ->
            val sender = l.sender
            if (sender != null && input.isBlocked(sender)) return@filter false
            val kind = l.event?.kind
            if (!input.showJoinPart && kind != null && kind in membershipKinds) return@filter false
            !l.redacted || input.showDeleted
        }

        // Collapse Replies: a reply to a parent that is itself shown becomes part of that parent's count row.
        val replies = HashMap<String, ReplyEntry>()
        if (input.replyCollapse && thread == null) {
            val all = lines.mapNotNullTo(HashSet()) { it.msgid }
            val parents = lines.filter { l -> l.replyTo?.let { it !in all } ?: true }.mapNotNullTo(HashSet()) { it.msgid }
            lines = lines.filter { l ->
                val parent = l.replyTo
                if (parent == null || parent !in parents || parent == l.msgid) return@filter true
                val entry = replies.getOrPut(parent) { ReplyEntry() }
                entry.count += 1
                val nick = l.sender
                if (nick != null && entry.nicks.none { it.equals(nick, ignoreCase = true) }) entry.nicks += nick
                false
            }
        }

        val art = artLayouts(lines, input.artChatText)
        val rows = ArrayList<ChatRow>()
        var day: LocalDate? = null
        var dividerPending = input.unreadSince != null
        // The previous `Line` row of the current run; separators reset it, media rows don't.
        var previous: IrcLine? = null
        fun dayOf(l: IrcLine): LocalDate = l.time.atZone(input.zone).toLocalDate()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val lineDay = dayOf(l)
            if (lineDay != day) {
                rows += ChatRow.Day(lineDay)
                day = lineDay
                previous = null
            }
            val since = input.unreadSince
            if (dividerPending && since != null && art[i] == null && isChat(l) && l.time > since &&
                l.time <= input.openedAt
            ) {
                rows += ChatRow.NewMessages(since)
                dividerPending = false
                previous = null
            }
            val layout = art[i]
            if (layout != null) {
                rows += ChatRow.Art(l, layout)
                previous = null
                i += 1
                continue
            }
            val kind = l.event?.kind
            if (kind != null && kind in collapsibleKinds) {
                var end = i + 1
                while (end < lines.size) {
                    val next = lines[end].event?.kind ?: break
                    if (next !in collapsibleKinds || dayOf(lines[end]) != lineDay) break
                    end += 1
                }
                val run = lines.subList(i, end)
                if (input.collapse && run.size >= 2) {
                    val group = MembershipGroup(groupId(l), run.toList())
                    val expanded = group.id in input.expanded
                    rows += ChatRow.Membership(group, expanded)
                    previous = null
                    if (!expanded) {
                        i = end
                        continue
                    }
                }
                run.mapTo(rows) { ChatRow.Line(it, header = false) } // events are never headers
                previous = run.last()
                i = end
                continue
            }
            if (l.serverLogTag == "motd") {
                var end = i + 1
                while (end < lines.size && lines[end].serverLogTag == "motd" && dayOf(lines[end]) == lineDay) end += 1
                val run = lines.subList(i, end)
                if (run.size >= 2) {
                    val group = MotdFoldGroup(groupId(l), run.toList())
                    val expanded = group.id in input.expanded
                    rows += ChatRow.MotdFold(group, expanded)
                    previous = null
                    if (!expanded) {
                        i = end
                        continue
                    }
                }
                run.mapTo(rows) { ChatRow.Line(it, header = false) }
                previous = run.last()
                i = end
                continue
            }
            rows += ChatRow.Line(l, isHeader(l, previous, input.separateNickLine))
            previous = l
            if (l.kind == IrcLineKind.MESSAGE || l.kind == IrcLineKind.NOTICE) {
                input.media(l).take(2).mapTo(rows) { ChatRow.Media(it, l) }
            }
            val id = l.msgid
            val r = id?.let { replies[it] }
            if (id != null && r != null) {
                rows += ChatRow.Replies(id, r.count, r.nicks.toList())
                previous = null
            }
            i += 1
        }
        return rows
    }

    /**
     * A message or notice starts a group unless the previous line row is the same kind from the same sender
     * (IRCCloud: no time window). Actions never do; nor does anything with Nicknames on Separate Line off.
     */
    fun isHeader(l: IrcLine, previous: IrcLine?, separateNickLine: Boolean): Boolean {
        if (!separateNickLine || (l.kind != IrcLineKind.MESSAGE && l.kind != IrcLineKind.NOTICE)) return false
        val p = previous ?: return true
        if (p.kind != l.kind) return true
        val a = p.sender ?: return true
        val b = l.sender ?: return true
        return !a.equals(b, ignoreCase = true)
    }

    private fun isChat(l: IrcLine): Boolean =
        l.kind == IrcLineKind.MESSAGE || l.kind == IrcLineKind.ACTION || l.kind == IrcLineKind.NOTICE

    private fun groupId(l: IrcLine): String = l.msgid ?: "${l.time}|${l.event?.nick ?: ""}"

    /**
     * Art lines are messages/notices whose text is block art. A block is a maximal run of consecutive art lines with
     * the same sender and kind; its rows share the widest row's column count.
     */
    private fun artLayouts(lines: List<IrcLine>, chatText: Color): List<ArtLayout?> {
        val rows: List<ArtRow?> = lines.map { line ->
            if (line.kind == IrcLineKind.MESSAGE || line.kind == IrcLineKind.NOTICE) {
                IrcFormatting.artRow(line.text, chatText, ArtBackground)
            } else {
                null
            }
        }
        val out = arrayOfNulls<ArtLayout>(lines.size)
        var start = 0
        while (start < lines.size) {
            if (rows[start] == null) {
                start += 1
                continue
            }
            var end = start + 1
            while (end < lines.size && rows[end] != null &&
                lines[end].sender == lines[start].sender && lines[end].kind == lines[start].kind
            ) end += 1
            var columns = 0
            for (k in start until end) columns = maxOf(columns, rows[k]?.columns ?: 0)
            for (k in start until end) {
                val row = rows[k] ?: continue
                out[k] = ArtLayout(row, columns, blockStart = k == start)
            }
            start = end
        }
        return out.asList()
    }
}

/** How the list follows a rebuild: insert rows at the end, replace the last row and what follows it, or reload. */
sealed class ChatRowDiff {
    data object Reload : ChatRowDiff()

    /** [rows] were added after every old row. */
    data class Append(val rows: List<ChatRow>) : ChatRowDiff()

    /** The old row at [from] (the last one) changed; [rows] are the new rows from that index on. */
    data class ReplaceTail(val from: Int, val rows: List<ChatRow>) : ChatRowDiff()

    companion object {
        /**
         * Port of `ChatRowDiff.apply`: every old row but the last must be unchanged (row equality, so an edit or
         * redaction higher up reloads); then either the last is unchanged too (append) or it and what follows are
         * replaced.
         */
        fun diff(old: List<ChatRow>, new: List<ChatRow>): ChatRowDiff {
            if (old.isEmpty() || new.size < old.size) return Reload
            val last = old.size - 1
            for (k in 0 until last) if (new[k] != old[k]) return Reload
            return if (new[last] == old[last]) {
                Append(new.subList(old.size, new.size).toList())
            } else {
                ReplaceTail(last, new.subList(last, new.size).toList())
            }
        }
    }
}

/** [ChatRowDiff.diff] as a top-level function. */
fun diff(old: List<ChatRow>, new: List<ChatRow>): ChatRowDiff = ChatRowDiff.diff(old, new)
