package com.kevinpostal.fiberirc.ui.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcMember

/**
 * IRCCloud's user sheet (ports `ios/UI/Actions/UserActionSheet.swift`): what you can do with a channel member —
 * Whois, Send a Message, Mention, Invite to Channel…, Ignore/Unignore; Op/Deop for ops and above; Voice/Devoice,
 * Kick… and Ban… for half-ops and above; Copy Hostmask when the host is known.
 *
 * Usage: `val users = rememberUserActionsState()`, `users.open(member, channel, client)` from a tap, and one
 * [UserActionsHost] in the same screen (it also shows the follow-up picker and Kick/Ban prompts).
 */
@Stable
class UserActionsState {
    internal data class Target(val member: IrcMember, val channel: IrcBuffer, val client: IrcClient)

    internal sealed interface Step {
        data object Sheet : Step
        data class Invite(val channels: List<IrcBuffer>) : Step
        data object Kick : Step
        data class Ban(val mask: String) : Step
    }

    internal var target: Target? by mutableStateOf(null)
    internal var step: Step? by mutableStateOf(null)

    /** Presents the sheet for [member] of [channel]. */
    fun open(member: IrcMember, channel: IrcBuffer, client: IrcClient) {
        target = Target(member, channel, client)
        step = Step.Sheet
    }

    fun dismiss() {
        step = null
        target = null
    }
}

@Composable
fun rememberUserActionsState(): UserActionsState = remember { UserActionsState() }

/**
 * Hosts [state]'s sheet. [onSendMessage] and [onMention] are null outside a session (iOS shows them only when the
 * presenter sits inside `SessionViewController`); [onMention] should also close the drawer.
 */
@Composable
fun UserActionsHost(
    state: UserActionsState,
    moderation: ModerationController,
    onWhois: (client: IrcClient, nick: String) -> Unit,
    onSendMessage: ((client: IrcClient, nick: String) -> Unit)?,
    onMention: ((nick: String) -> Unit)?,
) {
    val target = state.target ?: return
    val (member, channel, client) = target
    val nick = member.nick
    when (val step = state.step) {
        null -> Unit
        UserActionsState.Step.Sheet -> {
            val masks by moderation.blockList.masks.collectAsState()
            val blocked = remember(masks, nick) { moderation.blockList.isBlocked(nick) }
            val context = LocalContext.current
            val hostmask = hostmask(member)
            val actions = userActions(
                member = member,
                channel = channel,
                client = client,
                blocked = blocked,
                onWhois = { onWhois(client, nick) },
                onSendMessage = onSendMessage?.let { f -> { f(client, nick) } },
                onMention = onMention?.let { f -> { f(nick) } },
                onInvite = { others -> state.step = UserActionsState.Step.Invite(others) },
                onIgnore = { moderation.confirmBlock(nick) },
                onUnignore = { moderation.unblock(nick) },
                onKick = { state.step = UserActionsState.Step.Kick },
                onBan = { mask -> state.step = UserActionsState.Step.Ban(mask) },
                onCopyHostmask = { copyText(context, "Hostmask", it) },
            )
            ActionSheet(
                title = if (hostmask != null) member.realname.ifEmpty { nick } else nick,
                subtitle = hostmask?.let { "($it)" },
                actions = actions,
                // A row that moves on to a follow-up step has already replaced `step`; only a plain dismiss clears.
                onDismiss = { if (state.step == UserActionsState.Step.Sheet) state.dismiss() },
            )
        }
        is UserActionsState.Step.Invite -> ActionSheet(
            title = "Invite $nick to",
            subtitle = null,
            actions = step.channels.map { b -> SheetAction(b.name) { client.invite(nick, b) } },
            onDismiss = state::dismiss,
        )
        UserActionsState.Step.Kick -> KickDialog(
            nick = nick,
            channel = channel.name,
            onKick = { reason -> state.dismiss(); client.kick(nick, channel, reason) },
            onDismiss = state::dismiss,
        )
        is UserActionsState.Step.Ban -> ConfirmDialog(
            title = "Ban ${step.mask} from ${channel.name}?",
            action = "Ban",
            onConfirm = { state.dismiss(); client.setMode("+b", listOf(step.mask), channel) },
            onDismiss = state::dismiss,
        )
    }
}

/** `nick!user@host` (`*` for an unknown user) when the host is known. */
internal fun hostmask(member: IrcMember): String? =
    if (member.host.isEmpty()) null else "${member.nick}!${member.user.ifEmpty { "*" }}@${member.host}"

/**
 * The sheet's rows in the iOS order. Your rank in [channel] gates Op/Deop (≥3) and Voice/Devoice, Kick…, Ban… (≥2);
 * Invite to Channel… needs another joined channel; Ignore/Unignore is never offered for yourself.
 */
internal fun userActions(
    member: IrcMember,
    channel: IrcBuffer,
    client: IrcClient,
    blocked: Boolean,
    onWhois: () -> Unit,
    onSendMessage: (() -> Unit)?,
    onMention: (() -> Unit)?,
    onInvite: (List<IrcBuffer>) -> Unit,
    onIgnore: () -> Unit,
    onUnignore: () -> Unit,
    onKick: () -> Unit,
    onBan: (mask: String) -> Unit,
    onCopyHostmask: (String) -> Unit,
): List<SheetAction> {
    val nick = member.nick
    val out = ArrayList<SheetAction>()
    out += SheetAction("Whois", onClick = onWhois)
    if (onSendMessage != null) out += SheetAction("Send a Message", onClick = onSendMessage)
    if (onMention != null) out += SheetAction("Mention", onClick = onMention)
    val others = client.buffers.filter { it.kind == IrcBufferKind.CHANNEL && it.joined && it !== channel }
    if (others.isNotEmpty()) out += SheetAction("Invite to Channel…") { onInvite(others) }
    if (!nick.equals(client.nick, ignoreCase = true)) {
        out += if (blocked) SheetAction("Unignore", onClick = onUnignore) else SheetAction("Ignore", onClick = onIgnore)
    }
    val rank = channel.member(client.nick)?.rank ?: 0
    if (rank >= 3) {
        val opped = member.prefixes.contains('@')
        out += SheetAction(if (opped) "Deop" else "Op") {
            client.setMode(if (opped) "-o" else "+o", listOf(nick), channel)
        }
    }
    if (rank >= 2) {
        val voiced = member.prefixes.contains('+')
        out += SheetAction(if (voiced) "Devoice" else "Voice") {
            client.setMode(if (voiced) "-v" else "+v", listOf(nick), channel)
        }
        out += SheetAction("Kick…", onClick = onKick)
        out += SheetAction("Ban…") { onBan(IrcClient.banMask(nick, member.host)) }
    }
    hostmask(member)?.let { mask -> out += SheetAction("Copy Hostmask") { onCopyHostmask(mask) } }
    return out
}

/** "Kick <nick> from <channel>?" with an optional reason; [onKick] gets it trimmed. */
@Composable
private fun KickDialog(nick: String, channel: String, onKick: (reason: String) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kick $nick from $channel?") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = { Text("Reason (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { DestructiveButton("Kick") { onKick(reason.trim()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Puts [text] on the system clipboard. */
internal fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
