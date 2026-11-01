package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.WhoisInfo
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.BufferLink
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Whois wording, kept pure for tests (ports `WhoisViewController`'s statics). */
object WhoisText {
    /** "No such nick" for the server's 401/402; the client's own reasons otherwise. */
    fun errorText(error: String): String = when (error) {
        "Not connected", "Disconnected", "No reply from the server" -> error
        else -> "No such nick"
    }

    /** A 319 entry without its membership prefixes (`@#chan` → `#chan`; `&chan` stays a channel). */
    fun channelName(entry: String): String {
        val prefixes = "!*~&@%+"
        var s = entry
        while (s.length >= 2 && s[0] in prefixes && (s[1] in prefixes || s[1] == '#')) s = s.substring(1)
        return s
    }

    /** "5 minutes", "3 hours", "2 days": the idle time in its largest whole unit. */
    fun idleText(seconds: Double): String {
        val s = maxOf(0.0, seconds).toLong()
        fun unit(n: Long, name: String) = "$n $name${if (n == 1L) "" else "s"}"
        return when {
            s >= 86_400 -> unit(s / 86_400, "day")
            s >= 3_600 -> unit(s / 3_600, "hour")
            else -> unit(s / 60, "minute")
        }
    }
}

private sealed interface WhoisRow {
    data class Value(val text: String, val detail: String?) : WhoisRow
    data class Flag(val text: String, val icon: ImageVector) : WhoisRow
    data class Channel(val name: String) : WhoisRow
    data object SendMessage : WhoisRow
}

/**
 * IRCCloud's Whois screen (ports `WhoisViewController`): a spinner while `WHOIS` runs, then the answer in sections —
 * Real Name, Host, Account, Server, Channels (tap one to open it, or join it), Idle, Signed On, Away, the IRC
 * Operator and Secure Connection flags — and Send a Message. A nick that isn't online reads "No such nick".
 * [networkKey] is the session's key for [client] (`IrcSessionManager.networkKey`); null (no session) hides Send a
 * Message and makes channels inert. Opening a channel or conversation closes the screen, then [AppRouter.open]s it.
 */
@Composable
fun WhoisScreen(client: IrcClient, nick: String, networkKey: String?, onClose: () -> Unit) {
    var info by remember(nick) { mutableStateOf<WhoisInfo?>(null) }
    DisposableEffect(client, nick) {
        var active = true
        client.whois(nick) { if (active) info = it }
        onDispose { active = false }
    }
    val colors = LocalFiberIrcColors.current
    val shown = info

    fun open(link: BufferLink) {
        onClose()
        AppRouter.open(link)
    }

    ManagementScaffold(
        title = shown?.takeIf { it.error == null }?.nick ?: nick,
        actions = { BarButton("Done", onClick = onClose) },
    ) { padding ->
        when {
            shown == null -> LoadingIndicator(Modifier.padding(padding))
            shown.error != null -> StatusMessage(WhoisText.errorText(shown.error), Modifier.padding(padding))
            else -> {
                val sections = remember(shown, networkKey) { sections(shown, networkKey != null) }
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    for ((title, rows) in sections) {
                        if (title != null) item { SectionHeader(title) }
                        for (row in rows) item {
                            when (row) {
                                is WhoisRow.Value -> ListItem(
                                    headlineContent = { Text(row.text) },
                                    supportingContent = row.detail?.let { d -> { Text(d) } },
                                )
                                is WhoisRow.Flag -> ListItem(
                                    headlineContent = { Text(row.text) },
                                    leadingContent = { Icon(row.icon, contentDescription = null) },
                                )
                                is WhoisRow.Channel -> ListItem(
                                    headlineContent = { Text(row.name) },
                                    trailingContent = {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                    },
                                    modifier = if (networkKey == null) Modifier else Modifier.clickable {
                                        open(
                                            if (client.buffer(row.name)?.joined == true) BufferLink.Buffer(networkKey, row.name)
                                            else BufferLink.Join(client.settings.networkId, row.name),
                                        )
                                    },
                                )
                                WhoisRow.SendMessage -> ListItem(
                                    headlineContent = {
                                        Text(
                                            "Send a Message",
                                            color = colors.link,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        if (networkKey != null) open(BufferLink.Buffer(networkKey, nick))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sections(info: WhoisInfo, canSend: Boolean): List<Pair<String?, List<WhoisRow>>> {
    val s = ArrayList<Pair<String?, List<WhoisRow>>>()
    fun add(title: String, value: String?, detail: String? = null) {
        if (value.isNullOrEmpty()) return
        s.add(title to listOf(WhoisRow.Value(value, detail?.takeIf { it.isNotEmpty() })))
    }
    add("Real Name", info.realname)
    add("Host", if (info.host.isEmpty()) null else "${info.user}@${info.host}")
    add("Account", info.account)
    add("Server", info.server, detail = info.serverInfo)
    if (info.channels.isNotEmpty()) s.add("Channels" to info.channels.map { WhoisRow.Channel(WhoisText.channelName(it)) })
    add("Idle", info.idle?.let(WhoisText::idleText))
    add(
        "Signed On",
        info.signon?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(it)
        },
    )
    add("Away", info.away)
    val flags = ArrayList<WhoisRow>()
    if (info.operator) flags.add(WhoisRow.Flag("IRC Operator", Icons.Filled.Star))
    if (info.secure) flags.add(WhoisRow.Flag("Secure Connection", Icons.Filled.Lock))
    if (flags.isNotEmpty()) s.add(null to flags)
    if (canSend) s.add(null to listOf(WhoisRow.SendMessage))
    return s
}
