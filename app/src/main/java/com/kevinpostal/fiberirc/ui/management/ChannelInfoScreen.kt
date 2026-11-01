package com.kevinpostal.fiberirc.ui.management

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcClientListener
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.ui.chat.ChatLink
import com.kevinpostal.fiberirc.ui.chat.ChatLinks
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.legible
import java.time.Instant

/** Channel Info's wording and rules (IRCCloud `ChannelInfoViewController.m`), kept pure for tests. */
object ChannelInfoText {
    data class ModeRow(val title: String, val detail: String?)

    /** One row per mode letter: IRCCloud's name and hint for i, m, n, p, s and t, `k` with its key, any other "+X". */
    fun modeRows(modes: String, args: Map<Char, String>): List<ModeRow> = modes.map { m ->
        when (m) {
            'i' -> ModeRow("Invite Only (+i)", "Members must be invited to join this channel.")
            'k' -> ModeRow("Password (+k)", args['k'])
            'm' -> ModeRow("Moderated (+m)", "Only ops and voiced members may talk.")
            'n' -> ModeRow("No External Messages (+n)", "No messages allowed from outside the channel.")
            'p' -> ModeRow("Private (+p)", "Membership is only visible to other members.")
            's' -> ModeRow("Secret (+s)", "This channel is unlisted and membership is only visible to other members.")
            't' -> ModeRow("Topic Control (+t)", "Only ops can set the topic.")
            else -> ModeRow("+$m", null)
        }
    }

    fun modeHeader(modes: String): String = "MODE: +$modes"

    /**
     * "TOPIC"; while editing with a known `TOPICLEN`, "TOPIC (N CHARS)" with N what is left of it, counted in UTF-8
     * bytes as the server counts.
     */
    fun topicHeader(editing: Boolean, topicLength: Int?, text: String): String {
        if (!editing || topicLength == null) return "TOPIC"
        return "TOPIC (${topicLength - text.toByteArray(Charsets.UTF_8).size} CHARS)"
    }

    /** "Set by <nick> on <date>", "Set by <nick>" without a time, null when nobody is known. */
    fun setBy(nick: String?, at: Instant?): String? {
        if (nick.isNullOrEmpty()) return null
        if (at == null) return "Set by $nick"
        return "Set by $nick on ${mediumDateTime(at)}"
    }

    /** Half-ops and above may set the topic; anyone may while the channel lacks `t`. */
    fun canEditTopic(rank: Int, modes: String): Boolean = rank >= 2 || !modes.contains('t')

    /** The list modes Channel Info links to for half-ops and above: bans, then the server's EXCEPTS and INVEX modes. */
    fun listModes(rank: Int, excepts: Char?, invex: Char?): List<Char> {
        if (rank < 2) return emptyList()
        return listOf('b') + listOfNotNull(excepts, invex)
    }

    /** The tappable link in a channel URL (328): an http(s) URL. */
    fun urlLink(value: String): ChatLink? = ChatLinks.find(value).firstOrNull { it.link is ChatLink.Url }?.link
}

/**
 * Tapping a channel's title (IRCCloud Channel Info, ports `ChannelInfoViewController`): the topic and who set it,
 * editable by half-ops and above or by anyone while the channel lacks `t`; the channel URL (328); the modes with
 * IRCCloud's hints; and for half-ops and above the ban, exception and invite lists (pushed in place, back returns).
 * On appear it asks the server for the current modes (the client throttles that to once a minute). Links leave the
 * screen: [onClose] runs, then [onOpenLink].
 */
@Composable
fun ChannelInfoScreen(client: IrcClient, buffer: IrcBuffer, onClose: () -> Unit, onOpenLink: (ChatLink) -> Unit) {
    var listMode by remember { mutableStateOf<Char?>(null) }
    val shownList = listMode
    if (shownList != null) {
        BackHandler { listMode = null }
        ChannelModeListScreen(client, buffer, shownList, onBack = { listMode = null })
        return
    }

    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val listener = remember(buffer) {
        val target = buffer
        object : IrcClientListener {
            override fun onBufferUpdated(client: IrcClient, buffer: IrcBuffer) {
                if (buffer === target) revision++
            }

            // Topic changes and MODE lines arrive as event rows.
            override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) {
                if (buffer === target && line.kind == IrcLineKind.EVENT) revision++
            }

            override fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) {
                if (buffer === target) revision++
            }
        }
    }
    ObserveClient(client, listener)
    // viewWillAppear: also on return from a list.
    LaunchedEffect(Unit) { client.refreshChannelModes(buffer) }

    val colors = LocalFiberIrcColors.current
    // Read `revision` so a client event recomposes with the buffer's current state.
    val snapshot = remember(revision) {
        val rank = buffer.member(client.nick)?.rank ?: 0
        Triple(rank, ChannelInfoText.modeRows(buffer.channelModes, buffer.channelModeArgs.toMap()),
            ChannelInfoText.listModes(rank, client.exceptsMode, client.invexMode))
    }
    val (rank, modeRows, listModes) = snapshot

    fun endEdit() {
        editing = false
    }

    /** Sends the edited topic (one line; an empty one clears it). The server's TOPIC echo updates the screen. */
    fun saveTopic() {
        val text = draft.split("\r\n", "\n", "\r").joinToString(" ")
        if (text != (buffer.topic ?: "")) client.setTopic(text, buffer)
        endEdit()
    }

    fun leave(link: ChatLink) {
        onClose()
        onOpenLink(link)
    }

    if (editing) BackHandler { endEdit() }
    ManagementScaffold(
        title = buffer.name,
        navigation = {
            if (editing) BarButton("Cancel") { endEdit() } else BarButton("Done", onClick = onClose)
        },
        actions = {
            if (editing) {
                BarButton("Save") { saveTopic() }
            } else if (ChannelInfoText.canEditTopic(rank, buffer.channelModes)) {
                BarButton("Edit") {
                    draft = buffer.topic ?: ""
                    editing = true
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "topicHeader") {
                SectionHeader(ChannelInfoText.topicHeader(editing, client.topicLength, draft))
            }
            item(key = "topic") {
                if (editing) {
                    val focus = remember { FocusRequester() }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Topic") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveTopic() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 16.dp)
                            .focusRequester(focus),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                } else {
                    TopicText(buffer.topic ?: "", onLink = ::leave)
                }
            }
            if (!editing) {
                ChannelInfoText.setBy(buffer.topicSetBy, buffer.topicSetAt)?.let { s ->
                    item(key = "setBy") { SectionFooter(s) }
                }
            }
            val url = buffer.channelURL.orEmpty()
            if (url.isNotEmpty()) {
                item(key = "urlHeader") { SectionHeader("CHANNEL URL") }
                item(key = "url") {
                    val link = ChannelInfoText.urlLink(url)
                    ListItem(
                        headlineContent = { Text(url, color = colors.link) },
                        modifier = if (link != null) Modifier.clickable { leave(link) } else Modifier,
                    )
                }
            }
            if (modeRows.isNotEmpty()) {
                item(key = "modesHeader") { SectionHeader(ChannelInfoText.modeHeader(buffer.channelModes)) }
                modeRows.forEachIndexed { i, row ->
                    item(key = "mode$i") {
                        ListItem(
                            headlineContent = { Text(row.title) },
                            supportingContent = row.detail?.let { d -> { Text(d) } },
                        )
                    }
                }
            }
            if (listModes.isNotEmpty()) {
                item(key = "listsDivider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
                listModes.forEach { m ->
                    item(key = "list$m") {
                        ListItem(
                            headlineContent = {
                                Text(ChannelModeList.Kind.of(m, client.exceptsMode, client.invexMode).listName)
                            },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.clickable { listMode = m },
                        )
                    }
                }
            }
        }
    }
}

/** The topic with its IRC formatting, its URLs and channel names tappable; "(No topic set)" when there is none. */
@Composable
private fun TopicText(topic: String, onLink: (ChatLink) -> Unit) {
    val colors = LocalFiberIrcColors.current
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    if (IrcFormatting.strip(topic).isEmpty()) {
        Text("(No topic set)", color = colors.textSecondary, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        return
    }
    val text = remember(topic, colors) {
        val styled = IrcFormatting.toAnnotatedString(topic, colors.chatText, colors.groupedCell) { legible(it, colors.dark) }
        AnnotatedString.Builder(styled).apply {
            for (found in ChatLinks.find(styled.text)) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "topicLink",
                        styles = TextLinkStyles(SpanStyle(color = colors.link)),
                    ) { onLink(found.link) },
                    found.range.first,
                    found.range.last + 1,
                )
            }
        }.toAnnotatedString()
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}
