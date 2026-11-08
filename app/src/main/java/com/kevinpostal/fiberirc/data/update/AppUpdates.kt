package com.kevinpostal.fiberirc.data.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Where this installation receives updates, from the installer that installed it. */
enum class UpdateChannel { PLAY, APK }

/** How a known newer release is installed. Only validated destinations are ever constructed. */
sealed interface UpdateDestination {
    /**
     * Google Play. [flexibleAllowed] is Play's FLEXIBLE eligibility at check time: true → in-app flow (which must
     * re-fetch fresh `AppUpdateInfo` before starting), false → external Play listing handoff.
     */
    data class Play(val flexibleAllowed: Boolean) : UpdateDestination

    /** The pinned, versioned APK asset URL that [ApkReleaseClient] validated; opened externally, never "latest". */
    data class Apk(val url: String) : UpdateDestination
}

/**
 * A newer release for this installation's channel. Play reports only the build number, so [versionName] is null
 * for Play: show "build [versionCode]" rather than inventing a marketing version.
 */
data class AvailableRelease(
    val versionCode: Int,
    val versionName: String?,
    val destination: UpdateDestination,
) {
    val channel: UpdateChannel
        get() = when (destination) {
            is UpdateDestination.Play -> UpdateChannel.PLAY
            is UpdateDestination.Apk -> UpdateChannel.APK
        }

    /** `play:<versionCode>` / `apk:<versionCode>`: the per-channel identity suppression is keyed on. */
    val id: String get() = "${if (channel == UpdateChannel.PLAY) "play" else "apk"}:$versionCode"
}

/** What is known about updates. Presentation is separate: see [UpdateNotice]. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AvailableRelease) : UpdateState

    /** A newer APK exists but needs Android API [minSdk] (only the APK channel reports minimum SDK). */
    data class RequiresOs(val versionCode: Int, val versionName: String, val minSdk: Int) : UpdateState

    /** The channel couldn't answer: unresolved installer, store/network error, malformed metadata or timeout. */
    data object Unavailable : UpdateState

    /** A Play flexible download; [progress] is null until Play knows the total size. */
    data class Downloading(
        val release: AvailableRelease,
        val bytesDownloaded: Long,
        val totalBytesToDownload: Long,
    ) : UpdateState {
        val progress: Float?
            get() = if (totalBytesToDownload > 0) {
                (bytesDownloaded.toFloat() / totalBytesToDownload).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    /** Play finished downloading; only an explicit restart completes the update. */
    data class Downloaded(val release: AvailableRelease) : UpdateState
    data class Installing(val release: AvailableRelease) : UpdateState
}

enum class UpdateProblem { OPEN, INSTALL }

/**
 * Something to present, separate from [AppUpdates.state] so dismissing it never discards an available or
 * downloaded update. [sequence] increases for every notice, so an identical manual recheck still produces a new
 * value. [manual] notices answer a Settings check (any outcome, suppression ignored); automatic ones only ever
 * carry [UpdateState.Available] or [UpdateState.Downloaded] and must wait for a safe foreground presentation.
 */
data class UpdateNotice(
    val sequence: Long,
    val manual: Boolean,
    val outcome: UpdateState,
    val problem: UpdateProblem? = null,
)

/** The parts of Play's `AppUpdateInfo` the checker uses; the info itself is never retained (consent needs fresh info). */
data class PlayUpdateStatus(
    val updateAvailability: Int,
    val availableVersionCode: Int,
    val installStatus: Int,
    val flexibleAllowed: Boolean,
    val bytesDownloaded: Long,
    val totalBytesToDownload: Long,
)

fun AppUpdateInfo.toPlayUpdateStatus() = PlayUpdateStatus(
    updateAvailability = updateAvailability(),
    availableVersionCode = availableVersionCode(),
    installStatus = installStatus(),
    flexibleAllowed = isUpdateTypeAllowed(AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()),
    bytesDownloaded = bytesDownloaded(),
    totalBytesToDownload = totalBytesToDownload(),
)

/**
 * Optional app updates for this installation's channel (one per process, `Deps.appUpdates`). Play installs ask Play,
 * which is authoritative: its errors are unavailable and never fall back to APKs. Other installs read the pinned
 * GitHub APK releases through a plain, unauthenticated [ApkReleaseClient].
 *
 * Automatic checks run on foreground at most once per 24 hours (the attempt is recorded before any query; a clock
 * moving backwards permits one new attempt) and never in DEBUG builds. Manual checks bypass the interval and notice
 * suppression. Concurrent checks share one query. Later, cancellation and external handoff suppress the same
 * release's automatic notice for 24 hours.
 *
 * Constructing it performs no query. Main-thread confined: call everything from the main thread (Play delivers
 * install-state callbacks there).
 */
class AppUpdates internal constructor(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val automaticChecks: Boolean,
    private val installedVersionCode: Int,
    private val sdkInt: Int,
    private val resolveChannel: () -> UpdateChannel,
    private val playManager: Lazy<AppUpdateManager>,
    private val apkLookup: suspend () -> ApkRelease,
    private val queryPlay: suspend () -> PlayUpdateStatus = {
        playManager.value.appUpdateInfo.await().toPlayUpdateStatus()
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val queryTimeoutMillis: Long = QUERY_TIMEOUT_MILLIS,
) {
    constructor(context: Context) : this(context.applicationContext, ApkReleaseClient())

    private constructor(app: Context, apkClient: ApkReleaseClient) : this(
        prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        scope = MainScope(),
        automaticChecks = !BuildConfig.DEBUG,
        installedVersionCode = BuildConfig.VERSION_CODE,
        sdkInt = Build.VERSION.SDK_INT,
        resolveChannel = { installChannel(app) },
        playManager = lazy { AppUpdateManagerFactory.create(app) },
        apkLookup = apkClient::latest,
    )

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<UpdateNotice?>(null)
    val notice: StateFlow<UpdateNotice?> = _notice.asStateFlow()

    /** True while a check query runs, even when [state] keeps showing a download in progress. */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** The one Play manager, shared with the activity-bound Play flow (consent launcher and install listener). */
    val appUpdateManager: AppUpdateManager get() = playManager.value

    private var sequence = 0L
    private var inFlight: Flight? = null

    private class Flight(var manual: Boolean) {
        lateinit var job: Job
    }

    /**
     * Checks the installation's channel. Automatic checks ([manual] false) are throttled and silent unless they find
     * an unsuppressed release; manual ones always produce a [notice]. Joins a check already running (upgrading it to
     * manual when needed). Cancelling the caller doesn't cancel the shared check.
     */
    suspend fun check(manual: Boolean) {
        if (!manual && !claimAutomaticAttempt()) return
        val flight = inFlight?.also { if (manual) it.manual = true } ?: launchCheck(manual)
        flight.job.join()
    }

    /**
     * One foreground query: a due availability check also restores Play install state. Between those checks,
     * probe the install state independently so an already downloaded update is never hidden by the interval.
     */
    suspend fun onForeground() {
        if (!automaticChecks) return
        val flight = inFlight
        when {
            flight != null -> flight.job.join()
            claimAutomaticAttempt() -> launchCheck(manual = false).job.join()
            else -> restoreInstallState()
        }
    }

    /**
     * Re-reads Play's install state so a download that progressed or finished while the app was away (or before
     * the activity was recreated) is shown. Not throttled; does nothing for APK installs; failures are silent.
     */
    suspend fun restoreInstallState() {
        if (inFlight != null) return
        val channel = try {
            resolveChannel()
        } catch (e: Exception) {
            AppLog.w(TAG, "updates: install channel unavailable", e)
            return
        }
        if (channel != UpdateChannel.PLAY) return
        val status = bounded("Play install state") { queryPlay() } ?: return
        if (inFlight != null) return // a check started meanwhile and owns the outcome
        val outcome = playOutcome(status)
        val current = _state.value
        when {
            outcome.inProgress -> {
                _state.value = outcome
                if (outcome is UpdateState.Downloaded && !isSuppressed(outcome.release.id)) {
                    automaticNotice(outcome)
                }
            }
            // The download ended elsewhere (cancelled from the notification, failed): show what Play reports now.
            current.inProgress -> _state.value = outcome
            else -> Unit // availability changes wait for the throttled automatic check
        }
    }

    /**
     * Later on the current notice (or OK on a result). Retains [state]: an available or downloaded release stays
     * available; its automatic notice is suppressed for 24 hours. With [sequence], only that notice is dismissed.
     */
    fun dismiss(sequence: Long? = null) {
        val current = _notice.value ?: return
        if (sequence != null && current.sequence != sequence) return
        _notice.value = null
        current.outcome.release?.let { suppress(it.id) }
    }

    /**
     * Re-evaluates freshly fetched Play info (the Play flow fetches it before starting consent) and returns the
     * outcome; [UpdateState.Available] with a FLEXIBLE-eligible [UpdateDestination.Play] may start the in-app flow.
     */
    fun revalidatePlay(status: PlayUpdateStatus): UpdateState {
        val outcome = playOutcome(status)
        if (!(outcome == UpdateState.Unavailable && _state.value.inProgress)) _state.value = outcome
        return outcome
    }

    /** Presents an explicitly requested action's fresh result without making a second store query. */
    fun showCurrentResult() {
        emitNotice(manual = true, outcome = _state.value)
    }

    /** The Play consent flow (or a store/APK handoff about to leave the app) took over: clear the notice. */
    fun onUpdateFlowStarted() {
        _notice.value = null
    }

    /** The user accepted Play's consent: the flexible download starts. */
    fun onUpdateFlowAccepted() {
        val release = playRelease() ?: return
        _notice.value = null
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Downloading(release, 0, 0)
    }

    /** The user cancelled consent or the download: the release stays available, its automatic notice suppressed. */
    fun onUpdateFlowCancelled() {
        val release = _state.value.release ?: return
        _notice.value = null
        suppress(release.id)
        _state.value = UpdateState.Available(release)
    }

    /**
     * Starting or finishing the update failed. A failed restart returns to downloaded; anything else returns to
     * available, so the user can retry. Nothing is suppressed.
     */
    fun onUpdateFailed() {
        val current = _state.value
        val release = current.release
        if (release != null) {
            _state.value = if (current is UpdateState.Installing) {
                UpdateState.Downloaded(release)
            } else {
                UpdateState.Available(release)
            }
        }
        emitNotice(manual = true, outcome = UpdateState.Unavailable, problem = UpdateProblem.INSTALL)
    }

    /** The release was handed to the browser (APK) or the Play listing: suppress its automatic notice. */
    fun onExternalUpdateStarted() {
        val release = _notice.value?.outcome?.release ?: _state.value.release ?: return
        _notice.value = null
        suppress(release.id)
    }

    fun onExternalOpenFailed() {
        emitNotice(manual = true, outcome = UpdateState.Unavailable, problem = UpdateProblem.OPEN)
    }

    /**
     * A Play `InstallState` from the Play flow's listener. Ignored until a Play release is known
     * ([restoreInstallState] supplies it after recreation).
     */
    fun onInstallState(installStatus: Int, bytesDownloaded: Long, totalBytesToDownload: Long) {
        val current = _state.value
        val release = playRelease() ?: return
        when (installStatus) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING ->
                _state.value = UpdateState.Downloading(release, bytesDownloaded, totalBytesToDownload)
            InstallStatus.DOWNLOADED -> if (current !is UpdateState.Downloaded) {
                val downloaded = UpdateState.Downloaded(release)
                _state.value = downloaded
                // The user asked for this download in this session: announce it regardless of suppression.
                emitNotice(manual = false, outcome = downloaded)
            }
            InstallStatus.INSTALLING -> _state.value = UpdateState.Installing(release)
            InstallStatus.FAILED -> onUpdateFailed()
            InstallStatus.CANCELED -> onUpdateFlowCancelled()
            InstallStatus.INSTALLED -> {
                _state.value = UpdateState.UpToDate
                _notice.value = null
            }
            else -> Unit
        }
    }

    /** Explicit "Restart to update": true when a downloaded update may now be completed. */
    fun markInstalling(): Boolean {
        val current = _state.value as? UpdateState.Downloaded ?: return false
        _notice.value = null
        _state.value = UpdateState.Installing(current.release)
        return true
    }

    private fun launchCheck(manual: Boolean): Flight {
        val flight = Flight(manual)
        inFlight = flight
        _checking.value = true
        if (!_state.value.inProgress) _state.value = UpdateState.Checking
        flight.job = scope.launch {
            try {
                publish(resolve(), flight.manual)
            } finally {
                if (inFlight === flight) inFlight = null
                _checking.value = false
                if (_state.value == UpdateState.Checking) _state.value = UpdateState.Idle // cancelled before a result
            }
        }
        return flight
    }

    private suspend fun resolve(): UpdateState {
        val channel = try {
            resolveChannel()
        } catch (e: Exception) {
            AppLog.w(TAG, "updates: install channel unavailable", e)
            return UpdateState.Unavailable
        }
        return when (channel) {
            UpdateChannel.PLAY -> bounded("Play update info") { queryPlay() }?.let(::playOutcome)
            UpdateChannel.APK -> bounded("APK release") { apkLookup() }?.let(::apkOutcome)
        } ?: UpdateState.Unavailable
    }

    private fun publish(outcome: UpdateState, manual: Boolean) {
        val current = _state.value
        // A failed query doesn't erase a download Play already reported.
        _state.value = if (outcome == UpdateState.Unavailable && current.inProgress) current else outcome
        val release = outcome.release
        when {
            manual -> emitNotice(manual = true, outcome = outcome)
            (outcome is UpdateState.Available || outcome is UpdateState.Downloaded) &&
                release != null && !isSuppressed(release.id) -> automaticNotice(outcome)
        }
    }

    /** Play is authoritative: only a strictly higher code is offered; UNKNOWN or inconsistent info is unavailable. */
    private fun playOutcome(status: PlayUpdateStatus): UpdateState {
        val newer = status.availableVersionCode > installedVersionCode
        val release = AvailableRelease(
            versionCode = status.availableVersionCode,
            versionName = null,
            destination = UpdateDestination.Play(status.flexibleAllowed),
        )
        if (newer) {
            when (status.installStatus) {
                InstallStatus.DOWNLOADED -> return UpdateState.Downloaded(release)
                InstallStatus.PENDING, InstallStatus.DOWNLOADING ->
                    return UpdateState.Downloading(release, status.bytesDownloaded, status.totalBytesToDownload)
                InstallStatus.INSTALLING -> return UpdateState.Installing(release)
            }
        }
        return when (status.updateAvailability) {
            UpdateAvailability.UPDATE_AVAILABLE -> if (newer) UpdateState.Available(release) else UpdateState.Unavailable
            UpdateAvailability.UPDATE_NOT_AVAILABLE -> UpdateState.UpToDate
            else -> UpdateState.Unavailable
        }
    }

    private fun apkOutcome(release: ApkRelease): UpdateState = when {
        release.versionCode <= installedVersionCode -> UpdateState.UpToDate
        release.minSdk > sdkInt -> UpdateState.RequiresOs(release.versionCode, release.versionName, release.minSdk)
        else -> UpdateState.Available(
            AvailableRelease(release.versionCode, release.versionName, UpdateDestination.Apk(release.apkUrl)),
        )
    }

    /** [block] within the 15-second query timeout; null on failure or timeout. */
    private suspend fun <T : Any> bounded(what: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(queryTimeoutMillis) { block() }
            .also { if (it == null) AppLog.w(TAG, "updates: $what timed out") }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive() // our own cancellation propagates; a stray one is a failure
        AppLog.w(TAG, "updates: $what cancelled", e)
        null
    } catch (e: Exception) {
        AppLog.w(TAG, "updates: $what failed", e)
        null
    }

    private fun playRelease(): AvailableRelease? = _state.value.release?.takeIf { it.channel == UpdateChannel.PLAY }

    /** An automatic notice, unless the same outcome is already waiting to be presented. */
    private fun automaticNotice(outcome: UpdateState) {
        val pending = _notice.value
        if (pending?.manual == true) return
        if (pending != null && !pending.manual && pending.outcome == outcome) return
        emitNotice(manual = false, outcome = outcome)
    }

    private fun emitNotice(manual: Boolean, outcome: UpdateState, problem: UpdateProblem? = null) {
        _notice.value = UpdateNotice(++sequence, manual, outcome, problem)
    }

    /** Records this automatic attempt before any query; false when DEBUG-disabled or within 24 hours of the last. */
    private fun claimAutomaticAttempt(): Boolean {
        if (!automaticChecks) return false
        val time = now()
        if (prefs.contains(KEY_LAST_AUTOMATIC_ATTEMPT)) {
            val last = prefs.getLong(KEY_LAST_AUTOMATIC_ATTEMPT, 0)
            // A clock behind the last attempt permits this one, which then becomes the new reference.
            if (time >= last && time - last < INTERVAL_MILLIS) return false
        }
        prefs.edit().putLong(KEY_LAST_AUTOMATIC_ATTEMPT, time).apply()
        return true
    }

    private fun suppress(releaseId: String) {
        prefs.edit()
            .putString(KEY_LAST_OFFERED_RELEASE, releaseId)
            .putLong(KEY_LAST_OFFERED_AT, now())
            .apply()
    }

    /** Within 24 hours of Later/cancel/handoff for [releaseId]; a clock moved behind that time ends suppression. */
    private fun isSuppressed(releaseId: String): Boolean {
        if (prefs.getString(KEY_LAST_OFFERED_RELEASE, null) != releaseId) return false
        if (!prefs.contains(KEY_LAST_OFFERED_AT)) return false
        val at = prefs.getLong(KEY_LAST_OFFERED_AT, 0)
        val time = now()
        return time >= at && time - at < INTERVAL_MILLIS
    }

    /** Installer metadata only; the activity flow uses this to avoid binding Play for APK installs. */
    internal fun channelForFlow(): UpdateChannel = resolveChannel()

    companion object {
        const val FILE = "app_updates"
        const val KEY_LAST_AUTOMATIC_ATTEMPT = "lastAutomaticAttempt"
        const val KEY_LAST_OFFERED_RELEASE = "lastOfferedRelease"
        const val KEY_LAST_OFFERED_AT = "lastOfferedAt"
        const val INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val QUERY_TIMEOUT_MILLIS = 15_000L
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val TAG = "FiberIRC.updates"
    }
}

private val UpdateState.inProgress: Boolean
    get() = this is UpdateState.Downloading || this is UpdateState.Downloaded || this is UpdateState.Installing

private val UpdateState.release: AvailableRelease?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Downloaded -> release
        is UpdateState.Installing -> release
        else -> null
    }

/**
 * Play when `com.android.vending` installed this package, otherwise APK. Lookup errors propagate (→ unavailable),
 * never a channel switch.
 */
internal fun installChannel(context: Context): UpdateChannel {
    val packageManager = context.packageManager
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION") packageManager.getInstallerPackageName(context.packageName)
    }
    return if (installer == AppUpdates.PLAY_STORE_PACKAGE) UpdateChannel.PLAY else UpdateChannel.APK
}

/** Awaits a Play [Task] without a main-looper hop; failures and cancellation throw. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener(Executor(Runnable::run)) { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.resumeWithException(IllegalStateException("Play task cancelled"))
            else -> cont.resume(task.result)
        }
    }
}
