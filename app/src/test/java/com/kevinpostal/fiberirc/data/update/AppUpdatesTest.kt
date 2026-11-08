package com.kevinpostal.fiberirc.data.update

import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private val prefs = MemoryPrefs()
    private var clock = T0
    private var channel: () -> UpdateChannel = { UpdateChannel.APK }
    private var apk: suspend () -> ApkRelease = { throw AssertionError("APK lookup not expected") }
    private var play: suspend () -> PlayUpdateStatus = { throw AssertionError("Play query not expected") }
    private var apkCalls = 0
    private var playCalls = 0

    private fun TestScope.updates(automatic: Boolean = true) = AppUpdates(
        prefs = prefs,
        scope = backgroundScope,
        automaticChecks = automatic,
        installedVersionCode = INSTALLED_CODE,
        sdkInt = DEVICE_SDK,
        resolveChannel = { channel() },
        playManager = lazy<AppUpdateManager> { throw AssertionError("checks reach Play only through queryPlay") },
        apkLookup = { apkCalls++; apk() },
        queryPlay = { playCalls++; play() },
        now = { clock },
    )

    // ---- APK channel ----

    @Test fun apkHigherCodeOffersPinnedReleaseAutomatically() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()

        updates.check(manual = false)

        val expected = AvailableRelease(3, "1.0.3", UpdateDestination.Apk(apkUrl(3)))
        assertEquals(UpdateState.Available(expected), updates.state.value)
        assertEquals("apk:3", expected.id)
        assertEquals(UpdateNotice(1, manual = false, outcome = UpdateState.Available(expected)), updates.notice.value)
    }

    @Test fun apkEqualAndLowerCodesAreUpToDate() = runTest {
        val updates = updates()

        apk = { apkRelease(INSTALLED_CODE) }
        updates.check(manual = true)
        assertEquals(UpdateState.UpToDate, updates.state.value)
        assertEquals(UpdateNotice(1, manual = true, outcome = UpdateState.UpToDate), updates.notice.value)

        apk = { apkRelease(INSTALLED_CODE - 1) }
        updates.check(manual = true)
        assertEquals(UpdateState.UpToDate, updates.state.value)
        assertEquals(UpdateNotice(2, manual = true, outcome = UpdateState.UpToDate), updates.notice.value)
    }

    @Test fun apkHigherCodeNeedingNewerSdkRequiresOs() = runTest {
        apk = { apkRelease(3, minSdk = DEVICE_SDK + 1) }
        val updates = updates()

        updates.check(manual = false)
        val requires = UpdateState.RequiresOs(3, "1.0.3", DEVICE_SDK + 1)
        assertEquals(requires, updates.state.value)
        assertNull("automatic requires-OS stays silent", updates.notice.value)

        updates.check(manual = true)
        assertEquals(UpdateNotice(1, manual = true, outcome = requires), updates.notice.value)
    }

    @Test fun apkSupportedSdkBoundaryIsOffered() = runTest {
        apk = { apkRelease(3, minSdk = DEVICE_SDK) }
        val updates = updates()

        updates.check(manual = true)

        assertTrue(updates.state.value is UpdateState.Available)
    }

    @Test fun apkLookupFailureIsUnavailableSilentAutomaticallyReportedManually() = runTest {
        apk = { throw ApkReleaseUnavailableException("rate limited") }
        val updates = updates()

        updates.check(manual = false)
        assertEquals(UpdateState.Unavailable, updates.state.value)
        assertNull(updates.notice.value)

        updates.check(manual = true)
        assertEquals(UpdateNotice(1, manual = true, outcome = UpdateState.Unavailable), updates.notice.value)
    }

    // ---- Channel resolution and Play authority ----

    @Test fun failedChannelResolutionIsUnavailableWithoutAnyQuery() = runTest {
        channel = { throw IllegalStateException("package manager died") }
        apk = { apkRelease(3) }
        val updates = updates()

        updates.check(manual = true)

        assertEquals(UpdateState.Unavailable, updates.state.value)
        assertEquals(0, apkCalls)
        assertEquals(0, playCalls)
    }

    @Test fun playErrorsNeverFallBackToApk() = runTest {
        channel = { UpdateChannel.PLAY }
        apk = { apkRelease(9) }
        val updates = updates()

        play = { throw IOException("Play Store unavailable") }
        updates.check(manual = true)
        assertEquals(UpdateState.Unavailable, updates.state.value)

        play = { playStatus(UpdateAvailability.UNKNOWN, availableCode = 0) }
        updates.check(manual = true)
        assertEquals(UpdateState.Unavailable, updates.state.value)

        assertEquals(2, playCalls)
        assertEquals(0, apkCalls)
    }

    @Test fun playQueryTimesOutAfterFifteenSeconds() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { awaitCancellation() }
        val updates = updates()

        updates.check(manual = true)

        assertEquals(UpdateState.Unavailable, updates.state.value)
        assertEquals(AppUpdates.QUERY_TIMEOUT_MILLIS, testScheduler.currentTime)
        assertEquals(0, apkCalls)
    }

    @Test fun playAvailabilityStatesMapToUpdateStates() = runTest {
        channel = { UpdateChannel.PLAY }
        val updates = updates()
        fun outcomeOf(status: PlayUpdateStatus): UpdateState {
            return updates.revalidatePlay(status)
        }

        val flexible = AvailableRelease(3, null, UpdateDestination.Play(flexibleAllowed = true))
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, flexible = true) }
        updates.check(manual = true)
        assertEquals(UpdateState.Available(flexible), updates.state.value)
        assertEquals("play:3", flexible.id)

        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, flexible = false) }
        updates.check(manual = true)
        assertEquals(
            UpdateState.Available(AvailableRelease(3, null, UpdateDestination.Play(flexibleAllowed = false))),
            updates.state.value,
        )

        play = { playStatus(UpdateAvailability.UPDATE_NOT_AVAILABLE, INSTALLED_CODE) }
        updates.check(manual = true)
        assertEquals(UpdateState.UpToDate, updates.state.value)

        // Play claims availability without a higher code: inconsistent, never offered.
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, INSTALLED_CODE) }
        updates.check(manual = true)
        assertEquals(UpdateState.Unavailable, updates.state.value)

        assertEquals(
            UpdateState.Downloading(flexible.copy(destination = UpdateDestination.Play(false)), 40, 160),
            outcomeOf(playStatus(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, 3,
                installStatus = InstallStatus.DOWNLOADING, flexible = false, bytes = 40, total = 160)),
        )
        assertEquals(0.25f, (updates.state.value as UpdateState.Downloading).progress)
        assertEquals(
            UpdateState.Downloaded(flexible),
            outcomeOf(playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADED)),
        )
        // A stale DOWNLOADED for a code that isn't newer is not an update.
        assertEquals(
            UpdateState.UpToDate,
            outcomeOf(playStatus(UpdateAvailability.UPDATE_NOT_AVAILABLE, INSTALLED_CODE, InstallStatus.DOWNLOADED)),
        )
        assertEquals(0, apkCalls)
    }

    // ---- Automatic throttle ----

    @Test fun automaticAttemptIsRecordedBeforeFetchAndThrottledFor24Hours() = runTest {
        var recordedAtFetch: Long? = null
        apk = {
            recordedAtFetch = prefs.map[AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT] as Long?
            apkRelease(INSTALLED_CODE)
        }
        val updates = updates()

        updates.check(manual = false)
        assertEquals(T0, recordedAtFetch)
        assertEquals(1, apkCalls)

        clock = T0 + DAY - 1
        updates.check(manual = false)
        assertEquals(1, apkCalls)
        assertEquals(T0, prefs.map[AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT])

        clock = T0 + DAY
        updates.check(manual = false)
        assertEquals(2, apkCalls)
        assertEquals(T0 + DAY, recordedAtFetch)
    }

    @Test fun backwardsClockPermitsOneAttemptThenRecordsIt() = runTest {
        apk = { apkRelease(INSTALLED_CODE) }
        val updates = updates()
        updates.check(manual = false)

        clock = T0 - HOUR
        updates.check(manual = false)
        assertEquals(2, apkCalls)
        assertEquals(T0 - HOUR, prefs.map[AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT])

        clock = T0 - HOUR + 1
        updates.check(manual = false)
        assertEquals(2, apkCalls)
    }

    @Test fun failedAutomaticAttemptStillCountsTowardInterval() = runTest {
        apk = { throw ApkReleaseUnavailableException("offline") }
        val updates = updates()

        updates.check(manual = false)
        clock = T0 + HOUR
        updates.check(manual = false)

        assertEquals(1, apkCalls)
    }

    @Test fun manualCheckBypassesIntervalWithoutMovingIt() = runTest {
        apk = { apkRelease(INSTALLED_CODE) }
        val updates = updates()
        updates.check(manual = false)

        clock = T0 + HOUR
        updates.check(manual = true)

        assertEquals(2, apkCalls)
        assertEquals(T0, prefs.map[AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT])
    }

    @Test fun disabledAutomaticChecksQueryNothingButManualWorks() = runTest {
        apk = { apkRelease(3) }
        val updates = updates(automatic = false)

        updates.check(manual = false)
        assertEquals(UpdateState.Idle, updates.state.value)
        assertEquals(0, apkCalls)
        assertTrue(prefs.map.isEmpty())

        updates.check(manual = true)
        assertTrue(updates.state.value is UpdateState.Available)
    }

    @Test fun disabledForegroundDoesNotProbePlayButManualCheckWorks() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3) }
        val updates = updates(automatic = false)
        updates.onForeground()
        assertEquals(UpdateState.Idle, updates.state.value)
        assertEquals(0, playCalls)
        assertTrue(prefs.map.isEmpty())
        updates.check(manual = true)
        assertTrue(updates.state.value is UpdateState.Available)
        assertEquals(1, playCalls)
    }

    // ---- Suppression ----

    @Test fun laterRetainsAvailabilityAndSuppressesThatReleaseFor24Hours() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()
        updates.check(manual = false)
        val available = updates.state.value

        clock = T0 + HOUR
        updates.dismiss()
        assertEquals(available, updates.state.value)
        assertNull(updates.notice.value)
        assertEquals("apk:3", prefs.map[AppUpdates.KEY_LAST_OFFERED_RELEASE])
        assertEquals(T0 + HOUR, prefs.map[AppUpdates.KEY_LAST_OFFERED_AT])

        // Next automatic attempt is due, but the release is still suppressed.
        clock = T0 + DAY
        updates.check(manual = false)
        assertEquals(2, apkCalls)
        assertEquals(available, updates.state.value)
        assertNull(updates.notice.value)

        // Manual checks ignore suppression.
        updates.check(manual = true)
        assertEquals(UpdateNotice(2, manual = true, outcome = available), updates.notice.value)
        updates.dismiss()

        // Suppression expires 24 hours after the last Later.
        clock = T0 + 2 * DAY
        updates.check(manual = false)
        assertEquals(UpdateNotice(3, manual = false, outcome = available), updates.notice.value)
    }

    @Test fun suppressionIsPerChannelAndRelease() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()
        updates.check(manual = false)
        clock = T0 + HOUR
        updates.dismiss() // apk:3 suppressed until T0 + DAY + HOUR

        apk = { apkRelease(4) }
        clock = T0 + DAY
        updates.check(manual = false)
        assertEquals(4, (updates.notice.value!!.outcome as UpdateState.Available).release.versionCode)
        clock = T0 + DAY + 2 * HOUR
        updates.dismiss() // apk:4 suppressed until T0 + 2 * DAY + 2 * HOUR
        assertEquals("apk:4", prefs.map[AppUpdates.KEY_LAST_OFFERED_RELEASE])

        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 4) }
        clock = T0 + 2 * DAY
        updates.check(manual = false)
        assertEquals("play:4", (updates.notice.value!!.outcome as UpdateState.Available).release.id)
    }

    @Test fun backwardsClockEndsSuppression() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()
        updates.check(manual = false)
        updates.dismiss()

        clock = T0 - HOUR
        updates.check(manual = false)

        assertEquals(2, apkCalls)
        assertTrue(updates.notice.value!!.outcome is UpdateState.Available)
    }

    @Test fun dismissWithStaleSequenceKeepsNewerNotice() = runTest {
        apk = { apkRelease(INSTALLED_CODE) }
        val updates = updates()
        updates.check(manual = true)
        val first = updates.notice.value!!.sequence
        updates.check(manual = true)

        updates.dismiss(first)

        assertEquals(first + 1, updates.notice.value!!.sequence)
        assertFalse(prefs.map.containsKey(AppUpdates.KEY_LAST_OFFERED_RELEASE))
    }

    @Test fun identicalManualRechecksProduceDistinctNotices() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()

        updates.check(manual = true)
        val first = updates.notice.value
        updates.dismiss()
        updates.check(manual = true)
        val second = updates.notice.value

        assertEquals(first!!.outcome, second!!.outcome)
        assertNotEquals(first, second)
    }

    // ---- Coalescing ----

    @Test fun concurrentChecksShareOneQueryAndUpgradeToManual() = runTest {
        val gate = CompletableDeferred<Unit>()
        apk = { gate.await(); apkRelease(INSTALLED_CODE) }
        val updates = updates()

        val automatic = launch { updates.check(manual = false) }
        val manual = launch { updates.check(manual = true) }
        val again = launch { updates.check(manual = true) }
        runCurrent()
        assertEquals(1, apkCalls)
        assertEquals(UpdateState.Checking, updates.state.value)
        assertTrue(updates.checking.value)
        assertEquals(T0, prefs.map[AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT])

        gate.complete(Unit)
        automatic.join(); manual.join(); again.join()

        assertEquals(1, apkCalls)
        assertFalse(updates.checking.value)
        assertEquals(UpdateNotice(1, manual = true, outcome = UpdateState.UpToDate), updates.notice.value)
    }

    @Test fun cancelledCallerDoesNotCancelSharedCheck() = runTest {
        val gate = CompletableDeferred<Unit>()
        apk = { gate.await(); apkRelease(3) }
        val updates = updates()

        val caller = launch { updates.check(manual = true) }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        runCurrent()

        assertTrue(caller.isCancelled)
        assertEquals(1, apkCalls)
        assertTrue(updates.state.value is UpdateState.Available)
        assertEquals(UpdateNotice(1, manual = true, outcome = updates.state.value), updates.notice.value)
    }

    // ---- Play download lifecycle ----

    @Test fun dismissRetainsDownloadedUpdate() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADED) }
        val updates = updates()

        updates.check(manual = false)
        val downloaded = updates.state.value
        assertTrue(downloaded is UpdateState.Downloaded)
        assertEquals(UpdateNotice(1, manual = false, outcome = downloaded), updates.notice.value)

        updates.dismiss()

        assertEquals(downloaded, updates.state.value)
        assertEquals("play:3", prefs.map[AppUpdates.KEY_LAST_OFFERED_RELEASE])
    }

    @Test fun foregroundRestoresDownloadedIndependentlyOfInterval() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3) }
        val updates = updates()
        updates.onForeground()
        assertTrue(updates.state.value is UpdateState.Available)
        assertEquals(1, playCalls) // one query covers availability and install state

        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADED) }
        clock = T0 + HOUR
        updates.onForeground()

        val downloaded = UpdateState.Downloaded(AvailableRelease(3, null, UpdateDestination.Play(true)))
        assertEquals(downloaded, updates.state.value)
        assertEquals(downloaded, updates.notice.value!!.outcome)
        assertEquals(2, playCalls) // downloaded status is still queried inside the availability interval
        assertEquals(T0, prefs.map[AppUpdates.KEY_LAST_AUTOMATIC_ATTEMPT])
    }

    @Test fun restoreAfterLaterRestoresStateWithoutNotice() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADED) }
        val updates = updates()
        updates.check(manual = false)
        updates.dismiss()
        val restoredBefore = updates.state.value

        clock = T0 + HOUR
        updates.restoreInstallState()

        assertEquals(restoredBefore, updates.state.value)
        assertNull(updates.notice.value)
    }

    @Test fun restoreIsANoOpForApkInstalls() = runTest {
        val updates = updates()

        updates.restoreInstallState()

        assertEquals(UpdateState.Idle, updates.state.value)
        assertEquals(0, playCalls)
        assertEquals(0, apkCalls)
    }

    @Test fun restoreShowsDownloadEndedElsewhere() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADING) }
        val updates = updates()
        updates.check(manual = true)
        assertTrue(updates.state.value is UpdateState.Downloading)

        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.CANCELED) }
        updates.restoreInstallState()

        assertTrue(updates.state.value is UpdateState.Available)
    }

    @Test fun flexibleFlowProgressDownloadedAndExplicitRestart() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3) }
        val updates = updates()
        updates.check(manual = false)
        val release = (updates.state.value as UpdateState.Available).release

        updates.onUpdateFlowStarted()
        assertNull(updates.notice.value)
        updates.onUpdateFlowAccepted()
        assertEquals(UpdateState.Downloading(release, 0, 0), updates.state.value)
        assertNull((updates.state.value as UpdateState.Downloading).progress)

        updates.onInstallState(InstallStatus.DOWNLOADING, 30, 120)
        assertEquals(0.25f, (updates.state.value as UpdateState.Downloading).progress)

        assertFalse("restart needs a downloaded update", updates.markInstalling())

        updates.onInstallState(InstallStatus.DOWNLOADED, 120, 120)
        assertEquals(UpdateState.Downloaded(release), updates.state.value)
        val ready = updates.notice.value!!
        assertEquals(UpdateState.Downloaded(release), ready.outcome)
        updates.onInstallState(InstallStatus.DOWNLOADED, 120, 120)
        assertEquals("repeated callbacks don't re-announce", ready, updates.notice.value)

        assertTrue(updates.markInstalling())
        assertEquals(UpdateState.Installing(release), updates.state.value)
        assertNull(updates.notice.value)

        updates.onUpdateFailed()
        assertEquals(UpdateState.Downloaded(release), updates.state.value)
        assertEquals(UpdateProblem.INSTALL, updates.notice.value!!.problem)
        updates.dismiss()
        assertTrue(updates.markInstalling())
        updates.onInstallState(InstallStatus.INSTALLED, 120, 120)
        assertEquals(UpdateState.UpToDate, updates.state.value)
        assertNull(updates.notice.value)
    }

    @Test fun downloadFailurePermitsRetryWithoutSuppression() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3) }
        val updates = updates()
        updates.check(manual = false)
        val release = (updates.state.value as UpdateState.Available).release
        updates.onUpdateFlowAccepted()

        updates.onInstallState(InstallStatus.FAILED, 0, 0)

        assertEquals(UpdateState.Available(release), updates.state.value)
        assertFalse(prefs.map.containsKey(AppUpdates.KEY_LAST_OFFERED_RELEASE))
        assertEquals(UpdateProblem.INSTALL, updates.notice.value!!.problem)
        assertTrue(updates.notice.value!!.manual)
        updates.dismiss()
        clock = T0 + DAY
        updates.check(manual = false)
        assertEquals(UpdateState.Available(release), updates.notice.value!!.outcome)
    }

    @Test fun cancellingConsentSuppressesAndKeepsAvailable() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3) }
        val updates = updates()
        updates.check(manual = false)
        val release = (updates.state.value as UpdateState.Available).release

        clock = T0 + HOUR
        updates.onUpdateFlowCancelled()

        assertEquals(UpdateState.Available(release), updates.state.value)
        assertNull(updates.notice.value)
        assertEquals("play:3", prefs.map[AppUpdates.KEY_LAST_OFFERED_RELEASE])
        assertEquals(T0 + HOUR, prefs.map[AppUpdates.KEY_LAST_OFFERED_AT])
    }

    @Test fun externalHandoffSuppressesNotice() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()
        updates.check(manual = true)

        updates.onExternalUpdateStarted()

        assertTrue(updates.state.value is UpdateState.Available)
        assertNull(updates.notice.value)
        assertEquals("apk:3", prefs.map[AppUpdates.KEY_LAST_OFFERED_RELEASE])
    }

    @Test fun externalOpenFailureRetainsAvailabilityAndAllowsManualRetry() = runTest {
        apk = { apkRelease(3) }
        val updates = updates()
        updates.check(manual = true)
        val available = updates.state.value
        updates.onExternalUpdateStarted()
        updates.onExternalOpenFailed()
        assertEquals(available, updates.state.value)
        assertEquals(UpdateProblem.OPEN, updates.notice.value!!.problem)
        updates.dismiss()
        updates.check(manual = true)
        assertEquals(available, updates.notice.value!!.outcome)
        assertNull(updates.notice.value!!.problem)
    }

    @Test fun automaticRestoreDoesNotReplacePendingManualResult() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { throw IOException("offline") }
        val updates = updates()
        updates.check(manual = true)
        val error = updates.notice.value
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADED) }
        updates.onForeground()
        assertTrue(updates.state.value is UpdateState.Downloaded)
        assertEquals(error, updates.notice.value)
    }

    @Test fun failedCheckKeepsReportedDownload() = runTest {
        channel = { UpdateChannel.PLAY }
        play = { playStatus(UpdateAvailability.UPDATE_AVAILABLE, 3, installStatus = InstallStatus.DOWNLOADED) }
        val updates = updates()
        updates.check(manual = false)
        val downloaded = updates.state.value

        play = { throw IOException("offline") }
        updates.check(manual = true)

        assertEquals(downloaded, updates.state.value)
        assertEquals(UpdateState.Unavailable, updates.notice.value!!.outcome)
    }

    private companion object {
        const val INSTALLED_CODE = 2
        const val DEVICE_SDK = 34
        const val T0 = 1_800_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR

        fun apkUrl(code: Int) =
            "https://github.com/kevinpostal/ircfiber-pages/releases/download/android-v1.0.$code/ircfiber-android.apk"

        fun apkRelease(code: Int, minSdk: Int = 26) = ApkRelease(code, "1.0.$code", minSdk, apkUrl(code))

        fun playStatus(
            availability: Int,
            availableCode: Int,
            installStatus: Int = InstallStatus.UNKNOWN,
            flexible: Boolean = true,
            bytes: Long = 0,
            total: Long = 0,
        ) = PlayUpdateStatus(availability, availableCode, installStatus, flexible, bytes, total)
    }
}
