package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore.Key
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Display Options' switches and their buffer-pref effects (ports `DisplayOptionsViewController`'s statics). */
object DisplayOptions {
    /** A switch over one buffer pref. [inverted] shows the opposite of the stored key (Collapse = `expandJoinPart` off). */
    data class BufferToggle(val title: String, val key: Key, val inverted: Boolean = false)

    /** This channel's or conversation's switches, in IRCCloud's order. */
    val bufferToggles = listOf(
        BufferToggle("Show Joins, Parts, Quits", Key.SHOW_JOIN_PART),
        BufferToggle("Collapse Joins, Parts, Quits", Key.EXPAND_JOIN_PART, inverted = true),
        BufferToggle("Collapse Replies", Key.REPLY_COLLAPSE),
        BufferToggle("Show Typing", Key.TYPING),
        BufferToggle("Embed Uploaded Files", Key.INLINE_FILES),
        BufferToggle("Embed External Media", Key.INLINE_IMAGES),
        BufferToggle("Embed Text Snippets", Key.INLINE_PASTES),
        BufferToggle("Format Colours", Key.FORMAT_COLOR),
        BufferToggle("Nick Suggestions", Key.NICK_SUGGESTIONS),
        BufferToggle("Mark As Read Automatically", Key.MARK_AS_READ),
        BufferToggle("Show Unread Count", Key.SHOW_UNREAD_COUNT),
    )

    /** What one flip of [toggle] saves (one `BufferPrefsStore.set`). */
    fun changes(toggle: BufferToggle, on: Boolean): Map<Key, Boolean> = mapOf(toggle.key to if (toggle.inverted) !on else on)

    /** The Notifications control: Mentions, All Messages or Muted over `mute` and `notifyAll`. */
    enum class Notifications(val title: String) {
        MENTIONS("Mentions"), ALL("All Messages"), MUTED("Muted");

        val changes: Map<Key, Boolean>
            get() = when (this) {
                MENTIONS -> mapOf(Key.MUTE to false, Key.NOTIFY_ALL to false)
                ALL -> mapOf(Key.MUTE to false, Key.NOTIFY_ALL to true)
                MUTED -> mapOf(Key.MUTE to true, Key.NOTIFY_ALL to false)
            }

        companion object {
            fun of(mute: Boolean, notifyAll: Boolean): Notifications = if (mute) MUTED else if (notifyAll) ALL else MENTIONS
        }
    }
}

/**
 * Display Options (IRCCloud `DisplayOptionsViewController`), from the gear menu and a joined channel's drawer menu: the
 * account's Show Avatars and Show Timestamps (shared with Settings → Display), then this channel's or conversation's
 * own settings ([BufferPrefsStore], shared with the web's channel menu) and its notifications. Every switch applies at
 * once and saves; a failed save reverts it (the stores revert, the switches follow their flows) and says why.
 * The buffer sections show only for a channel or conversation on an account network.
 */
@Composable
fun DisplayOptionsScreen(
    client: IrcClient,
    buffer: IrcBuffer,
    accountSettings: AccountSettingsStore,
    bufferPrefs: BufferPrefsStore,
    deviceSettings: DeviceSettingsStore,
    onClose: () -> Unit,
) {
    val display by accountSettings.display.collectAsState()
    val device by deviceSettings.settings.collectAsState()
    @Suppress("UNUSED_VARIABLE") val stored by bufferPrefs.values.collectAsState()
    val networkId = client.settings.networkId
    val showBuffer = buffer.kind != IrcBufferKind.SERVER && networkId.isNotEmpty()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    fun save(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
    }

    fun effective(k: Key): Boolean {
        stored // recompose on any stored change
        return bufferPrefs.effective(k, networkId, buffer.name, device, display.formatColors)
    }

    fun saveBuffer(changes: Map<Key, Boolean>) = save { bufferPrefs.set(changes, networkId, buffer.name) }

    ManagementScaffold(title = "Display Options", navigation = { BarButton("Done", onClick = onClose) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Everywhere") }
            item {
                ToggleRow("Show Avatars", display.showUserIcons) { on ->
                    save { accountSettings.updateDisplay { it.copy(showUserIcons = on) } }
                }
            }
            item {
                ToggleRow("Show Timestamps", display.showTimestamps) { on ->
                    save { accountSettings.updateDisplay { it.copy(showTimestamps = on) } }
                }
            }
            if (showBuffer) {
                item { SectionHeader(if (buffer.kind == IrcBufferKind.CHANNEL) "This Channel" else "This Conversation") }
                for (t in DisplayOptions.bufferToggles) {
                    item {
                        val value = effective(t.key)
                        ToggleRow(t.title, if (t.inverted) !value else value) { on -> saveBuffer(DisplayOptions.changes(t, on)) }
                    }
                }
                item { SectionHeader("Notifications") }
                item {
                    val current = DisplayOptions.Notifications.of(effective(Key.MUTE), effective(Key.NOTIFY_ALL))
                    val all = DisplayOptions.Notifications.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        all.forEachIndexed { i, n ->
                            SegmentedButton(
                                selected = n == current,
                                onClick = { if (n != current) saveBuffer(n.changes) },
                                shape = SegmentedButtonDefaults.itemShape(i, all.size),
                            ) { Text(n.title) }
                        }
                    }
                }
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't save setting") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
