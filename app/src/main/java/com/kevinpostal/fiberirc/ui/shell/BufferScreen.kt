package com.kevinpostal.fiberirc.ui.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcState
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.irc.MediaItem
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.ui.actions.MessageMenuHost
import com.kevinpostal.fiberirc.ui.actions.MessageMenuTarget
import com.kevinpostal.fiberirc.ui.actions.ModerationController
import com.kevinpostal.fiberirc.ui.actions.rememberMessageMenuState
import com.kevinpostal.fiberirc.ui.chat.ChatLink
import com.kevinpostal.fiberirc.ui.chat.ChatLog
import com.kevinpostal.fiberirc.ui.chat.ChatRow
import com.kevinpostal.fiberirc.ui.chat.ChatRowCallbacks
import com.kevinpostal.fiberirc.ui.chat.ChatRows
import com.kevinpostal.fiberirc.ui.chat.ChatRowsInput
import com.kevinpostal.fiberirc.ui.chat.historyHeaderState
import com.kevinpostal.fiberirc.ui.chat.rememberChatRowStyle
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * What the chat screen hosts from other milestones ([SessionHost] supplies them).
 *
 * @param composer the shown buffer's composer (M7 `ComposerBar` host: drafts in [IrcBuffer.draft], sending through
 *   the client, [ShellController.composerRequest] applied then consumed). `thread` is the open thread's parent
 *   msgid, null for the buffer itself.
 * @param media inline previews under a line ([ChatRowsInput.media]: M10 `InlineMedia`, already filtered for closed
 *   previews, the buffer's embed prefs and metered networks).
 * @param mediaContent draws one preview row at the text column's width (M10 `InlineMediaCard`).
 */
class ChatSlots(
    val composer: @Composable (shown: ShownBuffer, thread: String?) -> Unit,
    val media: (client: IrcClient, buffer: IrcBuffer, line: IrcLine) -> List<MediaItem>,
    val mediaContent: @Composable (shown: ShownBuffer, row: ChatRow.Media, textWidth: Dp) -> Unit,
)

/**
 * The chat pane of the shell (the iOS chat navigation stack's root, `BufferViewController`): the title bar — menu
 * button (or Back in a thread), the buffer's name over its subtitle, quick-switch search and the members button —
 * then the message log ([ChatLog]) and the composer slot. The thread view is the same screen filtered to one
 * thread.
 */
@Composable
fun BufferScreen(
    controller: ShellController,
    stores: AccountStores,
    deviceSettings: DeviceSettingsStore,
    moderation: ModerationController,
    slots: ChatSlots,
    onQuickSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFiberIrcColors.current
    val shown = controller.current
    Column(
        modifier
            .fillMaxSize()
            .background(c.background)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        TitleBar(controller, shown, onQuickSwitch)
        if (shown == null) {
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatPane(controller, stores, deviceSettings, moderation, slots, shown)
            TipCapsule(controller.tip, onDone = { controller.tip = null })
        }
        slots.composer(shown, controller.thread)
    }
}

@Composable
private fun TitleBar(controller: ShellController, shown: ShownBuffer?, onQuickSwitch: () -> Unit) {
    val c = LocalFiberIrcColors.current
    controller.manager.revision.collectAsState().value   // topic, state and member changes
    val thread = controller.thread
    val buffer = shown?.buffer
    val client = shown?.client
    val (name, subtitle) = when {
        buffer == null -> "" to ""
        thread != null -> "Thread" to "in ${buffer.name}"
        else -> buffer.name to subtitle(client!!, buffer)
    }
    Column(Modifier.background(c.navBar).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(Grid.tapTarget), verticalAlignment = Alignment.CenterVertically) {
            when {
                thread != null -> IconButton(onClick = controller::closeThread, modifier = Modifier.size(Grid.tapTarget)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.textSecondary)
                }
                !controller.docked -> MenuButton(controller.menuState, onClick = { controller.toggleDrawer(DrawerSide.LEFT) })
                else -> Spacer(Modifier.width(Grid.tapTarget))
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = shown != null && thread == null) { titleTapped(controller, shown!!) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    color = c.heading,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("bufferTitle").semantics { heading() },
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("bufferTitleSubtitle"),
                    )
                }
            }
            IconButton(onClick = onQuickSwitch, modifier = Modifier.size(Grid.tapTarget).testTag("quickSwitchButton")) {
                Icon(Icons.Filled.Search, contentDescription = "Jump to channel", tint = c.textSecondary)
            }
            if (buffer?.kind == IrcBufferKind.CHANNEL && buffer.joined) {
                IconButton(
                    onClick = { controller.toggleDrawer(DrawerSide.RIGHT) },
                    modifier = Modifier.size(Grid.tapTarget).testTag("membersButton"),
                ) {
                    Icon(Icons.Filled.People, contentDescription = "Members", tint = c.textSecondary)
                }
            } else {
                Spacer(Modifier.width(Grid.tapTarget))
            }
        }
        HorizontalDivider(color = c.borderDark)
    }
}

/** A channel's topic, the server buffer's connection state, a conversation partner's real name. */
private fun subtitle(client: IrcClient, buffer: IrcBuffer): String = when (buffer.kind) {
    IrcBufferKind.CHANNEL -> buffer.topic?.let(IrcFormatting::strip).orEmpty()
    IrcBufferKind.SERVER -> when (client.state) {
        IrcState.CONNECTED -> "Connected"
        IrcState.CONNECTING, IrcState.REGISTERING -> "Connecting…"
        IrcState.DISCONNECTED -> "Offline"
    }
    IrcBufferKind.QUERY -> client.buffers.asSequence()
        .mapNotNull { it.member(buffer.name)?.realname }
        .firstOrNull { it.isNotEmpty() }
        ?.let { if (it.equals(buffer.name, ignoreCase = true)) "" else it }
        .orEmpty()
}

/** A channel opens Channel Info, a conversation Whois on its partner; a disconnected server buffer reconnects. */
private fun titleTapped(controller: ShellController, shown: ShownBuffer) {
    val client = shown.client
    val buffer = shown.buffer
    when (buffer.kind) {
        IrcBufferKind.CHANNEL -> controller.navigation.channelInfo(client, buffer)
        IrcBufferKind.QUERY -> controller.navigation.whois(client, buffer.name)
        IrcBufferKind.SERVER -> if (client.state == IrcState.DISCONNECTED) client.connect()
    }
}

@Composable
private fun ChatPane(
    controller: ShellController,
    stores: AccountStores,
    deviceSettings: DeviceSettingsStore,
    moderation: ModerationController,
    slots: ChatSlots,
    shown: ShownBuffer,
) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val client = shown.client
    val buffer = shown.buffer
    val thread = controller.thread
    val revision by controller.manager.revision.collectAsState()
    val display by stores.accountSettings.display.collectAsState()
    val device by deviceSettings.settings.collectAsState()
    val highlightWords by stores.accountSettings.highlightWords.collectAsState()
    val highlightOptions by stores.accountSettings.highlightOptions.collectAsState()
    val bufferPrefs by stores.bufferPrefs.values.collectAsState()
    val blocked by stores.blockList.masks.collectAsState()
    var expanded by remember(buffer) { mutableStateOf<Set<String>>(emptySet()) }
    var hiddenMediaRevision by remember(buffer) { mutableStateOf(0) }
    val openedAt = remember(shown) { Instant.now() }
    val menu = rememberMessageMenuState()

    fun pref(k: BufferPrefsStore.Key) = controller.effective(k, client, buffer)

    val rows = remember(shown, thread, revision, display, device, bufferPrefs, blocked, expanded, hiddenMediaRevision, c) {
        ChatRows.build(
            ChatRowsInput(
                lines = buffer.lines,
                unreadSince = shown.unreadSince,
                openedAt = openedAt,
                showJoinPart = pref(BufferPrefsStore.Key.SHOW_JOIN_PART),
                collapse = !pref(BufferPrefsStore.Key.EXPAND_JOIN_PART),
                expanded = expanded,
                isBlocked = stores.blockList::isBlocked,
                showDeleted = device.showDeletedMessages,
                replyCollapse = pref(BufferPrefsStore.Key.REPLY_COLLAPSE),
                thread = thread,
                media = { slots.media(client, buffer, it) },
                separateNickLine = device.nicknamesOnSeparateLine,
                artChatText = c.chatText,
            ),
        )
    }
    val style = rememberChatRowStyle(
        display = display,
        device = device,
        highlightWords = highlightWords,
        highlightOptions = highlightOptions,
        ownNick = client.nick,
        members = buffer.members,
    )
    val callbacks = ChatRowCallbacks(
        onLink = { link -> openLink(context, controller, client, link, device.openLinksInApp) },
        onOpenThread = controller::openThread,
        onReplyToSender = { controller.requestComposer(ComposerRequest.ReplyToSender(it)) },
        onTap = { focus.clearFocus() },
        onLongPress = { line, url, preview -> menu.open(MessageMenuTarget(line, url, preview)) },
        onToggleGroup = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
    )
    // A search result: scroll to its row and flash it; a line no longer here (trimmed) scrolls to the bottom.
    val listState = rememberLazyListState()
    var flashKey by remember(buffer) { mutableStateOf<String?>(null) }
    val reveal = controller.reveal
    LaunchedEffect(reveal, rows) {
        val line = reveal ?: return@LaunchedEffect
        if (thread != null) return@LaunchedEffect
        controller.consumeReveal(line)
        val index = rows.indexOfFirst { it is ChatRow.Line && it.line === line }
        if (index < 0) {
            if (rows.isNotEmpty()) listState.scrollToItem(rows.size)
            return@LaunchedEffect
        }
        listState.scrollToItem(index + 1) // item 0 is the history header
        flashKey = rows[index].anchorKey
        delay(FLASH_MILLIS)
        flashKey = null
    }
    ChatLog(
        rows = rows,
        style = style,
        callbacks = callbacks,
        header = historyHeaderState(buffer),
        canPage = client.canRequestOlderHistory(buffer),
        modifier = Modifier.fillMaxSize(),
        state = listState,
        flashKey = flashKey,
        onNearTop = { client.requestOlderHistory(buffer) },
        onMarkRead = {
            client.markRead(buffer)
            controller.updateMenuState()
        },
        mediaContent = { row, width -> slots.mediaContent(shown, row, width) },
    )
    MessageMenuHost(
        state = menu,
        client = client,
        buffer = buffer,
        moderation = moderation,
        onReply = { line ->
            if (thread == null) {
                (line.replyTo ?: line.msgid)?.let(controller::openThread)
            } else {
                controller.requestComposer(ComposerRequest.Focus)
            }
        },
        onEdit = { controller.requestComposer(ComposerRequest.Edit(it)) },
        onPreviewClosed = { hiddenMediaRevision++ },
    )
}

/** How long a revealed search result keeps its flash key (MessageRow's 1.2 s hold + 0.6 s fade). */
private const val FLASH_MILLIS = 1800L

/** A URL opens per Open Links In App; a channel name joins (or shows) it on the same network. */
private fun openLink(context: Context, controller: ShellController, client: IrcClient, link: ChatLink, openLinksInApp: Boolean) {
    when (link) {
        is ChatLink.Url -> LinkOpener.open(context, link.url, openLinksInApp)
        is ChatLink.Channel -> controller.open(BufferLink.Join(client.settings.networkId, link.name))
    }
}
