package com.kevinpostal.fiberirc.ui.actions

import com.kevinpostal.fiberirc.data.irc.AppForegroundMonitoring
import com.kevinpostal.fiberirc.data.irc.ConnectionEvent
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcEncryptionConfig
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcMember
import com.kevinpostal.fiberirc.data.irc.IrcTransport
import com.kevinpostal.fiberirc.data.irc.PathMonitoring
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class RecordingTransport : IrcTransport {
    private val channel = Channel<ConnectionEvent>(Channel.UNLIMITED)
    override val events: Flow<ConnectionEvent> = channel.receiveAsFlow()
    val sent = ArrayList<String>()
    override fun connect(host: String, port: Int, tls: Boolean) {
        channel.trySend(ConnectionEvent.Connected)
    }
    override fun send(line: String) {
        sent += line
    }
    override fun disconnect() {
        channel.trySend(ConnectionEvent.Disconnected(null))
    }
    fun feed(line: String) {
        channel.trySend(ConnectionEvent.Line(line))
    }
}

private object StillPath : PathMonitoring {
    override var isSatisfied = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
}

private object Foreground : AppForegroundMonitoring {
    override var isForeground = true
    override var isActive = true
    override var onChange: ((Boolean) -> Unit)? = null
    override fun start() {}
    override fun cancel() {}
}

/** The user sheet's rows (`UserActionSheet.swift`): order, rank gates, self/blocked rules, and what Op sends. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserActionsTest {
    private val t = RecordingTransport()
    private val client = IrcClient(
        ConnectionSettings(nick = "me", realName = "", autoJoinChannels = ""),
        t, StillPath, Foreground, UnconfinedTestDispatcher(), encryption = IrcEncryptionConfig.Plaintext,
    )
    private val channel = IrcBuffer("#chan", IrcBufferKind.CHANNEL)

    @After
    fun tearDown() = client.dispose()

    private fun titles(member: IrcMember, blocked: Boolean = false, session: Boolean = true) =
        actions(member, blocked, session).map { it.title }

    private fun actions(member: IrcMember, blocked: Boolean = false, session: Boolean = true) = userActions(
        member = member,
        channel = channel,
        client = client,
        blocked = blocked,
        onWhois = {},
        onSendMessage = if (session) ({}) else null,
        onMention = if (session) ({}) else null,
        onInvite = {},
        onIgnore = {},
        onUnignore = {},
        onKick = {},
        onBan = {},
        onCopyHostmask = {},
    )

    private val bob = IrcMember("bob", "", user = "b", host = "bob.host", realname = "Bob B")

    @Test
    fun plainMemberSeesNoModeration() {
        channel.setMembers(listOf(IrcMember("me", ""), bob))
        assertEquals(listOf("Whois", "Send a Message", "Mention", "Ignore", "Copy Hostmask"), titles(bob))
        assertEquals(listOf("Whois", "Unignore", "Copy Hostmask"), titles(bob, blocked = true, session = false))
        // Never Ignore yourself; no hostmask row while the host is unknown.
        assertEquals(listOf("Whois", "Send a Message", "Mention"), titles(IrcMember("ME", "")))
    }

    @Test
    fun rankGatesOpVoiceKickBan() {
        channel.setMembers(listOf(IrcMember("me", "%"), bob))
        assertEquals(
            listOf("Whois", "Send a Message", "Mention", "Ignore", "Voice", "Kick…", "Ban…", "Copy Hostmask"),
            titles(bob),
        )
        channel.setMembers(listOf(IrcMember("me", "@"), bob.copy(prefixes = "@+")))
        assertEquals(
            listOf("Whois", "Send a Message", "Mention", "Ignore", "Deop", "Devoice", "Kick…", "Ban…", "Copy Hostmask"),
            titles(bob.copy(prefixes = "@+")),
        )
    }

    @Test
    fun inviteNeedsAnotherJoinedChannel() {
        channel.setMembers(listOf(IrcMember("me", ""), bob))
        client.joinChannel("#other")
        client.buffer("#other")!!.joined = true
        var offered: List<IrcBuffer>? = null
        val rows = userActions(bob, channel, client, false, {}, null, null, { offered = it }, {}, {}, {}, {}, {})
        assertEquals(listOf("Whois", "Invite to Channel…", "Ignore", "Copy Hostmask"), rows.map { it.title })
        rows.first { it.title == "Invite to Channel…" }.onClick()
        assertEquals(listOf("#other"), offered!!.map { it.name })
    }

    @Test
    fun banOffersTheHostMaskAndOpSendsMode() {
        client.connect()
        t.feed(":s 001 me :hi")
        channel.setMembers(listOf(IrcMember("me", "@"), bob))
        var mask: String? = null
        val rows = userActions(bob, channel, client, false, {}, null, null, {}, {}, {}, {}, { mask = it }, {})
        rows.first { it.title == "Ban…" }.onClick()
        assertEquals("*!*@bob.host", mask)
        t.sent.clear()
        rows.first { it.title == "Op" }.onClick()
        assertEquals(listOf("MODE #chan +o bob"), t.sent)
    }

    @Test
    fun hostmaskUsesStarForUnknownUser() {
        assertEquals("bob!*@h", hostmask(IrcMember("bob", "", host = "h")))
        assertEquals("bob!b@bob.host", hostmask(bob))
        assertNull(hostmask(IrcMember("bob", "")))
    }
}
