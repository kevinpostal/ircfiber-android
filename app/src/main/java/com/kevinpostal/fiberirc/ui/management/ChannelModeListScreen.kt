package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcClientListener
import com.kevinpostal.fiberirc.data.irc.IrcEventKind
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.ModeListEntry
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** Wording of a channel's list modes (IRCCloud's ban, exception and invite lists), kept pure for tests. */
object ChannelModeList {
    enum class Kind {
        BANS, EXCEPTIONS, INVITES;

        /** Channel Info's row. */
        val listName: String
            get() = when (this) {
                BANS -> "Ban List"
                EXCEPTIONS -> "Exception List"
                INVITES -> "Invite List"
            }

        fun title(channel: String): String = when (this) {
            BANS -> "Bans for $channel"
            EXCEPTIONS -> "Exception list for $channel"
            INVITES -> "Invite list for $channel"
        }

        val emptyText: String
            get() = when (this) {
                BANS -> "No bans"
                EXCEPTIONS -> "No exceptions"
                INVITES -> "No invite exceptions"
            }

        companion object {
            /** `b` is the ban list; the server's EXCEPTS letter the exception list, anything else the invite list. */
            fun of(mode: Char, excepts: Char?, @Suppress("UNUSED_PARAMETER") invex: Char?): Kind = when (mode) {
                'b' -> BANS
                excepts -> EXCEPTIONS
                else -> INVITES
            }
        }
    }

    /** "Set by X · <date>", either half alone when the other is unknown, null when neither is. */
    fun subtitle(e: ModeListEntry): String? {
        val date = e.setAt?.let { mediumDateTime(it) }
        val setter = e.setter
        if (setter.isNullOrEmpty()) return date
        if (date == null) return "Set by $setter"
        return "Set by $setter · $date"
    }
}

/**
 * A channel's ban (`b`), exception (EXCEPTS) or invite-exception (INVEX) list (ports
 * `ChannelModeListViewController`): each mask in mono with who set it and when. Half-ops and above remove an entry
 * with its trailing delete button (iOS: swipe; `MODE <chan> -<m> <mask>`) and add one with "+" (`+<m>`); the list
 * reloads when a MODE line changes it. The row stays until the server's MODE echo reloads the list, so a refused
 * change leaves it in place.
 */
@Composable
fun ChannelModeListScreen(client: IrcClient, buffer: IrcBuffer, mode: Char, onBack: () -> Unit) {
    val kind = remember(mode) { ChannelModeList.Kind.of(mode, client.exceptsMode, client.invexMode) }
    var entries by remember { mutableStateOf<List<ModeListEntry>?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var membersRevision by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }

    val listener = remember(buffer, mode) {
        val target = buffer
        object : IrcClientListener {
            override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) {
                val e = line.event ?: return
                if (buffer === target && e.kind == IrcEventKind.MODE && e.modes.any { it.mode == mode }) reloads++
            }

            override fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) {
                if (buffer === target) membersRevision++
            }
        }
    }
    ObserveClient(client, listener)
    LaunchedEffect(reloads) {
        client.requestModeList(mode, buffer) { entries = it }
    }

    val canChange = remember(membersRevision) { (buffer.member(client.nick)?.rank ?: 0) >= 2 }

    ManagementScaffold(
        title = kind.title(buffer.name),
        navigation = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            if (canChange) IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
        },
    ) { padding ->
        val list = entries
        when {
            list == null -> LoadingIndicator(Modifier.padding(padding))
            list.isEmpty() -> StatusMessage(kind.emptyText, Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.mask }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.mask, fontFamily = FontFamily.Monospace) },
                        supportingContent = ChannelModeList.subtitle(entry)?.let { s -> { Text(s) } },
                        trailingContent = if (canChange) {
                            {
                                IconButton(onClick = { client.setMode("-$mode", listOf(entry.mask), buffer) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = LocalFiberIrcColors.current.error)
                                }
                            }
                        } else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        var mask by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(kind.listName) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Mask to add, such as nick!user@host. * and ? match anything.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = mask,
                        onValueChange = { mask = it },
                        placeholder = { Text("*!*@host", fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adding = false
                    val m = mask.trim(' ')
                    if (m.isNotEmpty() && !m.contains(' ')) client.setMode("+$mode", listOf(m), buffer)
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
