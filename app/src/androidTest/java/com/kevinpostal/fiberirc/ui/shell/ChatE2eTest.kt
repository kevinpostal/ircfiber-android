package com.kevinpostal.fiberirc.ui.shell

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.TestSession
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [SessionHost] (buffer list + chat + composer, unmodified production code) with a
 * [TestSession]: a real [com.kevinpostal.fiberirc.data.irc.IrcClient] over a [com.kevinpostal.fiberirc.support.FakeTransport]
 * instead of a socket. Validates the signed-in half of the app the same way [com.kevinpostal.fiberirc.ui.login.LoginE2eTest]
 * validates sign-in.
 */
@RunWith(AndroidJUnit4::class)
class ChatE2eTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val settings = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = "#general",
    )

    /** Registration (001) → the client auto-joins `#general`; the server echoes it back → the buffer shows up. */
    private fun TestSession.joinGeneral() {
        transport.feed(":server 001 tester :Welcome")
        compose.waitUntil(timeoutMillis = 5_000) { transport.sentLines.any { it.startsWith("JOIN #general") } }
        transport.feed(":tester!u@h JOIN #general")
    }

    /** A line sent by another user appears as a chat row once a buffer exists for the channel. */
    @Test
    fun receivedMessageAppearsInChat() {
        val session = TestSession(app, server, listOf(settings))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.joinGeneral()

        session.transport.feed(":alice!u@h PRIVMSG #general :hello from alice")
        // Message rows collapse their semantics into one contentDescription (accessibility), not a plain Text node.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("alice: hello from alice", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Typing in the composer and tapping Send writes a real `PRIVMSG` line to the network client. */
    @Test
    fun sendingMessageWritesPrivmsg() {
        val session = TestSession(app, server, listOf(settings))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.joinGeneral()
        session.transport.clearSent()

        compose.onNodeWithContentDescription("Message").performTextInput("hi everyone")
        compose.onNodeWithTag("sendButton").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            session.transport.sentLines.any { it == "PRIVMSG #general :hi everyone" }
        }
        assertTrue(session.transport.sentLines.any { it == "PRIVMSG #general :hi everyone" })
    }
    /** A blank bouncer buffer must request history once attach finishes, without requiring a scroll. */
    @Test
    fun bouncerHistoryLoadsAfterAttachCompletes() {
        val bouncerSettings = settings.copy(
            viaBouncer = true,
            autoJoinChannels = "",
            serverPassword = "tester@test-device/net1:token",
        )
        val session = TestSession(app, server, listOf(bouncerSettings))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.transport.feed(":s CAP * LS :server-time message-tags echo-message batch")
        session.transport.feed(":s CAP tester ACK :server-time message-tags echo-message batch")
        session.transport.feed(":s 001 tester :hi")
        session.transport.feed(":tester!u@h JOIN #general")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(session.transport.sentLines.none { it == "CHATHISTORY LATEST #general * 100" })
        session.transport.feed(":s PONG s :fiberirc-sync")
        compose.waitUntil(timeoutMillis = 5_000) {
            session.transport.sentLines.any { it == "CHATHISTORY LATEST #general * 100" }
        }
        session.transport.feed(":s BATCH +h chathistory #general")
        session.transport.feed("@batch=h;time=2026-11-04T10:00:00.000Z;msgid=m1 :alice!u@h PRIVMSG #general :replayed after attach")
        session.transport.feed(":s BATCH -h")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("alice: replayed after attach", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
