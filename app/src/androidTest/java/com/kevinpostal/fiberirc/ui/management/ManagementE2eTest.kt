package com.kevinpostal.fiberirc.ui.management

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.TestSession
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.BufferLink
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The management screens [com.kevinpostal.fiberirc.ui.shell.SessionHost] pushes (channel list, Whois, Channel Info
 * with its ban list, conversation cleanup, reorder), hosted directly with the real [IrcClient] of a [TestSession]
 * (a [com.kevinpostal.fiberirc.support.FakeTransport] instead of a socket). Each scenario feeds the numerics the
 * screen's request is answered with and checks both what the screen shows and the IRC line its action writes.
 */
@RunWith(AndroidJUnit4::class)
class ManagementE2eTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val settings = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = "#general",
    )

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        AppRouter.pending.value?.let(AppRouter::consume)
    }

    @After
    fun tearDown() {
        AppRouter.pending.value?.let(AppRouter::consume)
        server.shutdown()
    }

    private fun TestSession.client(): IrcClient = manager.clients.value.first()

    /** The lines the client has written so far, read on the main thread the client writes them on. */
    private fun TestSession.sent(): List<String> = compose.runOnUiThread { transport.sentLines.toList() }

    private fun TestSession.awaitSent(line: String) {
        compose.waitUntil(timeoutMillis = 5_000) { sent().any { it == line } }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Registration (001) → auto-join `#general`; the server echoes the JOIN, NAMES (`@tester`, an op, and alice) and
     * the topic. Returns the joined channel once its member list has landed.
     */
    private fun TestSession.joinGeneral(): IrcBuffer {
        transport.feed(":server 001 tester :Welcome")
        compose.waitUntil(timeoutMillis = 5_000) { sent().any { it.startsWith("JOIN #general") } }
        transport.feed(":tester!u@h JOIN #general")
        transport.feed(":server 332 tester #general :Welcome to general")
        transport.feed(":server 333 tester #general alice!u@h 1700000000")
        transport.feed(":server 353 tester = #general :@tester alice")
        transport.feed(":server 366 tester #general :End of /NAMES list.")
        val client = client()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnUiThread { client.buffer("#general")?.member("alice") != null }
        }
        clearSentOnUi()
        return compose.runOnUiThread { client.buffer("#general")!! }
    }

    private fun TestSession.clearSentOnUi() = compose.runOnUiThread { transport.clearSent() }

    /** Browse Channels: `LIST` goes out, the 322 rows show most users first with formatting stripped, search narrows them, a tap joins. */
    @Test
    fun channelListShowsListRepliesAndJoinsTappedChannel() {
        val session = TestSession(app, server, listOf(settings))
        session.joinGeneral()
        var closed = false
        compose.setContent { FiberIrcTheme { ChannelListScreen(session.client(), onClose = { closed = true }) } }

        session.awaitSent("LIST")
        awaitText("Loading channels…")
        session.transport.feed(":server 321 tester Channel :Users  Name")
        session.transport.feed(":server 322 tester #kotlin 7 :\u0002Kotlin\u0002 fans")
        session.transport.feed(":server 322 tester #rust 42 :Rust talk")
        session.transport.feed(":server 323 tester :End of /LIST")

        awaitText("#rust · 42 users")
        compose.onNodeWithText("#kotlin · 7 users").assertExists()
        compose.onNodeWithText("Kotlin fans").assertExists()
        compose.onNodeWithText("Loading channels…").assertDoesNotExist()
        val top = compose.onNodeWithText("#rust · 42 users").fetchSemanticsNode().boundsInRoot.top
        val next = compose.onNodeWithText("#kotlin · 7 users").fetchSemanticsNode().boundsInRoot.top
        assertTrue("most users first", top < next)

        compose.onNode(hasSetTextAction()).performTextInput("kot")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("#rust · 42 users").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithText("#kotlin · 7 users").performClick()
        compose.waitForIdle()
        assertTrue(closed)
        assertEquals(BufferLink.Join("net1", "#kotlin"), AppRouter.pending.value)
    }

    /** Whois: `WHOIS alice alice` goes out; the 311/312/319/330/671/318 answer fills the sections; a channel tap opens it. */
    @Test
    fun whoisShowsReplyAndOpensChannel() {
        val session = TestSession(app, server, listOf(settings))
        session.joinGeneral()
        var closed = false
        compose.setContent {
            FiberIrcTheme {
                val client = session.client()
                WhoisScreen(client, "alice", IrcSessionManager.networkKey(client), onClose = { closed = true })
            }
        }

        session.awaitSent("WHOIS alice alice")
        session.transport.feed(":server 311 tester alice ~alice example.org * :Alice Liddell")
        session.transport.feed(":server 312 tester alice irc.example.org :Example Server")
        session.transport.feed(":server 319 tester alice :@#general #rust")
        session.transport.feed(":server 330 tester alice alice_acct :is logged in as")
        session.transport.feed(":server 671 tester alice :is using a secure connection")
        session.transport.feed(":server 318 tester alice :End of /WHOIS list.")

        awaitText("Alice Liddell")
        compose.onNodeWithText("~alice@example.org").assertExists()
        compose.onNodeWithText("irc.example.org").assertExists()
        compose.onNodeWithText("Example Server").assertExists()
        compose.onNodeWithText("alice_acct").assertExists()
        compose.onNodeWithText("Secure Connection").assertExists()
        compose.onNodeWithText("#general").assertExists() // `@` membership prefix stripped

        // Not joined → Join; the joined #general would open its buffer instead.
        compose.onNodeWithText("#rust").performClick()
        compose.waitForIdle()
        assertTrue(closed)
        assertEquals(BufferLink.Join("net1", "#rust"), AppRouter.pending.value)
    }

    /** Whois for a nick that is not online: the 401 ends the request and the screen says so instead of spinning. */
    @Test
    fun whoisForMissingNickSaysNoSuchNick() {
        val session = TestSession(app, server, listOf(settings))
        session.joinGeneral()
        compose.setContent {
            FiberIrcTheme {
                val client = session.client()
                WhoisScreen(client, "ghost", IrcSessionManager.networkKey(client), onClose = {})
            }
        }

        session.awaitSent("WHOIS ghost ghost")
        session.transport.feed(":server 401 tester ghost :No such nick/channel")
        session.transport.feed(":server 318 tester ghost :End of /WHOIS list.")
        awaitText("No such nick")
    }

    /**
     * Channel Info: `MODE #general` on appear, the 324 answer lists the modes; Edit → Save writes `TOPIC` and the echo
     * updates the topic; Ban List (an op) loads `MODE #general b`, removes with `-b` and adds with `+b`.
     */
    @Test
    fun channelInfoEditsTopicAndManagesBans() {
        val session = TestSession(app, server, listOf(settings))
        val buffer = session.joinGeneral()
        compose.setContent {
            FiberIrcTheme { ChannelInfoScreen(session.client(), buffer, onClose = {}, onOpenLink = {}) }
        }

        session.awaitSent("MODE #general")
        session.transport.feed(":server 324 tester #general +nt")
        awaitText("Topic Control (+t)")
        compose.onNodeWithText("No External Messages (+n)").assertExists()
        compose.onNodeWithText("MODE: +nt").assertExists()
        compose.onNodeWithText("Welcome to general").assertExists()
        compose.onNodeWithText("Set by alice", substring = true).assertExists()

        // Topic edit (an op may, despite +t).
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Rust and Kotlin only")
        compose.onNodeWithText("Save").performClick()
        session.awaitSent("TOPIC #general :Rust and Kotlin only")
        session.transport.feed(":tester!u@h TOPIC #general :Rust and Kotlin only")
        awaitText("Rust and Kotlin only")
        compose.onNodeWithText("Set by tester", substring = true).assertExists()

        // Ban list.
        session.clearSentOnUi()
        compose.onNodeWithText("Ban List").performClick()
        session.awaitSent("MODE #general b")
        session.transport.feed(":server 367 tester #general *!*@spam.example alice!u@h 1700000000")
        session.transport.feed(":server 368 tester #general :End of channel ban list")
        awaitText("*!*@spam.example")
        compose.onNodeWithText("Bans for #general").assertExists()
        compose.onNodeWithText("Set by alice", substring = true).assertExists()

        compose.onNodeWithContentDescription("Remove").performClick()
        session.awaitSent("MODE #general -b *!*@spam.example")

        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("*!*@bad.example")
        compose.onNodeWithText("Add").performClick()
        session.awaitSent("MODE #general +b *!*@bad.example")

        // The server's echoes reload the list.
        session.clearSentOnUi()
        session.transport.feed(":tester!u@h MODE #general -b *!*@spam.example")
        session.transport.feed(":tester!u@h MODE #general +b *!*@bad.example")
        session.awaitSent("MODE #general b")
        session.transport.feed(":server 367 tester #general *!*@bad.example tester!u@h 1700000100")
        session.transport.feed(":server 368 tester #general :End of channel ban list")
        awaitText("*!*@bad.example")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("*!*@spam.example").fetchSemanticsNodes().isEmpty()
        }

        // Back returns to Channel Info.
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Topic Control (+t)")
    }

    /** Delete Conversations: a checked conversation is closed on its client (gone from its buffers) and the screen closes. */
    @Test
    fun conversationCleanupDeletesCheckedConversation() {
        val session = TestSession(app, server, listOf(settings))
        session.joinGeneral()
        val client = session.client()
        session.transport.feed(":bob!u@h PRIVMSG tester :hi there")
        session.transport.feed(":carol!u@h PRIVMSG tester :hello")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnUiThread { client.buffer("bob") != null && client.buffer("carol") != null }
        }
        var closed = false
        compose.setContent {
            FiberIrcTheme { ConversationCleanupScreen(session.manager.clients.value, onClose = { closed = true }) }
        }

        awaitText("Test Net")
        compose.onNodeWithText("Delete (0)").assertExists()
        compose.onNodeWithText("bob").performClick()
        compose.onNodeWithText("Delete (1)").performClick()
        compose.waitForIdle()

        assertTrue(closed)
        compose.runOnUiThread {
            assertNull(client.buffer("bob"))
            assertTrue(client.buffer("carol") != null)
            assertTrue(client.buffer("#general") != null)
        }
    }

    /** Reorder: dragging a row's handle below the next row and Save hands the ids in their new order to the caller. */
    @Test
    fun reorderDragAndSaveHandsNewOrder() {
        var saved: List<String>? = null
        var closed = false
        compose.setContent {
            FiberIrcTheme {
                ReorderScreen(
                    title = "Reorder Pins",
                    items = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma"),
                    onSave = { saved = it },
                    onClose = { closed = true },
                )
            }
        }
        dragHandle("Alpha", below = "Beta")
        val alphaTop = compose.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top
        val betaTop = compose.onNodeWithText("Beta").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Alpha now shows below Beta", alphaTop > betaTop)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { closed }
        assertEquals(listOf("b", "a", "c"), saved)
    }

    /** Reorder: a failed save says why and stays open. */
    @Test
    fun reorderFailedSaveShowsErrorAndStaysOpen() {
        var closed = false
        compose.setContent {
            FiberIrcTheme {
                ReorderScreen(
                    title = "Reorder Pins",
                    items = listOf("a" to "Alpha", "b" to "Beta"),
                    onSave = { throw IllegalStateException("Server said no") },
                    onClose = { closed = true },
                )
            }
        }

        compose.onNodeWithText("Save").performClick()
        awaitText("Couldn't save the order")
        compose.onNodeWithText("Server said no").assertExists()
        assertFalse(closed)
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Save").assertExists()
    }

    /**
     * Drags [title]'s handle down past the middle of [below]'s row, in small steps as a finger would. The handle is
     * found in the unmerged tree: `ListItem` merges its children, so the merged node is the whole row, whose center
     * is not on the handle.
     */
    private fun dragHandle(title: String, below: String) {
        fun handle(t: String) = compose.onNodeWithContentDescription("Reorder $t", useUnmergedTree = true)
        val from = handle(title).fetchSemanticsNode().boundsInRoot.center.y
        val to = handle(below).fetchSemanticsNode().boundsInRoot.center.y
        val distance = (to - from) * 1.3f
        val steps = 20
        handle(title).performTouchInput {
            down(center)
            repeat(steps) { moveBy(Offset(0f, distance / steps)) }
            up()
        }
        compose.waitForIdle()
    }
}
