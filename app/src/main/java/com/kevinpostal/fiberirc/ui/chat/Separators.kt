package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.ui.theme.Grid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Port of iOS `UI/Chat/SeparatorCells.swift` and the chat's history header (`BufferViewController.historyHeader`).

/** Day separator text: the locale's `"EEEEMMMMdyyyy"` skeleton ("Monday, October 27, 2026"). */
object DayText {
    private val icuCache = HashMap<String, android.icu.text.DateFormat?>()

    fun format(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val icu = icuCache.getOrPut(locale.toLanguageTag()) {
            runCatching { android.icu.text.DateFormat.getInstanceForSkeleton("EEEEMMMMdyyyy", locale) }.getOrNull()
        }
        if (icu != null) {
            val cal = android.icu.util.Calendar.getInstance(locale)
            cal.clear()
            cal.set(date.year, date.monthValue - 1, date.dayOfMonth)
            icu.format(cal.time)?.let { return it }
        }
        // ICU missing (JVM unit tests): the locale's full date.
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
    }
}

/** The date before each day's first row (IRCCloud: "Monday, October 27, 2026" between hairlines). */
@Composable
fun DaySeparator(date: LocalDate, style: ChatRowStyle, modifier: Modifier = Modifier) {
    val c = style.colors
    val text = remember(date) { DayText.format(date) }
    Column(
        modifier
            .fillMaxWidth()
            .background(c.background)
            .heightIn(min = 28.dp)
            .semantics { heading(); contentDescription = text },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDark))
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = Grid.margin, vertical = Grid.unit),
            style = ChatRowStyle.caption,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderDark))
    }
}

/** "——— New messages ———": the last-seen position (SeenDivider.svelte), a rule behind a label whose backing cuts it. */
@Composable
fun NewMessagesDivider(style: ChatRowStyle, modifier: Modifier = Modifier) {
    val c = style.colors
    Box(
        modifier.fillMaxWidth().background(c.background).padding(vertical = Grid.margin).testTag("newMessages"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Grid.margin).height(1.dp).background(c.seenDividerLine))
        Text(
            "New messages",
            Modifier.background(c.background).padding(horizontal = Grid.gutter),
            style = ChatRowStyle.caption,
            color = c.seenDividerText,
        )
    }
}

/** "1 reply: alice" / "3 replies: alice, bob" (iOS `RepliesCell`). */
fun repliesText(count: Int, nicks: List<String>): String {
    val names = nicks.joinToString(", ")
    return if (count == 1) "1 reply: $names" else "$count replies: $names"
}

/** "3 replies: alice, bob" under a parent whose replies are collapsed (Collapse Replies); a tap opens the thread. */
@Composable
fun RepliesRow(row: ChatRow.Replies, style: ChatRowStyle, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = style.colors
    Text(
        repliesText(row.count, row.nicks),
        modifier
            .fillMaxWidth()
            .background(c.background)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = style.columns.textStart(hasAvatar = true), end = style.columns.textEndInset)
            .padding(vertical = Grid.unit),
        style = ChatRowStyle.caption,
        color = c.link,
    )
}

/** A collapsed run of joins, parts, quits, nick changes and modes; a tap shows or hides each event. */
@Composable
fun MembershipRow(row: ChatRow.Membership, style: ChatRowStyle, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = style.colors
    val events = row.group.events
    val text = remember(row.group, style) { MembershipSummary.annotated(events, c, style.device.colouriseNicknames) }
    val plain = remember(row.group) { MembershipSummary.text(events) }
    Text(
        text,
        modifier
            .fillMaxWidth()
            .background(c.background)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = plain
                stateDescription = if (row.expanded) "Expanded" else "Collapsed"
            }
            .padding(start = style.columns.textStart(hasAvatar = false), end = style.columns.textEndInset)
            .padding(vertical = Grid.unit),
        style = style.regular,
        color = c.logText,
    )
}

/** "Message of the day (N lines)" / "Hide message of the day" (iOS `MotdFoldCell`). */
fun motdFoldText(lines: Int, expanded: Boolean): String =
    if (expanded) "Hide message of the day" else "Message of the day ($lines lines)"

/** A collapsed run of MOTD lines; a tap shows or hides them. */
@Composable
fun MotdFoldRow(row: ChatRow.MotdFold, style: ChatRowStyle, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = style.colors
    Text(
        motdFoldText(row.group.lines.size, row.expanded),
        modifier
            .fillMaxWidth()
            .background(c.background)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = style.columns.textStart(hasAvatar = false), end = style.columns.textEndInset)
            .padding(vertical = Grid.unit),
        style = style.regular,
        color = c.logText,
    )
}

/** The list's top item: a spinner while a CHATHISTORY page loads, "Start of conversation" once exhausted. */
enum class HistoryHeaderState { IDLE, LOADING, EXHAUSTED }

/** Server buffers never page; others show [IrcBuffer.historyLoading] / [IrcBuffer.historyExhausted]. */
fun historyHeaderState(buffer: IrcBuffer): HistoryHeaderState {
    val pages = buffer.kind != IrcBufferKind.SERVER
    return when {
        pages && buffer.historyLoading -> HistoryHeaderState.LOADING
        pages && buffer.historyExhausted -> HistoryHeaderState.EXHAUSTED
        else -> HistoryHeaderState.IDLE
    }
}

/** Fixed 44dp so toggling it never shifts content. */
@Composable
fun HistoryHeader(state: HistoryHeaderState, style: ChatRowStyle, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Grid.tapTarget).testTag("historyHeader"), contentAlignment = Alignment.Center) {
        when (state) {
            HistoryHeaderState.LOADING ->
                CircularProgressIndicator(Modifier.size(20.dp), color = style.colors.textSecondary, strokeWidth = 2.dp)
            HistoryHeaderState.EXHAUSTED ->
                Text("Start of conversation", style = ChatRowStyle.caption, color = style.colors.textTertiary)
            HistoryHeaderState.IDLE -> Unit
        }
    }
}
