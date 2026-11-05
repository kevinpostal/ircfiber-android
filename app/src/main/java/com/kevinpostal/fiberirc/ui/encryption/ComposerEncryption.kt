package com.kevinpostal.fiberirc.ui.encryption

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.e2ee.ComposerLock
import com.kevinpostal.fiberirc.data.e2ee.E2eeComposerLock
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeTexts
import com.kevinpostal.fiberirc.data.e2ee.archivedBy
import com.kevinpostal.fiberirc.data.e2ee.audience
import com.kevinpostal.fiberirc.data.e2ee.e2eeBufferKey
import com.kevinpostal.fiberirc.data.e2ee.E2eeAudience
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcEncryptionConfig
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.ui.chat.ControlButton
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect

/** The composer's encryption controls for one buffer; null where encryption doesn't apply (server buffer, direct IRC). */
@Stable
class ComposerEncryption(
    val service: E2eeService,
    val client: IrcClient,
    val buffer: IrcBuffer,
    val lock: ComposerLock,
    val archivedBy: String?,
) {
    val networkId: String get() = client.settings.networkId
    val channel: Boolean get() = buffer.kind == IrcBufferKind.CHANNEL
}

/**
 * The shown buffer's [ComposerEncryption], recomputed on service state, buffer-pref and session ([revision]) changes.
 * The lock comes from the message path's own decision ([IrcClient.lockDecision]), so the composer never shows a
 * target as unencrypted that a send would encrypt or hold.
 */
@Composable
fun rememberComposerEncryption(
    service: E2eeService?,
    client: IrcClient,
    buffer: IrcBuffer,
    prefs: BufferPrefsStore,
    revision: Long,
): ComposerEncryption? {
    if (service == null || client.encryption !is IrcEncryptionConfig.Encrypted) return null
    if (buffer.kind == IrcBufferKind.SERVER || client.settings.networkId.isEmpty()) return null
    val state by service.state.collectAsState()
    val values by prefs.values.collectAsState()
    val key = e2eeBufferKey(client.settings.networkId, buffer.name)
    return remember(state, values, revision, buffer, client) {
        val lock = E2eeComposerLock.of(client.lockDecision(buffer.name), state, key)
        ComposerEncryption(service, client, buffer, lock, (state as? E2eeServiceState.Running)?.status?.archivedBy(key))
    }
}

/** The lock button first in the composer row: off → turns encryption on; otherwise a menu of the buffer's options. */
@Composable
fun ComposerLockControl(enc: ComposerEncryption, onDetails: () -> Unit, onAudience: () -> Unit, onUnlock: () -> Unit, onError: (String) -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val lock = enc.lock

    fun setLock(on: Boolean) = scope.launch {
        try {
            enc.service.setLock(enc.networkId, enc.buffer.name, on)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(E2eeTexts.error(e))
        }
    }

    val (label, tint) = when (lock) {
        ComposerLock.Off -> "Encryption off" to c.textSecondary
        ComposerLock.On -> "Encrypted" to c.submitGreen
        is ComposerLock.Partial, ComposerLock.NoPeer, ComposerLock.Locked, is ComposerLock.Unavailable -> "Encryption needs attention" to c.error
        is ComposerLock.Pending -> "Setting up encryption" to c.textSecondary
    }
    Box {
        ControlButton(label, "encryptionLockButton", onClick = { if (lock == ComposerLock.Off) setLock(true) else menu = true }) {
            Icon(
                when (lock) {
                    ComposerLock.Off -> Icons.Outlined.LockOpen
                    is ComposerLock.Pending -> Icons.Filled.HourglassTop
                    else -> Icons.Filled.Lock
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (lock == ComposerLock.Locked) {
                DropdownMenuItem(text = { Text("Unlock…") }, onClick = { menu = false; onUnlock() })
            }
            if (lock is ComposerLock.Pending && lock.cancellable) {
                DropdownMenuItem(text = { Text("Cancel Encryption Setup") }, onClick = { menu = false; setLock(false) })
            } else if (lock != ComposerLock.Off) {
                DropdownMenuItem(text = { Text("Turn Off Encryption") }, onClick = { menu = false; setLock(false) })
            }
            if (enc.channel && lock != ComposerLock.Off) {
                DropdownMenuItem(text = { Text("Who Can Read…") }, onClick = { menu = false; onAudience() })
            }
            DropdownMenuItem(text = { Text("Encryption Details…") }, onClick = { menu = false; onDetails() })
        }
    }
}

/**
 * The state line above the field while the buffer encrypts: lock state, reader counts, the channel's audience chip,
 * Cancel for a setup still settling, Unlock when locked, the compliance-archive notice and the last lock error.
 */
@Composable
fun ComposerEncryptionBanner(enc: ComposerEncryption, peer: String?, revision: Long, error: String?, onAudience: () -> Unit, onUnlock: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val label = E2eeComposerLock.label(enc.lock, peer)
    var chip by remember(enc.buffer) { mutableStateOf<String?>(null) }
    if (enc.channel && enc.lock.encrypting && enc.lock !is ComposerLock.Unavailable) {
        LaunchedEffect(enc.buffer, revision, enc.lock) {
            chip = runCatching { enc.service.audience(enc.networkId, enc.buffer.name) }.getOrNull()?.chip
        }
    }
    if (label == null && error == null && enc.archivedBy == null) return
    val warn = enc.lock is ComposerLock.Partial || enc.lock == ComposerLock.NoPeer || enc.lock is ComposerLock.Unavailable
    val small = TextStyle(fontSize = 12.sp)
    Row(
        Modifier.fillMaxWidth().padding(start = Grid.margin, end = Grid.margin, top = Grid.unit).testTag("encryptionBanner"),
        horizontalArrangement = Arrangement.spacedBy(Grid.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = listOfNotNull(
            error,
            label,
            enc.archivedBy?.let { "Archived by $it for compliance" },
        ).joinToString(" · ")
        Text(
            text,
            style = small,
            color = if (error != null || warn) c.error else c.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        chip?.takeIf { enc.lock.encrypting }?.let {
            Text(it, style = small, color = c.link, modifier = Modifier.clickable(onClick = onAudience).testTag("audienceChip"))
        }
        val pending = enc.lock
        if (pending is ComposerLock.Pending && pending.cancellable) {
            Text(
                "Cancel",
                style = small,
                color = c.link,
                modifier = Modifier.clickable {
                    scope.launch { runCatching { enc.service.setLock(enc.networkId, enc.buffer.name, false) } }
                },
            )
        }
        if (enc.lock == ComposerLock.Locked) {
            Text("Unlock", style = small, color = c.link, modifier = Modifier.clickable(onClick = onUnlock).testTag("encryptionUnlock"))
        }
    }
}
