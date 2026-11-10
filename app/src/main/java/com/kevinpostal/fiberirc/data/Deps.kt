package com.kevinpostal.fiberirc.data

import android.app.Application
import androidx.annotation.MainThread
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.CookieJarStore
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.fiberIrcHttpClient
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.data.e2ee.E2eePasskeys
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcConnectionService
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.irc.IrcSessionState
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.ArchiveListStore
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.data.prefs.DeviceIdentity
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.data.prefs.DisplaySettingsStore
import com.kevinpostal.fiberirc.data.prefs.NetworkLayoutStore
import com.kevinpostal.fiberirc.data.prefs.NotificationPolicyStore
import com.kevinpostal.fiberirc.data.prefs.NotificationPrefsStore
import com.kevinpostal.fiberirc.data.prefs.PinListStore
import com.kevinpostal.fiberirc.data.prefs.ShareTargetsStore
import com.kevinpostal.fiberirc.data.push.ConversationShortcuts
import com.kevinpostal.fiberirc.data.push.PushRegistrar
import com.kevinpostal.fiberirc.data.update.AppUpdates
import com.kevinpostal.fiberirc.data.update.PlayUpdateFlow
import com.kevinpostal.fiberirc.data.voice.VoiceRoomOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * The process's object graph (manual DI, as vanlife's `Deps`): one cookie-carrying [OkHttpClient] every
 * ircfiber.com call shares, the [SiteApi] over it, this install's [DeviceIdentity], every settings store, push and
 * conversation shortcuts. Built once by [com.kevinpostal.fiberirc.FiberIrcApp]; activities and services reach it
 * through `application as FiberIrcApp`. Main-thread confined like the stores it holds.
 * Update metadata uses its own unauthenticated client, never [httpClient].
 *
 * The signed-in IRC [session] belongs to the process, not an activity: recreating or reopening
 * [com.kevinpostal.fiberirc.MainActivity] shows the same connections, and [IrcConnectionService] keeps them out of
 * the cached-app freezer meanwhile. A cold start begins at sign-in; nothing here signs in by itself.
 */
class Deps(private val app: Application, val apiBaseUrl: String = BuildConfig.API_BASE_URL) {
    private val sessionScope = MainScope()
    val deviceIdentity = DeviceIdentity(app)
    val cookieJar = CookieJarStore(app)
    val httpClient: OkHttpClient = fiberIrcHttpClient(cookieJar, apiBaseUrl) { deviceIdentity.id }
    val api = SiteApi(httpClient, apiBaseUrl)
    val media = MediaApi(httpClient, api)
    val voice = VoiceRoomOwner(app, api, httpClient, apiBaseUrl, { deviceIdentity.id })
    init { api.beforeCredentialLoss = {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { voice.stopBeforeCredentialLoss() }
    } }

    val accountSettings = AccountSettingsStore(app, api)
    val deviceSettings = DeviceSettingsStore(app)
    val displaySettings = DisplaySettingsStore(app)
    val notificationPrefs = NotificationPrefsStore(app, api)
    val notificationPolicy = NotificationPolicyStore(app, api)
    val pinList = PinListStore(app, api)
    val archiveList = ArchiveListStore(app, api)
    val blockList = BlockListStore(api)
    val networkLayout = NetworkLayoutStore(app, api)
    val bufferPrefs = BufferPrefsStore(app, api)

    val accountStores = AccountStores(
        accountSettings = accountSettings,
        notificationPrefs = notificationPrefs,
        notificationPolicy = notificationPolicy,
        pinList = pinList,
        archiveList = archiveList,
        blockList = blockList,
        networkLayout = networkLayout,
        bufferPrefs = bufferPrefs,
    )

    val push = PushRegistrar(app, api)
    val conversationShortcuts = ConversationShortcuts(app)
    val shareTargets = ShareTargetsStore(app, MainScope()) { pinList.keys.value }.also {
        it.onPublished = conversationShortcuts::publish
    }

    /** Native PRF passkeys for encryption backup/unlock; the root activity attaches to present the system sheets. */
    val passkeys = E2eePasskeys(app, apiBaseUrl)

    val appUpdates = AppUpdates(app)
    val playUpdateFlow = PlayUpdateFlow(appUpdates)

    private val _session = MutableStateFlow<IrcSessionState>(IrcSessionState.Login(null, 0))

    /** Sign-in or the signed-in session's connections (main thread). */
    val session: StateFlow<IrcSessionState> = _session.asStateFlow()
    private var loginGeneration = 0

    /**
     * Signed in (user-visible sign-in only): one session's connections and its account's encryption service, kept
     * alive by the foreground [IrcConnectionService]. A no-op while a live session exists, so reopening the
     * activity never opens a second set of sockets. When Android refuses the foreground service the connections
     * never start and sign-in shows why.
     */
    @MainThread
    fun startSession(start: SessionStart) {
        if ((_session.value as? IrcSessionState.Session)?.manager?.isTornDown == false) return
        lateinit var manager: IrcSessionManager
        // Only the current session may leave for sign-in; a replaced one's late callback is ignored.
        val leave: (String) -> Unit = { notice ->
            if ((_session.value as? IrcSessionState.Session)?.manager === manager) recoverIrc(manager, notice)
        }
        manager = IrcSessionManager(app, start.connections, api, { deviceIdentity.id }, leave) { m ->
            E2eeService(
                app, api, httpClient, { deviceIdentity.id }, m, bufferPrefs,
                passkeys = passkeys.takeIf { it.supported },
            )
        }
        voice.bind(start.account?.id)
        _session.value = IrcSessionState.Session(manager, start.account)
        try {
            IrcConnectionService.start(app)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException / SecurityException: unkept sockets would be frozen.
            AppLog.e(TAG, "connection service refused to start", e)
            stopConnections("Android didn't allow IRC Fiber to stay connected — open the app and sign in again.")
            return
        }
        manager.start()
    }

    /**
     * Sign-out, account deletion or rejected/expired credentials: disconnects every client, shows sign-in with
     * [notice] (empty: none) and ends the foreground service.
     */
    @MainThread
    fun showLogin(notice: String) = endSession(notice, resumeStoredSession = true)

    /** IRC service loss pauses text connections, not the authenticated account or independent audio. */
    @MainThread
    fun stopConnections(notice: String) {
        // Losing IRC foreground ownership is not account loss and cannot end an ongoing voice call.
        (_session.value as? IrcSessionState.Session)?.manager?.pauseConnections()
        AppLog.w(TAG, notice)
    }

    /** Only a website 401/account mismatch ends voice; an IRC token rotation replaces IRC alone. */
    private fun recoverIrc(manager: IrcSessionManager, notice: String) {
        sessionScope.launch {
            val old = _session.value as? IrcSessionState.Session ?: return@launch
            if (old.manager !== manager) return@launch
            val result = api.me()
            if ((_session.value as? IrcSessionState.Session)?.manager !== manager) return@launch
            val me = result.getOrNull()
            if (me == null) {
                if (result.exceptionOrNull() is com.kevinpostal.fiberirc.data.api.SiteApiError.Unauthorized) showLogin(notice)
                return@launch
            }
            if (me.id != old.account?.id) { showLogin("Account changed — sign in again."); return@launch }
            val bouncer = com.kevinpostal.fiberirc.data.api.SessionBootstrap.bouncerSettings(me, api, deviceIdentity.id).getOrNull() ?: return@launch
            val settings = if (bouncer.isNotEmpty() || manager.clients.value.all { it.settings.viaBouncer }) bouncer
                else listOf(ConnectionSettings.forAccount(me.username,
                    com.kevinpostal.fiberirc.data.api.SessionBootstrap.ircAccountWaitingForReady(api, attempts = 1).getOrNull()))
            if ((_session.value as? IrcSessionState.Session)?.manager !== manager) return@launch
            manager.stop()
            startSession(SessionStart(me, settings))
        }
    }

    fun resumeIrcConnections() {
        val manager = (_session.value as? IrcSessionState.Session)?.manager ?: return
        if (!manager.connectionsPaused) return
        try { IrcConnectionService.start(app); manager.resumeConnections() }
        catch (e: RuntimeException) { AppLog.e(TAG, "IRC service restart refused", e) }
    }

    private fun endSession(notice: String, resumeStoredSession: Boolean) {
        voice.unbind()
        (_session.value as? IrcSessionState.Session)?.manager?.stop()
        _session.value = IrcSessionState.Login(notice.ifEmpty { null }, ++loginGeneration, resumeStoredSession)
    }

    private companion object {
        const val TAG = "FiberIRC.session"
    }
}
