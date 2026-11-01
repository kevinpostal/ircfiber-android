package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.ui.theme.FiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.Grid

// Port of iOS `UI/Chat/UnreadBars.swift` (+ the counting in `BufferViewController.updateUnreadBars`).

/**
 * The chat's unread bars (IRCCloud `EventsTableView`): the top bar counts the unread messages above the screen, the
 * bottom bar the mentions and messages that arrived below it while scrolled up.
 */
object UnreadBars {
    /** "↑ 1 unread message" / "↑ N unread messages". */
    fun topText(count: Int): String = if (count == 1) "↑ 1 unread message" else "↑ $count unread messages"

    private fun unreadText(n: Int) = if (n == 1) "1 unread message" else "$n unread messages"

    /** "N mentions", "N unread messages", or "N mentions and N unread messages"; "" when both are zero. */
    fun bottomText(mentions: Int, unread: Int): String {
        val mentionText = if (mentions == 1) "1 mention" else "$mentions mentions"
        return when {
            mentions > 0 && unread > 0 -> "$mentionText and ${unreadText(unread)}"
            mentions > 0 -> mentionText
            unread > 0 -> unreadText(unread)
            else -> ""
        }
    }

    /**
     * The bottom bar's text after its red mention count (IRCCloud `updateUnread`): "mention", "mentions and 1 unread
     * message"; without mentions, [bottomText].
     */
    fun bottomCaption(mentions: Int, unread: Int): String {
        if (mentions <= 0) return bottomText(0, unread)
        val word = if (mentions == 1) "mention" else "mentions"
        return if (unread > 0) "$word and ${unreadText(unread)}" else word
    }

    /** A chat row the top bar counts: a message/action/notice line, or an art row. */
    fun isChat(row: ChatRow): Boolean = when (row) {
        is ChatRow.Line -> row.line.kind == IrcLineKind.MESSAGE || row.line.kind == IrcLineKind.ACTION ||
            row.line.kind == IrcLineKind.NOTICE
        is ChatRow.Art -> true
        else -> false
    }

    /** Index of the "New messages" row, or null. */
    fun dividerIndex(rows: List<ChatRow>): Int? = rows.indexOfFirst { it is ChatRow.NewMessages }.takeIf { it >= 0 }

    /**
     * The top bar's count: with the divider at [divider] scrolled above the screen and [firstVisible] the first row
     * on screen, the chat rows between them; 0 when the divider is on screen or below.
     */
    fun countAbove(rows: List<ChatRow>, divider: Int?, firstVisible: Int): Int {
        if (divider == null || divider >= rows.size) return 0
        val first = minOf(firstVisible, rows.size)
        if (first <= divider) return 0
        return rows.subList(divider + 1, first).count(::isChat)
    }

    /** 32dp, growing with the font scale up to 44dp. */
    @Composable
    fun height(): Dp = (32.dp * LocalDensity.current.fontScale).coerceIn(32.dp, 44.dp)

    /** iOS `Theme.footnote`. */
    val footnote = TextStyle(fontSize = 13.sp)
}

/**
 * "↑ N unread messages" pinned to the top of the messages while the "New messages" row is above them: a tap scrolls
 * to it ([onTap]), ✕ marks the chat read ([onDismiss]).
 */
@Composable
fun TopUnreadBar(count: Int, colors: FiberIrcColors, onTap: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = UnreadBars.topText(count)
    Row(
        modifier.fillMaxWidth().height(UnreadBars.height()).background(colors.unreadBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onTap)
                .padding(start = Grid.margin, end = Grid.gutter)
                .wrapContentHeight(Alignment.CenterVertically)
                .testTag("unreadAboveBar"),
            style = UnreadBars.footnote,
            color = colors.unreadBarText,
            maxLines = 1,
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.width(Grid.tapTarget).padding(end = Grid.unit).testTag("dismissUnread"),
        ) {
            Icon(Icons.Filled.Cancel, contentDescription = "Mark as Read", tint = colors.accent)
        }
    }
}

/**
 * "N mentions and N unread messages" above the composer while new rows arrived below the screen; the mention count is
 * a red pill (IRCCloud `HighlightsCountView`). A tap ([onTap]) scrolls to the newest row and marks the chat read.
 */
@Composable
fun BottomUnreadBar(mentions: Int, unread: Int, colors: FiberIrcColors, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val label = UnreadBars.bottomText(mentions, unread)
    Row(
        modifier
            .fillMaxWidth()
            .height(UnreadBars.height())
            .background(colors.unreadBar)
            .clickable(onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = Grid.margin)
            .testTag("unreadBelowBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mentions > 0) {
            Text(
                "$mentions",
                Modifier
                    .background(colors.badge, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .widthIn(min = 12.dp),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(Modifier.width(Grid.unit))
        }
        Text(
            UnreadBars.bottomCaption(mentions, unread),
            style = UnreadBars.footnote,
            color = colors.unreadBarText,
            maxLines = 1,
        )
    }
}
