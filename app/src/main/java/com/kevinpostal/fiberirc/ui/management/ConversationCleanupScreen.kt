package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/**
 * Delete Active Conversations… (ports `ConversationCleanupViewController`): every conversation on every network (a
 * section each), tapped to check. "Delete (N)" closes the checked ones the way closing a conversation does anywhere
 * else ([IrcClient.close]) and closes the screen.
 */
@Composable
fun ConversationCleanupScreen(clients: List<IrcClient>, onClose: () -> Unit) {
    val sections = remember(clients) {
        clients.mapNotNull { c ->
            val queries = c.buffers.filter { it.kind == IrcBufferKind.QUERY }
            if (queries.isEmpty()) null else c to queries
        }
    }
    var checked by remember { mutableStateOf(emptySet<IrcBuffer>()) }
    val colors = LocalFiberIrcColors.current

    ManagementScaffold(
        title = "Delete Conversations",
        navigation = { BarButton("Cancel", onClick = onClose) },
        actions = {
            BarButton("Delete (${checked.size})", enabled = checked.isNotEmpty(), color = colors.error) {
                for ((client, queries) in sections) {
                    for (b in queries) if (b in checked) client.close(b)
                }
                onClose()
            }
        },
    ) { padding ->
        if (sections.isEmpty()) {
            StatusMessage("No conversations", Modifier.padding(padding))
            return@ManagementScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            for ((client, queries) in sections) {
                item { SectionHeader(client.settings.networkName) }
                items(queries) { b ->
                    val on = b in checked
                    ListItem(
                        headlineContent = { androidx.compose.material3.Text(b.name, color = colors.chatText) },
                        trailingContent = if (on) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent) }
                        } else null,
                        modifier = Modifier
                            .semantics { selected = on }
                            .clickable { checked = if (on) checked - b else checked + b },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
