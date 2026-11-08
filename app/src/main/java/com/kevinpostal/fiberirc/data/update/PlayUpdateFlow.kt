package com.kevinpostal.fiberirc.data.update

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Activity plumbing only: availability and retained download/notice state belong to [AppUpdates]. */
class PlayUpdateFlow(private val updates: AppUpdates) {
    private val scope = MainScope()
    private var activity = WeakReference<ComponentActivity>(null)
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var observer: DefaultLifecycleObserver? = null
    private var listener: InstallStateUpdatedListener? = null
    private var listeningManager: AppUpdateManager? = null
    private var operation: Job? = null

    /** Called in onCreate, before STARTED. Old activity references and listeners are released on destruction. */
    fun attach(target: ComponentActivity) {
        if (activity.get() === target) return
        detach()
        activity = WeakReference(target)
        launcher = target.activityResultRegistry.register(
            RESULT_KEY, target, ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> updates.onUpdateFlowAccepted()
                Activity.RESULT_CANCELED -> updates.onUpdateFlowCancelled()
                else -> updates.onUpdateFailed()
            }
        }
        observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (activity.get() === owner) detach()
            }
        }.also(target.lifecycle::addObserver)
        // APK installations don't need a Play binding or install-state receiver.
        val play = runCatching { updates.channelForFlow() == UpdateChannel.PLAY }.getOrDefault(false)
        if (play) registerListener()
    }

    /** Only an explicit Update action calls this; every attempt gets fresh, unconsumed AppUpdateInfo. */
    fun start() {
        if (operation?.isActive == true) return
        updates.onUpdateFlowStarted()
        operation = scope.launch {
            try {
                val manager = updates.appUpdateManager
                val info = withTimeout(AppUpdates.QUERY_TIMEOUT_MILLIS) { manager.appUpdateInfo.await() }
                val outcome = updates.revalidatePlay(info.toPlayUpdateStatus())
                // Resolve the current activity only after the query; never keep an activity across the fetch.
                val target = activity.get()
                val resultLauncher = launcher
                if (target == null || resultLauncher == null ||
                    !target.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    updates.showCurrentResult()
                    return@launch
                }
                val destination = (outcome as? UpdateState.Available)?.release?.destination as? UpdateDestination.Play
                when {
                    destination == null -> updates.showCurrentResult()
                    !destination.flexibleAllowed -> openListing(target)
                    !registerListener() -> updates.onUpdateFailed()
                    else -> {
                        val started = manager.startUpdateFlowForResult(
                            info, resultLauncher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        )
                        if (!started) updates.onUpdateFailed()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.w(TAG, "updates: Play update query timed out", e)
                updates.onUpdateFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "updates: Play update could not start", e)
                updates.onUpdateFailed()
            } finally {
                if (operation === coroutineContext[Job]) operation = null
            }
        }
    }

    /** Only an explicit Restart to update action may complete a flexible update. */
    fun complete() {
        if (operation?.isActive == true || !updates.markInstalling()) return
        operation = scope.launch {
            try {
                if (!registerListener()) {
                    updates.onUpdateFailed()
                    return@launch
                }
                withTimeout(AppUpdates.QUERY_TIMEOUT_MILLIS) { updates.appUpdateManager.completeUpdate().await() }
            } catch (e: TimeoutCancellationException) {
                AppLog.w(TAG, "updates: Play update completion timed out", e)
                updates.onUpdateFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "updates: Play update could not complete", e)
                updates.onUpdateFailed()
            } finally {
                if (operation === coroutineContext[Job]) operation = null
            }
        }
    }

    /** Known Play updates without flexible eligibility stay on Play; never fall back to an APK. */
    fun openListing(context: Context) {
        updates.onExternalUpdateStarted()
        if (!LinkOpener.openExternally(context, PLAY_URI) && !LinkOpener.openExternally(context, PLAY_WEB_URI)) {
            updates.onExternalOpenFailed()
        }
    }

    private fun registerListener(): Boolean {
        if (listener != null) return true
        return try {
            val manager = updates.appUpdateManager
            val next = InstallStateUpdatedListener { state ->
                updates.onInstallState(state.installStatus(), state.bytesDownloaded(), state.totalBytesToDownload())
            }
            manager.registerListener(next)
            listeningManager = manager
            listener = next
            true
        } catch (e: Exception) {
            // An automatic attach must not show an error. An explicit action reports a failed registration.
            AppLog.w(TAG, "updates: Play install listener unavailable", e)
            false
        }
    }

    private fun detach() {
        operation?.cancel()
        operation = null
        val old = activity.get()
        observer?.let { old?.lifecycle?.removeObserver(it) }
        observer = null
        listener?.let { previous ->
            runCatching { listeningManager?.unregisterListener(previous) }
                .onFailure { AppLog.w(TAG, "updates: Play install listener could not detach", it) }
        }
        listener = null
        listeningManager = null
        launcher?.unregister()
        launcher = null
        activity.clear()
    }

    companion object {
        internal const val RESULT_KEY = "fiberirc.appUpdates"
        const val PLAY_URI = "market://details?id=com.kevinpostal.fiberirc"
        const val PLAY_WEB_URI = "https://play.google.com/store/apps/details?id=com.kevinpostal.fiberirc"
        private const val TAG = "FiberIRC.updates"
    }
}
