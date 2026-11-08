package com.kevinpostal.fiberirc.ui.login

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.support.FakeSignup
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.fakeMeJson
import com.kevinpostal.fiberirc.support.testAccountStores
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [LoginScreen] + [LoginViewModel] against a [FakeSiteServer] standing in for ircfiber.com: no
 * production network call, but every tap goes through the real Compose UI and the real sign-in state machine.
 *
 * Signup scenarios never sleep through the two-second verification poll interval: a status check is triggered by
 * the screen becoming visible/foreground or by "Check verification", and in-flight answers are held server-side
 * ([FakeSignup.hold]) so cancellation and late completions are deterministic. "Nothing more happens" checks use a
 * short [assertNever] window.
 */
@RunWith(AndroidJUnit4::class)
class LoginE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val appPrefs: SharedPreferences get() = app.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE)
    /** One cookie jar file per test, shared by every [viewModel] of that test so a relaunch keeps its cookies. */
    private val cookies = "test_cookies_${System.nanoTime()}"
    private val stores = HashMap<LoginViewModel, ViewModelStore>()

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        // A fresh install for every test: Community Guidelines not yet accepted, no pending signup.
        appPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        stores.keys.toList().forEach(::kill)
        server.shutdown()
        app.getSharedPreferences(cookies, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** A view model owned by its own [ViewModelStore], so [kill] can end it the way process death would. */
    private fun viewModel(
        notice: String? = null,
        resumeStoredSession: Boolean = true,
        api: SiteApi = server.siteApi(cookiePrefs = cookies),
    ): LoginViewModel {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(
                api = api,
                stores = testAccountStores(app, api),
                deviceSettings = DeviceSettingsStore(app),
                deviceId = { "test-device" },
                appPrefs = appPrefs,
                notice = notice,
                resumeStoredSession = resumeStoredSession,
            ) as T
        }
        return ViewModelProvider(store, factory)[LoginViewModel::class.java].also { stores[it] = store }
    }

    /** Clears [vm]'s store: `onCleared`, its coroutines cancelled. */
    private fun kill(vm: LoginViewModel) {
        val store = stores.remove(vm) ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
    }

    private fun host(vm: LoginViewModel, onSession: (List<ConnectionSettings>) -> Unit = {}) {
        compose.setContent { LoginScreen(viewModel = vm, baseUrl = server.baseUrl, onSession = onSession) }
    }

    /** Hosts [first] in a slot a test can empty or refill with a later view model (a relaunch). */
    private fun hostSwappable(first: LoginViewModel, onSession: (List<ConnectionSettings>) -> Unit): MutableState<LoginViewModel?> {
        val slot = mutableStateOf<LoginViewModel?>(first)
        compose.setContent {
            slot.value?.let { vm -> key(vm) { LoginScreen(viewModel = vm, baseUrl = server.baseUrl, onSession = onSession) } }
        }
        return slot
    }

    /** The sign-in form only renders once `resumeSession()`'s `/api/me` round trip answers; Compose's own
     *  idle-wait doesn't cover that async call, so every test waits for it explicitly before touching a field. */
    private fun waitForForm(vm: LoginViewModel) = compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.formVisible }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 5_000, condition = condition)

    /** Fails if [condition] holds at any point during a short [ms] window. */
    private fun assertNever(message: String, ms: Long = 750, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            compose.waitForIdle()
            assertFalse(message, condition())
            Thread.sleep(50)
        }
    }

    private fun pending(): PendingSignupStore.Entry? = PendingSignupStore(appPrefs).load(server.baseUrl)

    /** Opens the native form from sign-in and fills it in. */
    private fun fillSignup(vm: LoginViewModel, username: String = "newbie", email: String = "new@example.test", password: String = SIGNUP_PASSWORD) {
        waitForForm(vm)
        compose.onNodeWithText("Create an account").performScrollTo().performClick()
        waitFor { vm.state.value.mode == LoginMode.SIGN_UP }
        compose.onNodeWithTag("signupUsername").performTextInput(username)
        compose.onNodeWithTag("signupEmail").performTextInput(email)
        compose.onNodeWithTag("signupPassword").performTextInput(password)
    }

    private fun submitSignup() {
        compose.onNodeWithTag("signupSubmit").performScrollTo().performClick()
    }

    /** Form → accepted 202 → the waiting screen, with its first (immediate) status check answered. */
    private fun signUpAndWait(vm: LoginViewModel, script: FakeSignup) {
        fillSignup(vm)
        submitSignup()
        waitFor { vm.state.value.mode == LoginMode.CHECK_EMAIL }
        waitFor { script.statusHits.get() >= 1 && script.statusInFlight.get() == 0 && !vm.state.value.verificationChecking }
    }

    private fun tapCheck(vm: LoginViewModel) {
        waitFor { !vm.state.value.verificationChecking }
        compose.onNodeWithTag("signupCheck").performScrollTo().performClick()
    }

    private fun agreeAndExpectOneSession(vm: LoginViewModel, sessions: AtomicInteger) {
        waitFor { vm.state.value.guidelinesVisible }
        compose.onNodeWithText("Community Guidelines").assertIsDisplayed()
        assertFalse(
            "Creating an account must not accept the Community Guidelines by itself",
            appPrefs.getBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, false),
        )
        compose.onNodeWithText("Agree").performClick()
        waitFor { sessions.get() >= 1 }
        assertNever("The session must start exactly once") { sessions.get() > 1 || vm.state.value.guidelinesVisible }
        assertNull("Authenticated: the pending signup is forgotten", pending())
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Stopping connections must not immediately reopen them using the still-valid account cookie. */
    @Test
    fun stoppedSessionRequiresExplicitSignIn() {
        server.defaultsPasswordLogin(fakeMeJson(username = "carol"))
        server.on("GET", "/api/me", body = fakeMeJson(username = "carol"))
        server.on("GET", "/api/me/bouncer", body = """{"enabled":false,"host":"","port":0,"tls":false,"username":""}""")
        server.on("GET", "/api/me/irc-account", body = """{"status":"ready","account":"carol","password":"x"}""")
        appPrefs.edit().putBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, true).commit()
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
            appPrefs.getBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, false),
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
        appPrefs.edit().putBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, true).commit()
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }

        compose.waitUntil(timeoutMillis = 5_000) { connected != null }
        assertEquals("carol", connected!![0].nick)
    }

    // MARK: Native signup

    /**
     * Native form → 202 with a watch → the waiting screen (accepted email, nonsecret marker, password gone) →
     * `pending` → confirmed elsewhere → "Check verification" → `verified` → `/api/me` → Guidelines → one session.
     */
    @Test
    fun signupWaitsForEmailThenContinuesThroughGuidelinesOnce() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie", email = "new@example.test"))
        val sessions = AtomicInteger()
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it; sessions.incrementAndGet() }

        signUpAndWait(vm, script)
        compose.onNodeWithTag("signupWaiting").assertIsDisplayed()
        compose.onNodeWithText("new@example.test").assertIsDisplayed()
        assertEquals("new@example.test", vm.state.value.pendingEmail)
        assertEquals("", vm.state.value.password)
        assertEquals(VerificationPhase.FOLLOW_WATCH, vm.state.value.verificationPhase)
        compose.onNodeWithTag("signupCheck").assertIsEnabled()
        compose.onNodeWithTag("signupEdit").assertIsEnabled()
        compose.onNodeWithTag("signupSignIn").assertIsEnabled()

        val form = server.requests().single { it.method == "POST" && it.path == "/register" }.body.readUtf8()
        assertTrue(form, "username=newbie" in form && "email=new%40example.test" in form && "langs=" in form)
        val entry = pending()
        assertNotNull("An accepted signup with a watch leaves a resumable marker", entry)
        assertEquals("newbie", entry!!.username)
        assertEquals("new@example.test", entry.email)
        val raw = appPrefs.getString(PendingSignupStore.KEY, null).orEmpty()
        assertFalse("The pending marker never holds the password", SIGNUP_PASSWORD in raw)
        assertFalse("Still pending: nothing signs in", vm.state.value.guidelinesVisible)

        script.state = "verified"
        tapCheck(vm)
        agreeAndExpectOneSession(vm, sessions)
        assertEquals("newbie", connected!![0].nick)
        assertTrue(appPrefs.getBoolean(LoginViewModel.KEY_ACCEPTED_TERMS, false))
    }

    /** Verification off: the 302 starts the session at once, through `/api/me` and Guidelines, never waiting. */
    @Test
    fun immediateSignupWithoutVerificationContinuesThroughGuidelines() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"), verification = false)
        val sessions = AtomicInteger()
        val vm = viewModel()
        host(vm) { sessions.incrementAndGet() }

        fillSignup(vm)
        submitSignup()
        agreeAndExpectOneSession(vm, sessions)
        assertEquals(0, script.statusHits.get())
        assertEquals("", vm.state.value.password)
    }

    /**
     * A registration 409 shows the server's own message, keeps the nonsecret details on the form, and neither
     * reaches `/api/me` nor bootstraps a session (it is not a preference-revision conflict).
     */
    @Test
    fun rejectedSignupKeepsDetailsAndDoesNotBootstrap() {
        server.defaultsSignup(fakeMeJson(username = "newbie"))
        val taken = "That username is already taken"
        server.on("POST", "/register", code = 409, body = """{"error":"$taken","code":"username_taken","params":{"nick":"newbie"}}""")
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }

        fillSignup(vm)
        submitSignup()
        waitFor { server.count("POST", "/register") == 1 && !vm.state.value.busy && vm.state.value.statusIsError }

        compose.onNodeWithText(taken).assertIsDisplayed()
        assertEquals(LoginMode.SIGN_UP, vm.state.value.mode)
        compose.onNodeWithTag("signupUsername").assertTextContains("newbie")
        compose.onNodeWithTag("signupEmail").assertTextContains("new@example.test")
        assertNever("A rejected signup must not continue") { vm.state.value.guidelinesVisible || connected != null }
        assertEquals("Only the startup probe reaches /api/me", 1, server.count("GET", "/api/me"))
        assertEquals(0, server.count("GET", "/api/me/bouncer"))
        assertEquals(0, server.count("GET", "/api/me/notification-policy"))
        assertNull(pending())
    }

    /** System Back is swallowed while the registration POST runs, then leaves the waiting screen for sign-in. */
    @Test
    fun backIsBlockedDuringSubmitThenReturnsToSignIn() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        val api = server.siteApi(cookiePrefs = cookies)
        val vm = viewModel(api = api)
        host(vm)

        fillSignup(vm)
        script.hold()
        submitSignup()
        waitFor { vm.state.value.busy && server.count("POST", "/register") == 1 }
        pressBack()
        assertEquals(LoginMode.SIGN_UP, vm.state.value.mode)
        compose.onNodeWithTag("signupSubmit").assertIsNotEnabled()

        script.release()
        waitFor { vm.state.value.mode == LoginMode.CHECK_EMAIL && !vm.state.value.busy }
        waitFor { script.statusInFlight.get() == 0 && !vm.state.value.verificationChecking }
        assertTrue(api.hasSignupWatch())

        pressBack()
        waitFor { vm.state.value.mode == LoginMode.SIGN_IN }
        assertEquals("newbie", vm.state.value.username)
        assertEquals("", vm.state.value.password)
        assertNull("Leaving forgets the local marker", pending())
        assertFalse("Leaving forgets the watch cookie", api.hasSignupWatch())
        val hits = script.statusHits.get()
        assertNever("No polling once the waiting screen is left") { script.statusHits.get() != hits }
    }

    /**
     * "Edit details" during an in-flight check cancels it: the late `verified` answer cannot sign in, polling stops,
     * and the form keeps username/email with an empty password.
     */
    @Test
    fun editDetailsCancelsInFlightCheckAndIgnoresLateVerification() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }
        signUpAndWait(vm, script)

        script.hold()
        tapCheck(vm)
        waitFor { script.statusInFlight.get() == 1 }
        compose.onNodeWithTag("signupEdit").performScrollTo().assertIsEnabled().performClick()
        waitFor { vm.state.value.mode == LoginMode.SIGN_UP }
        val hits = script.statusHits.get()

        script.state = "verified"
        script.release()
        waitFor { script.statusInFlight.get() == 0 }
        assertNever("A check from the abandoned wait must not sign in or poll again") {
            vm.state.value.guidelinesVisible || vm.state.value.mode != LoginMode.SIGN_UP ||
                connected != null || script.statusHits.get() != hits
        }
        assertEquals("Only the startup probe reaches /api/me", 1, server.count("GET", "/api/me"))
        compose.onNodeWithTag("signupUsername").assertTextContains("newbie")
        compose.onNodeWithTag("signupEmail").assertTextContains("new@example.test")
        assertEquals("", vm.state.value.password)
    }

    /**
     * Backgrounding cancels the in-flight check and stops polling; a confirmation answered meanwhile cannot sign in
     * until the screen is back. Foreground then checks at once, one request at a time, and continues exactly once.
     */
    @Test
    fun backgroundStopsPollingAndForegroundResumesWithoutOverlap() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        val sessions = AtomicInteger()
        val vm = viewModel()
        host(vm) { sessions.incrementAndGet() }
        signUpAndWait(vm, script)

        script.hold()
        tapCheck(vm)
        waitFor { script.statusInFlight.get() == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)

        script.state = "verified"
        script.release()
        waitFor { script.statusInFlight.get() == 0 }
        val hits = script.statusHits.get()
        assertNever("Nothing signs in or polls while in the background") {
            vm.state.value.guidelinesVisible || sessions.get() > 0 || script.statusHits.get() != hits
        }
        assertEquals(LoginMode.CHECK_EMAIL, vm.state.value.mode)

        script.maxStatusInFlight.set(0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { script.statusHits.get() > hits }
        agreeAndExpectOneSession(vm, sessions)
        assertTrue("Foreground checks never overlap", script.maxStatusInFlight.get() <= 1)
    }

    /** `gone` after a lost `verified` answer: `/api/me` finds the session it started, and sign-in continues. */
    @Test
    fun goneWithSessionRecoversThroughApiMe() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        script.state = "gone"
        script.sessionOnGone = true
        val sessions = AtomicInteger()
        val vm = viewModel()
        host(vm) { sessions.incrementAndGet() }

        fillSignup(vm)
        submitSignup()
        agreeAndExpectOneSession(vm, sessions)
    }

    /** `gone` without a session: manual sign-in recovery (no Check), and "Already confirmed?" prefills the username. */
    @Test
    fun goneWithoutSessionOffersManualSignIn() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        script.state = "gone"
        var connected: List<ConnectionSettings>? = null
        val vm = viewModel()
        host(vm) { connected = it }

        fillSignup(vm)
        submitSignup()
        waitFor { vm.state.value.verificationPhase == VerificationPhase.MANUAL_SIGN_IN && !vm.state.value.verificationChecking }
        compose.onNodeWithTag("signupCheck").assertIsNotEnabled()
        compose.onNodeWithTag("signupEdit").assertIsEnabled()
        assertNull("An unusable watch is not resumable", pending())
        assertFalse(vm.state.value.guidelinesVisible)

        compose.onNodeWithTag("signupSignIn").performScrollTo().performClick()
        waitFor { vm.state.value.mode == LoginMode.SIGN_IN }
        assertEquals("newbie", vm.state.value.username)
        assertEquals("", vm.state.value.password)
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        assertNull(connected)
    }

    /** An accepted 202 whose watch never started: check email, then sign in manually; nothing polls or resumes. */
    @Test
    fun acceptedSignupWithoutWatchOffersManualSignIn() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        script.setsWatch = false
        val vm = viewModel()
        host(vm)

        fillSignup(vm)
        submitSignup()
        waitFor { vm.state.value.mode == LoginMode.CHECK_EMAIL && !vm.state.value.busy }
        assertEquals(VerificationPhase.MANUAL_SIGN_IN, vm.state.value.verificationPhase)
        compose.onNodeWithText("new@example.test").assertIsDisplayed()
        compose.onNodeWithTag("signupCheck").assertIsNotEnabled()
        assertNever("No status check can work without a watch") { script.statusHits.get() > 0 }
        assertNull("No resumable marker without a watch", pending())

        compose.onNodeWithTag("signupSignIn").performScrollTo().performClick()
        waitFor { vm.state.value.mode == LoginMode.SIGN_IN }
        assertEquals("newbie", vm.state.value.username)
    }

    /** A relaunch (process death) while waiting restores the wait from marker + watch cookie and still completes. */
    @Test
    fun pendingSignupSurvivesRelaunch() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        val sessions = AtomicInteger()
        val first = viewModel()
        val slot = hostSwappable(first) { sessions.incrementAndGet() }
        signUpAndWait(first, script)

        compose.runOnUiThread { slot.value = null }
        compose.waitForIdle()
        kill(first)
        val hitsBefore = script.statusHits.get()

        val second = viewModel()
        compose.runOnUiThread { slot.value = second }
        waitFor { second.state.value.formVisible && second.state.value.mode == LoginMode.CHECK_EMAIL }
        assertEquals("new@example.test", second.state.value.pendingEmail)
        compose.onNodeWithTag("signupWaiting").assertIsDisplayed()
        waitFor { script.statusHits.get() > hitsBefore && script.statusInFlight.get() == 0 }

        script.state = "verified"
        tapCheck(second)
        agreeAndExpectOneSession(second, sessions)
    }

    /** Stopped connections never probe or restore a pending signup: explicit sign-in only. */
    @Test
    fun stoppedSessionIgnoresPendingSignup() {
        val script = server.defaultsSignup(fakeMeJson(username = "newbie"))
        val first = viewModel()
        val slot = hostSwappable(first) {}
        signUpAndWait(first, script)
        compose.runOnUiThread { slot.value = null }
        compose.waitForIdle()
        kill(first)
        val hits = script.statusHits.get()
        val probes = server.count("GET", "/api/me")

        val stopped = viewModel("Connections stopped", resumeStoredSession = false)
        compose.runOnUiThread { slot.value = stopped }
        waitForForm(stopped)
        assertEquals(LoginMode.SIGN_IN, stopped.state.value.mode)
        compose.onNodeWithText("Connections stopped").assertIsDisplayed()
        assertNever("A stopped session neither probes nor resumes the signup") {
            script.statusHits.get() != hits || server.count("GET", "/api/me") != probes ||
                stopped.state.value.mode != LoginMode.SIGN_IN || stopped.state.value.guidelinesVisible
        }
    }

    private companion object {
        const val SIGNUP_PASSWORD = "correct horse battery"
    }
}
