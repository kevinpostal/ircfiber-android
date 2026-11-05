package com.kevinpostal.fiberirc.ui.encryption

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.e2ee.E2eeAudience
import com.kevinpostal.fiberirc.data.e2ee.E2eeBackupStatus
import com.kevinpostal.fiberirc.data.e2ee.E2eeRecoveryKey
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeTexts
import com.kevinpostal.fiberirc.data.e2ee.audience
import com.kevinpostal.fiberirc.data.e2ee.backupStatus
import com.kevinpostal.fiberirc.data.e2ee.policy
import com.kevinpostal.fiberirc.data.e2ee.setAudience
import com.kevinpostal.fiberirc.data.e2ee.unlockWithPasskey
import com.kevinpostal.fiberirc.data.e2ee.unlockWithRecoveryKey
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A QR code of [text] (black on a white card so any camera reads it in dark mode too). */
@Composable
fun QrImage(text: String, size: Dp = 200.dp, modifier: Modifier = Modifier) {
    val image = remember(text) { runCatching { QrCodes.bitmap(QrCodes.encode(text)).asImageBitmap() }.getOrNull() } ?: return
    QrCard(image, size, modifier)
}

/** A PNG QR the runtime drew (`qrDataUrl`). */
@Composable
fun QrPng(png: ByteArray, size: Dp = 200.dp, modifier: Modifier = Modifier) {
    val image = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() } ?: return
    QrCard(image, size, modifier)
}

@Composable
private fun QrCard(image: ImageBitmap, size: Dp, modifier: Modifier) {
    Image(
        bitmap = image,
        contentDescription = "QR code",
        filterQuality = FilterQuality.None,
        modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp).testTag("qrCode"),
    )
}

/** Digits in a monospace face, grouped as given ("01234 56789 …"). */
@Composable
fun SafetyDigits(number: String, modifier: Modifier = Modifier) {
    Text(
        number,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalFiberIrcColors.current.chatText,
        modifier = modifier.testTag("safetyNumber"),
    )
}

/**
 * Unlock when the account or this device requires it: a passkey (PRF) when this device has a passkey provider and an
 * enrolled credential, else the explicit not-supported explanation; a recovery key only when unlock isn't required
 * by the organization and a recovery-key backup exists (the runtime proves the key against that backup).
 */
@Composable
fun UnlockDialog(service: E2eeService, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by service.state.collectAsState()
    val policy = (state as? E2eeServiceState.Running)?.status?.policy
    var backup by remember { mutableStateOf<E2eeBackupStatus?>(null) }
    var backupLoaded by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        backup = runCatching { service.backupStatus() }.getOrNull()
        backupLoaded = true
    }
    val orgRequired = policy?.requireUnlock == true
    val recoveryAllowed = !orgRequired && backup?.passkey == false
    val passkeys = service.passkeysAvailable

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cancelled passkey sheet leaves the dialog as it was.
                error = if (E2eeTexts.isPasskeyCancellation(e)) null else E2eeTexts.error(e)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Unlock Encryption") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                Text(
                    if (orgRequired) "Your organization requires a passkey to unlock encrypted messages on this device."
                    else "Encrypted messages on this device are locked until you unlock them.",
                )
                if (!passkeys) Text(E2eeTexts.PRF_UNSUPPORTED, color = LocalFiberIrcColors.current.textSecondary)
                if (recoveryAllowed) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Recovery key") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("unlockRecoveryKey"),
                    )
                } else if (backupLoaded && !orgRequired && !passkeys) {
                    Text("Without a recovery-key backup this device can't be unlocked here. You can remove this device's keys in Settings → Encryption and link it again.")
                }
                error?.let { Text(it, color = LocalFiberIrcColors.current.error) }
            }
        },
        confirmButton = {
            Row {
                if (recoveryAllowed) {
                    TextButton(
                        enabled = !busy && E2eeRecoveryKey.isWellFormed(key),
                        onClick = { run { service.unlockWithRecoveryKey(key) } },
                    ) { Text("Unlock with Recovery Key") }
                }
                if (passkeys) {
                    TextButton(enabled = !busy, onClick = { run { service.unlockWithPasskey(service.unlockCredentialId()) } }) {
                        Text("Unlock with Passkey")
                    }
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A channel's encrypted audience (web `AudiencePopover`): Everyone / Everyone except… / Only…, over the channel's
 * members that have an IRC account (a member without one can't read encrypted messages). Saved per buffer on this
 * device; the count follows membership changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudienceSheet(service: E2eeService, networkId: String, buffer: IrcBuffer, ownNick: String, revision: Long, onDismiss: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    var audience by remember { mutableStateOf<E2eeAudience?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        audience = try {
            service.audience(networkId, buffer.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = E2eeTexts.error(e)
            null
        }
    }
    val self = service.username.orEmpty()
    val members = remember(revision, buffer) {
        buffer.members.filter { !it.nick.equals(ownNick, ignoreCase = true) && !it.account.equals(self, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Grid.margin)) {
            Text("Who can read", style = MaterialTheme.typography.titleMedium, color = c.heading)
            val a = audience
            if (a == null) {
                Text(error ?: "Loading…", color = if (error != null) c.error else c.textSecondary, modifier = Modifier.padding(vertical = Grid.margin))
                return@Column
            }
            for (mode in E2eeAudience.Mode.entries) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Grid.tapTarget).clickable { audience = a.copy(mode = mode) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = a.mode == mode, onClick = { audience = a.copy(mode = mode) })
                    Text(mode.title, color = c.chatText)
                }
            }
            val accounts = members.map { it.account }.filter { it.isNotEmpty() && it != "*" }
            val readers = E2eeAudience.readers(accounts, a, self)
            val total = maxOf(members.size + 1, readers.size)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(members, key = { it.nick }) { m ->
                    val account = m.account.lowercase().takeIf { it.isNotEmpty() && it != "*" }
                    val checked = account != null && account in a.accounts
                    Row(Modifier.fillMaxWidth().heightIn(min = Grid.tapTarget), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            enabled = account != null && a.mode != E2eeAudience.Mode.EVERYONE,
                            onCheckedChange = { on ->
                                val acct = account ?: return@Checkbox
                                audience = a.copy(accounts = if (on) (a.accounts + acct).distinct() else a.accounts - acct)
                            },
                        )
                        Column {
                            Text(m.nick, color = c.chatText)
                            Text(account ?: "not on ircfiber — can't read encrypted messages", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            val nobody = a.mode == E2eeAudience.Mode.ONLY && readers.size <= 1
            Text(
                if (nobody) "No one selected can read this" else "${readers.size} of $total can read",
                color = if (nobody) c.error else c.textSecondary,
                modifier = Modifier.padding(vertical = Grid.gutter).testTag("audienceCount"),
            )
            error?.let { Text(it, color = c.error) }
            Row(Modifier.fillMaxWidth().padding(bottom = Grid.margin), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = !saving && !nobody,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                service.setAudience(networkId, buffer.name, if (a.mode == E2eeAudience.Mode.EVERYONE) E2eeAudience.EVERYONE else a)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = E2eeTexts.error(e)
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text("Save") }
            }
        }
    }
}
