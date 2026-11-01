package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.ChannelListEntry
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.ui.chat.SearchFold
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.BufferLink
import java.text.Collator

/** What the channel list shows for a search (ports `ChannelListing`). */
object ChannelListing {
    /**
     * Entries whose name or topic contains [query] (case- and diacritic-insensitive; blank matches all), most users
     * first, then by name.
     */
    fun rows(entries: List<ChannelListEntry>, query: String): List<ChannelListEntry> {
        val q = query.trim(' ', '\t')
        val hits = if (q.isEmpty()) entries else {
            val needle = SearchFold.fold(q)
            entries.filter { SearchFold.fold(it.name).contains(needle) || SearchFold.fold(it.topic).contains(needle) }
        }
        // `localizedCaseInsensitiveCompare`: the locale's collation, case ignored.
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return hits.sortedWith { a, b ->
            if (a.users != b.users) b.users.compareTo(a.users) else collator.compare(a.name, b.name)
        }
    }
}

/**
 * The network's channels (`LIST`, from "Browse Channels…" and `/list`; ports `ChannelListViewController`): a search
 * over names and topics, rows `"#chan · N users"` over the topic in two lines, most users first. Tapping a channel
 * closes the screen and joins and selects it ([AppRouter.open] with [BufferLink.Join]).
 */
@Composable
fun ChannelListScreen(client: IrcClient, onClose: () -> Unit) {
    var entries by remember { mutableStateOf(emptyList<ChannelListEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    DisposableEffect(client) {
        var active = true
        client.listChannels(
            onPage = { page ->
                if (active) entries = entries + page.map { ChannelListEntry(it.name, it.users, IrcFormatting.strip(it.topic)) }
            },
            completion = { if (active) loading = false },
        )
        onDispose { active = false }
    }
    val rows = remember(entries, query) { ChannelListing.rows(entries, query) }

    ManagementScaffold(
        title = "Channels on ${client.settings.networkName}",
        navigation = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search names and topics") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { entry ->
                    ListItem(
                        headlineContent = { Text("${entry.name} · ${entry.users} users") },
                        supportingContent = if (entry.topic.isEmpty()) null else {
                            { Text(entry.topic, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        },
                        modifier = Modifier.clickable {
                            onClose()
                            AppRouter.open(BufferLink.Join(client.settings.networkId, entry.name))
                        },
                    )
                    HorizontalDivider()
                }
                if (loading || rows.isEmpty()) {
                    item(key = "status") { StatusMessage(if (loading) "Loading channels…" else "No channels") }
                }
            }
        }
    }
}
