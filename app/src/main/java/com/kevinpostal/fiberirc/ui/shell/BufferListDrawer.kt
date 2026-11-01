package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcState
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.ui.chat.LetterAvatar
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch

/**
 * The session's channel drawer (IRCCloud's buffer list, ports `BufferListViewController.swift`): the pins, then
 * each network's row with its channels, conversations, Archives group and "Join a channel" row, then "Add a
 * network" ([BufferListModel]). Picking a buffer (a network row picks its server buffer) shows it; the shown
 * buffer's row is drawn selected. Long-pressing a row offers its actions. Strips at the top and bottom edges point
 * at unread rows scrolled out of view.
 */
@Composable
fun BufferListDrawer(controller: ShellController, stores: AccountStores, modifier: Modifier = Modifier) {
    val c = LocalFiberIrcColors.current
    // Recompose on every client event and on each store the rows read.
    controller.manager.revision.collectAsState().value
    val clients by controller.manager.clients.collectAsState()
    val pins by stores.pinList.keys.collectAsState()
    val archived by stores.archiveList.keys.collectAsState()
    val collapsed by stores.networkLayout.collapsed.collectAsState()
    stores.networkLayout.networkOrder.collectAsState().value
    stores.bufferPrefs.values.collectAsState().value
    val infos by controller.manager.networkInfos.collectAsState()
    val rows = controller.drawerRows()
    val selected = controller.shownBuffer
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun info(cl: IrcClient): NetworkInfo? =
        if (cl.settings.networkId.isEmpty()) null else infos.firstOrNull { it.id == cl.settings.networkId }

    fun isUnread(b: IrcBuffer, cl: IrcClient) =
        b.unreadCount > 0 && controller.effective(BufferPrefsStore.Key.SHOW_UNREAD_COUNT, cl, b)

    fun edgeKind(row: BufferRow): EdgeKind {
        if (row !is BufferRow.Pinned && row !is BufferRow.Buffer && row !is BufferRow.Network) return EdgeKind.NONE
        val (cl, b) = controller.target(row) ?: return EdgeKind.NONE
        if (b.highlightCount > 0) return EdgeKind.HIGHLIGHT
        return if (isUnread(b, cl)) EdgeKind.UNREAD else EdgeKind.NONE
    }

    fun bufferContent(b: IrcBuffer, cl: IrcClient, inactive: Boolean): RowContent {
        val unread = isUnread(b, cl)
        val isSelected = selected?.matches(BufferKey.of(cl, b)) == true
        return RowContent(
            text = b.name,
            icon = if (b.kind == IrcBufferKind.CHANNEL && 'k' in b.channelModes) Icons.Filled.Lock else null,
            minHeight = 40.dp,
            textColor = if (inactive) c.bufferInactiveText else if (unread) c.bufferUnreadText else c.bufferText,
            bold = unread,
            unreadBar = unread,
            highlights = b.highlightCount,
            selected = isSelected,
            stateDescription = if (b.highlightCount > 0) "${b.highlightCount} mentions" else if (unread) "Unread" else null,
        )
    }

    fun content(row: BufferRow): RowContent = when (row) {
        BufferRow.PinnedHeader -> RowContent.header("Pinned", Icons.Filled.PushPin)
        is BufferRow.ArchivesHeader -> RowContent.header(
            "Archives",
            if (row.expanded) Icons.Outlined.IndeterminateCheckBox else Icons.Outlined.AddBox,
        ).copy(stateDescription = if (row.expanded) "Expanded" else "Collapsed", isHeader = false)
        is BufferRow.JoinChannel -> RowContent(text = "Join a channel", icon = Icons.Outlined.AddCircleOutline, minHeight = 40.dp)
        BufferRow.AddNetwork -> RowContent(
            text = "Add a network", icon = Icons.Outlined.AddCircleOutline, minHeight = Grid.tapTarget,
            background = c.networkRow,
        )
        is BufferRow.Network -> {
            val cl = clients[row.client]
            val base = bufferContent(cl.serverBuffer, cl, inactive = cl.state != IrcState.CONNECTED)
            val failedLink = cl.state == IrcState.DISCONNECTED && cl in controller.failed
            val tls = info(cl)?.tls?.let { it != "disabled" } ?: cl.settings.useTLS
            base.copy(
                text = cl.settings.networkName,
                networkAvatar = cl.settings.networkName,
                minHeight = Grid.tapTarget,
                background = if (base.selected) base.background else c.networkRow,
                icon = if (failedLink) Icons.Filled.Warning else if (tls) Icons.Filled.VerifiedUser else Icons.Filled.Public,
                iconColor = if (failedLink) c.offline else null,
                isCollapsed = if (cl.settings.networkId.isEmpty()) null else cl.settings.networkId in collapsed,
            )
        }
        is BufferRow.Pinned -> bufferContent(row.buffer, clients[row.client], row.buffer.kind == IrcBufferKind.CHANNEL && !row.buffer.joined)
        is BufferRow.Buffer -> bufferContent(row.buffer, clients[row.client], row.buffer.kind == IrcBufferKind.CHANNEL && !row.buffer.joined)
        is BufferRow.Archived -> bufferContent(row.buffer, clients[row.client], inactive = true)
    }

    fun tapped(row: BufferRow) {
        when (row) {
            BufferRow.PinnedHeader -> {}
            is BufferRow.ArchivesHeader -> controller.toggleArchives(clients[row.client].settings.networkId)
            is BufferRow.JoinChannel -> controller.promptJoinChannel(clients[row.client])
            BufferRow.AddNetwork -> {
                controller.closeDrawer()
                controller.navigation.addNetwork(null)
            }
            else -> controller.target(row)?.let { (cl, b) -> controller.show(b, cl) }
        }
    }

    // Unread rows scrolled off each edge (a row counts once its middle leaves the viewport). `listState.layoutInfo`
    // is a new instance every layout pass even when nothing visible changed, so reading it straight in the
    // composable body free-runs recomposition↔layout; `derivedStateOf` only recomposes when `above`/`below`
    // actually differ.
    val edges by remember(rows) {
        derivedStateOf {
            val layout = listState.layoutInfo
            var above = EdgeKind.NONE
            var below = EdgeKind.NONE
            val visible = layout.visibleItemsInfo
            if (visible.isNotEmpty()) {
                val top = layout.viewportStartOffset
                val bottom = layout.viewportEndOffset
                val firstIndex = visible.first().index
                val lastIndex = visible.last().index
                rows.forEachIndexed { i, row ->
                    val kind = edgeKind(row)
                    if (kind == EdgeKind.NONE) return@forEachIndexed
                    val item = visible.firstOrNull { it.index == i }
                    val isAbove = if (item != null) item.offset + item.size / 2 < top else i < firstIndex
                    val isBelow = if (item != null) item.offset + item.size / 2 > bottom else i > lastIndex
                    if (isAbove && above != EdgeKind.HIGHLIGHT) above = kind
                    if (isBelow && below != EdgeKind.HIGHLIGHT) below = kind
                }
            }
            above to below
        }
    }
    val (above, below) = edges

    fun scrollToEdge(topEdge: Boolean) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: return
        val last = visible.last().index
        val candidates = rows.indices.filter { edgeKind(rows[it]) != EdgeKind.NONE && if (topEdge) it < first else it > last }
        val i = (if (topEdge) candidates.lastOrNull() else candidates.firstOrNull()) ?: return
        scope.launch { listState.animateScrollToItem(i) }
    }

    Box(modifier.fillMaxSize().background(c.drawerBackground).testTag("bufferList")) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            itemsIndexed(rows) { _, row ->
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    BufferCell(
                        content = content(row),
                        onClick = if (row == BufferRow.PinnedHeader) null else ({ tapped(row) }),
                        onLongClick = { menuOpen = true },
                        onDisclosure = (row as? BufferRow.Network)?.let { n ->
                            clients.getOrNull(n.client)?.takeIf { it.settings.networkId.isNotEmpty() }?.let { cl ->
                                { controller.toggleCollapsed(cl) }
                            }
                        },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (menuOpen) RowMenu(controller, stores, clients, row, info = { info(it) }, dismiss = { menuOpen = false })
                    }
                }
            }
        }
        if (above != EdgeKind.NONE) UnreadEdge(above, top = true, onClick = { scrollToEdge(true) }, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        if (below != EdgeKind.NONE) UnreadEdge(below, top = false, onClick = { scrollToEdge(false) }, Modifier.align(Alignment.BottomCenter))
    }
}

private enum class EdgeKind { NONE, UNREAD, HIGHLIGHT }

/** IRCCloud's off-screen unread strip: a 24dp hit strip with a 4dp bar on the edge (badge red / accent blue). */
@Composable
private fun UnreadEdge(kind: EdgeKind, top: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = LocalFiberIrcColors.current
    val label = when {
        top && kind == EdgeKind.UNREAD -> "Unread channels above"
        top -> "Mentions above"
        kind == EdgeKind.UNREAD -> "Unread channels below"
        else -> "Mentions below"
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .combinedClickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(if (top) "unreadAbove" else "unreadBelow"),
        contentAlignment = if (top) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(if (kind == EdgeKind.HIGHLIGHT) c.badge else c.accent))
    }
}

/** What a drawer row draws (`BufferCell.Content`). */
private data class RowContent(
    val text: String,
    val icon: ImageVector?,
    val minHeight: Dp,
    /** Overrides the text colour for the icon (the offline warning). */
    val iconColor: Color? = null,
    /** Network identity: a letter avatar keyed by this name with [icon] as a small corner badge. */
    val networkAvatar: String? = null,
    /** Non-null only on a collapsible network row: whether its network is collapsed (trailing chevron). */
    val isCollapsed: Boolean? = null,
    val textColor: Color? = null,
    val bold: Boolean = false,
    val small: Boolean = false,
    val background: Color? = null,
    val selected: Boolean = false,
    val unreadBar: Boolean = false,
    val highlights: Int = 0,
    val isHeader: Boolean = false,
    val stateDescription: String? = null,
) {
    companion object {
        /** The Pinned and Archives headers: 32dp, small bold title. */
        fun header(text: String, icon: ImageVector) =
            RowContent(text = text, icon = icon, minHeight = 32.dp, small = true, bold = true, isHeader = true)
    }
}

/**
 * A drawer row (IRCCloud's buffer cell): a 6dp bar on the leading edge (blue when unread, the darker accent on the
 * selected row), an icon column, the name, and a red mention count.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BufferCell(content: RowContent, onClick: (() -> Unit)?, onLongClick: () -> Unit, onDisclosure: (() -> Unit)?) {
    val c = LocalFiberIrcColors.current
    val textColor = content.textColor ?: c.bufferText
    val foreground = if (content.selected) Color.White else textColor
    val background = if (content.selected) c.accent else content.background ?: c.drawerBackground
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = content.minHeight)
            .background(background)
            .combinedClickable(enabled = onClick != null, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            .semantics {
                contentDescription = content.text
                content.stateDescription?.let { stateDescription = it }
                if (content.isHeader) heading()
                selected = content.selected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .heightIn(min = content.minHeight)
                .fillMaxHeight()
                .background(
                    when {
                        content.selected -> c.accentActive
                        content.unreadBar -> c.accent
                        else -> Color.Transparent
                    },
                ),
        )
        Spacer(Modifier.width(Grid.margin - 6.dp))
        Box(Modifier.width(Grid.leadingColumn), contentAlignment = Alignment.Center) {
            val iconTint = if (content.selected) Color.White else content.iconColor ?: foreground
            if (content.networkAvatar != null) {
                LetterAvatar(nick = content.networkAvatar, size = Grid.leadingColumn)
                if (content.icon != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(3.dp, 3.dp)
                            .size(14.dp)
                            .background(background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(content.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(9.dp))
                    }
                }
            } else if (content.icon != null) {
                Icon(content.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(Grid.textKeyline - Grid.margin - Grid.leadingColumn))
        Text(
            content.text,
            color = foreground,
            fontSize = if (content.small) 15.sp else 16.sp,
            fontWeight = if (content.bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = Grid.unit),
        )
        if (content.highlights > 0) {
            Spacer(Modifier.width(Grid.gutter))
            Text(
                if (content.highlights > 99) "99+" else content.highlights.toString(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = 18.dp)
                    .background(c.badge, RoundedCornerShape(9.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (content.isCollapsed != null && onDisclosure != null) {
            val label = if (content.isCollapsed) "Expand" else "Collapse"
            IconButton(onClick = onDisclosure, modifier = Modifier.size(Grid.tapTarget).testTag("networkDisclosure")) {
                Icon(
                    if (content.isCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                    contentDescription = label,
                    tint = foreground,
                )
            }
        } else {
            Spacer(Modifier.width(Grid.margin))
        }
    }
}

/** A row's long-press menu: its own actions, then the actions every row offers. */
@Composable
private fun RowMenu(
    controller: ShellController,
    stores: AccountStores,
    clients: List<IrcClient>,
    row: BufferRow,
    info: (IrcClient) -> NetworkInfo?,
    dismiss: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val own = rowActions(controller, stores, clients, row, info)
    @Composable
    fun item(a: RowAction) = DropdownMenuItem(
        text = { Text(a.title, color = if (a.destructive) c.error else c.textPrimary) },
        enabled = a.enabled,
        onClick = {
            dismiss()
            a.perform()
        },
    )
    own.forEach { item(it) }
    if (own.isNotEmpty()) HorizontalDivider()
    globalActions(controller, stores, clients, row).forEach { item(it) }
}

private class RowAction(
    val title: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val perform: () -> Unit,
)

private fun rowActions(
    controller: ShellController,
    stores: AccountStores,
    clients: List<IrcClient>,
    row: BufferRow,
    info: (IrcClient) -> NetworkInfo?,
): List<RowAction> = when (row) {
    is BufferRow.Network -> clients.getOrNull(row.client)?.let { networkActions(controller, stores, it, info(it)) } ?: emptyList()
    is BufferRow.Pinned -> clients.getOrNull(row.client)?.let { bufferActions(controller, stores, row.buffer, it) } ?: emptyList()
    is BufferRow.Buffer -> clients.getOrNull(row.client)?.let { bufferActions(controller, stores, row.buffer, it) } ?: emptyList()
    is BufferRow.Archived -> clients.getOrNull(row.client)?.let { bufferActions(controller, stores, row.buffer, it) } ?: emptyList()
    else -> emptyList()
}

private fun networkActions(controller: ShellController, stores: AccountStores, cl: IrcClient, info: NetworkInfo?): List<RowAction> {
    val actions = ArrayList<RowAction>()
    val networkId = cl.settings.networkId
    if (info != null) {
        actions += if (info.connected == true) {
            RowAction("Disconnect") { controller.disconnectNetwork(cl) }
        } else {
            RowAction("Reconnect") { controller.reconnectNetwork(cl) }
        }
    }
    if (networkId.isNotEmpty()) actions += RowAction("Edit Connection") { controller.editConnection(cl) }
    if (networkId.isNotEmpty()) {
        val collapsed = networkId in stores.networkLayout.collapsed.value
        actions += RowAction(if (collapsed) "Expand" else "Collapse") { controller.toggleCollapsed(cl) }
    }
    actions += RowAction("Mark All as Read") {
        for (b in cl.buffers) cl.markRead(b)
        controller.updateMenuState()
    }
    if (info != null && info.systemManaged != true) {
        actions += RowAction("Delete", destructive = true) { controller.confirmDeleteNetwork(cl) }
    }
    return actions
}

private fun bufferActions(controller: ShellController, stores: AccountStores, b: IrcBuffer, cl: IrcClient): List<RowAction> {
    val networkId = cl.settings.networkId
    val pinned = stores.pinList.isPinned(networkId, b.name)
    val pin = RowAction(if (pinned) "Unpin" else "Pin") { controller.setPinned(!pinned, cl, b) }
    val archived = stores.archiveList.isArchived(networkId, b.name)
    val archive = RowAction(if (archived) "Unarchive" else "Archive") { controller.setArchived(!archived, cl, b) }
    val markRead = RowAction("Mark as Read") {
        cl.markRead(b)
        controller.updateMenuState()
    }
    val delete = RowAction("Delete", destructive = true) { controller.confirmDelete(cl, b) }
    val accountActions = networkId.isNotEmpty()   // pins and archives belong to an account network
    return when (b.kind) {
        IrcBufferKind.CHANNEL -> if (b.joined) {
            // Offline the PART can't be sent.
            val connected = cl.state == IrcState.CONNECTED
            val leave = RowAction(if (connected) "Leave" else "Leave (Offline)", destructive = true, enabled = connected) {
                controller.confirmLeave(cl, b)
            }
            val invite = RowAction("Invite to Channel…") { controller.promptInvite(cl, b) }
            val displayOptions = RowAction("Display Options") {
                controller.closeDrawer()
                controller.navigation.displayOptions(cl, b)
            }
            listOf(leave, invite) + (if (accountActions) listOf(pin) else emptyList()) + listOf(markRead, displayOptions)
        } else {
            val rejoin = RowAction("Rejoin") { cl.joinChannel(b.name, b.channelModeArgs['k']) }
            listOf(rejoin) + (if (accountActions) listOf(archive) else emptyList()) + listOf(delete)
        }
        IrcBufferKind.QUERY -> {
            val whois = RowAction("Whois") {
                controller.closeDrawer()
                controller.navigation.whois(cl, b.name)
            }
            listOf(whois) + (if (accountActions) listOf(pin, archive) else emptyList()) + listOf(markRead, delete)
        }
        IrcBufferKind.SERVER -> emptyList()
    }
}

/**
 * The section on every row: Mark All as Read, Join a Channel… and Send a Message… (on the row's network, else
 * asked), Add a Network…, Delete Active Conversations…, Reorder Connections… (2 or more networks) and Reorder
 * Pins… (2 or more pins).
 */
private fun globalActions(controller: ShellController, stores: AccountStores, clients: List<IrcClient>, row: BufferRow): List<RowAction> {
    val client = when (row) {
        is BufferRow.Network -> clients.getOrNull(row.client)
        is BufferRow.Pinned -> clients.getOrNull(row.client)
        is BufferRow.Buffer -> clients.getOrNull(row.client)
        is BufferRow.Archived -> clients.getOrNull(row.client)
        is BufferRow.ArchivesHeader -> clients.getOrNull(row.client)
        is BufferRow.JoinChannel -> clients.getOrNull(row.client)
        BufferRow.PinnedHeader, BufferRow.AddNetwork -> null
    }
    val actions = arrayListOf(
        RowAction("Mark All as Read") { controller.markAllChannelsRead() },
        RowAction("Join a Channel…") { controller.promptJoinChannel(client) },
        RowAction("Send a Message…") { controller.promptSendMessage(client) },
        RowAction("Add a Network…") {
            controller.closeDrawer()
            controller.navigation.addNetwork(null)
        },
        RowAction("Delete Active Conversations…") {
            controller.closeDrawer()
            controller.navigation.conversationCleanup()
        },
    )
    if (controller.networkItems().size >= 2) {
        actions += RowAction("Reorder Connections…") {
            controller.closeDrawer()
            controller.reorderConnections()
        }
    }
    if (stores.pinList.keys.value.size >= 2) {
        actions += RowAction("Reorder Pins…") {
            controller.closeDrawer()
            controller.reorderPins()
        }
    }
    return actions
}
