package com.kevinpostal.fiberirc.ui.encryption

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import com.kevinpostal.fiberirc.data.e2ee.ComposerLock
import com.kevinpostal.fiberirc.data.e2ee.E2eeComposerLock
import com.kevinpostal.fiberirc.data.e2ee.E2eeDraftContinuation
import com.kevinpostal.fiberirc.data.e2ee.E2eePendingDraft
import com.kevinpostal.fiberirc.data.e2ee.E2eePendingDrafts
import com.kevinpostal.fiberirc.data.e2ee.E2eePolicySnapshot
import com.kevinpostal.fiberirc.data.e2ee.E2eePolicySnapshots
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeTexts
import com.kevinpostal.fiberirc.data.e2ee.e2eeBufferKey
import com.kevinpostal.fiberirc.data.e2ee.installOrgCertificate
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcEncryptionConfig
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import com.kevinpostal.fiberirc.data.irc.IrcSendState
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.irc.IrcState
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.ui.login.LoginViewModel
import com.kevinpostal.fiberirc.ui.settings.ConfirmDialog
import com.kevinpostal.fiberirc.ui.settings.Confirmation
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.BufferLink
import com.kevinpostal.fiberirc.ui.shell.SessionActions
import com.kevinpostal.fiberirc.ui.shell.ShellController
import com.kevinpostal.fiberirc.ui.shell.SignOutKeysChoice
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The session's encryption duties outside any one screen: the policy snapshot external entry points read, purging
 * decrypted text of conversations left behind (unless "Keep decrypted messages" is on), the organization sign-in's
 * return, encrypted drafts continued from a notification or share, and the sign-out keys question.
 */
@Composable
fun EncryptionSessionHost(
    manager: IrcSessionManager,
    controller: ShellController,
    api: SiteApi,
    prefs: BufferPrefsStore,
    sessionActions: SessionActions,
    openEncryptionSettings: () -> Unit,
) {
    SignOutKeysDialog(sessionActions)
    val service = manager.e2ee ?: return
    PolicySnapshotWriter(service, manager, prefs)
    DecryptedCachePurge(service, controller)
    OrgSignInReturn(service, api, openEncryptionSettings)
    PendingDrafts(service, manager, controller)
}

// ── policy snapshot ─────────────────────────────────────────────────────────

/**
 * What one target's snapshot says: only what the message path itself would send in plaintext is `off`; anything
 * waiting for a decision is `pending`; everything else `on`.
 */
fun snapshotState(decision: IrcLockDecision): String = when (decision) {
    IrcLockDecision.OFF -> E2eePolicySnapshots.OFF
    IrcLockDecision.PENDING -> E2eePolicySnapshots.PENDING
    IrcLockDecision.ENCRYPT -> E2eePolicySnapshots.ON
}

/** Every channel and conversation of the session's encrypting networks, keyed like the runtime. */
fun snapshotTargets(clients: List<IrcClient>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (c in clients) {
        val networkId = c.settings.networkId
        if (networkId.isEmpty() || c.encryption !is IrcEncryptionConfig.Encrypted) continue
        for (b in c.buffers) {
            if (b.kind == IrcBufferKind.SERVER) continue
            out[e2eeBufferKey(networkId, b.name)] = snapshotState(c.lockDecision(b.name))
        }
    }
    return out
}

/** Rewrites the account's snapshot on every lock, default, preference or account change and before suspension. */
@OptIn(FlowPreview::class)
@Composable
private fun PolicySnapshotWriter(service: E2eeService, manager: IrcSessionManager, prefs: BufferPrefsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun write() {
        val userId = service.userId ?: return
        val status = (service.state.value as? E2eeServiceState.Running)?.status
        val defaults = service.localDefaults()
        val snapshot = E2eePolicySnapshot(
            userId = userId,
            apiOrigin = service.apiOrigin,
            writtenAt = System.currentTimeMillis(),
            dmDefault = (status?.dmDefault ?: false) || defaults.dmDefault,
            autoLockIncoming = defaults.autoLockIncoming,
            perTarget = snapshotTargets(manager.clients.value),
        )
        scope.launch(Dispatchers.IO) {
            runCatching { E2eePolicySnapshots.write(context, snapshot) }.onFailure { Log.w(TAG, "policy snapshot not written: ${it.message}") }
        }
    }

    LaunchedEffect(service, manager) {
        combine(service.state, prefs.values, manager.revision, manager.clients) { _, _, _, _ -> }
            .debounce(SNAPSHOT_DEBOUNCE_MS)
            .collect { write() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { write() }
}

// ── decrypted cache ─────────────────────────────────────────────────────────

/**
 * "Keep decrypted messages while switching conversations" off: the conversation left behind drops its decrypted
 * text (rows go back to pending with their ciphertext); the one shown is decrypted again from the encrypted archive.
 * Turning the setting off purges every conversation not on screen. Nothing is ever cached on disk.
 */
@Composable
private fun DecryptedCachePurge(service: E2eeService, controller: ShellController) {
    val state by service.state.collectAsState()
    var cacheWasOn by remember { mutableStateOf(service.localDefaults().cacheDecrypted) }
    LaunchedEffect(state) {
        val on = service.localDefaults().cacheDecrypted
        if (cacheWasOn && !on) {
            val shown = controller.current
            for (c in controller.manager.clients.value) {
                for (b in c.buffers) if (!(shown?.client === c && shown.buffer === b)) c.purgeDecrypted(b)
            }
        }
        cacheWasOn = on
    }
    LaunchedEffect(controller) {
        var previous: Pair<IrcClient, IrcBuffer>? = null
        snapshotFlow { controller.current?.let { it.client to it.buffer } }.collect { now ->
            val prev = previous
            if (prev != null && (now == null || prev.first !== now.first || prev.second !== now.second)) {
                if (!service.localDefaults().cacheDecrypted) prev.first.purgeDecrypted(prev.second)
            }
            if (now != null && (prev == null || prev.first !== now.first || prev.second !== now.second)) {
                now.first.resubmitPendingEncrypted(now.second)
            }
            previous = now
        }
    }
}

// ── organization sign-in return ─────────────────────────────────────────────

/**
 * A `fiberirc://auth?code=` hand-off while signed in belongs to the encryption settings' organization sign-in only
 * when this account has a live pending action; it is redeemed, the account must be the same immutable user id,
 * then the certificate is installed. Another account's sign-in aborts and clears the pending action.
 */
@Composable
private fun OrgSignInReturn(service: E2eeService, api: SiteApi, openEncryptionSettings: () -> Unit) {
    val context = LocalContext.current
    val redirect by AppRouter.pendingAuthRedirect.collectAsState()
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(redirect) {
        val uri = redirect ?: return@LaunchedEffect
        val userId = service.userId ?: return@LaunchedEffect
        E2eeOrgSignIn.pending(context, service.apiOrigin, userId) ?: return@LaunchedEffect
        AppRouter.consumeAuthRedirect(uri)
        E2eeOrgSignIn.clear(context)
        val code = LoginViewModel.authCode(Uri.parse(uri))
        if (code.isNullOrEmpty()) {
            result = "Organization Sign-In" to "Sign-in did not complete; your certificate wasn't changed."
            return@LaunchedEffect
        }
        val session = api.appSession(code).getOrElse {
            result = "Organization Sign-In" to "Sign-in failed (${it.message ?: "error"}); your certificate wasn't changed."
            return@LaunchedEffect
        }
        if (session.id != userId) {
            result = "Organization Sign-In" to "That sign-in belongs to another account, so no certificate was installed."
            return@LaunchedEffect
        }
        openEncryptionSettings()
        // The same account's refreshed session: let the service re-verify it, then install.
        service.retry()
        result = try {
            withTimeoutOrNull(STARTUP_WAIT_MS) { service.state.first { it is E2eeServiceState.Running } }
            service.installOrgCertificate()
            "Organization Certificate" to "Your organization's certificate is installed on this device."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Certificate Not Installed" to E2eeTexts.error(e)
        }
    }
    result?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}

// ── pending encrypted drafts ────────────────────────────────────────────────

/**
 * A message a notification Reply or share couldn't send as plaintext waits sealed until the user sends it here.
 * The continuation route ([AppRouter.pendingEncryptedDraft]) and, on each foreground, any leftover draft of this
 * account open its buffer and offer it for an explicit Send; it is deleted only after that send succeeded or the
 * user discarded it. Another account's or a missing draft only consumes the route.
 */
@Composable
private fun PendingDrafts(service: E2eeService, manager: IrcSessionManager, controller: ShellController) {
    val context = LocalContext.current
    val route by AppRouter.pendingEncryptedDraft.collectAsState()
    val serviceState by service.state.collectAsState()
    var shown by remember { mutableStateOf<E2eePendingDraft?>(null) }
    var later by remember { mutableStateOf(setOf<String>()) }
    var foregrounds by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { foregrounds++ }

    LaunchedEffect(route, service.userId) {
        val id = route ?: return@LaunchedEffect
        val userId = service.userId ?: return@LaunchedEffect
        val draft = withContext(Dispatchers.IO) { runCatching { E2eePendingDrafts.load(context, id, service.apiOrigin, userId) }.getOrNull() }
        AppRouter.consumeEncryptedDraft(id)
        if (draft != null) {
            later = later - draft.id
            shown = draft
        }
    }
    LaunchedEffect(foregrounds, service.userId, serviceState is E2eeServiceState.Running) {
        if (shown != null) return@LaunchedEffect
        val userId = service.userId ?: return@LaunchedEffect
        val leftovers = withContext(Dispatchers.IO) { runCatching { E2eePendingDrafts.list(context, service.apiOrigin, userId) }.getOrDefault(emptyList()) }
        shown = leftovers.firstOrNull { it.id !in later }
    }

    val draft = shown ?: return
    val client = manager.clients.value.firstOrNull { it.settings.networkId == draft.networkId }
    LaunchedEffect(draft.id) {
        E2eeDraftContinuation.cancel(context, draft.id)
        if (client != null) controller.open(BufferLink.Buffer(IrcSessionManager.networkKey(client), draft.target))
    }
    PendingDraftDialog(
        service = service,
        manager = manager,
        draft = draft,
        client = client,
        onLater = {
            later = later + draft.id
            shown = null
        },
        onFinished = { shown = null },
    )
}

/** Sends [draft] through the normal lock-checked path and reports when every resulting row settled. */
private sealed interface DraftSend {
    data object Sent : DraftSend
    data class Failed(val reason: String) : DraftSend
    data class Uncertain(val reason: String) : DraftSend
}

/** Waits for the own rows [ids] of [buffer] to leave [IrcSendState.Sending]. */
private suspend fun awaitRows(manager: IrcSessionManager, buffer: IrcBuffer, ids: Set<String>): DraftSend {
    if (ids.isEmpty()) return DraftSend.Failed("Nothing was sent — check the connection")
    var outcome: DraftSend? = null
    manager.revision.first {
        val rows = buffer.lines.filter { it.id in ids }
        val uncertain = rows.firstNotNullOfOrNull { (it.send as? IrcSendState.Uncertain)?.reason }
        val failed = rows.firstNotNullOfOrNull { (it.send as? IrcSendState.Failed)?.reason }
        outcome = when {
            uncertain != null -> DraftSend.Uncertain(uncertain)
            failed != null -> DraftSend.Failed(failed)
            rows.size == ids.size && rows.all { it.send == null } -> DraftSend.Sent
            rows.size < ids.size -> DraftSend.Failed("The message was discarded")
            else -> null
        }
        outcome != null
    }
    return outcome!!
}

@Composable
private fun PendingDraftDialog(
    service: E2eeService,
    manager: IrcSessionManager,
    draft: E2eePendingDraft,
    client: IrcClient?,
    onLater: () -> Unit,
    onFinished: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by service.state.collectAsState()
    val revision by manager.revision.collectAsState()
    var sending by remember(draft.id) { mutableStateOf(false) }
    var error by remember(draft.id) { mutableStateOf<String?>(null) }
    var uncertain by remember(draft.id) { mutableStateOf(false) }
    var unlockOpen by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }

    val buffer = client?.buffer(draft.target)
    val lock = remember(state, revision, client, buffer) {
        client?.let { E2eeComposerLock.of(it.lockDecision(draft.target), state, e2eeBufferKey(draft.networkId, draft.target)) }
    }
    val connected = client?.state == IrcState.CONNECTED
    val canSend = client != null && buffer != null && connected && !sending && !uncertain &&
        lock != ComposerLock.Locked && lock !is ComposerLock.Unavailable

    suspend fun delete(discard: Boolean) = withContext(Dispatchers.IO) {
        val userId = service.userId ?: return@withContext
        if (discard) E2eePendingDrafts.discard(context, draft.id, service.apiOrigin, userId)
        else E2eePendingDrafts.consume(context, draft.id, service.apiOrigin, userId)
    }

    fun send() {
        val cl = client ?: return
        val b = buffer ?: return
        sending = true
        error = null
        scope.launch {
            try {
                val before = b.lines.mapTo(HashSet()) { it.id }
                // Positively off now (the main app's own decision): today's plaintext path, accepted once written.
                val plaintext = cl.lockDecision(draft.target) == IrcLockDecision.OFF
                cl.sendInput(draft.body, b, replyTo = draft.replyTo)
                val mine = b.lines.filter { it.id !in before && isOwnRow(it, cl) }.mapTo(HashSet()) { it.id }
                val outcome = if (plaintext) DraftSend.Sent else awaitRows(manager, b, mine)
                when (outcome) {
                    DraftSend.Sent -> {
                        delete(discard = false)
                        onFinished()
                    }
                    is DraftSend.Failed -> {
                        // Nothing reached the wire: drop the failed row so the draft stays the only copy to retry.
                        b.lines.filter { it.id in mine && it.send is IrcSendState.Failed }.forEach { cl.discardEncryptedSend(it, b) }
                        error = "Not sent: ${outcome.reason}"
                    }
                    is DraftSend.Uncertain -> {
                        uncertain = true
                        error = "Delivery uncertain: ${outcome.reason}. It may already have been delivered."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = E2eeTexts.error(e)
            } finally {
                sending = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onLater() },
        title = { Text("Message Waiting to Send") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                Text("To ${draft.target}" + (client?.settings?.networkName?.let { " on $it" } ?: ""), color = c.textSecondary)
                if (draft.replyTo != null) Text("Reply", color = c.textSecondary)
                Text(draft.body, color = c.chatText, modifier = Modifier.padding(vertical = Grid.unit).testTag("draftBody"))
                Text(
                    when {
                        client == null -> "This network isn't in your session anymore."
                        !connected -> "Waiting for the connection…"
                        else -> E2eeComposerLock.label(lock ?: ComposerLock.Off, draft.target) ?: "This conversation isn't encrypted; it will be sent normally."
                    },
                    color = c.textSecondary,
                )
                error?.let { Text(it, color = c.error) }
            }
        },
        confirmButton = {
            when {
                uncertain -> TextButton(onClick = { scope.launch { runCatching { delete(discard = true) }; onFinished() } }) { Text("Discard Draft") }
                lock == ComposerLock.Locked -> TextButton(onClick = { unlockOpen = true }) { Text("Unlock…") }
                else -> TextButton(enabled = canSend, onClick = ::send, modifier = Modifier.testTag("draftSend")) { Text(if (sending) "Sending…" else "Send") }
            }
        },
        dismissButton = {
            Column {
                TextButton(enabled = !sending, onClick = {
                    confirmation = Confirmation("Discard Message", "Delete this unsent message? It can't be recovered.", "Discard") {
                        scope.launch {
                            runCatching { delete(discard = true) }.onFailure { error = "Couldn't discard: ${it.message}" }
                            if (error == null) onFinished()
                        }
                    }
                }) { Text("Discard", color = c.error) }
                TextButton(enabled = !sending, onClick = onLater) { Text(if (uncertain) "Keep Draft" else "Later") }
            }
        },
    )
    if (unlockOpen) UnlockDialog(service, onDismiss = { unlockOpen = false })
    ConfirmDialog(confirmation) { confirmation = null }
}

private fun isOwnRow(line: IrcLine, client: IrcClient): Boolean =
    line.e2e?.source?.own == true || line.send != null || line.sender.equals(client.nick, ignoreCase = true)

// ── sign-out keys question ──────────────────────────────────────────────────

private enum class SignOutStep { ASK, CONFIRM_REMOVE, REMOVING, CONFIRM_LOCAL_ONLY }

/**
 * Sign-out with this device's encryption store: keep the encrypted history on this device, or remove this device's
 * keys (revoked first while the session is still authenticated). A failed revocation is reported; deleting the keys
 * from this device only is a separate, confirmed choice. Cancel stays signed in.
 */
@Composable
fun SignOutKeysDialog(actions: SessionActions) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val pending by actions.keysChoice.collectAsState()
    val deferred = pending ?: return
    val service = actions.e2ee
    if (service == null) {
        LaunchedEffect(deferred) { deferred.complete(SignOutKeysChoice.KEEP) }
        return
    }
    var step by remember(deferred) { mutableStateOf(SignOutStep.ASK) }
    var failure by remember(deferred) { mutableStateOf<String?>(null) }

    fun answer(choice: SignOutKeysChoice) {
        deferred.complete(choice)
    }

    when (step) {
        SignOutStep.ASK -> AlertDialog(
            onDismissRequest = { answer(SignOutKeysChoice.CANCEL) },
            title = { Text("Sign Out") },
            text = { Text("This device has encrypted messages and keys for your account. Keep them for the next time you sign in here, or remove them?") },
            confirmButton = {
                Column {
                    TextButton(onClick = { answer(SignOutKeysChoice.KEEP) }, modifier = Modifier.testTag("signOutKeep")) {
                        Text("Keep Encrypted History on This Device")
                    }
                    TextButton(onClick = { step = SignOutStep.CONFIRM_REMOVE }, modifier = Modifier.testTag("signOutRemove")) {
                        Text("Remove This Device's Keys…", color = c.error)
                    }
                    TextButton(onClick = { answer(SignOutKeysChoice.CANCEL) }) { Text("Cancel") }
                }
            },
        )
        SignOutStep.CONFIRM_REMOVE -> AlertDialog(
            onDismissRequest = { answer(SignOutKeysChoice.CANCEL) },
            title = { Text("Remove This Device's Keys") },
            text = {
                Text(
                    (failure?.let { "Couldn't remove this device from your account: $it\n\n" } ?: "") +
                        "This device is removed from your encryption devices and its keys and encrypted history are deleted. " +
                        "Messages you can't read on another device become unreadable here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    step = SignOutStep.REMOVING
                    scope.launch {
                        try {
                            service.revokeAndRemoveKeysAndStop()
                            answer(SignOutKeysChoice.REMOVED)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failure = E2eeTexts.error(e)
                            step = SignOutStep.CONFIRM_LOCAL_ONLY
                        }
                    }
                }) { Text("Remove", color = c.error) }
            },
            dismissButton = { TextButton(onClick = { answer(SignOutKeysChoice.CANCEL) }) { Text("Cancel") } },
        )
        SignOutStep.REMOVING -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Removing…") },
            text = { Text("Removing this device from your account.") },
            confirmButton = {},
        )
        SignOutStep.CONFIRM_LOCAL_ONLY -> AlertDialog(
            onDismissRequest = { answer(SignOutKeysChoice.CANCEL) },
            title = { Text("Couldn't Remove This Device") },
            text = {
                Text(
                    "${failure ?: "Revocation failed"}. You can delete the keys from this device only; it stays listed in " +
                        "your devices until you remove it from another device.",
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        step = SignOutStep.REMOVING
                        scope.launch {
                            try {
                                service.removeLocalStateOnlyAndStop()
                                answer(SignOutKeysChoice.REMOVED)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = E2eeTexts.error(e)
                                step = SignOutStep.CONFIRM_LOCAL_ONLY
                            }
                        }
                    }) { Text("Remove from This Device Only", color = c.error) }
                    TextButton(onClick = { answer(SignOutKeysChoice.KEEP) }) { Text("Keep Keys and Sign Out") }
                    TextButton(onClick = { answer(SignOutKeysChoice.CANCEL) }) { Text("Cancel") }
                }
            },
        )
    }
}

private const val TAG = "EncryptionSession"
private const val SNAPSHOT_DEBOUNCE_MS = 500L
private const val STARTUP_WAIT_MS = 30_000L
