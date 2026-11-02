package com.kevinpostal.fiberirc.data

import android.app.Application
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.CookieJarStore
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.fiberIrcHttpClient
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
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
import kotlinx.coroutines.MainScope
import okhttp3.OkHttpClient

/**
 * The process's object graph (manual DI, as vanlife's `Deps`): one cookie-carrying [OkHttpClient] every
 * ircfiber.com call shares, the [SiteApi] over it, this install's [DeviceIdentity], every settings store, push and
 * conversation shortcuts. Built once by [com.kevinpostal.fiberirc.FiberIrcApp]; activities and services reach it
 * through `application as FiberIrcApp`. Main-thread confined like the stores it holds.
 *
 * IRC connections are per sign-in (they need the login's [ConnectionSettings]), so the graph hands out
 * [sessionManager]s instead of holding one.
 */
class Deps(private val app: Application, val apiBaseUrl: String = BuildConfig.API_BASE_URL) {
    val deviceIdentity = DeviceIdentity(app)
    val cookieJar = CookieJarStore(app)
    val httpClient: OkHttpClient = fiberIrcHttpClient(cookieJar) { deviceIdentity.id }
    val api = SiteApi(httpClient, apiBaseUrl)
    val media = MediaApi(httpClient, api)

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

    /** A signed-in session's connections; [showLogin] leaves for the sign-in screen (rejected credentials). */
    fun sessionManager(settings: List<ConnectionSettings>, showLogin: (notice: String) -> Unit): IrcSessionManager =
        IrcSessionManager(app, settings, api, { deviceIdentity.id }, showLogin)
}
