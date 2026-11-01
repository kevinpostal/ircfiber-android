package com.kevinpostal.fiberirc.ui.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.irc.ClientTagFeatures
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * IRCCloud's long-press on a message (ports `ios/UI/Actions/MessageMenu.swift` for which items a line gets and
 * `BufferViewController`'s `menuAction`/`confirmDeleteMessage`/`confirmClearBacklog` for what they do).
 */
object MessageMenu {
    enum class Item { COPY_URL, SHARE_URL, CLOSE_PREVIEW, COPY_MESSAGE, REPLY, EDIT_MESSAGE, DELETE_MESSAGE, REPORT, BLOCK, CLEAR_BACKLOG }

    /** The server's name for a network's server buffer in [clearBacklog]. */
    const val SERVER_BUFFER_NAME = "_server"

    /**
     * `onURL`: the press is on a URL in the line; `preview`: on one of its inline previews (its URL, and Close
     * Preview). `clearBacklog`: the buffer's network belongs to the account (the site clears its history).
     */
    fun items(
        line: IrcLine,
        ownNick: String,
        onURL: Boolean,
        preview: Boolean = false,
        features: ClientTagFeatures,
        clearBacklog: Boolean,
    ): List<Item> {
        val items = ArrayList<Item>()
        if (preview) {
            items += listOf(Item.COPY_URL, Item.SHARE_URL, Item.CLOSE_PREVIEW)
        } else if (onURL) {
            items += listOf(Item.COPY_URL, Item.SHARE_URL)
        }
        items += Item.COPY_MESSAGE
        val sender = line.sender.orEmpty()
        val own = sender.isNotEmpty() && sender.equals(ownNick, ignoreCase = true)
        val live = line.msgid != null && !line.redacted
        val said = line.kind == IrcLineKind.MESSAGE || line.kind == IrcLineKind.ACTION
        if (live && said && features.reply) items += Item.REPLY
        if (own && live && line.kind == IrcLineKind.MESSAGE && features.edit) items += Item.EDIT_MESSAGE
        if (own && live && said && features.redact) items += Item.DELETE_MESSAGE
        if (sender.isNotEmpty() && !own && (said || line.kind == IrcLineKind.NOTICE)) items += listOf(Item.REPORT, Item.BLOCK)
        if (clearBacklog) items += Item.CLEAR_BACKLOG
        return items
    }

    /** `POST /api/networks/<id>/buffers/clear` — deletes the buffer's stored history on the site. */
    suspend fun clearBacklog(api: SiteApi, networkId: String, buffer: IrcBuffer): Result<Unit> {
        val stored = if (buffer.kind == IrcBufferKind.SERVER) SERVER_BUFFER_NAME else buffer.name
        return api.postJson(listOf("api", "networks", networkId, "buffers", "clear"), buildJsonObject { put("buffer", stored) })
            .map { }
    }
}

/** What was long-pressed: a line (or art row), the URL under the press if any, or one of its inline previews. */
data class MessageMenuTarget(val line: IrcLine, val url: String? = null, val preview: Boolean = false)

/** One per chat screen: rows call [open] on long-press; [MessageMenuHost] shows the menu and its confirmations. */
@Stable
class MessageMenuState {
    internal sealed interface Step {
        data class Menu(val target: MessageMenuTarget) : Step
        data class ConfirmDelete(val msgid: String) : Step
        data object ConfirmClearBacklog : Step
    }

    internal var step: Step? by mutableStateOf(null)

    fun open(target: MessageMenuTarget) {
        step = Step.Menu(target)
    }

    fun dismiss() {
        step = null
    }
}

@Composable
fun rememberMessageMenuState(): MessageMenuState = remember { MessageMenuState() }

/**
 * Hosts [state]'s menu for [buffer] of [client]. The chat owns the composer and the thread view, so it supplies
 * [onReply] (open the line's thread, or focus the composer inside one), [onEdit] (load the line into the composer
 * as a `+draft/edit`) and [onPreviewClosed] (rebuild rows after the URL joined `buffer.hiddenMedia`). Clear Backlog
 * empties the buffer through [IrcClient.clearLines], which tells observers to rebuild.
 */
@Composable
fun MessageMenuHost(
    state: MessageMenuState,
    client: IrcClient,
    buffer: IrcBuffer,
    moderation: ModerationController,
    onReply: (IrcLine) -> Unit,
    onEdit: (IrcLine) -> Unit,
    onPreviewClosed: (url: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (val step = state.step) {
        null -> Unit
        is MessageMenuState.Step.Menu -> {
            val (line, url, preview) = step.target
            val networkId = client.settings.networkId
            val items = MessageMenu.items(
                line = line,
                ownNick = client.nick,
                onURL = url != null,
                preview = preview,
                features = client.clientTagFeatures,
                clearBacklog = networkId.isNotEmpty(),
            )
            val nick = line.sender.orEmpty()
            val actions = items.mapNotNull { item ->
                when (item) {
                    MessageMenu.Item.COPY_URL -> url?.let {
                        SheetAction("Copy URL", icon = Icons.Filled.Link) { copyText(context, "URL", it) }
                    }
                    MessageMenu.Item.SHARE_URL -> url?.let {
                        SheetAction("Share URL", icon = Icons.Filled.Share) { shareText(context, it) }
                    }
                    MessageMenu.Item.CLOSE_PREVIEW -> url?.let {
                        SheetAction("Close Preview", icon = Icons.Filled.VisibilityOff) {
                            buffer.hiddenMedia.add(it)
                            onPreviewClosed(it)
                        }
                    }
                    MessageMenu.Item.COPY_MESSAGE -> SheetAction("Copy Message", icon = Icons.Filled.ContentCopy) {
                        copyText(context, "Message", IrcFormatting.strip(line.text))
                    }
                    MessageMenu.Item.REPLY -> SheetAction("Reply", icon = Icons.AutoMirrored.Filled.Reply) { onReply(line) }
                    MessageMenu.Item.EDIT_MESSAGE -> SheetAction("Edit Message", icon = Icons.Filled.Edit) { onEdit(line) }
                    MessageMenu.Item.DELETE_MESSAGE -> line.msgid?.let { msgid ->
                        SheetAction("Delete Message", destructive = true, icon = Icons.Filled.Delete) {
                            state.step = MessageMenuState.Step.ConfirmDelete(msgid)
                        }
                    }
                    MessageMenu.Item.REPORT -> SheetAction("Report Message…", icon = Icons.Filled.Report) {
                        moderation.reportMessage(line, nick, buffer.name, client.settings.networkName)
                    }
                    MessageMenu.Item.BLOCK -> SheetAction("Block $nick", destructive = true, icon = Icons.Filled.PanTool) {
                        moderation.confirmBlock(nick)
                    }
                    MessageMenu.Item.CLEAR_BACKLOG ->
                        SheetAction("Clear Backlog", destructive = true, icon = Icons.Filled.ClearAll) {
                            state.step = MessageMenuState.Step.ConfirmClearBacklog
                        }
                }
            }
            ActionSheet(
                title = null,
                subtitle = null,
                actions = actions,
                // A row that moved on to a confirmation has already replaced `step`.
                onDismiss = { if (state.step === step) state.dismiss() },
            )
        }
        is MessageMenuState.Step.ConfirmDelete -> ConfirmDialog(
            title = "Delete this message?",
            action = "Delete",
            onConfirm = { state.dismiss(); client.redact(step.msgid, buffer) },
            onDismiss = state::dismiss,
        )
        MessageMenuState.Step.ConfirmClearBacklog -> {
            val name = buffer.name
            ConfirmDialog(
                title = "Clear the backlog of $name?",
                action = "Clear",
                onConfirm = {
                    state.dismiss()
                    val networkId = client.settings.networkId
                    if (networkId.isNotEmpty()) {
                        scope.launch {
                            MessageMenu.clearBacklog(moderation.api, networkId, buffer)
                                .onSuccess { client.clearLines(buffer) }
                                .onFailure { moderation.showError("Couldn't clear $name", it) }
                        }
                    }
                },
                onDismiss = state::dismiss,
            )
        }
    }
}

/** The system share sheet for a URL. */
private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, null)
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
    }
}
