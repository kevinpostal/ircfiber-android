package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings → Blocked Users (ports `BlockedUsersViewController`): the nicks hidden by the account's shared ignore
 * list; swipe a row to unblock, or block a nick from the Add field. Both save the whole list
 * (POST /api/me/ignores) and apply only on success ([BlockListStore]).
 */
@Composable
fun BlockedUsersScreen(blockList: BlockListStore, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    val masks by blockList.masks.collectAsStateWithLifecycle()
    val nicks = masks.mapNotNull(BlockListStore::nickPattern)
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun run(failure: String, action: suspend () -> Unit) {
        scope.launch {
            saving = true
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alerts.show(failure, e)
            } finally {
                saving = false
            }
        }
    }

    fun add() {
        val nick = draft.trim()
        if (nick.isEmpty() || saving) return
        if (!IrcClient.isValidNick(nick)) {
            alerts.show("Couldn't block $nick", "Not a valid nickname.")
            return
        }
        run("Couldn't block $nick") {
            blockList.block(nick)
            draft = ""
        }
    }

    SettingsScaffold(title = "Blocked Users", onBack = onBack) {
        item(key = "add") {
            Row(Modifier.fillMaxWidth().padding(top = Grid.section), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Nickname") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.groupedCell,
                        unfocusedContainerColor = c.groupedCell,
                        focusedTextColor = c.chatText,
                        unfocusedTextColor = c.chatText,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::add, enabled = !saving && draft.isNotBlank()) { Text("Add") }
            }
        }
        if (nicks.isEmpty()) {
            item(key = "empty") { SettingsRow("No blocked users", titleColor = c.textSecondary, centered = true) }
        } else {
            settingsSection(
                key = "nicks",
                rows = nicks.map { nick ->
                    {
                        SwipeActionRow("Unblock", c.error, onSwipe = { run("Couldn't unblock $nick") { blockList.unblock(nick) } }) {
                            SettingsRow(nick)
                        }
                    }
                },
            )
        }
    }
    ErrorDialog(alerts)
}
