package com.kevinpostal.fiberirc.ui.encryption

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.e2ee.ComposerLock
import com.kevinpostal.fiberirc.data.e2ee.E2eeComposerLock
import com.kevinpostal.fiberirc.data.e2ee.E2eePeerDirectory
import com.kevinpostal.fiberirc.data.e2ee.E2eePeerKey
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeTexts
import com.kevinpostal.fiberirc.data.e2ee.archivedBy
import com.kevinpostal.fiberirc.data.e2ee.audience
import com.kevinpostal.fiberirc.data.e2ee.e2eeBufferKey
import com.kevinpostal.fiberirc.data.e2ee.ktLookup
import com.kevinpostal.fiberirc.data.e2ee.listDevices
import com.kevinpostal.fiberirc.data.e2ee.markPeerVerified
import com.kevinpostal.fiberirc.data.e2ee.peerKey
import com.kevinpostal.fiberirc.data.e2ee.pinPeerKey
import com.kevinpostal.fiberirc.data.e2ee.safetyNumber
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.ui.settings.Confirmation
import com.kevinpostal.fiberirc.ui.settings.ConfirmDialog
import com.kevinpostal.fiberirc.ui.settings.ErrorDialog
import com.kevinpostal.fiberirc.ui.settings.PopUpRow
import com.kevinpostal.fiberirc.ui.settings.SettingsRow
import com.kevinpostal.fiberirc.ui.settings.SettingsScaffold
import com.kevinpostal.fiberirc.ui.settings.SettingsSwitchRow
import com.kevinpostal.fiberirc.ui.settings.rememberAlertState
import com.kevinpostal.fiberirc.ui.settings.settingsSection
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A conversation peer's encryption identity: their IRC account, KT directory and the safety number with you. */
private data class PeerSafety(val account: String, val directory: E2eePeerDirectory, val number: String)

/**
 * One channel's or conversation's encryption (opened from the composer's lock menu, the gear menu or a member):
 * the lock, a channel's audience and compliance archive, a conversation's mode (IRC Fiber MLS or OpenPGP), the
 * peer's safety number and key-transparency credentials, and their pinned OpenPGP key.
 */
@Composable
fun ConversationEncryptionScreen(
    service: E2eeService?,
    api: SiteApi,
    prefs: BufferPrefsStore,
    manager: IrcSessionManager,
    client: IrcClient,
    buffer: IrcBuffer,
    onBack: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    val networkId = client.settings.networkId
    val title = buffer.name
    if (service == null || networkId.isEmpty()) {
        SettingsScaffold(title = title, onBack = onBack, message = "Encryption isn't available on this network.") {}
        return
    }
    val state by service.state.collectAsState()
    val revision by manager.revision.collectAsState()
    val values by prefs.values.collectAsState()
    val status = (state as? E2eeServiceState.Running)?.status
    val usable = status != null && !status.locked
    val key = e2eeBufferKey(networkId, buffer.name)
    val lock = remember(state, revision, values) { E2eeComposerLock.of(client.lockDecision(buffer.name), state, key) }
    val channel = buffer.kind == IrcBufferKind.CHANNEL
    val mode = remember(values) { prefs.e2eeMode(networkId, buffer.name) ?: BufferPrefsStore.E2eeMode.MLS }

    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var audienceOpen by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf<String?>(null) }
    var safety by remember { mutableStateOf<PeerSafety?>(null) }
    var safetyError by remember { mutableStateOf<String?>(null) }
    var peerKey by remember { mutableStateOf<E2eePeerKey?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }

    fun op(title: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alerts.show(title, E2eeTexts.error(e))
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(status != null, reload, lock) {
        if (status == null) return@LaunchedEffect
        if (channel) chip = runCatching { service.audience(networkId, buffer.name) }.getOrNull()?.chip
        if (!channel && usable) peerKey = runCatching { service.peerKey(networkId, buffer.name) }.getOrNull()
    }
    LaunchedEffect(status != null, reload) {
        if (channel || status == null) return@LaunchedEffect
        safetyError = null
        safety = try {
            val account = peerAccount(client, buffer.name)
            if (account == null) {
                safetyError = "${buffer.name} isn't signed in to an IRC account, so there's no encryption identity to compare."
                null
            } else {
                val self = service.username ?: error("not signed in")
                val directory = api.ktLookup(account).getOrThrow()
                if (directory.devices.isEmpty()) {
                    safetyError = "$account has no encryption device yet."
                    null
                } else {
                    val mine = service.listDevices().map { it.credential }
                    // Both sides order the two credential sets by account name.
                    val number = if (self.lowercase() < account.lowercase()) service.safetyNumber(mine, directory.credentials)
                    else service.safetyNumber(directory.credentials, mine)
                    PeerSafety(account, directory, number)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safetyError = E2eeTexts.error(e)
            null
        }
    }

    SettingsScaffold(title = title, onBack = onBack) {
        settingsSection(
            key = "lock",
            footer = E2eeComposerLock.label(lock, buffer.name.takeIf { !channel }) ?: "Messages here are not encrypted.",
            footerColor = if (lock is ComposerLock.Partial || lock == ComposerLock.NoPeer || lock is ComposerLock.Unavailable) c.error else null,
            rows = listOf<RowContent>(
                {
                    SettingsSwitchRow("End-to-End Encryption", checked = lock.encrypting, enabled = !busy && status != null) { on ->
                        op("Encryption not changed") { service.setLock(networkId, buffer.name, on) }
                    }
                },
            ),
        )
        status?.archivedBy(key)?.let { org ->
            settingsSection(key = "archived", footer = "This conversation is archived by $org for compliance.", rows = emptyList())
        }
        if (channel) {
            settingsSection(
                key = "audience",
                header = "Who Can Read",
                footer = "Only members signed in to an IRC account with an encryption device can read encrypted messages.",
                rows = listOf<RowContent>(
                    {
                        SettingsRow(
                            title = "Audience",
                            value = chip ?: "Everyone in group",
                            onClick = if (lock.encrypting && usable && !busy) ({ audienceOpen = true }) else null,
                        )
                    },
                ),
            )
        } else {
            settingsSection(
                key = "mode",
                header = "Encryption Mode",
                footer = "OpenPGP mode talks to people using other OpenPGP software; both of you need each other's keys.",
                rows = listOf<RowContent>(
                    {
                        PopUpRow(
                            title = "Mode",
                            options = BufferPrefsStore.E2eeMode.entries,
                            selected = mode,
                            label = { if (it == BufferPrefsStore.E2eeMode.MLS) "IRC Fiber (MLS)" else "OpenPGP" },
                        ) { next -> op("Mode not changed") { prefs.setE2eeMode(next, networkId, buffer.name) } }
                    },
                ),
            )
            settingsSection(
                key = "safety",
                header = "Safety Number",
                footer = safetyError ?: "Compare this number with ${buffer.name} in person or over another channel. If it matches, nobody is in between.",
                footerColor = safetyError?.let { c.error },
                rows = safety?.let { s ->
                    listOf<RowContent>(
                        {
                            Column(Modifier.fillMaxWidth().padding(Grid.margin), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                                QrImage(s.number)
                                SafetyDigits(s.number)
                            }
                        },
                        {
                            SettingsRow(
                                title = s.account,
                                value = (if (s.directory.orgCertificate) "Org certificate" else "KT ✓ seq ${s.directory.maxSeq}") +
                                    " · ${s.directory.devices.size} device" + if (s.directory.devices.size == 1) "" else "s",
                            )
                        },
                    )
                } ?: emptyList(),
            )
            settingsSection(
                key = "pgp",
                header = "${buffer.name}'s OpenPGP Key",
                footer = if (peerKey?.verified == true) "You verified this key." else
                    "A pasted key is trusted on first use. Mark it verified after comparing the fingerprint with ${buffer.name}.",
                rows = buildList<RowContent> {
                    add { SettingsRow(title = "Fingerprint", value = if (!usable) "Locked" else peerKey?.let { formatFingerprint(it.fingerprint) } ?: "None", modifier = Modifier.testTag("peerFingerprint")) }
                    add {
                        SettingsRow(
                            title = if (peerKey == null) "Paste Their Public Key…" else "Replace Their Public Key…",
                            titleColor = if (usable) c.link else null,
                            onClick = if (usable && !busy) ({ pasteOpen = true }) else null,
                        )
                    }
                    val pk = peerKey
                    if (pk != null && !pk.verified) {
                        add {
                            SettingsRow(title = "Mark as Verified…", titleColor = if (usable) c.link else null, onClick = if (usable && !busy) ({
                                confirmation = Confirmation(
                                    "Mark as Verified",
                                    "Only if ${buffer.name} confirmed this fingerprint to you over another channel:\n\n${formatFingerprint(pk.fingerprint)}",
                                    "Verified",
                                ) { op("Not marked verified") { service.markPeerVerified(networkId, buffer.name) } }
                            }) else null)
                        }
                    }
                },
            )
        }
    }

    if (audienceOpen) {
        AudienceSheet(service, networkId, buffer, client.nick, revision, onDismiss = { audienceOpen = false; reload++ })
    }
    if (pasteOpen) {
        PeerKeyDialog(peer = buffer.name, onDismiss = { pasteOpen = false }) { armored ->
            pasteOpen = false
            op("Key not saved") { service.pinPeerKey(networkId, buffer.name, armored) }
        }
    }
    ConfirmDialog(confirmation) { confirmation = null }
    ErrorDialog(alerts)
}

/** The peer's IRC account: a shared channel's member list first, else WHOIS. Never inferred from the nick. */
private suspend fun peerAccount(client: IrcClient, nick: String): String? {
    client.buffers.firstNotNullOfOrNull { b -> b.member(nick)?.account?.takeIf { it.isNotEmpty() && it != "*" } }?.let { return it }
    return withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { cont -> client.whois(nick) { info -> if (cont.isActive) cont.resume(info.account?.takeIf { it.isNotEmpty() && it != "*" }) } }
    }
}

@Composable
private fun PeerKeyDialog(peer: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var armored by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$peer's Public Key") },
        text = {
            OutlinedTextField(
                value = armored,
                onValueChange = { armored = it },
                label = { Text("-----BEGIN PGP PUBLIC KEY BLOCK-----") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("peerArmored"),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(enabled = armored.isNotBlank(), onClick = { onSave(armored.trim()) }) { Text("Save") } },
    )
}
