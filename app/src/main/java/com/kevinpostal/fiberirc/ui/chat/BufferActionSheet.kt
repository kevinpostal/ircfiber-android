package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/**
 * IRCCloud's gear menu (`MainViewController.m:3707-3768`), ported from `ios/UI/Actions/BufferActionSheet.swift`:
 * Pin/Unpin, then the buffer kind's actions, then the ones every buffer has, then Sign Out and Settings together.
 * Unlike iOS's `UIMenu` (which opens upward and renders its sections reversed), the Compose dropdown opens
 * downward from the gear, so the sections render in declared order.
 */
enum class BufferActionItem(val title: String) {
    PIN("Pin"),
    UNPIN("Unpin"),
    RECONNECT("Reconnect"),
    DISCONNECT("Disconnect"),
    EDIT_CONNECTION("Edit Connection"),
    LEAVE("Leave"),
    BAN_LIST("Ban List"),
    INVITE_TO_CHANNEL("Invite to Channel"),
    SET_TOPIC("Set Topic"),
    SEARCH("Search"),
    REJOIN("Rejoin"),
    ARCHIVE("Archive"),
    UNARCHIVE("Unarchive"),
    DELETE("Delete"),
    WHOIS("Whois"),
    IGNORE_LIST("Ignore List"),
    ADD_NETWORK("Add Network"),
    JOIN_CHANNEL("Join a Channel"),
    DISPLAY_OPTIONS("Display Options"),
    EXPORT_LOG("Export Log"),
    SETTINGS("Settings"),
    SEND_FEEDBACK("Send Feedback"),
    SIGN_OUT("Sign Out"),
    ;

    /** Red text and icon. */
    val isDestructive: Boolean get() = this == LEAVE || this == DELETE || this == SIGN_OUT

    /** Sent on the IRC link (PART, INVITE, TOPIC, JOIN): disabled while it is down. */
    val needsLink: Boolean get() = this == LEAVE || this == INVITE_TO_CHANNEL || this == SET_TOPIC || this == REJOIN

    val icon: ImageVector
        get() = when (this) {
            PIN -> Icons.Outlined.PushPin
            UNPIN -> Icons.Filled.PushPin
            RECONNECT -> Icons.Outlined.Bolt
            DISCONNECT -> Icons.Outlined.FlashOff
            EDIT_CONNECTION -> Icons.Outlined.Edit
            LEAVE -> Icons.AutoMirrored.Outlined.Logout
            BAN_LIST -> Icons.Outlined.Block
            INVITE_TO_CHANNEL -> Icons.Outlined.PersonAdd
            SET_TOPIC -> Icons.Outlined.ChatBubbleOutline
            SEARCH -> Icons.Outlined.Search
            REJOIN -> Icons.AutoMirrored.Outlined.Undo
            ARCHIVE -> Icons.Outlined.Archive
            UNARCHIVE -> Icons.Outlined.Unarchive
            DELETE -> Icons.Outlined.Delete
            WHOIS -> Icons.Outlined.ContactPage
            IGNORE_LIST -> Icons.Outlined.BackHand
            ADD_NETWORK -> Icons.Outlined.Lan
            JOIN_CHANNEL -> Icons.Outlined.AddComment
            DISPLAY_OPTIONS -> Icons.Outlined.Tune
            EXPORT_LOG -> Icons.Outlined.IosShare
            SETTINGS -> Icons.Outlined.Settings
            SEND_FEEDBACK -> Icons.Outlined.Email
            SIGN_OUT -> Icons.AutoMirrored.Outlined.ExitToApp
        }
}

/** What the menu depends on (`BufferActionSheet.Context`). */
data class BufferActionContext(
    val kind: IrcBufferKind,
    /** A channel you are in (false for a parted one). */
    val joined: Boolean,
    /** The buffer's network belongs to the account (it has a network id): pins, archives and the REST network calls. */
    val accountNetwork: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    /** The network's upstream link per `GET /api/networks`; null while unknown (Reconnect/Disconnect hidden). */
    val networkConnected: Boolean?,
    /** Your rank in the channel (`IrcMember.rank`): half-ops (2) and above get Ban List. */
    val rank: Int,
)

/** The menu's four sections in display order (`BufferActionSheet.Groups`). */
data class BufferActionGroups(
    val pinned: List<BufferActionItem>,
    val contextual: List<BufferActionItem>,
    val common: List<BufferActionItem>,
    val last: List<BufferActionItem>,
) {
    val sections: List<List<BufferActionItem>> get() = listOf(pinned, contextual, common, last)
    val items: List<BufferActionItem> get() = pinned + contextual + common + last
}

object BufferActionSheet {
    fun groups(c: BufferActionContext): BufferActionGroups {
        val pinned = if (c.kind != IrcBufferKind.SERVER && c.accountNetwork) {
            listOf(if (c.pinned) BufferActionItem.UNPIN else BufferActionItem.PIN)
        } else {
            emptyList()
        }
        val contextual = ArrayList<BufferActionItem>()
        val archive = if (c.archived) BufferActionItem.UNARCHIVE else BufferActionItem.ARCHIVE
        when {
            c.kind == IrcBufferKind.SERVER -> {
                c.networkConnected?.let {
                    contextual.add(if (it) BufferActionItem.DISCONNECT else BufferActionItem.RECONNECT)
                }
                if (c.accountNetwork) contextual.add(BufferActionItem.EDIT_CONNECTION)
            }
            c.kind == IrcBufferKind.CHANNEL && c.joined -> {
                contextual.add(BufferActionItem.LEAVE)
                if (c.rank >= 2) contextual.add(BufferActionItem.BAN_LIST)
                contextual += listOf(BufferActionItem.INVITE_TO_CHANNEL, BufferActionItem.SET_TOPIC, BufferActionItem.SEARCH)
            }
            c.kind == IrcBufferKind.CHANNEL -> {
                contextual.add(BufferActionItem.REJOIN)
                if (c.accountNetwork) contextual.add(archive)
                contextual.add(BufferActionItem.DELETE)
            }
            else -> {
                contextual += listOf(BufferActionItem.WHOIS, BufferActionItem.SEARCH)
                if (c.accountNetwork) contextual.add(archive)
                contextual.add(BufferActionItem.DELETE)
            }
        }
        val common = listOf(
            BufferActionItem.IGNORE_LIST, BufferActionItem.ADD_NETWORK, BufferActionItem.JOIN_CHANNEL,
            BufferActionItem.DISPLAY_OPTIONS, BufferActionItem.EXPORT_LOG, BufferActionItem.SEND_FEEDBACK,
        )
        return BufferActionGroups(pinned, contextual, common, listOf(BufferActionItem.SIGN_OUT, BufferActionItem.SETTINGS))
    }
}

/**
 * One handler per gear-menu item; the chat screen wires them to real actions (management screens, `SiteApi`
 * reconnect/disconnect, sign-out, …). This menu only decides which items show and calls back.
 */
class BufferActionCallbacks(
    val onPin: () -> Unit = {},
    val onUnpin: () -> Unit = {},
    val onReconnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onEditConnection: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onBanList: () -> Unit = {},
    val onInviteToChannel: () -> Unit = {},
    val onSetTopic: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onRejoin: () -> Unit = {},
    val onArchive: () -> Unit = {},
    val onUnarchive: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onWhois: () -> Unit = {},
    val onIgnoreList: () -> Unit = {},
    val onAddNetwork: () -> Unit = {},
    val onJoinChannel: () -> Unit = {},
    val onDisplayOptions: () -> Unit = {},
    val onExportLog: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onSendFeedback: () -> Unit = {},
    val onSignOut: () -> Unit = {},
) {
    fun perform(item: BufferActionItem) = when (item) {
        BufferActionItem.PIN -> onPin()
        BufferActionItem.UNPIN -> onUnpin()
        BufferActionItem.RECONNECT -> onReconnect()
        BufferActionItem.DISCONNECT -> onDisconnect()
        BufferActionItem.EDIT_CONNECTION -> onEditConnection()
        BufferActionItem.LEAVE -> onLeave()
        BufferActionItem.BAN_LIST -> onBanList()
        BufferActionItem.INVITE_TO_CHANNEL -> onInviteToChannel()
        BufferActionItem.SET_TOPIC -> onSetTopic()
        BufferActionItem.SEARCH -> onSearch()
        BufferActionItem.REJOIN -> onRejoin()
        BufferActionItem.ARCHIVE -> onArchive()
        BufferActionItem.UNARCHIVE -> onUnarchive()
        BufferActionItem.DELETE -> onDelete()
        BufferActionItem.WHOIS -> onWhois()
        BufferActionItem.IGNORE_LIST -> onIgnoreList()
        BufferActionItem.ADD_NETWORK -> onAddNetwork()
        BufferActionItem.JOIN_CHANNEL -> onJoinChannel()
        BufferActionItem.DISPLAY_OPTIONS -> onDisplayOptions()
        BufferActionItem.EXPORT_LOG -> onExportLog()
        BufferActionItem.SETTINGS -> onSettings()
        BufferActionItem.SEND_FEEDBACK -> onSendFeedback()
        BufferActionItem.SIGN_OUT -> onSignOut()
    }
}

/**
 * The gear's dropdown. Place it inside the gear button's `Box` (pass it as `ComposerBar(gearMenu = { … })`) so it
 * anchors there. Items are rebuilt from [context] every time it opens; empty sections are skipped and a divider
 * separates the rest. An item whose `needsLink` is true is disabled while [linkUp] is false. Picking an item
 * dismisses the menu, then calls [callbacks].
 */
@Composable
fun BufferActionSheet(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    context: BufferActionContext,
    linkUp: Boolean,
    callbacks: BufferActionCallbacks,
    modifier: Modifier = Modifier,
) {
    val c = LocalFiberIrcColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier.testTag("bufferGearMenu")) {
        if (!expanded) return@DropdownMenu
        val sections = BufferActionSheet.groups(context).sections.filter { it.isNotEmpty() }
        sections.forEachIndexed { index, section ->
            if (index > 0) HorizontalDivider()
            for (item in section) {
                val colors = if (item.isDestructive) {
                    MenuDefaults.itemColors(textColor = c.offlineError, leadingIconColor = c.offlineError)
                } else {
                    MenuDefaults.itemColors()
                }
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        onDismissRequest()
                        callbacks.perform(item)
                    },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                    enabled = linkUp || !item.needsLink,
                    colors = colors,
                    modifier = Modifier.testTag("bufferAction_${item.name}"),
                )
            }
        }
    }
}
