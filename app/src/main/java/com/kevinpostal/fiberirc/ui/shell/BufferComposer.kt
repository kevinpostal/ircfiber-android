package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.irc.ComposeStyle
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcState
import com.kevinpostal.fiberirc.data.irc.IrcEncryptionConfig
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.irc.TypingState
import com.kevinpostal.fiberirc.data.media.Snippet
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.ui.actions.UploadActionSheet
import com.kevinpostal.fiberirc.ui.chat.BufferActionCallbacks
import com.kevinpostal.fiberirc.ui.chat.BufferActionContext
import com.kevinpostal.fiberirc.ui.chat.BufferActionSheet
import com.kevinpostal.fiberirc.ui.chat.Completion
import com.kevinpostal.fiberirc.ui.chat.ComposerBar
import com.kevinpostal.fiberirc.ui.chat.ComposerCompletion
import com.kevinpostal.fiberirc.ui.chat.ComposerSend
import com.kevinpostal.fiberirc.ui.chat.CompletionSuggestion
import com.kevinpostal.fiberirc.ui.chat.CompletionKind
import com.kevinpostal.fiberirc.ui.chat.applyCompletion
import com.kevinpostal.fiberirc.ui.chat.insertMention
import com.kevinpostal.fiberirc.ui.chat.insertReply
import com.kevinpostal.fiberirc.ui.media.LogExportDialog
import com.kevinpostal.fiberirc.ui.media.SnippetEditorScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kevinpostal.fiberirc.ui.encryption.AudienceSheet
import com.kevinpostal.fiberirc.ui.encryption.ComposerEncryptionBanner
import com.kevinpostal.fiberirc.ui.encryption.ComposerLockControl
import com.kevinpostal.fiberirc.ui.encryption.UnlockDialog
import com.kevinpostal.fiberirc.ui.encryption.rememberComposerEncryption
import java.time.Instant

/** Gear-menu items that open screens the session host owns. */
class BufferComposerActions(
    val banList: (IrcClient, IrcBuffer) -> Unit,
    val search: (IrcClient, IrcBuffer) -> Unit,
    val ignoreList: () -> Unit,
    val signOut: suspend () -> Unit,
)

/**
 * The chat's composer (ports the composer half of `BufferViewController`): [ComposerBar] over the shown buffer's
 * draft (a thread's composer keeps none), nick/channel/emoji completion, "is typing" both ways, Edit Message,
 * replies inside a thread, `/paste` and the long-text snippet offer, `/list` as the channel browser, the upload
 * sheet and the gear menu ([BufferActionSheet]). The whole-message style comes from [DeviceSettingsStore]; a
 * styled send resets it when Clear Formatting After Sending is on.
 *
 * Bare nicknames open after two code points and 250 ms idle, then refilter immediately. Picking preserves the
 * token's following words and keyboard; Return sends the literal draft. Focus/IME dismissal and buffer/thread
 * changes cancel automatic opening. Hardware Tab keeps its synchronous snapshot, even with suggestions disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BufferComposer(
    shown: ShownBuffer,
    thread: String?,
    controller: ShellController,
    deps: Deps,
    actions: BufferComposerActions,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val client = shown.client
    val buffer = shown.buffer
    val device by deps.deviceSettings.settings.collectAsState()
    val display by deps.accountSettings.display.collectAsState()
    val infos by controller.manager.networkInfos.collectAsState()
    val revision by controller.manager.revision.collectAsState()

    var value by remember(buffer, thread) {
        val draft = if (thread == null) buffer.draft else ""
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    /** Edit Message: the msgid the field's text replaces when sent; cleared by sending or emptying the field. */
    var editingMsgid by remember(buffer, thread) { mutableStateOf<String?>(null) }
    /** The edit the send in progress carries (only its first line edits). */
    var sendingEdit by remember(buffer, thread) { mutableStateOf<String?>(null) }
    val completion = remember(buffer, thread) { ComposerCompletion() }
    var completionRevision by remember(buffer, thread) { mutableLongStateOf(-1) }
    val fieldFocus = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    var automaticNickVisible by remember(buffer, thread) { mutableStateOf(false) }
    val showSuggestions = controller.effective(BufferPrefsStore.Key.NICK_SUGGESTIONS, client, buffer)
    val imeVisible = WindowInsets.isImeVisible
    var previouslyVisibleIme by remember { mutableStateOf(imeVisible) }
    var automaticDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (previouslyVisibleIme && !imeVisible) {
            automaticDismissed = true
            automaticNickVisible = false
        } else if (imeVisible) {
            automaticDismissed = false
        }
        previouslyVisibleIme = imeVisible
    }

    var gearOpen by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var topicDraft by remember { mutableStateOf<String?>(null) }
    /** The snippet editor's text, and whether it was offered for the field's text (sent → the field clears). */
    var snippet by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var typingText by remember(buffer) { mutableStateOf("") }

    val suggest: (Completion.Word) -> List<CompletionSuggestion> = { w ->
        val channels = client.buffers.filter { it.kind == IrcBufferKind.CHANNEL }.map { it.name }
        Completion.suggestions(w, buffer, channels, client.nick, Completion.recentSenders(buffer.lines))
    }
    val latestSuggest by rememberUpdatedState(suggest)
    fun bareWord(v: TextFieldValue): Completion.Word? =
        Completion.word(v.text, v.selection.start)?.takeIf { it.word.first() !in "/@#&:" }
    fun automaticEligible(v: TextFieldValue): Boolean {
        val word = bareWord(v) ?: return false
        return inputFocused && !automaticDismissed && showSuggestions && v.selection.collapsed && v.composition == null &&
            word.word.codePointCount(0, word.word.length) >= 2
    }

    // Keep the synchronous engine available to hardware Tab; only automatic presentation waits.
    // A visible automatic row refilters immediately. Never refresh a manual Tab snapshot on roster changes.
    LaunchedEffect(buffer, thread, value.text, value.selection, value.composition, inputFocused,
        showSuggestions, automaticDismissed, revision, completion.suggestions, completion.selection) {
        if (completion.selection != null) {
            automaticNickVisible = false
            return@LaunchedEffect
        }
        if (!automaticEligible(value)) {
            automaticNickVisible = false
            return@LaunchedEffect
        }
        if (completionRevision != revision || completion.range == null) {
            completion.refresh(value, latestSuggest)
            completionRevision = revision
        }
        if (completion.suggestions.isEmpty()) {
            automaticNickVisible = false
            return@LaunchedEffect
        }
        if (automaticNickVisible) return@LaunchedEffect
        val openingDraft = value
        delay(250)
        if (value != openingDraft || !automaticEligible(value) || completion.selection != null) return@LaunchedEffect
        completion.refresh(value, latestSuggest)
        completionRevision = revision
        automaticNickVisible = completion.suggestions.isNotEmpty()
    }

    val visibleSuggestions = when {
        !inputFocused || value.composition != null || !value.selection.collapsed -> emptyList()
        completion.selection != null -> completion.suggestions
        bareWord(value) != null -> if (automaticEligible(value) && automaticNickVisible) completion.suggestions else emptyList()
        Completion.word(value.text, value.selection.start)?.word?.firstOrNull()?.let { it in "@#&:" } == true -> completion.suggestions
        else -> emptyList()
    }

    /** The field's new value: the draft follows it, and typing is shared while it holds a message. */
    fun setText(new: TextFieldValue) {
        val old = value
        value = new
        if (!automaticEligible(new)) automaticNickVisible = false
        if (thread == null) buffer.draft = new.text
        if (old.text == new.text) return
        if (new.text.isEmpty()) {
            editingMsgid = null
            client.sendTyping(TypingState.DONE, buffer)
        } else if (!new.text.startsWith("/") && device.shareTyping &&
            controller.effective(BufferPrefsStore.Key.TYPING, client, buffer) && client.clientTagFeatures.typing
        ) {
            client.sendTyping(TypingState.ACTIVE, buffer)
        }
    }

    fun edit(new: TextFieldValue) {
        val old = value
        if (old.text != new.text || old.selection != new.selection) automaticDismissed = false
        setText(new)
        completion.onEdit(old, new, suggest)
        if (old.composition != new.composition) completion.refresh(new, suggest)
        if (old.text != new.text || old.selection != new.selection || old.composition != new.composition) completionRevision = revision
        if (completion.suggestions.isEmpty()) automaticNickVisible = false
    }

    /** Clear Formatting After Sending: a styled send moves the style to "last" and starts over unstyled. */
    fun resetStyle(wasStyled: Boolean) {
        if (!wasStyled || !device.clearFormattingAfterSending) return
        deps.deviceSettings.update { it.copy(composeStyleLast = it.composeStyle, composeStyle = ComposeStyle.Default) }
    }

    fun wasStyled(plain: String) = !ComposerSend.isCommand(plain) && device.composeStyle.isActive()

    // Mention, reply-to-sender, Edit and Focus requests from the member sheet, the chat and the message menu.
    val request = controller.composerRequest
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        when (r) {
            is ComposerRequest.Mention -> edit(insertMention(value, r.nick))
            is ComposerRequest.ReplyToSender -> edit(insertReply(value, r.nick))
            is ComposerRequest.Edit -> r.line.msgid?.let { msgid ->
                edit(TextFieldValue(r.line.text, TextRange(r.line.text.length)))
                editingMsgid = msgid
            }
            ComposerRequest.Focus -> Unit
        }
        controller.consumeComposerRequest(r)
        runCatching { fieldFocus.requestFocus() }
    }

    // "a is typing": lapsed states are dropped every second while anyone is still typing.
    LaunchedEffect(buffer, revision) {
        while (true) {
            val now = Instant.now()
            buffer.typing.entries.removeAll { !it.value.isAfter(now) }
            val nicks = buffer.typing.keys.sorted().map { key ->
                buffer.member(key)?.nick ?: if (buffer.name.lowercase() == key) buffer.name else key
            }
            typingText = TypingText.text(nicks)
            if (nicks.isEmpty()) break
            delay(1000)
        }
    }

    val networkId = client.settings.networkId
    val gearContext = BufferActionContext(
        kind = buffer.kind,
        joined = buffer.joined,
        accountNetwork = networkId.isNotEmpty(),
        pinned = deps.pinList.isPinned(networkId, buffer.name),
        archived = deps.archiveList.isArchived(networkId, buffer.name),
        networkConnected = infos.firstOrNull { it.id == networkId }?.let { it.connected == true },
        rank = buffer.member(client.nick)?.rank ?: 0,
        encryption = controller.manager.e2ee != null && client.encryption is IrcEncryptionConfig.Encrypted && networkId.isNotEmpty(),
    )
    val callbacks = BufferActionCallbacks(
        onPin = { controller.setPinned(true, client, buffer) },
        onUnpin = { controller.setPinned(false, client, buffer) },
        onReconnect = { controller.reconnectNetwork(client) },
        onDisconnect = { controller.disconnectNetwork(client) },
        onEditConnection = { controller.editConnection(client) },
        onLeave = { controller.confirmLeave(client, buffer) },
        onBanList = { actions.banList(client, buffer) },
        onInviteToChannel = { controller.promptInvite(client, buffer) },
        onSetTopic = { topicDraft = buffer.topic.orEmpty() },
        onSearch = { actions.search(client, buffer) },
        onRejoin = { client.joinChannel(buffer.name, buffer.channelModeArgs['k']) },
        onArchive = { controller.setArchived(true, client, buffer) },
        onUnarchive = { controller.setArchived(false, client, buffer) },
        onDelete = { controller.confirmDelete(client, buffer) },
        onWhois = { controller.navigation.whois(client, buffer.name) },
        onIgnoreList = actions.ignoreList,
        onAddNetwork = { controller.navigation.addNetwork(null) },
        onJoinChannel = { controller.promptJoinChannel(client) },
        onDisplayOptions = { controller.navigation.displayOptions(client, buffer) },
        onExportLog = { exporting = true },
        onSettings = controller::openSettings,
        onSendFeedback = {
            LinkOpener.open(context, "${BuildConfig.API_BASE_URL.trimEnd('/')}/support", device.openLinksInApp)
        },
        onSignOut = { scope.launch { actions.signOut() } },
        onEncryption = { controller.navigation.conversationEncryption(client, buffer) },
    )

    // Encryption (bouncer networks of a signed-in account): the lock, its state line, audience and unlock.
    val encryption = rememberComposerEncryption(controller.manager.e2ee, client, buffer, deps.bufferPrefs, revision)
    var lockError by remember(buffer) { mutableStateOf<String?>(null) }
    var audienceOpen by remember(buffer) { mutableStateOf(false) }
    var unlockOpen by remember { mutableStateOf(false) }
    LaunchedEffect(lockError) {
        if (lockError != null) {
            delay(6_000)
            lockError = null
        }
    }

    ComposerBar(
        value = value,
        onValueChange = ::edit,
        onSend = { line ->
            client.sendInput(line, buffer, replyTo = thread, editOf = sendingEdit)
            sendingEdit = null
        },
        style = device.composeStyle,
        suggestions = visibleSuggestions,
        selectedSuggestion = completion.selection,
        onTabCycle = {
            automaticNickVisible = false
            if (value.composition == null) completion.tab(value, suggest)?.let(::setText)
        },
        onPickSuggestion = { s ->
            if (value.composition == null) {
                val automaticPick = automaticNickVisible && completion.selection == null && bareWord(value) != null && s.kind == CompletionKind.NICK
                automaticNickVisible = false
                if (automaticPick) {
                    val start = completion.range?.first ?: s.replaceRange.first
                    var end = value.selection.start
                    while (end < value.text.length) {
                        val ch = value.text[end]
                        if (ch == '\u0085' || (ch.isWhitespace() && ch !in '\u001C'..'\u001F')) break
                        end++
                    }
                    if (s.insertText.endsWith(" ") && value.text.getOrNull(end) == ' ') end++
                    val out = applyCompletion(value, start until end, s.insertText)
                    setText(out)
                    completion.refresh(out, suggest)
                } else {
                    setText(completion.pick(value, s, suggest))
                }
                fieldFocus.requestFocus()
            }
        },
        showSuggestions = showSuggestions,
        onInputFocusChange = { focused ->
            inputFocused = focused
            if (focused) automaticDismissed = false
            if (!focused) automaticNickVisible = false
        },
        onStyleChange = { s -> deps.deviceSettings.update { it.copy(composeStyle = s) } },
        onUploadClick = {
            focus.clearFocus()
            uploading = true
        },
        onGearClick = {
            focus.clearFocus()
            gearOpen = true
        },
        gearMenu = {
            BufferActionSheet(
                expanded = gearOpen,
                onDismissRequest = { gearOpen = false },
                context = gearContext,
                linkUp = client.state == IrcState.CONNECTED,
                callbacks = callbacks,
            )
        },
        typingText = typingText,
        convertEmoji = device.convertEmoji,
        autoCapitalization = device.autoCapitalization,
        chatFontScale = display.chatFontScale,
        interceptSend = { plain ->
            // An edit ends with this send, whichever way the text goes.
            sendingEdit = editingMsgid
            editingMsgid = null
            when {
                Snippet.isPasteCommand(plain) -> {
                    edit(TextFieldValue())
                    resetStyle(wasStyled(plain))
                    snippet = Snippet.pasteBody(plain) to false
                    true
                }
                Snippet.offersSnippet(plain, device.askToPostSnippet) -> {
                    focus.clearFocus()
                    snippet = plain to true
                    true
                }
                plain.trim().lowercase() == "/list" -> {
                    // The channel browser instead of raw 322 rows in the server buffer (`/list <args>` stays raw).
                    edit(TextFieldValue())
                    controller.navigation.browseChannels(client)
                    true
                }
                else -> false
            }
        },
        afterSend = { plain, styled ->
            resetStyle(styled)
            if (!plain.startsWith("/")) controller.showTip(FirstRunTip.DOUBLE_TAP)
        },
        modifier = Modifier.focusRequester(fieldFocus),
        lockControl = encryption?.let { enc ->
            {
                ComposerLockControl(
                    enc,
                    onDetails = { controller.navigation.conversationEncryption(client, buffer) },
                    onAudience = { audienceOpen = true },
                    onUnlock = { unlockOpen = true },
                    onError = { lockError = it },
                )
            }
        },
        encryptionBanner = encryption?.let { enc ->
            {
                ComposerEncryptionBanner(
                    enc,
                    peer = buffer.name.takeIf { buffer.kind == IrcBufferKind.QUERY },
                    revision = revision,
                    error = lockError,
                    onAudience = { audienceOpen = true },
                    onUnlock = { unlockOpen = true },
                )
            }
        },
    )

    if (audienceOpen && encryption != null) {
        AudienceSheet(encryption.service, networkId, buffer, client.nick, revision, onDismiss = { audienceOpen = false })
    }
    if (unlockOpen) {
        controller.manager.e2ee?.let { UnlockDialog(it, onDismiss = { unlockOpen = false }) }
    }

    if (uploading) {
        UploadActionSheet(client, buffer, deps.media, deps.deviceSettings, onDismiss = { uploading = false })
    }
    if (exporting) {
        LogExportDialog(client, buffer, onDone = { exporting = false })
    }
    snippet?.let { (body, offered) ->
        SnippetEditorScreen(
            client = client,
            buffer = buffer,
            media = deps.media,
            initialBody = body,
            onDismiss = { snippet = null },
            onSent = {
                if (offered) {
                    resetStyle(wasStyled(value.text))
                    edit(TextFieldValue())
                }
            },
        )
    }
    topicDraft?.let { draft ->
        SetTopicDialog(
            channel = buffer.name,
            initial = draft,
            onSet = { text ->
                topicDraft = null
                if (text != buffer.topic.orEmpty()) client.setTopic(text, buffer)
            },
            onDismiss = { topicDraft = null },
        )
    }
}

/** Gear → Set Topic (`BufferViewController.editTopic`): empty clears the topic. */
@Composable
private fun SetTopicDialog(channel: String, initial: String, onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Topic") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Topic for $channel. Leave it empty to clear the topic.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("No topic") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSet(text.trim()) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The composer's "is typing" line (ports `TypingText.swift`): names in the order given, "" for nobody. */
object TypingText {
    fun text(nicks: List<String>): String = when (nicks.size) {
        0 -> ""
        1 -> "${nicks[0]} is typing"
        2 -> "${nicks[0]} and ${nicks[1]} are typing"
        3 -> "${nicks[0]}, ${nicks[1]} and ${nicks[2]} are typing"
        else -> "${nicks.size} people are typing"
    }
}
