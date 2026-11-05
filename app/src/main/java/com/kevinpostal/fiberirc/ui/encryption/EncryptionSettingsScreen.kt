package com.kevinpostal.fiberirc.ui.encryption

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.e2ee.E2eeBackupStatus
import com.kevinpostal.fiberirc.data.e2ee.E2eeDefaults
import com.kevinpostal.fiberirc.data.e2ee.E2eeDevice
import com.kevinpostal.fiberirc.data.e2ee.E2eeOwnSafety
import com.kevinpostal.fiberirc.data.e2ee.E2eePendingDrafts
import com.kevinpostal.fiberirc.data.e2ee.E2eePgpImport
import com.kevinpostal.fiberirc.data.e2ee.E2eePolicy
import com.kevinpostal.fiberirc.data.e2ee.E2eeRecoveryKey
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeStatus
import com.kevinpostal.fiberirc.data.e2ee.E2eeTexts
import com.kevinpostal.fiberirc.data.e2ee.backupStatus
import com.kevinpostal.fiberirc.data.e2ee.backupWithPasskey
import com.kevinpostal.fiberirc.data.e2ee.backupWithRecoveryKey
import com.kevinpostal.fiberirc.data.e2ee.bootstrapError
import com.kevinpostal.fiberirc.data.e2ee.deleteBackup
import com.kevinpostal.fiberirc.data.e2ee.importOwnKey
import com.kevinpostal.fiberirc.data.e2ee.ktUnknownDevice
import com.kevinpostal.fiberirc.data.e2ee.listDevices
import com.kevinpostal.fiberirc.data.e2ee.oldKeyFingerprints
import com.kevinpostal.fiberirc.data.e2ee.ownKeyFingerprint
import com.kevinpostal.fiberirc.data.e2ee.policy
import com.kevinpostal.fiberirc.data.e2ee.removeDevice
import com.kevinpostal.fiberirc.data.e2ee.removeLocalKeys
import com.kevinpostal.fiberirc.data.e2ee.resetIdentity
import com.kevinpostal.fiberirc.data.e2ee.restoreWithPasskey
import com.kevinpostal.fiberirc.data.e2ee.restoreWithRecoveryKey
import com.kevinpostal.fiberirc.data.e2ee.setDefaults
import com.kevinpostal.fiberirc.data.e2ee.setLocalPassphrase
import com.kevinpostal.fiberirc.data.e2ee.unlockWithPasskey
import com.kevinpostal.fiberirc.ui.settings.Confirmation
import com.kevinpostal.fiberirc.ui.settings.ConfirmDialog
import com.kevinpostal.fiberirc.ui.settings.ErrorDialog
import com.kevinpostal.fiberirc.ui.settings.SettingsRow
import com.kevinpostal.fiberirc.ui.settings.SettingsScaffold
import com.kevinpostal.fiberirc.ui.settings.SettingsSwitchRow
import com.kevinpostal.fiberirc.ui.settings.copySensitive
import com.kevinpostal.fiberirc.ui.settings.rememberAlertState
import com.kevinpostal.fiberirc.ui.settings.relativeTime
import com.kevinpostal.fiberirc.ui.settings.settingsSection
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EncryptionPage { MAIN, LINK_OFFER, LINK_ACCEPT }

/** One settings row (`settingsSection` rows). */
internal typealias RowContent = @Composable () -> Unit

/**
 * Settings → Encryption (the web's `SettingsEncryption`, native): status and setup errors, unlock, key-transparency
 * alarms, this device and its safety number, the account's devices, encrypted backup and restore, device linking,
 * OpenPGP for direct messages, the organization certificate, the defaults and the danger zone. Every action runs
 * through the account's encryption service; nothing is shown as done before the runtime committed it. While
 * encryption is locked only the non-secret shell (status, unlock, device id) is usable.
 */
@Composable
fun EncryptionSettingsScreen(service: E2eeService?, api: SiteApi, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(EncryptionPage.MAIN) }
    if (service == null) {
        SettingsScaffold(title = "Encryption", onBack = onBack, message = "End-to-end encryption is available for the networks of your ircfiber.com account.") {}
        return
    }
    when (page) {
        EncryptionPage.MAIN -> EncryptionMain(service, api, onLink = { page = it }, onBack = onBack)
        EncryptionPage.LINK_OFFER -> LinkOfferScreen(service, onBack = { page = EncryptionPage.MAIN })
        EncryptionPage.LINK_ACCEPT -> LinkAcceptScreen(service, onBack = { page = EncryptionPage.MAIN })
    }
}

@Composable
private fun EncryptionMain(service: E2eeService, api: SiteApi, onLink: (EncryptionPage) -> Unit, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    val state by service.state.collectAsState()
    val status = (state as? E2eeServiceState.Running)?.status
    val policy = status?.policy
    val locked = status?.locked == true
    val usable = status != null && !locked

    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<E2eeDevice>?>(null) }
    var devicesError by remember { mutableStateOf<String?>(null) }
    var backup by remember { mutableStateOf<E2eeBackupStatus?>(null) }
    var backupLoaded by remember { mutableStateOf(false) }
    var ownKey by remember { mutableStateOf<String?>(null) }
    var oldKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var unlockOpen by remember { mutableStateOf(false) }
    var recoveryKeyShown by remember { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf("") }
    var importOpen by remember { mutableStateOf(false) }
    var prfInfo by remember { mutableStateOf(false) }
    var turnOffBackup by remember { mutableStateOf(false) }

    LaunchedEffect(status != null, locked, reload) {
        if (status == null) return@LaunchedEffect
        devices = try {
            devicesError = null
            service.listDevices()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            devicesError = E2eeTexts.error(e)
            null
        }
        backup = runCatching { service.backupStatus() }.getOrNull()
        backupLoaded = true
        ownKey = if (locked) null else runCatching { service.ownKeyFingerprint() }.getOrNull()
        // Fingerprints only (no key material); unreadable while locked.
        oldKeys = if (locked) emptyList() else runCatching { service.oldKeyFingerprints() }.getOrDefault(emptyList())
    }

    /** Runs one user action; [done] words its success. Errors show as an alert; a cancelled passkey sheet is quiet. */
    fun op(title: String, done: String? = null, block: suspend () -> Unit) {
        busy = true
        notice = null
        scope.launch {
            try {
                block()
                if (done != null) notice = done
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!E2eeTexts.isPasskeyCancellation(e)) alerts.show(title, E2eeTexts.error(e))
            } finally {
                busy = false
            }
        }
    }

    val saveKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val key = recoveryKeyShown ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write((key + "\n").toByteArray()) }
                        ?: error("couldn't open the file")
                }
            }.onFailure { alerts.show("Recovery key not saved", it) }
        }
    }

    val defaults = service.localDefaults()
    fun saveDefaults(next: E2eeDefaults) = op("Setting not saved") { service.setDefaults(next) }

    SettingsScaffold(title = "Encryption", onBack = onBack) {
        // ── status ──
        settingsSection(
            key = "status",
            footer = statusFooter(state, status),
            footerColor = if (statusIsProblem(state, status)) c.error else null,
            rows = buildList<RowContent> {
                add {
                    SettingsRow(
                        title = "End-to-End Encryption",
                        value = statusValue(state, status),
                        valueColor = if (statusIsProblem(state, status)) c.error else null,
                    )
                }
                if (locked) add { SettingsRow(title = "Unlock…", titleColor = c.link, onClick = { unlockOpen = true }) }
                val canRetry = state is E2eeServiceState.Unavailable || status?.bootstrapError != null || (status != null && policy == null)
                if (canRetry) add { SettingsRow(title = "Try Again", titleColor = c.link, onClick = { notice = null; service.retry() }) }
                val unavailable = state as? E2eeServiceState.Unavailable
                if (unavailable?.webViewUpdate == true) {
                    add {
                        SettingsRow(title = "Update Android System WebView", titleColor = c.link, onClick = {
                            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
                            try {
                                context.startActivity(market)
                            } catch (_: ActivityNotFoundException) {
                                alerts.show("Can't open the store", "Update Android System WebView from your device's app store.")
                            }
                        })
                    }
                }
                if (state is E2eeServiceState.RecoveryRequired) {
                    add {
                        SettingsRow(title = "Remove Unreadable Keys…", titleColor = c.error, onClick = {
                            confirmation = Confirmation(
                                "Remove Unreadable Keys",
                                "This device's encryption keys can't be read. Removing them starts over with a new identity; " +
                                    "encrypted history stored on this device is lost. Your other devices are unaffected.",
                                "Remove",
                            ) { op("Keys not removed", "Started over with a new identity") { service.discardUnrecoverableStore() } }
                        })
                    }
                }
            },
        )
        notice?.let { text -> item(key = "notice") { Text(text, color = c.submitGreen, modifier = Modifier.padding(top = Grid.gutter).testTag("encryptionNotice")) } }
        status?.let { s ->
            if (s.ktAlarm) {
                settingsSection(
                    key = "kt",
                    header = "Key transparency check failed",
                    footer = "A device was added to your account that this device doesn't know (${s.ktUnknownDevice ?: "?"}). " +
                        "If that wasn't you, reset your identity below.",
                    footerColor = c.error,
                    rows = emptyList(),
                )
            }
            if (policy?.archiveEnabled == true && policy.orgDomain.isNotEmpty()) {
                settingsSection(key = "archive", footer = "${policy.orgDomain} archives encrypted conversations for compliance.", rows = emptyList())
            }
        }

        // ── this device + devices ──
        if (status != null) {
            val mine = devices?.firstOrNull { it.deviceId == status.deviceId }
            settingsSection(
                key = "device",
                header = "This Device",
                footer = "Compare the safety number with what your contacts see for you. It changes when you add or remove a device.",
                rows = buildList<RowContent> {
                    add { SettingsRow(title = "Device", value = status.deviceId ?: "Not set up yet") }
                    mine?.let { d ->
                        add { SettingsRow(title = "Credential", value = if (d.orgCertificate) "Org certificate: ${policy?.orgDomain?.ifEmpty { null } ?: service.username}" else "KT ✓ seq ${d.ktSeq}") }
                    }
                    val list = devices
                    if (!list.isNullOrEmpty() && service.username != null) {
                        add {
                            val payload = remember(list) { E2eeOwnSafety.payload(service.username!!, list) }
                            val number = remember(payload) { E2eeOwnSafety.number(payload) }
                            Column(Modifier.fillMaxWidth().padding(Grid.margin), horizontalAlignment = Alignment.CenterHorizontally) {
                                QrImage(payload, modifier = Modifier.padding(bottom = Grid.gutter))
                                SafetyDigits(number)
                            }
                        }
                    }
                },
            )
            settingsSection(
                key = "devices",
                header = "Your Devices",
                footer = devicesError ?: "Link a new phone, tablet or browser from a device that's already set up. Both devices must stay open and online.",
                footerColor = devicesError?.let { c.error },
                rows = buildList<RowContent> {
                    devices?.forEach { d ->
                        add {
                            val own = d.deviceId == status.deviceId
                            SettingsRow(
                                title = d.deviceId.take(8) + if (own) " · This device" else if (d.service) " · Service" else "",
                                subtitle = if (d.orgCertificate) "Org certificate" else "KT ✓ seq ${d.ktSeq}",
                                trailing = if (own || !usable) null else ({
                                    TextButton(enabled = !busy, onClick = {
                                        confirmation = Confirmation(
                                            "Remove Device",
                                            "Remove device ${d.deviceId}? It can no longer read new encrypted messages for your account.",
                                            "Remove",
                                        ) { op("Device not removed", "Device removed") { service.removeDevice(d.deviceId) } }
                                    }) { Text("Remove", color = c.error) }
                                }),
                            )
                        }
                    }
                    add { SettingsRow(title = "Add a New Device…", titleColor = if (usable) c.link else null, onClick = if (usable && !busy) ({ onLink(EncryptionPage.LINK_ACCEPT) }) else null) }
                    add { SettingsRow(title = "Link This Device to Your Account…", titleColor = if (usable) c.link else null, onClick = if (usable && !busy) ({ onLink(EncryptionPage.LINK_OFFER) }) else null) }
                },
            )
        }

        // ── backup / restore ──
        if (status != null && backupLoaded && policy?.backupMode != E2eePolicy.BackupMode.FORBIDDEN) {
            val b = backup
            settingsSection(
                key = "backup",
                header = "Encrypted Backup",
                footer = when {
                    policy?.backupMode == E2eePolicy.BackupMode.REQUIRED -> "Your organization requires a backup."
                    b == null -> "Devices only: if you lose all your devices, your encrypted history is gone. Backups are encrypted before they leave this device."
                    else -> "Backups are encrypted before they leave this device; ircfiber.com can't read them."
                },
                rows = buildList<RowContent> {
                    add {
                        SettingsRow(
                            title = "Backup",
                            value = when {
                                b == null -> "Off — devices only"
                                b.passkey -> "Passkey · ${relativeTime(b.updatedAt, 0)}"
                                else -> "Recovery key · ${relativeTime(b.updatedAt, 0)}"
                            },
                        )
                    }
                    add {
                        SettingsRow(
                            title = if (b == null) "Back Up with Recovery Key…" else "Back Up Now with a New Recovery Key…",
                            titleColor = if (usable) c.link else null,
                            onClick = if (usable && !busy) ({
                                op("Backup failed") { recoveryKeyShown = service.backupWithRecoveryKey() }
                            }) else null,
                        )
                    }
                    add {
                        SettingsRow(
                            title = "Back Up with Passkey…",
                            titleColor = if (usable) c.link else null,
                            onClick = if (usable && !busy) ({
                                if (!service.passkeysAvailable) prfInfo = true else op("Backup failed", "Backed up with your passkey") { service.backupWithPasskey() }
                            }) else null,
                        )
                    }
                    if (b != null && policy?.backupMode != E2eePolicy.BackupMode.REQUIRED) {
                        add { SettingsRow(title = "Turn Off Backup…", titleColor = if (usable) c.error else null, onClick = if (usable && !busy) ({ turnOffBackup = true }) else null) }
                    }
                },
            )
            if (b != null) {
                settingsSection(
                    key = "restore",
                    header = "Restore",
                    footer = "Restoring imports your saved history and OpenPGP keys into this device; it keeps this device's own identity.",
                    rows = buildList<RowContent> {
                        if (b.passkey) {
                            add {
                                SettingsRow(
                                    title = "Restore with Passkey",
                                    titleColor = if (usable) c.link else null,
                                    onClick = if (usable && !busy) ({
                                        if (!service.passkeysAvailable) {
                                            prfInfo = true
                                        } else {
                                            op("Restore failed") {
                                                val r = service.restoreWithPasskey()
                                                notice = restoreNotice(r.archivedRows, r.pgpConflicts.size)
                                            }
                                        }
                                    }) else null,
                                )
                            }
                        } else {
                            add {
                                Column(Modifier.fillMaxWidth().padding(Grid.margin), verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                                    OutlinedTextField(
                                        value = restoreKey,
                                        onValueChange = { restoreKey = it },
                                        label = { Text("Recovery key") },
                                        singleLine = true,
                                        enabled = usable && !busy,
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                                        modifier = Modifier.fillMaxWidth().testTag("restoreRecoveryKey"),
                                    )
                                    TextButton(
                                        enabled = usable && !busy && E2eeRecoveryKey.isWellFormed(restoreKey),
                                        onClick = {
                                            op("Restore failed") {
                                                val r = service.restoreWithRecoveryKey(restoreKey)
                                                restoreKey = ""
                                                notice = restoreNotice(r.archivedRows, r.pgpConflicts.size)
                                            }
                                        },
                                    ) { Text("Restore with Recovery Key") }
                                    if (restoreKey.isNotBlank() && !E2eeRecoveryKey.isWellFormed(restoreKey)) {
                                        Text("That isn't a valid recovery key", color = c.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }

        // ── OpenPGP ──
        if (status != null) {
            settingsSection(
                key = "pgp",
                header = "OpenPGP (Direct Messages)",
                footer = "For conversations in OpenPGP mode with people using other OpenPGP software. Your private key stays encrypted on this device.",
                rows = listOf(
                    { SettingsRow(title = "Your Key", value = if (locked) "Locked" else ownKey?.let { formatFingerprint(it) } ?: "None") },
                    {
                        SettingsRow(
                            title = if (ownKey == null) "Import Private Key…" else "Replace Private Key…",
                            titleColor = if (usable) c.link else null,
                            onClick = if (usable && !busy) ({ importOpen = true }) else null,
                        )
                    },
                ),
            )
            if (oldKeys.isNotEmpty()) {
                settingsSection(
                    key = "pgpOld",
                    header = "Previous Keys",
                    footer = "Kept only to decrypt older messages; new messages never use them.",
                    rows = oldKeys.map<String, RowContent> { fingerprint ->
                        { SettingsRow(title = formatFingerprint(fingerprint), value = "Decrypt only", modifier = Modifier.testTag("pgpOldKey")) }
                    },
                )
            }
        }

        // ── organization ──
        if (policy != null && policy.orgDomain.isNotEmpty()) {
            settingsSection(
                key = "org",
                header = "Organization",
                footer = if (policy.orgIdp) "Sign in with ${policy.orgDomain} to install a certificate that vouches for this device. Install it within 10 minutes of signing in." else null,
                rows = buildList<RowContent> {
                    add { SettingsRow(title = "Managed by", value = policy.orgDomain) }
                    if (policy.orgIdp) {
                        add {
                            SettingsRow(
                                title = "Sign In with Your Organization…",
                                titleColor = if (usable) c.link else null,
                                onClick = if (usable && !busy) ({
                                    if (!E2eeOrgSignIn.start(context, api, service, policy.orgDomain)) {
                                        alerts.show("Can't open the browser", "No browser is available for your organization's sign-in.")
                                    }
                                }) else null,
                            )
                        }
                    }
                },
            )
        }

        // ── defaults ──
        if (status != null) {
            val orgUnlock = policy?.requireUnlock == true
            val orgDm = policy?.dmDefault == true
            settingsSection(
                key = "defaults",
                header = "Defaults",
                footer = "Decrypted messages are never written to disk. With \"Keep decrypted messages\" off, conversations you leave are decrypted again from the encrypted archive when you return." +
                    if (locked) " Unlock to change these settings." else "",
                rows = listOf<RowContent>(
                    {
                        SettingsSwitchRow("Encrypt New Direct Messages", checked = orgDm || defaults.dmDefault, enabled = usable && !busy && !orgDm) {
                            saveDefaults(defaults.copy(dmDefault = it))
                        }
                    },
                    {
                        SettingsSwitchRow("Lock a Conversation When an Encrypted Message Arrives", checked = defaults.autoLockIncoming, enabled = usable && !busy) {
                            saveDefaults(defaults.copy(autoLockIncoming = it))
                        }
                    },
                    {
                        SettingsSwitchRow("Keep Decrypted Messages While Switching Conversations", checked = defaults.cacheDecrypted, enabled = usable && !busy) {
                            saveDefaults(defaults.copy(cacheDecrypted = it))
                        }
                    },
                    {
                        SettingsSwitchRow("Require Passkey to Unlock", checked = orgUnlock || defaults.requireUnlock, enabled = usable && !busy && !orgUnlock) { on ->
                            if (!on) {
                                saveDefaults(defaults.copy(requireUnlock = false))
                            } else if (!service.passkeysAvailable) {
                                prfInfo = true
                            } else {
                                // Enroll and prove a PRF credential first; only then require it.
                                op("Passkey unlock not turned on", "Passkey unlock is on") {
                                    val credentialId = service.enrollPasskey()
                                    service.unlockWithPasskey(credentialId)
                                    service.setUnlockCredentialId(credentialId)
                                    service.setDefaults(defaults.copy(requireUnlock = true))
                                }
                            }
                        }
                    },
                ).let { rows -> if (orgUnlock || orgDm) rows + { SettingsRow(title = "Required by your organization", value = listOfNotNull(if (orgDm) "DMs" else null, if (orgUnlock) "Unlock" else null).joinToString(" · ")) } else rows },
            )
        }

        // ── danger zone ──
        if (status != null) {
            settingsSection(
                key = "danger",
                header = "Danger Zone",
                footer = "Reset generates a new device credential and tells your contacts (at most once per 24 hours). Removing this device's keys deletes its encrypted history here and removes it from your devices.",
                rows = listOf(
                    {
                        SettingsRow(title = "Reset Identity…", titleColor = if (usable) c.error else null, onClick = if (usable && !busy) ({
                            confirmation = Confirmation(
                                "Reset Identity",
                                "Generate a new device credential? Your contacts will see a new safety number. You can do this at most once per 24 hours.",
                                "Reset",
                            ) { op("Identity not reset", "Identity reset") { service.resetIdentity() } }
                        }) else null)
                    },
                    {
                        SettingsRow(title = "Remove This Device's Keys…", titleColor = if (status.deviceId != null) c.error else null, onClick = if (!busy) ({
                            confirmation = Confirmation(
                                "Remove This Device's Keys",
                                "Remove this device from your encryption devices and delete its keys and encrypted history? Messages you can't read on another device become unreadable here.",
                                "Remove",
                            ) {
                                busy = true
                                scope.launch {
                                    try {
                                        service.removeLocalKeys()
                                        forgetDrafts(context, service)
                                        notice = "This device's keys were removed"
                                        reload++
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // Revocation failed: nothing was deleted. A local-only removal is a separate choice.
                                        confirmation = Confirmation(
                                            "Couldn't Remove This Device",
                                            "${E2eeTexts.error(e)}. You can delete the keys from this device only; it stays listed in your devices until you remove it from another device.",
                                            "Remove from This Device Only",
                                        ) {
                                            op("Keys not removed", "This device's keys were removed") {
                                                service.removeLocalStateOnlyAndRestart()
                                                forgetDrafts(context, service)
                                            }
                                        }
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }) else null)
                    },
                ),
            )
        }
    }

    if (unlockOpen) UnlockDialog(service, onDismiss = { unlockOpen = false; reload++ })
    recoveryKeyShown?.let { key -> RecoveryKeyDialog(key, onCopy = { copySensitive(context, "Recovery key", key) }, onSave = {
        saveKeyFile.launch("ircfiber-recovery-key-${service.username ?: "account"}.txt")
    }, onDone = { recoveryKeyShown = null; notice = "Backed up with your recovery key"; reload++ }) }
    if (importOpen) PgpImportDialog(service, onDone = { importOpen = false; reload++ })
    if (prfInfo) {
        AlertDialog(
            onDismissRequest = { prfInfo = false },
            title = { Text("Passkeys Not Supported") },
            text = { Text(E2eeTexts.PRF_UNSUPPORTED) },
            confirmButton = { TextButton(onClick = { prfInfo = false }) { Text("OK") } },
        )
    }
    if (turnOffBackup) {
        TurnOffBackupDialog(onDismiss = { turnOffBackup = false }) {
            turnOffBackup = false
            op("Backup not turned off", "Backup turned off — devices only") { service.deleteBackup() }
        }
    }
    ConfirmDialog(confirmation) { confirmation = null }
    ErrorDialog(alerts)
}

private suspend fun forgetDrafts(context: android.content.Context, service: E2eeService) {
    val userId = service.userId ?: return
    withContext(Dispatchers.IO) { runCatching { E2eePendingDrafts.removeAccount(context, service.apiOrigin, userId) } }
}

private fun restoreNotice(rows: Int, conflicts: Int): String =
    "History restored: $rows messages" + if (conflicts > 0) ". ${E2eeTexts.pgpConflicts(conflicts)}" else ""

/** `ABCD 1234 …` groups of 4 for a hex fingerprint. */
internal fun formatFingerprint(hex: String): String = hex.uppercase().chunked(4).joinToString(" ")

private fun statusIsProblem(state: E2eeServiceState, status: E2eeStatus?): Boolean =
    state is E2eeServiceState.Unavailable || state is E2eeServiceState.RecoveryRequired ||
        status?.bootstrapError != null || status?.ktAlarm == true || (status != null && status.policy == null)

private fun statusValue(state: E2eeServiceState, status: E2eeStatus?): String = when (state) {
    E2eeServiceState.Idle, E2eeServiceState.Starting -> "Starting…"
    is E2eeServiceState.Unavailable -> "Unavailable"
    is E2eeServiceState.RecoveryRequired -> "Needs recovery"
    E2eeServiceState.Stopped -> "Off"
    is E2eeServiceState.Running -> when {
        status?.locked == true -> "Locked"
        status?.bootstrapError != null -> "Setup failed"
        status?.policy == null -> "Policy unavailable"
        status?.ready == true -> "Ready"
        else -> "Not set up yet"
    }
}

private fun statusFooter(state: E2eeServiceState, status: E2eeStatus?): String? = when (state) {
    is E2eeServiceState.Unavailable -> if (state.webViewUpdate) "Update Android System WebView to use encryption. Encrypted sends stay blocked until then." else state.message
    is E2eeServiceState.RecoveryRequired -> "This device's encryption storage can't be read (${state.detail}). Nothing was deleted."
    is E2eeServiceState.Running -> when {
        status?.locked == true -> "Encrypted messages are hidden and sends are blocked until you unlock."
        status?.bootstrapError != null -> "Couldn't set up this device's key: ${status.bootstrapError!!.text}"
        status?.policy == null -> "Couldn't load your account's encryption policy. Encrypted sends stay blocked until it loads."
        else -> null
    }
    else -> null
}

/** The recovery key, shown once: copy or save only on the user's explicit action; Done after "I saved it". */
@Composable
private fun RecoveryKeyDialog(key: String, onCopy: () -> Unit, onSave: () -> Unit, onDone: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your Recovery Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                Text("Write this key down or save it somewhere safe. It's the only way to restore your backup without your devices, and it won't be shown again.")
                SelectionContainer { Text(key, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("recoveryKey")) }
                Row {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onSave) { Text("Save to File…") }
                }
                Row(Modifier.heightIn(min = Grid.tapTarget), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it }, modifier = Modifier.testTag("recoveryKeySaved"))
                    Text("I saved the key somewhere else")
                }
            }
        },
        confirmButton = { TextButton(enabled = saved, onClick = onDone) { Text("Done") } },
    )
}

@Composable
private fun TurnOffBackupDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn Off Backup") },
        text = {
            Column {
                Text("Your encrypted backup is deleted from ircfiber.com. If you lose all your devices, your encrypted history is gone.")
                Row(Modifier.heightIn(min = Grid.tapTarget), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(enabled = understood, onClick = onConfirm) { Text("Delete Backup", color = LocalFiberIrcColors.current.error) } },
    )
}

/** OpenPGP own-key import with every outcome the runtime reports, and the local passphrase for a key without one. */
@Composable
private fun PgpImportDialog(service: E2eeService, onDone: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    var armored by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var localPassphrase by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<E2eePgpImport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = E2eeTexts.error(e)
            } finally {
                busy = false
            }
        }
    }

    val r = result
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Import OpenPGP Private Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                if (r == null || r.error) {
                    OutlinedTextField(
                        value = armored,
                        onValueChange = { armored = it },
                        label = { Text("-----BEGIN PGP PRIVATE KEY BLOCK-----") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("pgpArmored"),
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Key passphrase (if any)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                r?.let {
                    Text(
                        it.text + (it.fingerprint?.let { f -> " · ${formatFingerprint(f)}" } ?: ""),
                        color = if (it.error) c.error else if (it.issue == "ok") c.submitGreen else c.textSecondary,
                        modifier = Modifier.testTag("pgpImportResult"),
                    )
                }
                if (r?.issue == "no_passphrase") {
                    OutlinedTextField(
                        value = localPassphrase,
                        onValueChange = { localPassphrase = it },
                        label = { Text("New passphrase for this device") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = c.error) }
            }
        },
        confirmButton = {
            when {
                r == null || r.error -> TextButton(enabled = !busy && armored.isNotBlank(), onClick = {
                    run {
                        val outcome = service.importOwnKey(armored.trim(), passphrase)
                        result = outcome
                        if (outcome.stored) {
                            armored = ""
                            passphrase = ""
                        }
                    }
                }) { Text("Import") }
                r.issue == "no_passphrase" -> TextButton(enabled = !busy && localPassphrase.isNotEmpty(), onClick = {
                    run {
                        service.setLocalPassphrase(localPassphrase)
                        localPassphrase = ""
                        onDone()
                    }
                }) { Text("Set Passphrase") }
                else -> TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(if (r?.stored == true) "Close" else "Cancel") } },
    )
}
