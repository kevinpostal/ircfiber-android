package com.kevinpostal.fiberirc.ui.actions

import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.Moderation
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Report/block flows shared by the chat and the member list (ports the `UIViewController` extension in
 * `ios/UI/Moderation.swift`). One per session screen: create with [rememberModerationController] and render
 * [ModerationDialogs] once; any screen then calls [confirmBlock], [unblock], [reportMessage] or [showError].
 */
@Stable
class ModerationController(
    val api: SiteApi,
    val blockList: BlockListStore,
    private val scope: CoroutineScope,
) {
    sealed interface Dialog {
        data class ConfirmBlock(val nick: String) : Dialog
        data class Report(val line: IrcLine, val nick: String, val buffer: String, val network: String) : Dialog
        data class ReportSent(val nick: String) : Dialog
        data class Error(val title: String, val message: String) : Dialog
    }

    var dialog: Dialog? by mutableStateOf(null)
        private set

    fun dismiss() {
        dialog = null
    }

    /** "Block <nick>?" with Cancel / Block. */
    fun confirmBlock(nick: String) {
        dialog = Dialog.ConfirmBlock(nick)
    }

    fun block(nick: String) {
        scope.launch { Moderation.block(blockList, nick).onFailure { showError(Moderation.blockFailedTitle(nick), it) } }
    }

    fun unblock(nick: String) {
        scope.launch { Moderation.unblock(blockList, nick).onFailure { showError(Moderation.unblockFailedTitle(nick), it) } }
    }

    /** Asks for an optional note, files the report, then offers to block the sender. */
    fun reportMessage(line: IrcLine, nick: String, buffer: String, network: String) {
        dialog = Dialog.Report(line, nick, buffer, network)
    }

    internal fun submitReport(report: Dialog.Report, note: String) {
        dialog = null
        scope.launch {
            Moderation.reportMessage(api, report.nick, report.line.text, report.buffer, report.network, report.line.time, note)
                .onSuccess { dialog = Dialog.ReportSent(report.nick) }
                .onFailure { showError(Moderation.REPORT_FAILED_TITLE, it) }
        }
    }

    /** An alert with [title] and the error's text. Titles can name a nick: only the error type is logged. */
    fun showError(title: String, error: Throwable) {
        Log.e(TAG, "moderation action failed: ${error.javaClass.simpleName}")
        dialog = Dialog.Error(title, error.message ?: error.javaClass.simpleName)
    }

    companion object {
        const val TAG = "FiberIRC.ui"
    }
}

@Composable
fun rememberModerationController(api: SiteApi, blockList: BlockListStore): ModerationController {
    val scope = rememberCoroutineScope()
    return remember(api, blockList, scope) { ModerationController(api, blockList, scope) }
}

/** Renders whichever [ModerationController] dialog is up. */
@Composable
fun ModerationDialogs(controller: ModerationController) {
    when (val d = controller.dialog) {
        null -> Unit
        is ModerationController.Dialog.ConfirmBlock -> ConfirmBlockDialog(
            nick = d.nick,
            onBlock = { controller.dismiss(); controller.block(d.nick) },
            onDismiss = controller::dismiss,
        )
        is ModerationController.Dialog.Report -> ReportMessageDialog(
            nick = d.nick,
            encrypted = d.line.e2e != null,
            onReport = { note -> controller.submitReport(d, note) },
            onDismiss = controller::dismiss,
        )
        is ModerationController.Dialog.ReportSent -> ReportSentDialog(
            nick = d.nick,
            onBlock = { controller.dismiss(); controller.block(d.nick) },
            onDismiss = controller::dismiss,
        )
        is ModerationController.Dialog.Error -> ErrorDialog(d.title, d.message, controller::dismiss)
    }
}

@Composable
fun ConfirmBlockDialog(nick: String, onBlock: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Moderation.blockTitle(nick)) },
        text = { Text(Moderation.blockMessage(nick)) },
        confirmButton = { DestructiveButton("Block", onBlock) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Report Message" with an optional "Details (optional)" note; [onReport] gets the note trimmed. [encrypted]: the
 * message was end-to-end encrypted, so the dialog says that reporting uploads its decrypted text before it does.
 */
@Composable
fun ReportMessageDialog(nick: String, onReport: (note: String) -> Unit, onDismiss: () -> Unit, encrypted: Boolean = false) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Moderation.REPORT_TITLE) },
        text = {
            Column {
                Text(Moderation.reportPrompt(nick))
                if (encrypted) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This message was end-to-end encrypted. Reporting sends its decrypted text to the ircfiber.com " +
                            "moderators.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Details (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { DestructiveButton(if (encrypted) "Report Decrypted Text" else "Report") { onReport(note.trim()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Report Sent", offering "Block <nick>" (destructive) and OK. */
@Composable
fun ReportSentDialog(nick: String, onBlock: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Moderation.REPORT_SENT_TITLE) },
        text = { Text(Moderation.REPORT_SENT_MESSAGE) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = { DestructiveButton("Block $nick", onBlock) },
    )
}

@Composable
fun ErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** A dialog button in the theme's error colour (UIAlertAction `.destructive`). */
@Composable
internal fun DestructiveButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = LocalFiberIrcColors.current.error) }
}

/** A confirm dialog with only a title and Cancel / [action] (destructive). */
@Composable
internal fun ConfirmDialog(title: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { DestructiveButton(action, onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
