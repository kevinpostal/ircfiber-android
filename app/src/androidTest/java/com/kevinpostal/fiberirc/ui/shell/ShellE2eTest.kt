package com.kevinpostal.fiberirc.ui.shell

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.TestSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signed-in shell beyond sending and receiving ([ChatE2eTest]): the channel drawer (pins, network row, Archives),
 * the quick switcher, the members drawer, sign-out from the gear menu and `fiberirc://buffer/…` deep links — all
 * through the real [SessionHost] over a [TestSession] (real `IrcClient`s on fake transports, real `SiteApi` on a
 * local [FakeSiteServer]).
 */
@RunWith(AndroidJUnit4::class)
class ShellE2eTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        // The shell reopens the last session's buffer; another test's choice must not decide which buffer shows.
        app.getSharedPreferences(ShellController.PREFS_FILE, Context.MODE_PRIVATE).edit()
            .remove(ShellController.KEY_LAST_BUFFER).commit()
    }

    @After
    fun tearDown() {
        AppRouter.pending.value?.let(AppRouter::consume)
        server.shutdown()
    }

    private fun settings(channels: String) = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = channels,
    )

    /** Seeds the account stores with [me] and answers the shell's own `GET /api/me` refresh with the same account. */
    private fun session(channels: String, pins: List<String> = emptyList(), archived: List<String> = emptyList()): TestSession {
        val list = { xs: List<String> -> xs.joinToString(",", "[", "]") { "\"$it\"" } }
        server.on(
            "GET", "/api/me",
            body = """{"id":"u1","username":"tester","email":"tester@example.com","ignores":[],
                "pinnedChannels":${list(pins)},"archivedChannels":${list(archived)},"collapsed":{},"networkOrder":[],
                "bufferPrefs":{},"highlightWords":[]}""",
        )
        val me = Me(id = "u1", username = "tester", pinnedChannels = pins, archivedChannels = archived)
        return TestSession(app, server, listOf(settings(channels)), me)
    }

    /** Registration (001) → the client auto-joins [channels]; the server echoes each JOIN back in order. */
    private fun TestSession.register(vararg channels: String) {
        transport.feed(":server 001 tester :Welcome")
        waitFor("auto-join of ${channels.toList()}") {
            channels.all { ch -> transport.sentLines.any { it.startsWith("JOIN") && ch in it } }
        }
        for (ch in channels) transport.feed(":tester!u@h JOIN $ch")
    }

    /** Waits for [condition]; on timeout the failure's first line names [what] and lists every node on screen. */
    private fun waitFor(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what; screen: ${screen()}", e)
        }
    }

    /** Every node's tag, text and description across all windows, on one line. */
    private fun screen(): String {
        val out = ArrayList<String>()
        fun walk(n: SemanticsNode) {
            val parts = listOfNotNull(
                n.config.getOrNull(SemanticsProperties.TestTag)?.let { "@$it" },
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("/")?.let { "'$it'" },
                n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")?.let { "\"$it\"" },
            )
            if (parts.isNotEmpty()) out += parts.joinToString(" ")
            n.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { walk(it) }
        return out.joinToString(" | ")
    }

    /** The title bar's name (its Text sits inside the clickable title column, so it lives in the unmerged tree). */
    private fun waitForTitle(name: String) {
        waitFor("title '$name'") {
            compose.onAllNodesWithTag("bufferTitle", useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == name } == true
            }
        }
    }

    private fun waitForDescription(description: String, substring: Boolean = false) {
        waitFor("description '$description'") {
            compose.onAllNodesWithContentDescription(description, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        waitFor("text '$text'") { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openChannelDrawer() {
        compose.onNodeWithTag("buffersButton").performClick()
        waitFor("channel drawer open") { compose.onAllNodesWithTag("closeSidebar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun drawerRows(name: String) = compose.onAllNodesWithContentDescription(name)

    /**
     * The drawer lists the pin (above its network), the network row, the channels and a collapsed Archives group;
     * tapping a row shows that buffer, closes the drawer and moves the drawer's selection to it.
     */
    @Test
    fun drawerListsPinsNetworkAndArchivesAndShowsTappedRow() {
        val session = session("#general,#random,#old", pins = listOf("net1:#random"), archived = listOf("net1:#old"))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.register("#general", "#random", "#old")
        waitForTitle("#general")

        openChannelDrawer()
        waitForDescription("Archives")
        compose.onNodeWithContentDescription("Pinned").assertExists()
        // The pin, then the same channel again under its network.
        assertEquals(2, drawerRows("#random").fetchSemanticsNodes().size)
        assertEquals(1, drawerRows("Test Net").fetchSemanticsNodes().size)
        // Archived channels stay hidden until their group is opened.
        assertEquals(0, drawerRows("#old").fetchSemanticsNodes().size)
        drawerRows("#general").onFirst().assertIsSelected()

        drawerRows("#random").onFirst().performClick()
        waitForTitle("#random")
        waitFor("channel drawer closed") { compose.onAllNodesWithTag("closeSidebar").fetchSemanticsNodes().isEmpty() }

        openChannelDrawer()
        drawerRows("#random").fetchSemanticsNodes().indices.forEach { drawerRows("#random")[it].assertIsSelected() }
        drawerRows("#general").onFirst().assertIsNotSelected()

        drawerRows("Archives").onFirst().performClick()
        waitForDescription("#old")
        drawerRows("#old").onFirst().performClick()
        waitForTitle("#old")

        // The network row shows the network's server buffer.
        openChannelDrawer()
        drawerRows("Test Net").onFirst().performClick()
        waitForTitle("Test Net")
    }

    private fun switcherField(): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("quickSwitcher")))

    private fun switcherRows(name: String) =
        compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("quickSwitcher")))

    /** Jump to channel: typing narrows the list, tapping a match shows it; Go opens the first match. */
    @Test
    fun quickSwitcherFiltersAndOpensPick() {
        val session = session("#general,#random")
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.register("#general", "#random")
        waitForTitle("#general")

        compose.onNodeWithTag("quickSwitchButton").performClick()
        compose.onNodeWithTag("quickSwitcher").assertExists()
        // Every buffer (the network's server buffer included) before any filter.
        assertEquals(1, switcherRows("#general").fetchSemanticsNodes().size)
        assertEquals(1, switcherRows("#random").fetchSemanticsNodes().size)

        switcherField().performTextInput("ran")
        waitFor("switcher filtered to #random") { switcherRows("#general").fetchSemanticsNodes().isEmpty() }
        switcherRows("#random").onFirst().performClick()

        waitForTitle("#random")
        waitFor("switcher closed") { compose.onAllNodesWithTag("quickSwitcher").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithTag("quickSwitchButton").performClick()
        switcherField().performTextInput("gen")
        switcherField().performImeAction()
        waitForTitle("#general")
        waitFor("switcher closed") { compose.onAllNodesWithTag("quickSwitcher").fetchSemanticsNodes().isEmpty() }
    }

    /** The members drawer lists the channel's NAMES, follows JOINs, and its user sheet opens a conversation. */
    @Test
    fun membersDrawerListsMembersAndOpensConversation() {
        val session = session("#general")
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.register("#general")
        session.transport.feed(":server 353 tester = #general :tester @alice bob")
        session.transport.feed(":server 366 tester #general :End of /NAMES list.")
        waitForTitle("#general")

        compose.onNodeWithTag("membersButton").performClick()
        waitForDescription("alice, ", substring = true)
        waitForDescription("bob, ", substring = true)

        session.transport.feed(":carol!u@h JOIN #general")
        waitForDescription("carol, ", substring = true)

        compose.onNodeWithContentDescription("bob, ", substring = true).performClick()
        waitForText("Send a Message")
        compose.onNodeWithText("Send a Message").performClick()
        waitForTitle("bob")
    }

    /** Gear → Sign Out: `GET /logout`, the account's stores emptied, and the session hands over to the sign-in screen. */
    @Test
    fun gearSignOutLogsOutAndResetsAccountStores() {
        val session = session("#general,#random", pins = listOf("net1:#random"), archived = listOf("net1:#old"))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.register("#general", "#random")
        waitForTitle("#general")
        val stores = session.deps.accountStores
        assertEquals(listOf("net1:#random"), stores.pinList.keys.value)
        assertEquals(listOf("net1:#old"), stores.archiveList.keys.value)

        compose.onNodeWithTag("bufferGearButton").performClick()
        compose.onNodeWithTag("bufferAction_SIGN_OUT").performClick()

        waitFor("sign-out to reach the sign-in screen", 10_000) { session.loggedOutNotice != null }
        assertEquals("Signed out", session.loggedOutNotice)
        assertTrue(server.requests().any { it.method == "GET" && it.path == "/logout" })
        assertTrue(stores.pinList.keys.value.isEmpty())
        assertTrue(stores.archiveList.keys.value.isEmpty())
    }

    /**
     * A `fiberirc://buffer/…` link (notification tap, conversation shortcut) that arrives before the session waits —
     * the shell opens its usual first channel meanwhile — until its channel exists, then opens it; one arriving while
     * the session runs opens a conversation right away.
     */
    @Test
    fun bufferDeepLinkOpensOnceTheChannelExists() {
        AppRouter.handle(Intent(Intent.ACTION_VIEW, Uri.parse(AppRouter.bufferUri("net1", "#later"))))
        val session = session("#general")
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }

        session.register("#general")
        waitForTitle("#general")
        assertNull(AppRouter.pending.value)

        // The server puts us in the linked channel later (e.g. another client of the account joined it).
        session.transport.feed(":tester!u@h JOIN #later")
        waitForTitle("#later")

        AppRouter.handle(Intent(Intent.ACTION_VIEW, Uri.parse(AppRouter.bufferUri("net1", "alice"))))
        waitForTitle("alice")
        assertNull(AppRouter.pending.value)
        assertTrue(session.manager.clients.value.single().buffer("alice") != null)
        // The channel drawer lists the new conversation.
        openChannelDrawer()
        compose.onNode(hasContentDescription("alice") and hasAnyAncestor(hasTestTag("bufferList"))).assertExists()
    }
}
