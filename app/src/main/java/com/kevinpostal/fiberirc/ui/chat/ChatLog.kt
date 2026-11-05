package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.irc.mentions
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.nickColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** iOS `isNearTop`: within 600pt of the top pages older history. */
private val NEAR_TOP = 600.dp

/** iOS `isNearBottom`: within 40pt of the bottom counts as at the bottom. */
private val NEAR_BOTTOM = 40.dp

/**
 * The chat's message list (the table half of iOS `BufferViewController`): the history header, every [ChatRow] keyed by
 * [ChatRow.anchorKey] (so history pages inserted above keep the visible row in place), and the unread bars.
 *
 * - Sticks to the bottom while the user is there; new rows arriving while scrolled up are counted into the bottom bar
 *   (mentions of [ChatRowStyle.ownNick] / highlight words separately), your own lines aren't.
 * - The top bar shows while the "New messages" row is above the screen, until ✕ ([onMarkRead]).
 * - [onNearTop] fires whenever the list comes within 600dp of the top (and again when [header] changes or
 *   [canPage] becomes true while there): call `IrcClient.requestOlderHistory(buffer)` from it.
 * - [flashKey]: the [ChatRow.anchorKey] of a line to flash (a search result scrolled to); clear it and set it again
 *   to flash twice.
 * - [mediaContent] draws an inline preview (`ui.media.InlineMediaCard`) at [Dp] text width; the band, mention bar
 *   and text-column offset around it are drawn here. Long-press on a preview should call
 *   `callbacks.onLongPress(row.parent, row.item.url, true)`.
 */
@Composable
fun ChatLog(
    rows: List<ChatRow>,
    style: ChatRowStyle,
    callbacks: ChatRowCallbacks,
    header: HistoryHeaderState,
    canPage: Boolean,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    flashKey: String? = null,
    onNearTop: () -> Unit = {},
    onMarkRead: () -> Unit = {},
    mediaContent: @Composable (row: ChatRow.Media, textWidth: Dp) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val nearTopPx = with(density) { NEAR_TOP.toPx() }
    val nearBottomPx = with(density) { NEAR_BOTTOM.toPx() }
    val currentNearTop by rememberUpdatedState(onNearTop)

    // Item 0 is the history header; row i is item i + 1.
    var sticksToBottom by remember { mutableStateOf(true) }
    var newWhileScrolledUp by remember { mutableIntStateOf(0) }
    var newMentionsWhileScrolledUp by remember { mutableIntStateOf(0) }
    var topDismissed by remember { mutableStateOf(false) }
    var previousRows by remember { mutableStateOf<List<ChatRow>?>(null) }

    val isNearBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index == info.totalItemsCount - 1 && (last.offset + last.size - info.viewportEndOffset) < nearBottomPx
        }
    }
    val isNearTop by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf false
            val avg = visible.sumOf { it.size } / visible.size.toFloat()
            state.firstVisibleItemIndex * avg + state.firstVisibleItemScrollOffset < nearTopPx
        }
    }

    // User scrolling decides whether new rows follow the bottom; programmatic scrolls don't flip it.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to isNearBottom }.collect { (scrolling, nearBottom) ->
            if (scrolling) sticksToBottom = nearBottom
            if (nearBottom) {
                newWhileScrolledUp = 0
                newMentionsWhileScrolledUp = 0
            }
        }
    }
    LaunchedEffect(state, header, canPage) {
        if (canPage) snapshotFlow { isNearTop }.distinctUntilChanged().filter { it }.collect { currentNearTop() }
    }

    // Follow appends: to the bottom when stuck there, else count them for the bottom bar.
    LaunchedEffect(rows) {
        val old = previousRows
        previousRows = rows
        if (rows.isEmpty()) return@LaunchedEffect
        if (old == null || sticksToBottom) {
            state.scrollToItem(rows.size) // the last row (header is item 0)
            return@LaunchedEffect
        }
        val added = when (val d = ChatRowDiff.diff(old, rows)) {
            is ChatRowDiff.Append -> d.rows
            is ChatRowDiff.ReplaceTail -> rows.subList(minOf(old.size, rows.size), rows.size)
            ChatRowDiff.Reload -> emptyList()
        }
        for (row in added) {
            val line = when (row) {
                is ChatRow.Line -> row.line
                is ChatRow.Art -> row.line
                else -> continue
            }
            if (!countsAsNew(line, style.ownNick)) continue
            if (line.mentions(style.ownNick) || HighlightMatcher.matches(line, style.highlightWords, style.ownNick, style.highlightOptions)) {
                newMentionsWhileScrolledUp += 1
            } else {
                newWhileScrolledUp += 1
            }
        }
    }

    val divider = remember(rows) { UnreadBars.dividerIndex(rows) }
    val unreadAbove by remember(rows, divider) {
        derivedStateOf {
            val d = divider ?: return@derivedStateOf 0
            // The divider row (item d + 1) is entirely above the screen.
            val firstRow = state.firstVisibleItemIndex - 1
            if (firstRow <= d) 0 else UnreadBars.countAbove(rows, d, firstRow)
        }
    }

    // Lazy keys must be unique; anchor keys can repeat (two msgid-less lines from one sender in the same instant).
    val keys = remember(rows) { uniqueKeys(rows) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val artHeaderStyle = remember { ChatRowStyle.chatTextStyle(mono = true, bold = true, scale = 1.0) }
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            item(key = "historyHeader", contentType = "historyHeader") { HistoryHeader(header, style) }
            itemsIndexed(rows, key = { i, _ -> keys[i] }, contentType = { _, row -> row::class.simpleName }) { _, row ->
                when (row) {
                    is ChatRow.Line -> MessageRow(row, style, callbacks, flash = row.anchorKey == flashKey)
                    is ChatRow.Day -> DaySeparator(row.date, style)
                    is ChatRow.NewMessages -> NewMessagesDivider(style)
                    is ChatRow.Membership -> MembershipRow(row, style, onToggle = { callbacks.onToggleGroup(row.group.id) })
                    is ChatRow.MotdFold -> MotdFoldRow(row, style, onToggle = { callbacks.onToggleGroup(row.group.id) })
                    is ChatRow.Replies -> RepliesRow(row, style, onOpen = { callbacks.onOpenThread(row.parentMsgid) })
                    is ChatRow.Art -> {
                        val cell = ArtGeometry.cellSize(containerWidth, row.layout.blockColumns, density.density)
                        val sender = row.line.sender ?: ""
                        ArtLineItem(
                            sender = sender,
                            text = row.line.text,
                            layout = row.layout,
                            cellSize = cell,
                            headerColor = nickColor(sender, style.colors.dark),
                            headerStyle = artHeaderStyle,
                            modifier = Modifier.fillMaxWidth().lineGestures(row.line, callbacks),
                        )
                    }
                    is ChatRow.Media -> {
                        val band = remember(row.parent, style) { rowBand(row.parent, style) }
                        val cols = style.columns
                        val textWidth = (containerWidth - cols.textX - cols.textEndInset).coerceAtLeast(0.dp)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawRect(band.background)
                                    if (band.mentioned) drawRect(style.colors.accent, size = Size(Grid.unit.toPx(), size.height))
                                }
                                .padding(start = cols.textX, end = cols.textEndInset),
                        ) {
                            mediaContent(row, textWidth)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            if (!topDismissed && unreadAbove > 0) {
                TopUnreadBar(
                    unreadAbove,
                    style.colors,
                    onTap = {
                        val d = divider ?: return@TopUnreadBar
                        sticksToBottom = false
                        scope.launch { state.animateScrollToItem(d + 1) }
                    },
                    onDismiss = {
                        topDismissed = true
                        onMarkRead()
                    },
                )
            }
        }
        if (newWhileScrolledUp + newMentionsWhileScrolledUp > 0) {
            BottomUnreadBar(
                newMentionsWhileScrolledUp,
                newWhileScrolledUp,
                style.colors,
                onTap = {
                    newWhileScrolledUp = 0
                    newMentionsWhileScrolledUp = 0
                    sticksToBottom = true
                    scope.launch { if (rows.isNotEmpty()) state.animateScrollToItem(rows.size) }
                    onMarkRead()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** iOS `didAppendLine`: someone else's message/action/notice arriving below the screen. */
private fun countsAsNew(line: IrcLine, ownNick: String): Boolean {
    val chat = line.kind == IrcLineKind.MESSAGE || line.kind == IrcLineKind.ACTION || line.kind == IrcLineKind.NOTICE
    val sender = line.sender ?: return false
    return chat && !sender.equals(ownNick, ignoreCase = true)
}

/** [ChatRow.anchorKey]s, with `#n` appended to the second and later occurrences of a repeated key. */
internal fun uniqueKeys(rows: List<ChatRow>): List<String> {
    val seen = HashMap<String, Int>()
    return rows.map { row ->
        val key = row.anchorKey
        val n = seen.merge(key, 1, Int::plus)!!
        if (n == 1) key else "$key#$n"
    }
}

/** Tap / double tap / long press on a row that has no gestures of its own (art rows). */
private fun Modifier.lineGestures(line: IrcLine, callbacks: ChatRowCallbacks): Modifier = pointerInput(line, callbacks) {
    detectTapGestures(
        onTap = { callbacks.onTap() },
        onDoubleTap = {
            val nick = line.sender
            if (!nick.isNullOrEmpty()) callbacks.onReplyToSender(nick)
        },
        onLongPress = { callbacks.onLongPress(line, null, false) },
    )
}
