package com.kevinpostal.fiberirc.data.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.data.e2ee.E2eeExternalGate
import com.kevinpostal.fiberirc.data.e2ee.E2eeExternalSend
import com.kevinpostal.fiberirc.ui.shell.BufferKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * This install's push notifications from ircfiber.com (site push worker, FCM), ported from `PushRegistrar.swift`:
 * the notification channels, the one-time permission request after sign-in, the FCM token registered with the
 * account every time a session is shown while notifications are allowed (and unregistered on sign-out), and the
 * server's badge count reset whenever the app comes to the foreground. Without Firebase ([FirebaseInit] skipped for
 * a blank `fiberirc.fcmApiKey`) nothing is asked or registered.
 *
 * One instance per process, built in `FiberIrcApp.onCreate` and published through [PushRegistrarHolder] for
 * [FiberIrcMessagingService] and [PushReplyReceiver].
 */
class PushRegistrar(context: Context, private val api: SiteApi) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val external = E2eeExternalSend(this.context, api)

    @Volatile
    private var foreground = false
    private var asking = false

    /**
     * The buffer the chat shows (`ShellController.shownBuffer`); a push for it is not shown while the app is in the
     * foreground (iOS `willPresent`). Read on the FCM thread.
     */
    @Volatile
    var shownBuffer: () -> BufferKey? = { null }

    /** App launch (main thread): registers the notification channels; each foregrounding resets the badge count. */
    fun start() {
        PushNotifications.registerChannels(context)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        foreground = true
                        didBecomeActive()
                    }
                    Lifecycle.Event.ON_STOP -> foreground = false
                    else -> Unit
                }
            },
        )
    }

    /**
     * A session was shown: asks for `POST_NOTIFICATIONS` (API 33+) the first time, from [activity], then registers
     * this install's FCM token whenever notifications are allowed. Null [activity] skips the question this time.
     */
    fun sessionStarted(activity: ComponentActivity?) {
        if (!FirebaseInit.started(context)) return
        scope.launch {
            if (!prefs.getBoolean(KEY_ASKED, false)) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissionGranted()) {
                    prefs.edit { putBoolean(KEY_ASKED, true) }
                } else if (activity != null && !asking) {
                    asking = true
                    try {
                        requestPermission(activity)
                    } finally {
                        asking = false
                    }
                    prefs.edit { putBoolean(KEY_ASKED, true) }
                }
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@launch
            val token = fetchToken() ?: return@launch
            register(token)
        }
    }

    /** FCM answered with this install's token: the account sends its pushes here. */
    fun registerToken(token: String) {
        scope.launch { register(token) }
    }

    /**
     * `FiberIrcMessagingService.onNewToken`: FCM rotated the token. Registered again only while this install is
     * registered (signed in with notifications allowed); otherwise the next [sessionStarted] registers it.
     */
    fun tokenRefreshed(token: String) {
        if (prefs.getString(KEY_TOKEN, null) != null) registerToken(token)
    }

    /** Signing out or deleting the account: the account stops pushing to this install. */
    suspend fun unregister() {
        val token = prefs.getString(KEY_TOKEN, null) ?: return
        prefs.edit { remove(KEY_TOKEN) }
        api.deletePath(listOf("api", "me", "push-devices", token))
            .onFailure { AppLog.w(TAG, "push: unregister failed", it) }
    }

    /** A notification Reply's result: posted as plaintext, kept as a sealed pending draft, or neither. */
    sealed interface ReplyOutcome {
        data object Sent : ReplyOutcome

        /** Not sendable as plaintext: sealed as draft [draftId] for an explicit Send in the app. */
        data class Saved(val draftId: String) : ReplyOutcome

        data class Failed(val error: Throwable) : ReplyOutcome
    }

    /**
     * A notification's Reply: `POST /api/networks/<networkId>/messages` (the server sends [text] to [target] as the
     * account, as a reply to [replyTo] when given) only when [E2eeExternalSend.check] finds the target positively
     * unencrypted; otherwise the text is sealed as a pending draft and never sent from here.
     */
    suspend fun reply(networkId: String, target: String, text: String, replyTo: String?): ReplyOutcome =
        when (val gate = external.check(networkId, target)) {
            is E2eeExternalGate.Plaintext -> external.postPlaintext(gate, text, replyTo)
                .fold({ ReplyOutcome.Sent }, { ReplyOutcome.Failed(it) })
            is E2eeExternalGate.ContinueInApp -> {
                AppLog.i(TAG, "push: reply continues in the app (${gate.reason})")
                val account = gate.account
                if (account == null) {
                    ReplyOutcome.Failed(IllegalStateException("no signed-in account to keep the reply for"))
                } else {
                    try {
                        ReplyOutcome.Saved(external.saveDraft(account, networkId, target, text, replyTo).id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ReplyOutcome.Failed(e)
                    }
                }
            }
        }

    /** A push for [p] would land in the buffer the user is reading. */
    fun isShowing(p: PushPayload): Boolean =
        foreground && shownBuffer()?.matches(BufferKey(p.networkId, p.buffer)) == true

    private suspend fun register(token: String) {
        prefs.edit { putString(KEY_TOKEN, token) }
        val body = buildJsonObject {
            put("token", token)
            put("environment", if (BuildConfig.DEBUG) "sandbox" else "production")
            put("bundleId", BuildConfig.APPLICATION_ID)
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("platform", "android")
        }
        api.postJson("api/me/push-devices", body).onFailure {
            // 404: a server without push support (iOS treats it as done too).
            if (!(it is SiteApiError.Server && it.code == 404)) AppLog.w(TAG, "push: register failed", it)
        }
    }

    /** Opening the app reads everything the badge counted: the server restarts its count. */
    private fun didBecomeActive() {
        if (prefs.getString(KEY_TOKEN, null) == null) return
        scope.launch {
            api.request("POST", listOf("api", "me", "push-badge", "reset"))
                .onFailure { AppLog.w(TAG, "push: badge reset failed", it) }
        }
    }

    private fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Asks for `POST_NOTIFICATIONS` (API 33+); false when denied or [activity] went away before the answer. */
    private suspend fun requestPermission(activity: ComponentActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return suspendCancellableCoroutine { cont ->
            var observer: LifecycleEventObserver? = null
            val launcher = activity.activityResultRegistry.register(
                PERMISSION_KEY,
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                observer?.let(activity.lifecycle::removeObserver)
                if (cont.isActive) cont.resume(granted)
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    launcher.unregister()
                    if (cont.isActive) cont.resume(false)
                }
            }.also(activity.lifecycle::addObserver)
            cont.invokeOnCancellation {
                scope.launch {
                    observer?.let(activity.lifecycle::removeObserver)
                    launcher.unregister()
                }
            }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.also { AppLog.i(TAG, "push: notification permission ${if (it) "granted" else "not granted"}") }
    }

    @Suppress("DEPRECATION") // registration tokens, which the backend's FCM v1 sender targets (vanlife's FcmPushRegistry)
    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) AppLog.w(TAG, "push: FCM token unavailable", task.exception)
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    companion object {
        private const val TAG = "FiberIRC.push"
        private const val PREFS = "fiberirc_push"
        private const val PERMISSION_KEY = "fiberirc.push.permission"
        const val KEY_ASKED = "push_asked"
        const val KEY_TOKEN = "push_token"
    }
}

/**
 * The process's [PushRegistrar] for entry points the system constructs ([FiberIrcMessagingService],
 * [PushReplyReceiver]): set once in `FiberIrcApp.onCreate`, which always runs before either.
 */
object PushRegistrarHolder {
    @Volatile
    var instance: PushRegistrar? = null
}
