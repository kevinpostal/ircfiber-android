package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.kevinpostal.fiberirc.data.irc.IrcEvent
import com.kevinpostal.fiberirc.data.irc.IrcEventKind
import com.kevinpostal.fiberirc.ui.theme.FiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.nickColor

/**
 * Port of iOS `MembershipSummary.swift`: phrases joins, parts, quits, nick changes, kicks and modes the way IRCCloud's
 * `CollapsedEvents` does: `→ a, b and c joined • ← d left • ⇐ e quit • old → new • mode: f (voiced)`. One event reads
 * the same way (`→ nick joined`, `← nick left (reason)`). Glyphs in `logArrow`, text in `logText`, nicks in their nick
 * colour when Colourise Nicknames is on.
 */
object MembershipSummary {
    private sealed interface Piece {
        val s: String

        data class Glyph(override val s: String) : Piece
        data class Text(override val s: String) : Piece
        data class Nick(override val s: String) : Piece
    }

    /** One nick's membership across the run, keyed by its lower-cased current nick (renames follow it). */
    private class Entry(var nick: String) {
        val firstNick = nick
        var first: IrcEventKind? = null
        var last: IrcEventKind? = null
        var reason: String? = null
    }

    /** Status mode letter → (added, removed) phrase. */
    private val userModes: Map<Char, Pair<String, String>> = mapOf(
        'q' to ("promoted to owner" to "demoted from owner"),
        'a' to ("promoted to admin" to "demoted from admin"),
        'o' to ("opped" to "de-opped"),
        'h' to ("promoted to half-op" to "demoted from half-op"),
        'v' to ("voiced" to "de-voiced"),
        'y' to ("given oper status" to "lost oper status"),
        'Y' to ("given oper status" to "lost oper status"),
    )

    /** List mode letter → (added, removed) verb. */
    private val listModes: Map<Char, Pair<String, String>> = mapOf(
        'b' to ("banned" to "un-banned"),
        'e' to ("exempted" to "un-exempted"),
        'I' to ("invite-exempted" to "un-invite-exempted"),
    )

    /** The summary as plain text (accessibility, tests). */
    fun text(events: List<IrcEvent>): String =
        sentences(events).joinToString(" • ") { sentence -> sentence.joinToString("") { it.s } }

    fun annotated(events: List<IrcEvent>, colors: FiberIrcColors, colouriseNicknames: Boolean): AnnotatedString =
        buildAnnotatedString {
            val text = SpanStyle(color = colors.logText)
            for ((i, sentence) in sentences(events).withIndex()) {
                if (i > 0) withStyle(text) { append(" • ") }
                for (piece in sentence) {
                    val style = when (piece) {
                        is Piece.Glyph -> SpanStyle(color = colors.logArrow)
                        is Piece.Text -> text
                        is Piece.Nick -> SpanStyle(
                            color = if (colouriseNicknames) nickColor(piece.s, colors.dark) else colors.logText,
                        )
                    }
                    withStyle(style) { append(piece.s) }
                }
            }
        }

    /** The glyph before an event line without a structured event (topic, your own join, server lines). */
    fun arrow(text: String): String = when {
        text.endsWith(" joined") || text.startsWith("You joined") -> "→"
        text.contains(" left") || text.contains(" kicked") -> "←"
        text.contains(" quit (") -> "⇐"
        text.startsWith("Topic:") || text.contains(" set the topic") || text.contains(" sets mode") -> "⚙"
        else -> ""
    }

    // region Building

    private fun sentences(events: List<IrcEvent>): List<List<Piece>> {
        val entries = HashMap<String, Entry>()
        val order = ArrayList<Entry>()
        val kicks = ArrayList<List<Piece>>()
        val modes = ModeMerge()

        fun entry(nick: String): Entry =
            entries[nick.lowercase()] ?: Entry(nick).also {
                entries[nick.lowercase()] = it
                order += it
            }

        for (ev in events) {
            when (ev.kind) {
                IrcEventKind.JOIN, IrcEventKind.PART, IrcEventKind.QUIT -> {
                    val e = entry(ev.nick)
                    if (e.first == null) e.first = ev.kind
                    e.last = ev.kind
                    e.reason = if (ev.kind == IrcEventKind.JOIN) null else ev.reason
                }
                IrcEventKind.NICK -> {
                    val old = ev.oldNick ?: continue
                    val e = entries.remove(old.lowercase()) ?: entry(old)
                    entries.remove(old.lowercase())
                    e.nick = ev.nick
                    entries[ev.nick.lowercase()] = e
                }
                IrcEventKind.KICK -> {
                    val s = mutableListOf<Piece>(
                        Piece.Glyph("← "), Piece.Nick(ev.target ?: ""), Piece.Text(" was kicked by "), Piece.Nick(ev.nick),
                    )
                    ev.reason?.let { s += Piece.Text(" ($it)") }
                    kicks += s
                }
                IrcEventKind.MODE -> modes.add(ev)
            }
        }

        val joined = ArrayList<Entry>()
        val left = ArrayList<Entry>()
        val quit = ArrayList<Entry>()
        val poppedIn = ArrayList<Entry>()
        val nippedOut = ArrayList<Entry>()
        val renames = ArrayList<List<Piece>>()
        for (e in order) {
            val first = e.first
            val last = e.last
            when {
                first == IrcEventKind.JOIN && last == IrcEventKind.JOIN -> joined += e
                first == IrcEventKind.JOIN && last != null -> poppedIn += e
                first != null && last == IrcEventKind.JOIN -> nippedOut += e
                first != null && last == IrcEventKind.QUIT -> quit += e
                first != null && last != null -> left += e
                else -> if (e.firstNick != e.nick) {
                    renames += listOf(Piece.Nick(e.firstNick), Piece.Glyph(" → "), Piece.Nick(e.nick))
                }
            }
        }

        val out = ArrayList<List<Piece>>()
        fun add(glyph: String, group: List<Entry>, verb: String, reasons: Boolean = false) {
            if (group.isEmpty()) return
            val s = mutableListOf<Piece>(Piece.Glyph("$glyph "))
            s += names(group.map { it.nick })
            s += Piece.Text(" $verb")
            val reason = group[0].reason
            if (reasons && group.size == 1 && !reason.isNullOrEmpty()) s += Piece.Text(" ($reason)")
            out += s
        }
        add("→", joined, "joined")
        add("←", left, "left", reasons = true)
        add("⇐", quit, "quit", reasons = true)
        add("↔", poppedIn, "popped in")
        add("↔", nippedOut, "nipped out")
        out += renames
        out += kicks
        out += modes.sentences()
        return out
    }

    /** "a", "a and b", "a, b and c". */
    private fun names(nicks: List<String>): List<Piece> {
        val out = ArrayList<Piece>()
        for ((i, nick) in nicks.withIndex()) {
            if (i > 0) out += Piece.Text(if (i == nicks.size - 1) " and " else ", ")
            out += Piece.Nick(nick)
        }
        return out
    }

    /** Mode changes merged across the run: `+x` then `-x` on the same target cancels out. */
    private class ModeMerge {
        private data class Change(val adding: Boolean, val mode: Char, val arg: String?)

        /** Status modes per target nick, in first-seen order. */
        private val users = ArrayList<Pair<String, MutableList<Change>>>()

        /** List modes (bans, exceptions, invite exceptions), with their setter. */
        private val lists = ArrayList<Pair<String, Change>>()

        /** Channel flags per setter, in first-seen order. */
        private val flags = ArrayList<Pair<String, MutableList<Change>>>()

        fun add(ev: IrcEvent) {
            for (m in ev.modes) {
                val change = Change(m.adding, m.mode, m.arg)
                val target = m.arg
                if (userModes.containsKey(m.mode) && target != null) {
                    val i = users.indexOfFirst { it.first.equals(target, ignoreCase = true) }
                    if (i >= 0) merge(change, users[i].second) else users += target to mutableListOf(change)
                } else if (listModes.containsKey(m.mode) && m.arg != null) {
                    val i = lists.indexOfFirst {
                        it.second.mode == m.mode && it.second.arg == m.arg && it.second.adding != m.adding
                    }
                    if (i >= 0) lists.removeAt(i) else lists += ev.nick to change
                } else {
                    val i = flags.indexOfFirst { it.first == ev.nick }
                    if (i >= 0) merge(change, flags[i].second) else flags += ev.nick to mutableListOf(change)
                }
            }
        }

        private fun merge(change: Change, changes: MutableList<Change>) {
            val i = changes.indexOfFirst { it.mode == change.mode && it.adding != change.adding }
            if (i >= 0) changes.removeAt(i) else changes += change
        }

        fun sentences(): List<List<Piece>> {
            val out = ArrayList<List<Piece>>()
            for ((nick, changes) in users) {
                if (changes.isEmpty()) continue
                val phrases = changes.mapNotNull { c -> userModes[c.mode]?.let { if (c.adding) it.first else it.second } }
                out += listOf(Piece.Text("mode: "), Piece.Nick(nick), Piece.Text(" (${phrases.joinToString(", ")})"))
            }
            for ((setter, c) in lists) {
                val verbs = listModes[c.mode] ?: continue
                val verb = if (c.adding) verbs.first else verbs.second
                val sign = if (c.adding) "+" else "-"
                out += listOf(Piece.Nick(setter), Piece.Text(" $verb ${c.arg ?: ""} ($sign${c.mode})"))
            }
            for ((setter, changes) in flags) {
                if (changes.isEmpty()) continue
                val letters = StringBuilder()
                var sign: Boolean? = null
                for (c in changes) {
                    if (c.adding != sign) {
                        letters.append(if (c.adding) "+" else "-")
                        sign = c.adding
                    }
                    letters.append(c.mode)
                }
                val args = changes.mapNotNull { it.arg }
                if (args.isNotEmpty()) letters.append(" ").append(args.joinToString(" "))
                out += listOf(Piece.Text("mode: $letters (set by "), Piece.Nick(setter), Piece.Text(")"))
            }
            return out
        }
    }

    // endregion
}
