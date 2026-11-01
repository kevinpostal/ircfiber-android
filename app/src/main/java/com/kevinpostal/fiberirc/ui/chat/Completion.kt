package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcLine

enum class CompletionKind { NICK, CHANNEL, EMOJI }

/**
 * One completion chip (`ios/UI/Chat/Completion.swift` `Suggestion`).
 *
 * [label] is the chip text: the nick, the channel, or `"😄 :smile:"`. [insertText] replaces the completed word:
 * `"nick: "` at the start of the line, else `"nick "`; `"#chan "`; the emoji. [replaceRange] is the word's UTF-16
 * range in the field (`first` = start, `last + 1` = the cursor), i.e. `start until cursor`.
 */
data class CompletionSuggestion(
    val insertText: String,
    val label: String,
    val replaceRange: IntRange,
    val kind: CompletionKind = CompletionKind.NICK,
)

/**
 * IRCCloud's nick completion (`MainViewController` `updateSuggestions`, `NickCompletionView`): suggestions for the
 * word at the composer's cursor. Ports `ios/UI/Chat/Completion.swift`.
 */
object Completion {
    const val MAX_SUGGESTIONS = 50
    const val MAX_EMOJI = 20

    /** The word ending at the cursor: its UTF-16 range (`start until cursor`), its text, and whether it starts the text. */
    data class Word(val range: IntRange, val word: String, val atStart: Boolean)

    /**
     * The word ending at [cursor] (a UTF-16 offset into [text]). Null when the cursor is 0, out of range, or follows
     * whitespace/newline.
     */
    fun word(text: String, cursor: Int): Word? {
        if (cursor <= 0 || cursor > text.length) return null
        var start = cursor
        while (start > 0 && !isWhitespaceOrNewline(text[start - 1])) start--
        if (start >= cursor) return null
        return Word(start until cursor, text.substring(start, cursor), start == 0)
    }

    /** Senders of [lines], newest first, each once (case-insensitive). */
    fun recentSenders(lines: List<IrcLine>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (i in lines.indices.reversed()) {
            val sender = lines[i].sender
            if (sender.isNullOrEmpty() || !seen.add(sender.lowercase())) continue
            out.add(sender)
        }
        return out
    }

    /**
     * Suggestions for [word]:
     * - `:` and longer than 2 characters: emoji codes with that prefix (up to 20, sorted), inserting the emoji;
     * - otherwise, leading `@` ignored: a `#`/`&` word first gets the buffer's own name (a channel) and the network's
     *   [channels] with that prefix (deduplicated, case-insensitive); then members (and a conversation's partner)
     *   whose nick or real name has the prefix, case-insensitively, most recent sender ([recent], newest first)
     *   first and then alphabetically, never [ownNick].
     * At most 50.
     */
    fun suggestions(
        word: Word,
        buffer: IrcBuffer,
        channels: List<String>,
        ownNick: String,
        recent: List<String>,
    ): List<CompletionSuggestion> {
        val w = word.word
        val range = word.range
        if (w.startsWith(":")) {
            if (w.codePointCount(0, w.length) <= 2) return emptyList()
            val prefix = w.substring(1).lowercase()
            return EmojiCodes.map.keys.filter { it.startsWith(prefix) }.sorted().take(MAX_EMOJI).mapNotNull { code ->
                EmojiCodes.map[code]?.let { CompletionSuggestion(it, "$it :$code:", range, CompletionKind.EMOJI) }
            }
        }
        val prefix = (if (w.startsWith("@")) w.substring(1) else w).lowercase()
        if (prefix.isEmpty()) return emptyList()
        val out = ArrayList<CompletionSuggestion>()

        val first = prefix[0]
        if (first == '#' || first == '&') {
            val seen = HashSet<String>()
            val names = (if (buffer.kind == IrcBufferKind.CHANNEL) listOf(buffer.name) else emptyList()) + channels
            for (name in names) {
                if (name.lowercase().startsWith(prefix) && seen.add(name.lowercase())) {
                    out.add(CompletionSuggestion("$name ", name, range, CompletionKind.CHANNEL))
                }
            }
        }

        val candidates = buffer.members.mapTo(ArrayList()) { it.nick to it.realname }
        if (buffer.kind == IrcBufferKind.QUERY && buffer.member(buffer.name) == null) {
            candidates.add(buffer.name to "")
        }
        val rank = HashMap<String, Int>()
        recent.forEachIndexed { i, nick -> rank.putIfAbsent(nick.lowercase(), i) }
        val nicks = candidates
            .filter { !it.first.equals(ownNick, ignoreCase = true) }
            .filter { it.first.lowercase().startsWith(prefix) || it.second.lowercase().startsWith(prefix) }
            .map { it.first }
            .sortedWith { a, b ->
                val ra = rank[a.lowercase()] ?: Int.MAX_VALUE
                val rb = rank[b.lowercase()] ?: Int.MAX_VALUE
                if (ra != rb) ra.compareTo(rb) else String.CASE_INSENSITIVE_ORDER.compare(a, b)
            }
        for (nick in nicks) {
            out.add(CompletionSuggestion(nick + if (word.atStart) ": " else " ", nick, range, CompletionKind.NICK))
        }
        return out.take(MAX_SUGGESTIONS)
    }

    /**
     * The chips for the field's cursor, or none with a selection or no word there (`ComposerView.updateSuggestions`).
     * [channels] = the network's channel buffer names; [ownNick] = the client's current nick.
     */
    fun suggestions(
        value: TextFieldValue,
        buffer: IrcBuffer,
        channels: List<String>,
        ownNick: String,
    ): List<CompletionSuggestion> {
        if (!value.selection.collapsed) return emptyList()
        val w = word(value.text, value.selection.start) ?: return emptyList()
        return suggestions(w, buffer, channels, ownNick, recentSenders(buffer.lines))
    }

    /** `CharacterSet.whitespacesAndNewlines`: Unicode Z* plus U+0009–U+000D and U+0085. */
    private fun isWhitespaceOrNewline(c: Char): Boolean =
        c == '\u0085' || (c.isWhitespace() && c !in '\u001C'..'\u001F')
}

/** Hardware Tab: the chip after [selectedIndex] (the first when null), wrapping; -1 when [current] is empty. */
fun cycleCompletion(current: List<CompletionSuggestion>, selectedIndex: Int?): Int {
    if (current.isEmpty()) return -1
    return selectedIndex?.let { (it + 1) % current.size } ?: 0
}

/** [value] with [range] (UTF-16, `start until end`) replaced by [insertText] and the cursor after it. */
fun applyCompletion(value: TextFieldValue, range: IntRange, insertText: String): TextFieldValue {
    val start = range.first.coerceIn(0, value.text.length)
    val end = (range.last + 1).coerceIn(start, value.text.length)
    val text = value.text.replaceRange(start, end, insertText)
    return TextFieldValue(text, TextRange(start + insertText.length))
}

/**
 * The composer's completion state (`ComposerView.completionRange` + `CompletionStrip.selection`), owned by the chat
 * screen alongside the field's [TextFieldValue]. Wiring:
 * - `ComposerBar(onValueChange = { v -> completion.onEdit(value, v, suggest); value = v }, ...)`
 * - `suggestions = completion.suggestions`, `selectedSuggestion = completion.selection`
 * - `onTabCycle = { completion.tab(value, suggest)?.let { value = it } }`
 * - `onPickSuggestion = { value = completion.pick(value, it, suggest) }`
 * where `suggest` is `{ word -> Completion.suggestions(word, buffer, channels, client.nick, Completion.recentSenders(buffer.lines)) }`.
 * A completion's own edit doesn't recompute the chips, so the next Tab keeps cycling.
 */
class ComposerCompletion {
    var suggestions: List<CompletionSuggestion> by mutableStateOf(emptyList())
        private set

    /** The Tab selection; null until the first Tab. */
    var selection: Int? by mutableStateOf(null)
        private set

    /** The word being completed, or the text the last Tab put in its place (the next Tab replaces it). */
    var range: IntRange? = null
        private set

    /** Recomputes the chips for the word at the cursor (`updateSuggestions`); the selection starts over. */
    fun refresh(value: TextFieldValue, suggest: (Completion.Word) -> List<CompletionSuggestion>) {
        val w = if (value.selection.collapsed) Completion.word(value.text, value.selection.start) else null
        range = w?.range
        suggestions = w?.let(suggest) ?: emptyList()
        selection = null
    }

    /** The user typed or moved the cursor: recompute when the text or selection actually changed. */
    fun onEdit(old: TextFieldValue, new: TextFieldValue, suggest: (Completion.Word) -> List<CompletionSuggestion>) {
        if (old.text != new.text || old.selection != new.selection) refresh(new, suggest)
    }

    /** Whether Tab has something to complete (`canComplete`). */
    fun canComplete(value: TextFieldValue): Boolean =
        suggestions.isNotEmpty() || Completion.word(value.text, value.selection.start) != null

    /**
     * Hardware Tab (`completeWithTab`): the next chip's text replaces the word (or the previous Tab's choice). Null
     * when there's nothing to complete. The chips stay until the user edits or moves the cursor.
     */
    fun tab(value: TextFieldValue, suggest: (Completion.Word) -> List<CompletionSuggestion>): TextFieldValue? {
        if (suggestions.isEmpty()) refresh(value, suggest)
        val r = range ?: return null
        val next = cycleCompletion(suggestions, selection)
        if (next < 0) return null
        selection = next
        val s = suggestions[next]
        range = r.first until r.first + s.insertText.length
        return applyCompletion(value, r, s.insertText)
    }

    /** A tapped chip replaces the word, then the chips are recomputed. */
    fun pick(
        value: TextFieldValue,
        s: CompletionSuggestion,
        suggest: (Completion.Word) -> List<CompletionSuggestion>,
    ): TextFieldValue {
        val r = range ?: return value
        val out = applyCompletion(value, r, s.insertText)
        refresh(out, suggest)
        return out
    }

    /** The field was emptied after a send (`clear()`). */
    fun clear() {
        range = null
        suggestions = emptyList()
        selection = null
    }
}
