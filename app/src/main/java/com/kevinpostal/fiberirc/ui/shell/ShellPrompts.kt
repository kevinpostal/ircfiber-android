package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** Swift `.whitespaces` trim: spaces and tabs, not newlines. */
private fun String.trimmedField(): String =
    trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/**
 * The session's small dialogs ([ShellController.prompt]), shared by the drawer, the chat and the member sheet
 * (ports `Prompts.swift`: Join a Channel, Send a Message, Invite to Channel, "Which network?"), plus the shell's
 * confirmations and error alerts. Opening a buffer goes through [ShellController.open].
 */
@Composable
fun ShellPrompts(controller: ShellController) {
    val p = controller.prompt ?: return
    val dismiss = { controller.prompt = null }
    when (p) {
        is ShellPrompt.ChooseNetwork -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Which network?") },
            text = {
                Column {
                    for (c in p.clients) {
                        TextButton(onClick = { dismiss(); p.then(c) }) { Text(c.settings.networkName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        is ShellPrompt.JoinChannel -> JoinChannelDialog(controller, p.client, dismiss)
        is ShellPrompt.SendMessage -> NickDialog(
            title = "Send a Message",
            message = "Their nickname on ${p.client.settings.networkName}",
            action = "Open",
            tag = "messageNickField",
            onDismiss = dismiss,
        ) { nick -> controller.open(BufferLink.Buffer(IrcSessionManager.networkKey(p.client), nick)) }
        is ShellPrompt.Invite -> NickDialog(
            title = "Invite to ${p.channel.name}",
            message = "Their nickname on ${p.client.settings.networkName}",
            action = "Invite",
            tag = "inviteNickField",
            onDismiss = dismiss,
        ) { nick -> p.client.invite(nick, p.channel) }
        is ShellPrompt.Confirm -> {
            val c = LocalFiberIrcColors.current
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(p.title) },
                text = { Text(p.message) },
                confirmButton = {
                    TextButton(onClick = { dismiss(); p.perform() }) { Text(p.action, color = c.error) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            )
        }
        is ShellPrompt.Error -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(p.title) },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
        )
    }
}

/** Join a Channel: a channel field, a Key field and "Browse Channels…" (the server's channel list). */
@Composable
private fun JoinChannelDialog(controller: ShellController, client: IrcClient, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val canJoin = name.trimmedField().isNotEmpty()
    fun join() {
        if (!canJoin) return
        dismiss()
        controller.join(client, name.trimmedField(), key.trimmedField())
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Join a Channel") },
        text = {
            Column {
                Text(client.settings.networkName)
                Spacer(Modifier.height(Grid.gutter))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("#channel") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.testTag("joinChannelField"),
                )
                Spacer(Modifier.height(Grid.gutter))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = { Text("Key (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { join() }),
                    modifier = Modifier.testTag("joinKeyField"),
                )
                TextButton(onClick = {
                    dismiss()
                    controller.closeDrawer()
                    controller.navigation.browseChannels(client)
                }) { Text("Browse Channels…") }
            }
        },
        confirmButton = { TextButton(onClick = ::join, enabled = canJoin) { Text("Join") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

/** A nickname dialog whose [action] is enabled only while the field holds a valid nickname. */
@Composable
private fun NickDialog(
    title: String,
    message: String,
    action: String,
    tag: String,
    onDismiss: () -> Unit,
    perform: (String) -> Unit,
) {
    var nick by remember { mutableStateOf("") }
    val valid = IrcClient.isValidNick(nick.trimmedField())
    fun go() {
        if (!valid) return
        onDismiss()
        perform(nick.trimmedField())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(Grid.gutter))
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    placeholder = { Text("Nickname") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.testTag(tag),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::go, enabled = valid) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
