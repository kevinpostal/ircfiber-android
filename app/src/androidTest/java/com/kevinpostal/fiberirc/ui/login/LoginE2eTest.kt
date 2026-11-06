package com.kevinpostal.fiberirc.ui.login

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.fakeMeJson
import com.kevinpostal.fiberirc.support.testAccountStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [LoginScreen] + [LoginViewModel] against a [FakeSiteServer] standing in for ircfiber.com: no
 * production network call, but every tap goes through the real Compose UI and the real sign-in state machine.
 */
@RunWith(AndroidJUnit4::class)
class LoginE2eTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        // A fresh install for every test: Community Guidelines not yet accepted.
        app.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun viewModel(notice: String? = null, resumeStoredSession: Boolean = true): LoginViewModel {
        val api = server.siteApi()
        return LoginViewModel(
            api = api,
            stores = testAccountStores(app, api),
            deviceSettings = DeviceSettingsStore(app),
            deviceId = { "test-device" },
            appPrefs = app.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE),
            notice = notice,
            resumeStoredSession = resumeStoredSession,
        )
    }

    private fun host(vm: LoginViewModel, onSession: (List<ConnectionSettings>) -> Unit = {}) {
        compose.setContent { LoginScreen(viewModel = vm, baseUrl = server.baseUrl, onSession = onSession) }
    }

    /** The sign-in form only renders once `resumeSession()`'s `/api/me` round trip answers; Compose's own
     *  idle-wait doesn't cover that async call, so every test waits for it explicitly before touching a field. */
    private fun waitForForm(vm: LoginViewModel) = compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.formVisible }

    /** Stopping connections must not immediately reopen them using the still-valid account cookie. */
    @Test
    fun stoppedSessionRequiresExplicitSignIn() {
        server.defaultsPasswordLogin(fakeMeJson(username = "carol"))
        server.on("GET", "/api/me", body = fakeMeJson(username = "carol"))
        server.on("GET", "/api/me/bouncer", body = """{"enabled":false,"host":"","port":0,"tls":false,"username":""}""")
        server.on("GET", "/api/me/irc-account", body = """{"status":"ready","account":"carol","password":"x"}""")
        app.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, true).commit()
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel("Connections stopped", resumeStoredSession = false)
        host(vm) { connected = it }

        waitForForm(vm)
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Connections stopped").assertIsDisplayed()
        assertEquals(null, connected)

        compose.onNodeWithText("Username").performTextInput("carol")
        compose.onNodeWithText("Password").performTextInput("password")
        compose.onNodeWithText("Sign in").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { connected != null }
        assertEquals("carol", connected!![0].nick)
    }

    /** Password sign-in, Community Guidelines accepted, bouncer disabled → direct-ircd settings reach [onSession]. */
    @Test
    fun passwordSignInAcceptsGuidelinesAndConnects() {
        server.defaultsSignedOut()
        server.defaultsPasswordLogin(fakeMeJson(username = "alice"))
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }
        waitForForm(vm)

        compose.onNodeWithText("Username").performTextInput("alice")
        compose.onNodeWithText("Password").performTextInput("hunter2")
        compose.onNodeWithText("Sign in").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.guidelinesVisible }
        compose.onNodeWithText("Community Guidelines").assertIsDisplayed()
        compose.onNodeWithText("Agree").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { connected != null }
        assertEquals(1, connected!!.size)
        assertEquals("alice", connected!![0].nick)
        assertTrue(
            "Agreeing should persist acceptance",
            app.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, false),
        )
    }

    /** Declining Community Guidelines logs out and returns to the sign-in form instead of connecting. */
    @Test
    fun decliningGuidelinesReturnsToSignIn() {
        server.defaultsSignedOut()
        server.defaultsPasswordLogin(fakeMeJson())
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }
        waitForForm(vm)

        compose.onNodeWithText("Username").performTextInput("bob")
        compose.onNodeWithText("Password").performTextInput("hunter2")
        compose.onNodeWithText("Sign in").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.guidelinesVisible }
        compose.onNodeWithText("Cancel").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.formVisible && !vm.state.value.guidelinesVisible }
        assertEquals(null, connected)
        compose.onNodeWithText("Username").assertIsDisplayed()
    }

    /** Wrong password: `SiteApiError.BadCredentials` shows as an error row, the form stays up for another try. */
    @Test
    fun badCredentialsShowsError() {
        server.defaultsSignedOut()
        server.on("GET", "/api/auth/providers", body = """{"providers":[]}""")
        server.on("POST", "/login", code = 401, body = """{"error":"bad creds"}""")
        val vm = viewModel()
        host(vm)
        waitForForm(vm)

        compose.onNodeWithText("Username").performTextInput("alice")
        compose.onNodeWithText("Password").performTextInput("wrong")
        compose.onNodeWithText("Sign in").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.status.isNotEmpty() && !vm.state.value.busy }
        assertTrue(vm.state.value.statusIsError)
        assertEquals("Incorrect username or password", vm.state.value.status)
        compose.onNodeWithText("Username").assertIsDisplayed()
    }

    /** A stored session (`/api/me` answers at once) skips the form and reaches [onSession] without any tap. */
    @Test
    fun resumedSessionSkipsForm() {
        server.on("GET", "/api/me", body = fakeMeJson(username = "carol"))
        server.on("GET", "/api/me/bouncer", body = """{"enabled":false,"host":"","port":0,"tls":false,"username":""}""")
        server.on("GET", "/api/me/irc-account", body = """{"status":"ready","account":"carol","password":"x"}""")
        server.on("POST", "/api/me/irc-account/retry")
        // Community Guidelines already accepted on a prior install for this scenario.
        app.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, true).commit()
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }

        compose.waitUntil(timeoutMillis = 5_000) { connected != null }
        assertEquals("carol", connected!![0].nick)
    }
}
