package com.kevinpostal.fiberirc.ui.share

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.api.originOf
import com.kevinpostal.fiberirc.data.e2ee.E2eeExternalSend
import com.kevinpostal.fiberirc.data.e2ee.E2eePendingDrafts
import com.kevinpostal.fiberirc.data.e2ee.E2eePolicySnapshot
import com.kevinpostal.fiberirc.data.e2ee.E2eePolicySnapshots
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.prefs.ShareTarget
import com.kevinpostal.fiberirc.data.prefs.ShareTargetsStore
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.fakeMeJson
import com.kevinpostal.fiberirc.support.TestSession
import com.kevinpostal.fiberirc.ui.shell.SessionHost
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The share sheet ([ShareSheet] + [ShareSheetController], the production code [ShareTargetActivity] hosts) over a
 * [TestSession]: the real signed-in [SessionHost] joins channels on fake transports, its real
 * [com.kevinpostal.fiberirc.ui.shell.ShellController] publishes them to the real [ShareTargetsStore], then the sheet
 * opens on an `ACTION_SEND` intent. A target the main app's fresh policy snapshot and the server (`/api/me`,
 * `/api/mls/policy`) agree is unencrypted posts to the [FakeSiteServer] (`POST /api/networks/<id>/messages`, as the
 * iOS share extension does — the share sheet never writes to the IRC socket itself); an encrypted one becomes a
 * sealed pending draft handed to the app, with nothing posted.
 *
 * Hosts the composable rather than launching the Activity, whose [com.kevinpostal.fiberirc.FiberIrcApp] graph
 * points at production ircfiber.com.
 */
@RunWith(AndroidJUnit4::class)
class ShareE2eTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The activity's lifecycle scope stand-in (Main.immediate, like `lifecycleScope`). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the sheet asked its host to do: `true` posted (RESULT_OK + finish), `false` cancelled, null still open. */
    @Volatile private var finished: Boolean? = null

    /** The pending draft the sheet handed to the app instead of posting. */
    @Volatile private var continuedDraft: String? = null

    private val origin get() = originOf(server.baseUrl.toHttpUrl())

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        // Published targets and recents persist in prefs; start each test from none.
        app.getSharedPreferences(ShareTargetsStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        // The account u1: #random is unencrypted, #general is locked (as the main app's snapshot and server say).
        server.on("GET", "/api/me", body = fakeMeJson(id = "u1").replace(""""bufferPrefs":{}""", """"bufferPrefs":{"net1:#general":{"e2ee":true}}"""))
        server.on("GET", "/api/mls/policy", body = """{"dmDefault":false,"requireUnlock":false}""")
        E2eePolicySnapshots.write(
            app,
            E2eePolicySnapshot(
                userId = "u1",
                apiOrigin = origin,
                writtenAt = System.currentTimeMillis(),
                dmDefault = false,
                autoLockIncoming = false,
                perTarget = mapOf("net1:#random" to E2eePolicySnapshots.OFF, "net1:#general" to E2eePolicySnapshots.ON),
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        E2eePolicySnapshots.remove(app, origin, "u1")
        E2eePendingDrafts.removeAccount(app, origin, "u1")
        server.shutdown()
    }

    private val testNet = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = "#general,#random",
    )

    private val otherNet = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net2",
        networkName = "Other Net",
        autoJoinChannels = "#lounge",
    )

    private fun textShare(text: String, shortcutId: String? = null) = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .apply { shortcutId?.let { putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, it) } }

    /**
     * Runs the signed-in app until every channel in [channels] (per network) is joined and published as a share
     * target, then swaps the screen for the share sheet opened on [intent] — the order a real user goes through
     * (sign in, channels joined, later share from another app).
     */
    private fun openShareSheet(
        session: TestSession,
        channels: Map<ConnectionSettings, List<String>>,
        intent: Intent,
    ): ShareSheetController {
        var shareOpen by mutableStateOf<ShareSheetController?>(null)
        compose.setContent {
            val controller = shareOpen
            if (controller == null) {
                SessionHost(session.deps, session.manager, session.sessionActions)
            } else {
                val look by session.deps.displaySettings.appearance.collectAsState()
                FiberIrcTheme(look) { ShareSheet(controller) }
            }
        }
        for ((settings, names) in channels) {
            val transport = session.transports.getValue(settings)
            transport.feed(":server 001 tester :Welcome")
            compose.waitUntil(timeoutMillis = 5_000) { transport.sentLines.any { it.startsWith("JOIN ") } }
            names.forEach { transport.feed(":tester!u@h JOIN $it") }
        }
        val expected = channels.flatMap { (s, names) -> names.map { s.networkId to it } }.toSet()
        // ShellController debounces the publish by 2 s after the last buffer change.
        compose.waitUntil(timeoutMillis = 10_000) {
            session.deps.shareTargets.targets.value.map { it.networkId to it.buffer }.toSet() == expected
        }
        lateinit var controller: ShareSheetController
        compose.runOnUiThread {
            controller = ShareSheetController(
                app, session.deps.media, E2eeExternalSend(app, session.deps.api), session.deps.shareTargets.targets, scope,
                continueInApp = { continuedDraft = it },
            ) { sent -> finished = sent }
            controller.load(intent)
            shareOpen = controller
        }
        compose.waitForIdle()
        return controller
    }

    private fun messagePosts(): List<RecordedRequest> =
        server.requests().filter { it.method == "POST" && it.path?.startsWith("/api/networks/") == true }

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.clone().readUtf8()).jsonObject

    /** Every joined channel is listed with its network; the first is preselected and the shared text prefilled. */
    @Test
    fun sheetListsJoinedChannels() {
        val session = TestSession(app, server, listOf(testNet))
        val share = openShareSheet(session, mapOf(testNet to listOf("#general", "#random")), textShare("  look at this  "))

        compose.onNodeWithText("#general").assertExists()
        compose.onNodeWithText("#random").assertExists()
        assertEquals(2, compose.onAllNodesWithText("Test Net").fetchSemanticsNodes().size)

        val first = session.deps.shareTargets.targets.value.first()
        assertEquals(first, share.selected)
        assertEquals(1, compose.onAllNodesWithContentDescription("Selected").fetchSemanticsNodes().size)
        compose.onNode(hasText(first.buffer) and hasContentDescription("Selected")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("look at this")).assertExists()
    }

    /** Tapping a channel selects it and Post sends the message there through the site API, then closes the sheet. */
    @Test
    fun pickingChannelAndPostingSendsMessage() {
        val session = TestSession(app, server, listOf(testNet))
        server.on("POST", "/api/networks/net1/messages", body = """{"ok":true}""")
        val share = openShareSheet(
            session, mapOf(testNet to listOf("#general", "#random")), textShare("https://example.com/cat"),
        )

        compose.onNodeWithText("#random").performClick()
        compose.onNode(hasText("#random") and hasContentDescription("Selected")).assertExists()
        assertEquals(ShareTarget("net1", "Test Net", "#random", ShareTarget.CHANNEL), share.selected)

        compose.onNodeWithText("Post").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { finished != null }

        assertEquals(true, finished)
        val posts = messagePosts()
        assertEquals(1, posts.size)
        assertEquals("/api/networks/net1/messages", posts.single().path)
        val body = posts.single().json()
        assertEquals("#random", body["target"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/cat", body["text"]?.jsonPrimitive?.content)
    }

    /** The search field narrows the rows by buffer or network name, case-insensitively. */
    @Test
    fun searchNarrowsTargets() {
        val session = TestSession(app, server, listOf(testNet, otherNet))
        openShareSheet(
            session,
            mapOf(testNet to listOf("#general", "#random"), otherNet to listOf("#lounge")),
            textShare("hi"),
        )
        compose.onNodeWithText("#lounge").assertExists()

        val search = compose.onNode(hasSetTextAction() and hasText("Search channels and conversations"))
        search.performTextInput("RAND")
        compose.onNodeWithText("#random").assertExists()
        compose.onNodeWithText("#general").assertDoesNotExist()
        compose.onNodeWithText("#lounge").assertDoesNotExist()

        // Network name matches too.
        compose.onNode(hasSetTextAction() and hasText("RAND")).performTextReplacement("other")
        compose.onNodeWithText("#lounge").assertExists()
        compose.onNodeWithText("#general").assertDoesNotExist()
        compose.onNodeWithText("#random").assertDoesNotExist()

        compose.onNode(hasSetTextAction() and hasText("other")).performTextReplacement("nothing-matches")
        assertTrue(compose.onAllNodesWithContentDescription("Selected").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("#lounge").assertDoesNotExist()
    }

    /**
     * A Direct Share conversation is preselected; a failed post keeps the sheet open with "Couldn't send", Post
     * enabled for a retry, and Cancel then closes it unsent.
     */
    @Test
    fun directShareTargetAndFailedPost() {
        val session = TestSession(app, server, listOf(testNet))
        server.on("POST", "/api/networks/net1/messages", code = 500, body = """{"error":"Network is offline"}""")
        val share = openShareSheet(
            session, mapOf(testNet to listOf("#general", "#random")), textShare("hello", shortcutId = "net1:#random"),
        )
        assertEquals("#random", share.selected?.buffer)
        compose.onNode(hasText("#random") and hasContentDescription("Selected")).assertExists()

        compose.onNodeWithText("Post").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Couldn't send").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(finished)
        assertEquals("#random", messagePosts().single().json()["target"]?.jsonPrimitive?.content)

        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Couldn't send").assertDoesNotExist()
        compose.onNodeWithText("Post").assertIsEnabled()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(timeoutMillis = 2_000) { finished != null }
        assertEquals(false, finished)
    }

    /**
     * An encrypted target: Post never reaches `POST /api/networks/…`; the text is sealed as a pending draft of the
     * signed-in account and handed to the app, which can read it back (another account can't).
     */
    @Test
    fun encryptedTargetBecomesPendingDraft() {
        val session = TestSession(app, server, listOf(testNet))
        server.on("POST", "/api/networks/net1/messages", body = """{"ok":true}""")
        val marker = "secret-share-marker-${System.nanoTime()}"
        openShareSheet(session, mapOf(testNet to listOf("#general", "#random")), textShare(marker, shortcutId = "net1:#general"))

        compose.onNodeWithText("Post").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { continuedDraft != null }

        assertNull(finished)
        assertTrue(messagePosts().isEmpty())
        assertTrue(server.requests().none { it.body.clone().readUtf8().contains(marker) })
        val draft = E2eePendingDrafts.load(app, continuedDraft!!, origin, "u1")
        assertEquals("net1", draft?.networkId)
        assertEquals("#general", draft?.target)
        assertEquals(marker, draft?.body)
        assertNull(E2eePendingDrafts.load(app, continuedDraft!!, origin, "u2"))
    }

    /** Without a fresh snapshot from the app, even an unencrypted target is unknown: it continues in the app. */
    @Test
    fun missingSnapshotContinuesInApp() {
        E2eePolicySnapshots.remove(app, origin, "u1")
        val session = TestSession(app, server, listOf(testNet))
        server.on("POST", "/api/networks/net1/messages", body = """{"ok":true}""")
        openShareSheet(session, mapOf(testNet to listOf("#general", "#random")), textShare("hi", shortcutId = "net1:#random"))

        compose.onNodeWithText("Post").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { continuedDraft != null }
        assertTrue(messagePosts().isEmpty())
    }
}
