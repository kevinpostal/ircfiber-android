package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcClientListener
import com.kevinpostal.fiberirc.data.irc.IrcMember
import com.kevinpostal.fiberirc.ui.actions.ModerationController
import com.kevinpostal.fiberirc.ui.actions.UserActionsHost
import com.kevinpostal.fiberirc.ui.actions.rememberUserActionsState
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.MemberCategory
import com.kevinpostal.fiberirc.ui.theme.prefixColor
import java.text.NumberFormat

/** One group of the member list: its band ([category] colours), display [title] and members in buffer order. */
data class MemberSection(val category: MemberCategory, val title: String, val members: List<IrcMember>)

/** Groups [members] (already rank-sorted by the buffer) into the non-empty bands, Opers first. */
fun memberSections(members: List<IrcMember>): List<MemberSection> {
    val groups = members.groupBy { MemberCategory.of(it.prefixes) }
    return MemberCategory.entries.mapNotNull { k -> groups[k]?.let { MemberSection(k, categoryTitle(k), it) } }
}

/** Display name for a band (iOS `categoryTitle`: the half-op key reads "Half Ops"). */
private fun categoryTitle(k: MemberCategory): String = if (k == MemberCategory.HALFOPS) "Half Ops" else k.title

/**
 * The session's right drawer (ports `ios/UI/MembersViewController.swift`, IRCCloud's `UsersTableView`): the shown
 * channel's members in groups — Opers, Owners, Admins, Ops, Half Ops, Voiced, Members — each under a tinted band with
 * its title and count. Away members are dimmed; the prefix glyph shows when [showMemberPrefixes]
 * (`AccountSettingsStore.showMemberPrefixes`) is on. Tapping a member opens the user sheet. Any other buffer shows
 * an empty list. Follows member changes through [IrcClientListener.onMembersUpdated].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MembersDrawer(
    client: IrcClient?,
    buffer: IrcBuffer?,
    showMemberPrefixes: Boolean,
    moderation: ModerationController,
    onWhois: (client: IrcClient, nick: String) -> Unit,
    onSendMessage: ((client: IrcClient, nick: String) -> Unit)?,
    onMention: ((nick: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = LocalFiberIrcColors.current
    val channel = buffer?.takeIf { it.kind == IrcBufferKind.CHANNEL }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(client, channel) {
        val listener = object : IrcClientListener {
            override fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) {
                if (buffer === channel) revision++
            }
        }
        if (channel != null) client?.addObserver(listener)
        onDispose { client?.removeObserver(listener) }
    }
    val sections = remember(channel, revision) { memberSections(channel?.members.orEmpty().toList()) }
    val users = rememberUserActionsState()
    val counts = remember { NumberFormat.getInstance() }

    LazyColumn(modifier.fillMaxSize().background(c.panelAlt)) {
        for (s in sections) {
            stickyHeader(key = "h:${s.category}") {
                val heading = s.category.heading(c.dark)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(s.category.background(c.dark))
                        .heightIn(min = 24.dp)
                        .padding(horizontal = Grid.margin, vertical = Grid.unit),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Grid.gutter),
                ) {
                    Text(
                        s.title,
                        color = heading,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Text(counts.format(s.members.size), color = heading, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(s.members, key = { "m:${s.category}:${it.nick}" }) { m ->
                val label = buildAnnotatedString {
                    val symbol = m.prefixes.firstOrNull()
                    if (showMemberPrefixes && symbol != null) {
                        withStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = prefixColor(m.prefixes, c)),
                        ) { append("$symbol ") }
                    }
                    withStyle(SpanStyle(color = if (m.away) c.textTertiary else c.chatText)) { append(m.nick) }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            if (client != null && channel != null) users.open(m, channel, client)
                        }
                        .heightIn(min = Grid.leadingColumn)
                        .padding(horizontal = Grid.margin, vertical = Grid.unit)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .semantics {
                            contentDescription = "${m.nick}, ${s.title}"
                            if (m.away) stateDescription = "Away"
                        },
                )
            }
        }
    }

    UserActionsHost(users, moderation, onWhois, onSendMessage, onMention)
}
