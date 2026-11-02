package com.kevinpostal.fiberirc.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.api.DEVICE_TZ_HEADER
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.prefs.ACCOUNT_PREFS_FILE
import com.kevinpostal.fiberirc.data.prefs.NotificationPolicyStore
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.fakePolicyViewJson
import com.kevinpostal.fiberirc.support.TestSession
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
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
 * Drives the real [SettingsFlow] (root list + every screen it pushes, unmodified production code) over a
 * [TestSession]: the real `Deps` stores and [com.kevinpostal.fiberirc.ui.shell.SessionActions], with ircfiber.com
 * replaced by a [FakeSiteServer]. Navigation is by real taps from the Settings root.
 */
@RunWith(AndroidJUnit4::class)
class SettingsE2eTest {
    /** v2 rule: effects run on a StandardTestDispatcher driven from the main thread, like production's
     * AndroidUiDispatcher. The v1 rule's UnconfinedTestDispatcher resumes `rememberCoroutineScope` work on whatever
     * thread finished the suspension (SiteApi's IO pool), so View calls after an API call (ChangePasswordScreen's
     * `focus.clearFocus()`) throw CalledFromWrongThreadException in tests only. */
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var closed = false

    private val settings = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
    )

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        // Settings root and Account both read /api/me; displayPrefs pins a known baseline over the on-disk cache.
        server.on(
            "GET", "/api/me",
            body = """{"id":"u1","username":"tester","email":"tester@example.com",
                "displayPrefs":{"displayPrefsSet":true,"showTimestamps":true,"showUserIcons":true},"highlightWords":[]}""",
        )
        server.on("GET", "/api/me/irc-account", body = """{"status":"ready","account":"tester","password":"x"}""")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun launch(me: Me = Me(id = "u1", username = "tester")): TestSession {
        val session = TestSession(app, server, listOf(settings), me)
        compose.setContent {
            FiberIrcTheme {
                SettingsFlow(
                    api = session.deps.api,
                    baseUrl = server.baseUrl,
                    accountSettings = session.deps.accountSettings,
                    deviceSettings = session.deps.deviceSettings,
                    displaySettings = session.deps.displaySettings,
                    notificationPrefs = session.deps.notificationPrefs,
                    notificationPolicy = session.deps.notificationPolicy,
                    blockList = session.deps.blockList,
                    sessionActions = session.sessionActions,
                    onClose = { closed = true },
                )
            }
        }
        // The profile row turns into the username once GET /api/me answered (and the account stores loaded it).
        waitForText("tester")
        return session
    }

    private fun waitForText(text: String, timeout: Long = 5_000) {
        compose.waitUntil(timeoutMillis = timeout) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForRequests(method: String, path: String, count: Int = 1): List<RecordedRequest> {
        compose.waitUntil(timeoutMillis = 5_000) { requestsTo(method, path).size >= count }
        return requestsTo(method, path)
    }

    private fun requestsTo(method: String, path: String) =
        server.requests().filter { it.method == method && it.path?.substringBefore('?') == path }

    private fun RecordedRequest.json(): JsonElement = Json.parseToJsonElement(body.clone().readUtf8())

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** The settings LazyColumn: scrolls [text]'s row into composition and view (bottom rows aren't composed yet). */
    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    }

    private fun switchOf(title: String) = compose.onNode(isToggleable() and hasAnyAncestor(hasText(title)))

    /**
     * Root → Display → an account-level toggle: the switch flips at once, POST /api/me/display-prefs carries only
     * the changed key, and the server's answer is what the store keeps. A refused save puts the switch back and
     * says so. Back → root → Back leaves Settings.
     */
    @Test
    fun displayToggleSavesToAccountAndRevertsOnFailure() {
        val session = launch()
        server.on(
            "POST", "/api/me/display-prefs",
            body = """{"displayPrefs":{"displayPrefsSet":true,"showTimestamps":false,"showUserIcons":true}}""",
        )
        compose.onNodeWithText("Display").performClick()
        waitForText("MESSAGE LAYOUT")

        scrollTo("Show Timestamps")
        switchOf("Show Timestamps").assertIsOn()
        compose.onNodeWithText("Show Timestamps").performClick()

        val saved = waitForRequests("POST", "/api/me/display-prefs").single()
        assertEquals(json("""{"showTimestamps":false}"""), saved.json())
        compose.waitUntil(timeoutMillis = 5_000) { !session.deps.accountSettings.display.value.showTimestamps }
        switchOf("Show Timestamps").assertIsOff()

        // A refused save: the switch comes back and an alert explains it.
        server.on("POST", "/api/me/display-prefs", code = 500, body = """{"error":"Database unavailable"}""")
        scrollTo("Show Avatars")
        compose.onNodeWithText("Show Avatars").performClick()
        val refused = waitForRequests("POST", "/api/me/display-prefs", count = 2).last()
        assertEquals(json("""{"showUserIcons":false}"""), refused.json())
        waitForText("Couldn't save setting")
        compose.onNodeWithText("Database unavailable").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertTrue(session.deps.accountSettings.display.value.showUserIcons)
        scrollTo("Show Avatars")
        switchOf("Show Avatars").assertIsOn()

        // Back to the root, then Back again leaves Settings.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Search Settings")
        assertFalse(closed)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { closed }
    }

    /**
     * Root → profile row → Account → Change Password: client-side checks stop a mismatch without a request; the
     * server's explanation of a wrong current password shows under the fields; a success posts
     * `{oldPassword,newPassword}`, reports the signed-out sessions and pops back to Account.
     */
    @Test
    fun changePasswordValidatesSubmitsAndReportsResult() {
        launch()
        compose.onNodeWithText("tester").performClick()
        waitForText("Change Password")
        // The username row fills in from GET /api/me.
        waitForText("tester@example.com")
        compose.onNodeWithText("Change Password").performClick()
        waitForText("Current Password")

        compose.onNodeWithText("Current Password").performTextInput("oldsecret")
        compose.onNodeWithText("New Password").performTextInput("newsecret1")
        compose.onNodeWithText("Confirm New Password").performTextInput("different1")
        compose.onNodeWithText("Save").performClick()
        waitForText("New passwords do not match")
        assertTrue(requestsTo("POST", "/api/me/password").isEmpty())

        // Fix the confirmation; the server refuses the current password (its own 401 explanation, not a lost session).
        server.on("POST", "/api/me/password", code = 401, body = """{"error":"Current password is incorrect"}""")
        retypeConfirmation("newsecret1")
        compose.onNodeWithText("Save").performClick()
        waitForText("Current password is incorrect")
        assertEquals(
            json("""{"oldPassword":"oldsecret","newPassword":"newsecret1"}"""),
            waitForRequests("POST", "/api/me/password").single().json(),
        )

        server.on("POST", "/api/me/password", body = """{"sessionsEnded":2}""")
        compose.onNodeWithText("Save").performClick()
        waitForText("Password Changed")
        compose.onNodeWithText("Signed out of 2 other sessions.").assertExists()
        assertEquals(2, requestsTo("POST", "/api/me/password").size)
        compose.onNodeWithText("OK").performClick()
        // Popped back to Account.
        waitForText("IRC ACCOUNT")
        compose.onNodeWithText("Password Changed").assertDoesNotExist()
    }

    /** Clears the third password field and types [text] (its value is masked, so it's found by position). */
    private fun retypeConfirmation(text: String) {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[2].performTextClearance()
        fields[2].performTextInput(text)
    }

    /**
     * Root tile counts the app-applicable blocks → Blocked Users lists them; Add posts the whole list with the new
     * `nick!*@*` and applies it only after the save; swiping a row unblocks (web-only masks are saved back untouched);
     * a refused save leaves the list as it was.
     */
    @Test
    fun blockAndUnblockSaveWholeIgnoreList() {
        val session = launch(Me(id = "u1", username = "tester", ignores = listOf("troll!*@*", "web!user@host")))
        server.on("POST", "/api/me/ignores", body = """{"ok":true}""")
        compose.onNode(hasText("Blocked Users") and hasText("1")).assertExists()
        scrollTo("Blocked Users")
        compose.onNodeWithText("Blocked Users").performClick()
        waitForText("Nickname")
        compose.onNodeWithText("troll").assertExists()

        compose.onNodeWithText("Nickname").performTextInput("spammer")
        compose.onNodeWithText("Add").performClick()
        assertEquals(
            json("""{"ignores":["troll!*@*","web!user@host","spammer!*@*"]}"""),
            waitForRequests("POST", "/api/me/ignores").single().json(),
        )
        waitForText("spammer")
        assertEquals(listOf("troll!*@*", "web!user@host", "spammer!*@*"), session.deps.blockList.masks.value)

        compose.onNodeWithText("troll").performTouchInput { swipeLeft() }
        assertEquals(
            json("""{"ignores":["web!user@host","spammer!*@*"]}"""),
            waitForRequests("POST", "/api/me/ignores", count = 2).last().json(),
        )
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText("troll")).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("web!user@host", "spammer!*@*"), session.deps.blockList.masks.value)

        // Refused: nothing changes locally and the error shows.
        server.on("POST", "/api/me/ignores", code = 500, body = """{"error":"Try again later"}""")
        compose.onNodeWithText("Nickname").performTextInput("flooder")
        compose.onNodeWithText("Add").performClick()
        waitForText("Couldn't block flooder")
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("web!user@host", "spammer!*@*"), session.deps.blockList.masks.value)

        // Back on the root, the tile counts the one app-applicable block left.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Search Settings")
        compose.onNode(hasText("Blocked Users") and hasText("1")).assertExists()
    }

    /** Sign Out: GET /logout, the account stores reset, and [SessionActions.signOut]'s sign-in screen with its notice. */
    @Test
    fun signOutLogsOutAndShowsLogin() {
        val session = launch()
        assertNull(session.loggedOutNotice)
        scrollTo("Sign Out")
        compose.onNodeWithText("Sign Out").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { session.loggedOutNotice != null }
        assertEquals("Signed out", session.loggedOutNotice)
        assertEquals(1, requestsTo("GET", "/logout").size)
        assertFalse(session.deps.accountSettings.display.value.displayPrefsSet)
    }

    /**
     * Delete Account… asks first; a refused DELETE /api/me keeps the session and shows the server's reason, and a
     * successful one signs out with the deletion notice.
     */
    @Test
    fun deleteAccountConfirmsAndHandlesRefusal() {
        val session = launch()
        server.on("DELETE", "/api/me", code = 403, body = """{"error":"Cancel your subscription first"}""")
        scrollTo("Delete Account…")
        compose.onNodeWithText("Delete Account…").performClick()
        waitForText("Delete Account")
        compose.onNodeWithText("Delete").performClick()

        waitForText("Account not deleted")
        compose.onNodeWithText("Cancel your subscription first").assertExists()
        assertNull(session.loggedOutNotice)
        compose.onNodeWithText("OK").performClick()

        server.on("DELETE", "/api/me")
        scrollTo("Delete Account…")
        compose.onNodeWithText("Delete Account…").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { session.loggedOutNotice != null }
        assertEquals("Your account was deleted.", session.loggedOutNotice)
        assertEquals(2, requestsTo("DELETE", "/api/me").size)
        assertEquals(1, requestsTo("GET", "/logout").size)
    }

    /**
     * A Notification Policy v2 server: Notifications opens the v2 screen (not the v1 Mute All screen). Picking a
     * preset PATCHes `{"applyPreset"}` with `If-Match`; a 409 adopts the server's `current` and retries once at its
     * rev. Turning this phone's DM push off PATCHes `clients/self` with only that cell, and the row says it's changed.
     */
    @Test
    fun notificationsV2PresetRetriesConflictAndThisPhoneOverrides() {
        app.getSharedPreferences(ACCOUNT_PREFS_FILE, Context.MODE_PRIVATE).edit().remove(NotificationPolicyStore.KEY_VIEW).commit()
        val meJson = """{"id":"u1","username":"tester","email":"tester@example.com","muteAll":false,"notificationSound":true,
            "highlightWords":["outage"],"notificationPolicy":${fakePolicyViewJson(rev = 3)}}"""
        server.on("GET", "/api/me", body = meJson)
        var policyWrites = 0
        server.defaultsNotificationPolicy(fakePolicyViewJson(rev = 3)) { req ->
            if (req.path == "/api/me/notification-policy") {
                policyWrites++
                if (policyWrites == 1) {
                    MockResponse().setResponseCode(409)
                        .setBody("""{"error":"changed","code":"rev_conflict","current":${fakePolicyViewJson(rev = 4)}}""")
                } else {
                    MockResponse().setBody(fakePolicyViewJson(rev = 5, preset = "everything"))
                }
            } else {
                MockResponse().setBody(
                    fakePolicyViewJson(rev = 6, preset = "everything")
                        .replace("\"clients\":{}", "\"clients\":{\"self01\":{\"cells\":{\"dm.mobile\":false}}}"),
                )
            }
        }
        val session = launch(SiteJson.decodeFromString(Me.serializer(), meJson))
        val policy = session.deps.notificationPolicy
        try {
            compose.onNodeWithText("Notifications").performClick()
            waitForText("NOTIFY ME ABOUT")
            compose.onNodeWithTag("notifications.preset.focused").assert(hasContentDescription("Selected"))

            compose.onNodeWithTag("notifications.preset.everything").performClick()
            val patches = waitForRequests("PATCH", "/api/me/notification-policy", count = 2)
            assertEquals("\"np-3\"", patches[0].getHeader("If-Match"))
            assertEquals("\"np-4\"", patches[1].getHeader("If-Match"))
            assertEquals(json("""{"applyPreset":"everything"}"""), patches[1].json())
            compose.waitUntil(timeoutMillis = 5_000) { policy.view.value?.rev == 5L }
            compose.onNodeWithTag("notifications.preset.everything").assert(hasContentDescription("Selected"))

            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("notifications.class.dm.mobile"))
            compose.onNodeWithTag("notifications.class.dm.mobile").assertIsOn().performClick()
            val client = waitForRequests("PATCH", "/api/me/notification-policy/clients/self").single()
            assertEquals("\"np-5\"", client.getHeader("If-Match"))
            assertEquals(json("""{"cells":{"dm.mobile":false}}"""), client.json())
            assertEquals(java.time.ZoneId.systemDefault().id, client.getHeader(DEVICE_TZ_HEADER))
            compose.waitUntil(timeoutMillis = 5_000) { policy.view.value?.rev == 6L }
            compose.onNodeWithTag("notifications.class.dm.mobile").assertIsOff()
            compose.onNodeWithText("Changed on this device").assertExists()
        } finally {
            policy.reset()
        }
    }
}
