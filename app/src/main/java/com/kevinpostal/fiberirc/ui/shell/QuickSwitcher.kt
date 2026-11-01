package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** What the quick switcher shows for a query (ports `QuickSwitch`, `QuickSwitcherViewController.swift:4-21`). */
object QuickSwitch {
    /**
     * Items whose name contains [query] (case-insensitive; blank matches all), names starting with it first
     * (ignoring a leading `#`/`&` on the name), each group in the given order.
     */
    fun <T> matches(items: List<T>, query: String, name: (T) -> String): List<T> {
        val q = query.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.lowercase()
        if (q.isEmpty()) return items
        val prefixed = ArrayList<T>()
        val contained = ArrayList<T>()
        for (item in items) {
            val n = name(item).lowercase()
            if (n.startsWith(q) || n.dropWhile { it == '#' || it == '&' }.startsWith(q)) {
                prefixed.add(item)
            } else if (n.contains(q)) {
                contained.add(item)
            }
        }
        return prefixed + contained
    }
}

/** One switcher row: [buffer] on [client]; [name] is the buffer's name, the network's for its server buffer. */
class QuickSwitchEntry(val client: IrcClient, val buffer: IrcBuffer, val name: String)

/**
 * "Jump to channel" (iOS ⌘K, here the top bar's search icon or Ctrl+K): a search field over every buffer in the
 * drawer's order, narrowed as you type. The IME's Go (or Enter) opens the first match; Escape or ✕ closes.
 */
@Composable
fun QuickSwitcher(
    entries: List<QuickSwitchEntry>,
    onPick: (IrcClient, IrcBuffer) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(entries, query) { QuickSwitch.matches(entries, query) { it.name } }
    val focus = remember { FocusRequester() }
    fun pick(e: QuickSwitchEntry) {
        onDismiss()
        onPick(e.client, e.buffer)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .background(c.panelAlt)
                .testTag("quickSwitcher"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.textSecondary)
                }
                Text("Jump to channel", color = c.heading, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Channel or nickname", color = c.textTertiary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { rows.firstOrNull()?.let(::pick) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.field,
                    unfocusedContainerColor = c.field,
                    focusedTextColor = c.chatText,
                    unfocusedTextColor = c.chatText,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Grid.margin)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Escape -> { onDismiss(); true }
                            Key.Enter, Key.NumPadEnter -> { rows.firstOrNull()?.let(::pick); true }
                            else -> false
                        }
                    },
            )
            Spacer(Modifier.height(Grid.unit))
            LazyColumn {
                itemsIndexed(rows, key = { i, _ -> i }) { _, e ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pick(e) }
                            .padding(horizontal = Grid.margin + Grid.unit, vertical = Grid.gutter),
                    ) {
                        Text(
                            e.name,
                            color = c.chatText,
                            fontSize = 15.sp,
                            fontWeight = if (e.buffer.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (e.buffer.kind != IrcBufferKind.SERVER) {
                            Spacer(Modifier.height(Grid.unit))
                            Text(e.client.settings.networkName, color = c.textSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    HorizontalDivider(color = c.borderDark)
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
