package com.kevinpostal.fiberirc.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.media.MediaUrls
import com.kevinpostal.fiberirc.data.media.Snippet
import com.kevinpostal.fiberirc.data.media.UploadFile
import com.kevinpostal.fiberirc.ui.settings.ErrorDialog
import com.kevinpostal.fiberirc.ui.settings.rememberAlertState
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch

/**
 * A new text snippet (ports `SnippetEditorViewController`, IRCCloud's pastebin editor): a name (`snippet.txt`), a
 * syntax, the monospaced text and an optional message. Post Snippet saves it on the site and sends
 * `"<message> <snippet page>"` to [buffer]; Send as Messages sends the text line by line instead
 * ([Snippet.messageLines]). Both need text. [onSent] runs after either went out (the composer clears its draft),
 * then [onDismiss]. Presented full screen over its caller.
 */
@Composable
fun SnippetEditorScreen(
    client: IrcClient,
    buffer: IrcBuffer,
    media: MediaApi,
    initialBody: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit = {},
) {
    FullScreenDialog(onDismiss) { SnippetEditorContent(client, buffer, media, initialBody, onDismiss, onSent) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditorContent(
    client: IrcClient,
    buffer: IrcBuffer,
    media: MediaApi,
    initialBody: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val alert = rememberAlertState()
    val focus = LocalFocusManager.current
    val bodyFocus = remember { FocusRequester() }
    var name by rememberSaveable { mutableStateOf(Snippet.DEFAULT_NAME) }
    var syntax by rememberSaveable { mutableStateOf(Snippet.syntaxes[0]) }
    var body by rememberSaveable { mutableStateOf(initialBody) }
    var message by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var syntaxMenu by remember { mutableStateOf(false) }
    val hasText = body.isNotBlank()

    fun finish() {
        onSent()
        onDismiss()
    }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { if (initialBody.isEmpty()) runCatching { bodyFocus.requestFocus() } }

    Scaffold(
        containerColor = c.groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Text Snippet") },
                navigationIcon = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.link) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.groupedBackground, titleContentColor = c.heading),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().padding(Grid.margin),
            verticalArrangement = Arrangement.spacedBy(Grid.gutter),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Name", color = c.textTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { bodyFocus.requestFocus() } }),
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { syntaxMenu = true }) {
                        Text(syntax, color = c.link)
                        Icon(Icons.Filled.UnfoldMore, contentDescription = "Syntax", tint = c.link, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = syntaxMenu, onDismissRequest = { syntaxMenu = false }) {
                        for (s in Snippet.syntaxes) {
                            DropdownMenuItem(
                                text = { Text(s) },
                                onClick = {
                                    syntax = s
                                    syntaxMenu = false
                                },
                                trailingIcon = if (s == syntax) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Snippet", color = c.textTertiary) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp).focusRequester(bodyFocus),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                placeholder = { Text("Add a message", color = c.textTertiary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (client.lockDecision(buffer.name) != IrcLockDecision.OFF) {
                Text(ENCRYPTED_UPLOAD_NOTE.replace("uploaded file", "snippet"), color = c.textSecondary, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                FilledTonalButton(
                    enabled = hasText && !busy,
                    onClick = {
                        for (line in Snippet.messageLines(body)) client.sendInput(line, buffer)
                        finish()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = Grid.tapTarget),
                ) { Text("Send as Messages") }
                Button(
                    enabled = hasText && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                    onClick = {
                        busy = true
                        val trimmed = name.trim()
                        scope.launch {
                            media.createPastebin(
                                name = trimmed.ifEmpty { Snippet.DEFAULT_NAME },
                                body = body,
                                syntax = syntax,
                                networkId = client.settings.networkId,
                                buffer = buffer.name,
                            ).onSuccess { paste ->
                                client.sendInput(UploadFile.message(message, MediaUrls.pasteUrl(paste.id, media.siteBase)), buffer)
                                finish()
                            }.onFailure {
                                busy = false
                                alert.show("Couldn't post the snippet", it)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = Grid.tapTarget),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.heading)
                    } else {
                        Text("Post Snippet")
                    }
                }
            }
        }
    }
    ErrorDialog(alert)
}
