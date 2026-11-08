package com.kevinpostal.fiberirc.ui.update

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.update.ApkReleaseClient
import com.kevinpostal.fiberirc.data.update.ApkReleaseUnavailableException
import com.kevinpostal.fiberirc.data.update.AppUpdates
import com.kevinpostal.fiberirc.data.update.AvailableRelease
import com.kevinpostal.fiberirc.data.update.PlayUpdateFlow
import com.kevinpostal.fiberirc.data.update.UpdateChannel
import com.kevinpostal.fiberirc.data.update.UpdateDestination
import com.kevinpostal.fiberirc.data.update.UpdateState
import com.kevinpostal.fiberirc.debug.UpdateFixtureActivity
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.TestSession
import com.kevinpostal.fiberirc.ui.settings.CHECK_FOR_UPDATES_TAG
import com.kevinpostal.fiberirc.ui.settings.SettingsFlow
import com.kevinpostal.fiberirc.ui.shell.SessionHost
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * Optional app updates end to end on a device: the real [AppUpdates], [PlayUpdateFlow] and [UpdateNoticeHost]
 * mounted by the DEBUG-only [UpdateFixtureActivity] (wired like `MainActivity`: attach in onCreate, `onForeground()`
 * on every resume) over the real [SettingsFlow] or chat [SessionHost] of a [TestSession]. Every test owns its
 * preferences file, clock, coroutine scope and channel.
 *
 * Play installs use Google's [FakeAppUpdateManager] injected as the actual manager, so the real Task await,
 * `AppUpdateInfo` conversion and FLEXIBLE eligibility run; the fake shows no consent UI and installs nothing, so
 * the user's consent answer is delivered the way Android does — an activity result for [PlayUpdateFlow.RESULT_KEY]
 * through the activity's own `ActivityResultRegistry` — and nothing here claims a store upgrade. APK installs read
 * releases through the real [ApkReleaseClient] whose OkHttp client answers from [GitHubFixture] in-process (no
 * production network). Browser/Play handoffs stop at the real `startActivity` boundary: [ExternalLaunches] captures
 * them before any app opens, and [NoHandlerContext] makes Android itself report that no app handles the link.
 *
 * UiAutomation screenshots of each visible state go to the `additionalTestOutputDir` instrumentation argument when
 * given, else to `<external files>/update-screenshots` (logged under tag `FiberIRC.updatesE2e`).
 */
@RunWith(AndroidJUnit4::class)
class AppUpdatesE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clock = AtomicLong(T0)
    private val apkLookups = AtomicInteger()
    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    private lateinit var server: FakeSiteServer
    private lateinit var prefs: SharedPreferences
    private lateinit var scope: CoroutineScope
    private lateinit var fake: FakeAppUpdateManager
    private var scenario: ActivityScenario<UpdateFixtureActivity>? = null

    private val network = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = "#general",
    )

    @Before
    fun setUp() {
        server = FakeSiteServer(instrumentation.targetContext)
        server.on(
            "GET", "/api/me",
            body = """{"id":"u1","username":"tester","email":"tester@example.com",
                "displayPrefs":{"displayPrefsSet":true,"showTimestamps":true,"showUserIcons":true},"highlightWords":[]}""",
        )
        server.on("GET", "/api/me/irc-account", body = """{"status":"ready","account":"tester","password":"x"}""")
        prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        scope = MainScope()
        fake = onMain { FakeAppUpdateManager(app) }
    }

    @After
    fun tearDown() {
        monitors.forEach(instrumentation::removeMonitor)
        scenario?.close()
        UpdateFixtureActivity.fixture = null
        scope.cancel()
        server.shutdown()
        prefs.edit().clear().commit()
    }

    /**
     * Play flexible: the automatic notice → Update fetches fresh info and starts the real consent flow → accepted →
     * a non-modal capsule shows indeterminate then known progress while Settings stays usable → downloaded shows
     * Update ready. Neither the download nor Later completes it; a manual recheck reads DOWNLOADED back from Play and
     * only Restart to update calls `completeUpdate()`.
     */
    @Test
    fun playFlexibleUpdateDownloadsAndCompletesOnlyOnExplicitRestart() {
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE) }
        val updates = playUpdates()
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        assertInDialog("IRC Fiber build $AVAILABLE_CODE is available.")
        assertInDialog(UpdateTexts.PLAY_FLEXIBLE_GUIDANCE)

        startFlexibleUpdate()
        assertEquals(UpdateState.Available(PLAY_FLEXIBLE), updates.state.value)
        assertNoProgress()

        acceptConsent(updates)
        compose.waitUntil(TIMEOUT) { exists(hasText(UpdateTexts.DOWNLOADING) and inProgressCapsule) }
        assertFalse("total unknown: no percentage yet", exists(hasText("%", substring = true) and inProgressCapsule))

        onMain {
            fake.downloadStarts()
            fake.setTotalBytesToDownload(1_000)
            fake.setBytesDownloaded(400)
        }
        awaitDownloading(updates, bytes = 400, total = 1_000)
        compose.waitUntil(TIMEOUT) { exists(hasText("40%") and inProgressCapsule) }
        // Non-modal: no dialog, and Settings underneath keeps reflecting the shared state.
        assertNoDialog()
        awaitRowStatus("Downloading 40%")
        screenshot("play-progress")

        onMain {
            fake.setBytesDownloaded(1_000)
            fake.downloadCompletes()
        }
        awaitDialog(updates, UpdateTexts.READY_TITLE)
        assertTrue(updates.state.value is UpdateState.Downloaded)
        assertNoProgress()
        assertInDialog("IRC Fiber build $AVAILABLE_CODE has downloaded.", substring = true)
        compose.onNode(hasTestTag(UpdateTags.PRIMARY) and hasText(UpdateTexts.RESTART)).assertExists()
        assertFalse("downloaded is not installing", onMain { fake.isInstallSplashScreenVisible })
        screenshot("play-ready")

        tapLater()
        assertTrue(updates.state.value is UpdateState.Downloaded)
        assertEquals("play:$AVAILABLE_CODE", prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null))
        awaitRowStatus("Ready to install")
        assertFalse("Later never completes the update", onMain { fake.isInstallSplashScreenVisible })

        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.READY_TITLE)
        assertFalse(onMain { fake.isInstallSplashScreenVisible })
        tapPrimary(UpdateTexts.RESTART)
        awaitMain({ "completeUpdate after Restart (state ${updates.state.value})" }) {
            fake.isInstallSplashScreenVisible && updates.state.value is UpdateState.Installing
        }
        assertNoDialog()

        // Play reports the install finished (the fake installs nothing): the consumer settles on up to date.
        onMain { fake.installCompletes() }
        awaitState(updates, UpdateState.UpToDate)
        assertEquals(0, apkLookups.get())
    }

    /**
     * Later keeps the release available and suppresses its automatic notice: a foreground after the 24-hour check
     * interval really checks again (attempt recorded) but stays quiet. A manual check ignores suppression and
     * offers it again over a typed Settings search, which Later leaves intact.
     */
    @Test
    fun laterSuppressesAutomaticNoticeWhileManualRecheckOffersAgainAndKeepsSearchDraft() {
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE) }
        val updates = playUpdates()
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        compose.onNode(hasTestTag(UpdateTags.PRIMARY) and hasText(UpdateTexts.UPDATE)).assertExists()
        compose.onNode(hasTestTag(UpdateTags.LATER) and hasText(UpdateTexts.LATER)).assertExists()
        screenshot("play-available")

        clock.set(T0 + 20 * HOUR)
        tapLater()
        assertEquals(UpdateState.Available(PLAY_FLEXIBLE), updates.state.value)
        assertEquals("play:$AVAILABLE_CODE", prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null))
        assertEquals(T0 + 20 * HOUR, prefs.getLong(AppUpdates.KEY_LAST_OFFERED_AT, 0))
        awaitRowStatus("Build $AVAILABLE_CODE available")

        clock.set(T0 + 25 * HOUR)
        foregroundAgain()
        awaitAutomaticCheck(updates, T0 + 25 * HOUR)
        assertEquals(UpdateState.Available(PLAY_FLEXIBLE), updates.state.value)
        assertNull("suppressed release: no automatic notice", updates.notice.value)
        assertNoDialog()

        compose.onNode(hasSetTextAction()).performTextInput(SEARCH_DRAFT)
        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        screenshot("settings-draft-available")
        tapLater()
        compose.onNode(hasSetTextAction()).assert(hasText(SEARCH_DRAFT))
        awaitRowStatus("Build $AVAILABLE_CODE available")
        assertEquals(T0 + 25 * HOUR, prefs.getLong(AppUpdates.KEY_LAST_OFFERED_AT, 0))
        screenshot("settings-draft-after-later")
        assertFalse(onMain { fake.isConfirmationDialogVisible })
    }

    /**
     * Over the real chat: a composer draft (keyboard put away) survives the next foreground's automatic notice and
     * its Later; the chat keeps receiving and sending afterwards. An immediate further foreground doesn't re-prompt.
     */
    @Test
    fun automaticNoticeOverChatKeepsComposerDraftAndChatUsable() {
        val updates = playUpdates()
        val session = TestSession(app, server, listOf(network))
        mount(updates) { SessionHost(session.deps, session.manager, session.sessionActions) }
        awaitAutomaticCheck(updates, T0)
        assertEquals(UpdateState.UpToDate, updates.state.value)
        assertNull("automatic up to date is silent", updates.notice.value)

        session.transport.feed(":server 001 tester :Welcome")
        compose.waitUntil(TIMEOUT) { session.transport.sentLines.any { it.startsWith("JOIN #general") } }
        session.transport.feed(":tester!u@h JOIN #general")
        compose.waitUntil(TIMEOUT) { exists(hasContentDescription("Message") and hasSetTextAction()) }
        compose.onNode(hasContentDescription("Message") and hasSetTextAction()).performTextInput(CHAT_DRAFT)
        hideKeyboard()

        onMain { fake.setUpdateAvailable(AVAILABLE_CODE) }
        clock.set(T0 + 25 * HOUR)
        foregroundAgain()
        awaitAutomaticCheck(updates, T0 + 25 * HOUR)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        screenshot("chat-draft-available")
        tapLater()
        compose.onNode(hasContentDescription("Message") and hasSetTextAction()).assert(hasText(CHAT_DRAFT))
        screenshot("chat-draft-after-later")

        clock.set(T0 + 25 * HOUR + MINUTE)
        foregroundAgain()
        assertEquals(T0 + 25 * HOUR, prefs.getLong(AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT, 0))
        assertNull(updates.notice.value)
        assertNoDialog()

        session.transport.feed(":alice!u@h PRIVMSG #general :still here")
        compose.waitUntil(TIMEOUT) { exists(hasContentDescription("alice: still here", substring = true)) }
        session.transport.clearSent()
        compose.onNode(hasTestTag("sendButton")).performClick()
        compose.waitUntil(TIMEOUT) { session.transport.sentLines.any { it == "PRIVMSG #general :$CHAT_DRAFT" } }
    }

    /**
     * Declining Play's consent (RESULT_CANCELED through the registered launcher) leaves the release available,
     * starts no download and suppresses its automatic notice past the next interval; a manual check offers it again
     * and Update starts a fresh consent flow.
     */
    @Test
    fun consentCancellationSuppressesReleaseUntilManualRecheck() {
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE) }
        val updates = playUpdates()
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        startFlexibleUpdate()

        clock.set(T0 + 20 * HOUR)
        consentResult(Activity.RESULT_CANCELED) { userRejectsUpdate() }
        awaitMain({ "cancelled consent suppresses play:3 (state ${updates.state.value})" }) {
            prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null) == "play:$AVAILABLE_CODE"
        }
        assertEquals(UpdateState.Available(PLAY_FLEXIBLE), updates.state.value)
        assertNull(updates.notice.value)
        assertFalse(onMain { fake.isConfirmationDialogVisible })
        assertNoDialog()
        assertNoProgress()

        clock.set(T0 + 25 * HOUR)
        foregroundAgain()
        awaitAutomaticCheck(updates, T0 + 25 * HOUR)
        assertNull("cancelled release: no automatic notice", updates.notice.value)
        assertNoDialog()

        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        startFlexibleUpdate()
        assertEquals(0, apkLookups.get())
    }

    /**
     * A failed download reports a Play install error (OK only) and returns to available without suppression; a
     * manual retry starts a new consent flow; cancelling that download from Play suppresses the release and clears
     * the progress capsule, keeping it available.
     */
    @Test
    fun downloadFailureReportsErrorAndAllowsRetryThenDownloadCancellation() {
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE) }
        val updates = playUpdates()
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        startFlexibleUpdate()
        acceptConsent(updates)
        onMain {
            fake.downloadStarts()
            fake.setTotalBytesToDownload(2_000)
            fake.setBytesDownloaded(500)
        }
        awaitDownloading(updates, bytes = 500, total = 2_000)
        compose.waitUntil(TIMEOUT) { exists(hasText("25%") and inProgressCapsule) }

        onMain { fake.downloadFails() }
        awaitDialog(updates, UpdateTexts.INSTALL_FAILED_TITLE)
        assertInDialog("Google Play couldn't download the update.", substring = true)
        assertAcknowledgeOnly()
        assertAvailableCode(updates)
        assertNoProgress()
        screenshot("play-download-failed")
        acknowledge()
        assertAvailableCode(updates)
        assertNull("a failure suppresses nothing", prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null))

        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        assertInDialog(UpdateTexts.PLAY_FLEXIBLE_GUIDANCE)
        startFlexibleUpdate()
        acceptConsent(updates)
        onMain { fake.downloadStarts() }
        compose.waitUntil(TIMEOUT) { exists(hasText(UpdateTexts.DOWNLOADING) and inProgressCapsule) }

        onMain { fake.userCancelsDownload() }
        awaitMain({ "cancelled download suppresses play:3 (state ${updates.state.value})" }) {
            updates.state.value is UpdateState.Available &&
                prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null) == "play:$AVAILABLE_CODE"
        }
        compose.waitUntil(TIMEOUT) { !exists(hasTestTag(UpdateTags.PROGRESS)) }
        assertNull(updates.notice.value)
        assertNoDialog()
        assertFalse(onMain { fake.isInstallSplashScreenVisible })
        assertEquals(0, apkLookups.get())
    }

    /**
     * A downloaded update outlives the activity: a configuration recreation presents the still-pending Update ready
     * again (the install listener reattaches), and a restarted process — fresh [AppUpdates]/[PlayUpdateFlow] over the
     * same preferences — restores Downloaded from Play on resume, quietly while Later's suppression lasts and as an
     * automatic Update ready after it; Restart then completes through the reattached flow.
     */
    @Test
    fun downloadedUpdateIsRestoredAfterRecreationAndProcessRestart() {
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE) }
        val updates = playUpdates()
        val session = launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        startFlexibleUpdate()
        acceptConsent(updates)
        onMain {
            fake.downloadStarts()
            fake.setTotalBytesToDownload(1_000)
            fake.setBytesDownloaded(1_000)
            fake.downloadCompletes()
        }
        awaitDialog(updates, UpdateTexts.READY_TITLE)

        checkNotNull(scenario).recreate()
        awaitDialog(updates, UpdateTexts.READY_TITLE)
        assertTrue(updates.state.value is UpdateState.Downloaded)
        assertFalse(onMain { fake.isInstallSplashScreenVisible })
        screenshot("play-ready-after-recreate")
        tapLater()

        val restarted = playUpdates()
        val restartedFlow = PlayUpdateFlow(restarted)
        assertEquals(UpdateState.Idle, restarted.state.value)
        UpdateFixtureActivity.fixture = UpdateFixtureActivity.Fixture(restarted, restartedFlow) {
            SettingsUnderTest(session, server.baseUrl, restarted)
        }
        checkNotNull(scenario).recreate()
        awaitMain({ "Downloaded restored from Play (state ${restarted.state.value})" }) {
            (restarted.state.value as? UpdateState.Downloaded)?.release?.versionCode == AVAILABLE_CODE
        }
        assertNull("Later's suppression still holds", restarted.notice.value)
        awaitRowStatus("Ready to install")
        assertNoDialog()

        clock.set(T0 + 25 * HOUR)
        foregroundAgain()
        awaitDialog(restarted, UpdateTexts.READY_TITLE)
        assertFalse(onMain { fake.isInstallSplashScreenVisible })
        tapPrimary(UpdateTexts.RESTART)
        awaitMain({ "completeUpdate after Restart (state ${restarted.state.value})" }) {
            fake.isInstallSplashScreenVisible && restarted.state.value is UpdateState.Installing
        }
        assertEquals(0, apkLookups.get())
    }

    /**
     * Play knows a newer build but it isn't FLEXIBLE-eligible: Update hands off to the fixed Play listing — no
     * in-app consent or immediate flow, no APK — and suppresses the release.
     */
    @Test
    fun playUpdateWithoutFlexibleEligibilityOpensFixedPlayListing() {
        val launches = monitor(ExternalLaunches())
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE, AppUpdateType.IMMEDIATE) }
        val updates = playUpdates()
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        assertInDialog(UpdateTexts.PLAY_LISTING_GUIDANCE)
        assertEquals(UpdateState.Available(PLAY_LISTING), updates.state.value)
        screenshot("play-listing-available")

        tapPrimary(UpdateTexts.UPDATE)
        awaitMain({ "Play listing handoff" }) { launches.intents.isNotEmpty() }
        assertNoDialog()
        assertEquals(listOf(PlayUpdateFlow.PLAY_URI), launches.intents.map { it.dataString })
        assertTrue(launches.intents.all { it.action == Intent.ACTION_VIEW })
        assertFalse(onMain { fake.isConfirmationDialogVisible || fake.isImmediateFlowVisible })
        assertEquals("play:$AVAILABLE_CODE", prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null))
        assertEquals(UpdateState.Available(PLAY_LISTING), updates.state.value)
        assertEquals(0, apkLookups.get())
    }

    /** No app opens the Play listing (`market:` nor the fixed https URL): a useful open error, never an APK. */
    @Test
    fun playListingWithoutHandlerReportsOpenErrorNotApk() {
        val attempts = CopyOnWriteArrayList<Intent>()
        onMain { fake.setUpdateAvailable(AVAILABLE_CODE, AppUpdateType.IMMEDIATE) }
        val updates = playUpdates()
        launchSettings(updates, linkContext = { NoHandlerContext(it, attempts) })
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        tapPrimary(UpdateTexts.UPDATE)

        awaitDialog(updates, UpdateTexts.OPEN_FAILED_TITLE)
        assertInDialog("Google Play couldn't be opened.", substring = true)
        assertAcknowledgeOnly()
        assertEquals(listOf(PlayUpdateFlow.PLAY_URI, PlayUpdateFlow.PLAY_WEB_URI), attempts.map { it.dataString })
        screenshot("play-listing-open-error")
        acknowledge()
        assertEquals(UpdateState.Available(PLAY_LISTING), updates.state.value)
        awaitRowStatus("Build $AVAILABLE_CODE available")
        assertEquals(0, apkLookups.get())
    }

    /**
     * Play errors are unavailable, never an APK: a failing info query is silent automatically and "Couldn't check"
     * manually; a Play error on Update's fresh query is an install error that keeps the release available. Nothing
     * reads GitHub or opens a link.
     */
    @Test
    fun playQueryErrorsAreUnavailableAndNeverFallBackToApk() {
        val launches = monitor(ExternalLaunches())
        onMain {
            fake.setUpdateAvailable(AVAILABLE_CODE)
            fake.setInstallErrorCode(InstallErrorCode.ERROR_API_NOT_AVAILABLE)
        }
        val updates = playUpdates()
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        assertEquals(UpdateState.Unavailable, updates.state.value)
        assertNull("automatic failures are silent", updates.notice.value)
        assertNoDialog()

        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.CHECK_TITLE)
        assertInDialog(UpdateTexts.CHECK_FAILED)
        assertAcknowledgeOnly()
        screenshot("play-check-failed")
        acknowledge()
        awaitRowStatus("Couldn't check")

        onMain { fake.setInstallErrorCode(InstallErrorCode.NO_ERROR) }
        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        onMain { fake.setInstallErrorCode(InstallErrorCode.ERROR_INTERNAL_ERROR) }
        tapPrimary(UpdateTexts.UPDATE)
        awaitDialog(updates, UpdateTexts.INSTALL_FAILED_TITLE)
        assertAcknowledgeOnly()
        assertFalse(onMain { fake.isConfirmationDialogVisible })
        acknowledge()
        assertEquals(UpdateState.Available(PLAY_FLEXIBLE), updates.state.value)

        assertEquals(0, apkLookups.get())
        assertTrue("no browser or store handoff: ${launches.intents}", launches.intents.isEmpty())
    }

    /**
     * APK install: the newest complete GitHub release (read by the real client) is offered with the APK guidance;
     * Update suppresses it and hands exactly the pinned, versioned asset URL to the system — never "latest".
     */
    @Test
    fun apkUpdateHandsPinnedReleaseAssetToBrowser() {
        val launches = monitor(ExternalLaunches())
        val github = GitHubFixture()
        val updates = apkUpdates(github)
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        assertInDialog("IRC Fiber $AVAILABLE_NAME is available.")
        assertInDialog(UpdateTexts.APK_GUIDANCE)
        assertEquals(UpdateState.Available(APK_RELEASE), updates.state.value)
        screenshot("apk-available")

        tapPrimary(UpdateTexts.UPDATE)
        awaitMain({ "APK handoff" }) { launches.intents.isNotEmpty() }
        assertNoDialog()
        val opened = launches.intents.single()
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(PINNED_APK, opened.dataString)
        assertEquals("apk:$AVAILABLE_CODE", prefs.getString(AppUpdates.KEY_LAST_OFFERED_RELEASE, null))
        assertEquals(UpdateState.Available(APK_RELEASE), updates.state.value)
        assertEquals(
            listOf(
                "https://api.github.com/repos/kevinpostal/ircfiber-pages/releases?per_page=100&page=1",
                ApkReleaseClient.assetUrl(APK_TAG, ApkReleaseClient.MANIFEST_ASSET),
            ),
            github.requests.map { it.url.toString() },
        )
    }

    /** No app opens the pinned APK link: a useful open error; the release stays available and is offered again. */
    @Test
    fun apkWithoutHandlerReportsOpenErrorAndKeepsUpdateAvailable() {
        val attempts = CopyOnWriteArrayList<Intent>()
        val updates = apkUpdates(GitHubFixture())
        launchSettings(updates, linkContext = { NoHandlerContext(it, attempts) })
        awaitAutomaticCheck(updates, T0)
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        tapPrimary(UpdateTexts.UPDATE)

        awaitDialog(updates, UpdateTexts.OPEN_FAILED_TITLE)
        assertInDialog("No app on this device could open the download link.", substring = true)
        assertAcknowledgeOnly()
        assertEquals(listOf(PINNED_APK), attempts.map { it.dataString })
        screenshot("apk-open-error")
        acknowledge()
        assertEquals(UpdateState.Available(APK_RELEASE), updates.state.value)
        awaitRowStatus("Version $AVAILABLE_NAME available")

        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.AVAILABLE_TITLE)
        assertInDialog(UpdateTexts.APK_GUIDANCE)
        tapLater()
    }

    /**
     * APK results without an Update action: offline is silent automatically and "Couldn't check" manually (not up
     * to date); a newer release needing a newer Android API says so, offers no Update, and opens nothing.
     */
    @Test
    fun apkOfflineAndUnsupportedOsGiveManualResultsWithoutUpdate() {
        val launches = monitor(ExternalLaunches())
        val github = GitHubFixture().apply { offline = true }
        val updates = apkUpdates(github)
        launchSettings(updates)
        awaitAutomaticCheck(updates, T0)
        assertEquals(UpdateState.Unavailable, updates.state.value)
        assertNull(updates.notice.value)
        assertNoDialog()

        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.CHECK_TITLE)
        assertInDialog(UpdateTexts.CHECK_FAILED)
        assertAcknowledgeOnly()
        screenshot("apk-offline")
        acknowledge()
        awaitRowStatus("Couldn't check")

        github.offline = false
        github.minSdk = Build.VERSION.SDK_INT + 1
        tapCheckForUpdates()
        awaitDialog(updates, UpdateTexts.CHECK_TITLE)
        assertInDialog(UpdateTexts.REQUIRES_OS)
        assertInDialog("API level ${Build.VERSION.SDK_INT + 1}", substring = true)
        assertAcknowledgeOnly()
        assertEquals(UpdateState.RequiresOs(AVAILABLE_CODE, AVAILABLE_NAME, Build.VERSION.SDK_INT + 1), updates.state.value)
        screenshot("apk-requires-os")
        acknowledge()
        awaitRowStatus("Needs newer Android")
        assertTrue(launches.intents.isEmpty())
    }

    // region fixture

    private fun playUpdates() = AppUpdates(
        prefs = prefs,
        scope = scope,
        automaticChecks = true,
        installedVersionCode = INSTALLED_CODE,
        sdkInt = Build.VERSION.SDK_INT,
        resolveChannel = { UpdateChannel.PLAY },
        playManager = lazy { fake },
        apkLookup = {
            apkLookups.incrementAndGet()
            throw ApkReleaseUnavailableException("a Play installation must never read APK releases")
        },
        now = clock::get,
    )

    private fun apkUpdates(github: GitHubFixture) = AppUpdates(
        prefs = prefs,
        scope = scope,
        automaticChecks = true,
        installedVersionCode = INSTALLED_CODE,
        sdkInt = Build.VERSION.SDK_INT,
        resolveChannel = { UpdateChannel.APK },
        playManager = lazy<AppUpdateManager> {
            throw AssertionError("an APK installation must never bind Google Play")
        },
        apkLookup = ApkReleaseClient(client = github.client)::latest,
        now = clock::get,
    )

    private fun mount(
        updates: AppUpdates,
        flow: PlayUpdateFlow = PlayUpdateFlow(updates),
        linkContext: (ComponentActivity) -> Context = { it },
        content: @Composable () -> Unit,
    ) {
        UpdateFixtureActivity.fixture = UpdateFixtureActivity.Fixture(updates, flow, linkContext, content)
        scenario = ActivityScenario.launch(UpdateFixtureActivity::class.java)
    }

    private fun launchSettings(
        updates: AppUpdates,
        linkContext: (ComponentActivity) -> Context = { it },
    ): TestSession {
        val session = TestSession(app, server, listOf(network))
        mount(updates, linkContext = linkContext) { SettingsUnderTest(session, server.baseUrl, updates) }
        // The profile row turns into the username once GET /api/me answered.
        compose.waitUntil(TIMEOUT) { exists(hasText("tester")) }
        // An immediate fake query may finish before the initial window is eligible. Exercise the next foreground.
        foregroundAgain()
        return session
    }

    /** Background → foreground: onStop, then onStart/onResume (a new foreground event for checks and the host). */
    private fun foregroundAgain() {
        val scenario = checkNotNull(scenario)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    /** Tap Update on a Play FLEXIBLE notice: fresh info, then the real consent flow starts. */
    private fun startFlexibleUpdate() {
        tapPrimary(UpdateTexts.UPDATE)
        awaitMain({ "Play consent flow started" }) { fake.isConfirmationDialogVisible }
        assertNoDialog()
    }

    private fun acceptConsent(updates: AppUpdates) {
        consentResult(Activity.RESULT_OK) { userAcceptsUpdate() }
        awaitMain({ "download pending (state ${updates.state.value})" }) { updates.state.value is UpdateState.Downloading }
    }

    /**
     * The user answers Play's consent sheet. Its activity covers ours (stopped meanwhile); Android hands the result
     * to `ComponentActivity`'s registry by request code, and the launcher [PlayUpdateFlow] registered under
     * [PlayUpdateFlow.RESULT_KEY] receives it when the activity starts again.
     */
    private fun consentResult(resultCode: Int, answer: FakeAppUpdateManager.() -> Unit) {
        val scenario = checkNotNull(scenario)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.onActivity { activity ->
            fake.answer()
            val registry = activity.activityResultRegistry
            val saved = Bundle().also(registry::onSaveInstanceState)
            val keys = checkNotNull(saved.getStringArrayList(REGISTERED_KEYS))
            val codes = checkNotNull(saved.getIntegerArrayList(REGISTERED_CODES))
            val index = keys.indexOf(PlayUpdateFlow.RESULT_KEY)
            check(index >= 0) { "PlayUpdateFlow registered no ${PlayUpdateFlow.RESULT_KEY} launcher: $keys" }
            check(registry.dispatchResult(codes[index], resultCode, null))
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    /** Puts the keyboard away as the user would, keeping the focused field and its text. */
    private fun hideKeyboard() {
        checkNotNull(scenario).onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (imeVisible() != false) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Keyboard still shown: ime=${imeVisible()}")
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    /** Whether the activity's root insets show the keyboard (null before insets exist); call off the main thread. */
    private fun imeVisible(): Boolean? {
        var visible: Boolean? = null
        checkNotNull(scenario).onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())
        }
        return visible
    }

    private fun <T : ActivityMonitorWithIntents> monitor(monitor: T): T {
        instrumentation.addMonitor(monitor)
        monitors += monitor
        return monitor
    }

    // endregion

    // region waits and assertions

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /** Polls [condition] on the main thread (where [AppUpdates] and the fake's listeners live). */
    private fun awaitMain(what: () -> String, timeoutMillis: Long = TIMEOUT, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!onMain(condition)) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out: ${onMain(what)}")
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun awaitState(updates: AppUpdates, expected: UpdateState) =
        awaitMain({ "state $expected, was ${updates.state.value}" }) { updates.state.value == expected }

    private fun awaitDownloading(updates: AppUpdates, bytes: Long, total: Long) =
        awaitMain({ "downloading $bytes/$total, was ${updates.state.value}" }) {
            (updates.state.value as? UpdateState.Downloading)?.let {
                it.release.versionCode == AVAILABLE_CODE && it.bytesDownloaded == bytes && it.totalBytesToDownload == total
            } == true
        }

    /** The automatic attempt at [at] was recorded and its query (if any) finished. */
    private fun awaitAutomaticCheck(updates: AppUpdates, at: Long) =
        awaitMain({
            "automatic check at $at: last=${prefs.getLong(AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT, -1)} " +
                "checking=${updates.checking.value}"
        }) { prefs.getLong(AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT, -1) == at && !updates.checking.value }

    private fun assertAvailableCode(updates: AppUpdates) {
        val state = updates.state.value
        assertTrue("expected available build $AVAILABLE_CODE, was $state",
            state is UpdateState.Available && state.release.versionCode == AVAILABLE_CODE)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val inDialog = hasAnyAncestor(hasTestTag(UpdateTags.DIALOG))
    private val inProgressCapsule = hasAnyAncestor(hasTestTag(UpdateTags.PROGRESS))

    private fun awaitDialog(updates: AppUpdates, title: String) {
        try {
            compose.waitUntil(TIMEOUT) { exists(hasText(title) and inDialog) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "No \"$title\" dialog: notice=${updates.notice.value} state=${updates.state.value} " +
                    "ime=${imeVisible()}",
                e,
            )
        }
    }

    private fun assertInDialog(text: String, substring: Boolean = false) {
        assertTrue("dialog shows \"$text\"", exists(hasText(text, substring = substring) and inDialog))
    }

    /** A result/error dialog: OK only, no Update or Later. */
    private fun assertAcknowledgeOnly() {
        assertTrue(exists(hasTestTag(UpdateTags.PRIMARY) and hasText(UpdateTexts.OK)))
        assertFalse(exists(hasTestTag(UpdateTags.LATER)))
        assertFalse(exists(hasText(UpdateTexts.UPDATE) and inDialog))
    }

    private fun assertNoDialog() {
        compose.waitUntil(TIMEOUT) { !exists(hasTestTag(UpdateTags.DIALOG)) }
    }

    private fun assertNoProgress() {
        compose.waitUntil(TIMEOUT) { !exists(hasTestTag(UpdateTags.PROGRESS)) }
    }

    private fun tapPrimary(text: String) {
        compose.onNode(hasTestTag(UpdateTags.PRIMARY) and hasText(text)).performClick()
    }

    private fun tapLater() {
        compose.onNode(hasTestTag(UpdateTags.LATER) and hasText(UpdateTexts.LATER)).performClick()
        assertNoDialog()
    }

    /** OK on a result or error dialog: it closes (the update state it describes stays). */
    private fun acknowledge() {
        tapPrimary(UpdateTexts.OK)
        assertNoDialog()
    }

    private fun scrollToCheckForUpdates() {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag(CHECK_FOR_UPDATES_TAG))
    }

    /** Settings → Check for updates, once a running check no longer disables it. */
    private fun tapCheckForUpdates() {
        scrollToCheckForUpdates()
        compose.waitUntil(TIMEOUT) { exists(hasTestTag(CHECK_FOR_UPDATES_TAG) and hasClickAction()) }
        compose.onNode(hasTestTag(CHECK_FOR_UPDATES_TAG)).performClick()
    }

    private fun awaitRowStatus(status: String) {
        scrollToCheckForUpdates()
        compose.waitUntil(TIMEOUT) { exists(hasTestTag(CHECK_FOR_UPDATES_TAG) and hasText(status)) }
    }

    // endregion

    // region screenshots

    private val screenshotDir: File by lazy {
        val requested = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        requested?.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() }
            ?: File(checkNotNull(app.getExternalFilesDir(null)) { "no external files dir" }, "update-screenshots")
                .apply { check(isDirectory || mkdirs()) { "can't create $this" } }
    }

    /** The whole screen (dialogs are their own windows) via UiAutomation, for native visual proof. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(SCREENSHOT_SETTLE_MILLIS) // window enter animations, for the picture only
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation returned no screenshot" }
        val file = File(screenshotDir, "AppUpdatesE2eTest-$name.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" } }
        bitmap.recycle()
        Log.i(LOG_TAG, "screenshot ${file.absolutePath}")
    }

    // endregion

    private companion object {
        const val INSTALLED_CODE = 2
        const val AVAILABLE_CODE = 3
        const val AVAILABLE_NAME = "1.0.2"
        const val APK_TAG = "android-v$AVAILABLE_NAME"
        const val PINNED_APK =
            "https://github.com/kevinpostal/ircfiber-pages/releases/download/$APK_TAG/ircfiber-android.apk"
        const val PREFS_FILE = "app_updates_e2e"
        const val T0 = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val TIMEOUT = 10_000L
        const val POLL_MILLIS = 50L
        const val SCREENSHOT_SETTLE_MILLIS = 400L
        const val SEARCH_DRAFT = "updates"
        const val CHAT_DRAFT = "half-written reply"
        const val LOG_TAG = "FiberIRC.updatesE2e"
        const val REGISTERED_KEYS = "KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"
        const val REGISTERED_CODES = "KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"

        val PLAY_FLEXIBLE = AvailableRelease(AVAILABLE_CODE, null, UpdateDestination.Play(flexibleAllowed = true))
        val PLAY_LISTING = AvailableRelease(AVAILABLE_CODE, null, UpdateDestination.Play(flexibleAllowed = false))
        val APK_RELEASE = AvailableRelease(AVAILABLE_CODE, AVAILABLE_NAME, UpdateDestination.Apk(PINNED_APK))
    }
}

/** The production Settings flow over a [TestSession], with the test's [AppUpdates] behind Check for updates. */
@Composable
private fun SettingsUnderTest(session: TestSession, baseUrl: String, appUpdates: AppUpdates) {
    SettingsFlow(
        api = session.deps.api,
        baseUrl = baseUrl,
        accountSettings = session.deps.accountSettings,
        deviceSettings = session.deps.deviceSettings,
        displaySettings = session.deps.displaySettings,
        notificationPrefs = session.deps.notificationPrefs,
        notificationPolicy = session.deps.notificationPolicy,
        blockList = session.deps.blockList,
        sessionActions = session.sessionActions,
        appUpdates = appUpdates,
        onClose = { error("Settings is the fixture's only screen; nothing here navigates back out of it") },
    )
}

/** An [Instrumentation.ActivityMonitor] that records the activity starts it intercepted. */
private abstract class ActivityMonitorWithIntents : Instrumentation.ActivityMonitor() {
    val intents = CopyOnWriteArrayList<Intent>()
}

/**
 * Intercepts `ACTION_VIEW` starts at `Instrumentation.execStartActivity` — the call `LinkOpener.openExternally`
 * makes through `startActivity` — so no browser or store opens (no production network), recording the intent.
 */
private class ExternalLaunches : ActivityMonitorWithIntents() {
    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        if (intent.action != Intent.ACTION_VIEW) return null
        intents += Intent(intent)
        return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
    }
}

/**
 * A device with no app for the link: every activity start is aimed at a package that isn't installed, so Android
 * itself throws `ActivityNotFoundException` from `startActivity` (what `LinkOpener` reports as false). Records what
 * was asked.
 */
private class NoHandlerContext(base: Context, private val attempts: MutableList<Intent>) : ContextWrapper(base) {
    override fun startActivity(intent: Intent) {
        attempts += Intent(intent)
        super.startActivity(Intent(intent).setPackage(MISSING_PACKAGE))
    }

    private companion object {
        const val MISSING_PACKAGE = "com.kevinpostal.fiberirc.nohandler"
    }
}

/**
 * GitHub for the real [ApkReleaseClient], answered in-process by an application interceptor (nothing leaves the
 * device): one release page with a legacy APK-only release and the complete `android-v1.0.2` pair, and that
 * release's `android-update.json`. [offline] fails every request as an unreachable host; [minSdk] is the manifest's.
 */
private class GitHubFixture : Interceptor {
    @Volatile var offline = false
    @Volatile var minSdk = 26
    val requests = CopyOnWriteArrayList<Request>()
    val client: OkHttpClient = ApkReleaseClient.plainHttpClient().newBuilder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        if (offline) throw UnknownHostException("${request.url.host}: device offline")
        val url = request.url
        return when {
            url.host == "api.github.com" && url.encodedPath == "/repos/kevinpostal/ircfiber-pages/releases" ->
                respond(request, 200, if (url.queryParameter("page") == "1") RELEASES else "[]")
            url.toString() == ApkReleaseClient.assetUrl(TAG, ApkReleaseClient.MANIFEST_ASSET) ->
                respond(
                    request, 200,
                    """{"applicationId":"com.kevinpostal.fiberirc","versionCode":3,"versionName":"1.0.2","minSdk":$minSdk}""",
                )
            else -> respond(request, 404, """{"message":"Not Found"}""")
        }
    }

    private fun respond(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Not Found")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private companion object {
        const val TAG = "android-v1.0.2"
        val RELEASES = """[
            {"tag_name":"$TAG","draft":false,"prerelease":false,"published_at":"2026-11-01T00:00:00Z","assets":[
                {"name":"ircfiber-android.apk","state":"uploaded",
                 "browser_download_url":"${ApkReleaseClient.assetUrl(TAG, ApkReleaseClient.APK_ASSET)}"},
                {"name":"android-update.json","state":"uploaded",
                 "browser_download_url":"${ApkReleaseClient.assetUrl(TAG, ApkReleaseClient.MANIFEST_ASSET)}"}]},
            {"tag_name":"android-v1.0.0","draft":false,"prerelease":false,"published_at":"2026-06-01T00:00:00Z","assets":[
                {"name":"ircfiber-android.apk","state":"uploaded",
                 "browser_download_url":"${ApkReleaseClient.assetUrl("android-v1.0.0", ApkReleaseClient.APK_ASSET)}"}]}
        ]"""
    }
}
