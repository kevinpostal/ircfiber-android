package com.kevinpostal.fiberirc.ui.shell

import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.data.irc.InlineMedia
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.irc.MediaItem
import com.kevinpostal.fiberirc.data.irc.MediaLinks
import com.kevinpostal.fiberirc.data.media.MediaNetwork
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.ui.chat.BufferSearchScreen
import com.kevinpostal.fiberirc.ui.chat.ChatLink
import com.kevinpostal.fiberirc.ui.management.ChannelInfoScreen
import com.kevinpostal.fiberirc.ui.management.ChannelListScreen
import com.kevinpostal.fiberirc.ui.management.ChannelModeListScreen
import com.kevinpostal.fiberirc.ui.management.ConversationCleanupScreen
import com.kevinpostal.fiberirc.ui.management.DisplayOptionsScreen
import com.kevinpostal.fiberirc.ui.management.NetworkEditorScreen
import com.kevinpostal.fiberirc.ui.management.ReorderScreen
import com.kevinpostal.fiberirc.ui.management.WhoisScreen
import com.kevinpostal.fiberirc.ui.media.ImageViewerScreen
import com.kevinpostal.fiberirc.ui.media.InlineMediaCard
import com.kevinpostal.fiberirc.ui.media.MediaTap
import com.kevinpostal.fiberirc.ui.media.mediaTap
import com.kevinpostal.fiberirc.ui.settings.BlockedUsersScreen
import com.kevinpostal.fiberirc.ui.settings.SettingsFlow

/** A screen pushed over the shell (iOS pushes or presents these over the session). */
private sealed interface SessionRoute {
    data object Settings : SessionRoute
    data class Network(val info: NetworkInfo?, val prefill: NetworkPrefill?) : SessionRoute
    data class BrowseChannels(val client: IrcClient) : SessionRoute
    data class Whois(val client: IrcClient, val nick: String) : SessionRoute
    data class DisplayOptions(val client: IrcClient, val buffer: IrcBuffer) : SessionRoute
    data object ConversationCleanup : SessionRoute
    data class ChannelInfo(val client: IrcClient, val buffer: IrcBuffer) : SessionRoute
    class Reorder(val title: String, val items: List<Pair<String, String>>, val save: suspend (List<String>) -> Unit) :
        SessionRoute
    data class BanList(val client: IrcClient, val buffer: IrcBuffer) : SessionRoute
    data class Search(val client: IrcClient, val buffer: IrcBuffer) : SessionRoute
    data object BlockedUsers : SessionRoute
}

/**
 * The signed-in session (ports `SessionViewController`'s ownership of the chat and the screens it presents): the
 * [ShellScreen] over [manager], with the chat's composer and inline media supplied through [ChatSlots], every
 * [ShellNavigation] screen pushed over it (Back pops), and this install's push registration refreshed on each resume.
 * Leaving (sign-out, rejected credentials) is [sessionActions]' and the activity's job: they stop [manager].
 */
@Composable
fun SessionHost(deps: Deps, manager: IrcSessionManager, sessionActions: SessionActions) {
    val context = LocalContext.current
    val routes = remember(manager) { mutableStateListOf<SessionRoute>() }
    var viewer by remember(manager) { mutableStateOf<MediaTap.Viewer?>(null) }

    val navigation = remember(manager) {
        ShellNavigation(
            openSettings = { routes += SessionRoute.Settings },
            addNetwork = { prefill -> routes += SessionRoute.Network(null, prefill) },
            editNetwork = { info -> routes += SessionRoute.Network(info, null) },
            browseChannels = { client -> routes += SessionRoute.BrowseChannels(client) },
            whois = { client, nick -> routes += SessionRoute.Whois(client, nick) },
            displayOptions = { client, buffer -> routes += SessionRoute.DisplayOptions(client, buffer) },
            conversationCleanup = { routes += SessionRoute.ConversationCleanup },
            channelInfo = { client, buffer -> routes += SessionRoute.ChannelInfo(client, buffer) },
            reorder = { title, items, save -> routes += SessionRoute.Reorder(title, items, save) },
        )
    }
    val controller = rememberShellController(
        manager, deps.api, deps.accountStores, deps.deviceSettings, deps.shareTargets, navigation,
    )

    // Pushes for the buffer on screen stay quiet; each resume (the first included) registers this install's token.
    val activity = context as? ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        deps.push.shownBuffer = { controller.shownBuffer }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deps.push.sessionStarted(activity)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            deps.push.shownBuffer = { null }
        }
    }

    val metered by remember(context) { MediaNetwork.get(context).isMetered }.collectAsState()
    val device by deps.deviceSettings.settings.collectAsState()
    val mediaLinks = remember(manager) { LruCache<String, List<MediaItem>>(MEDIA_CACHE_SIZE) }
    // The site the session's API talks to: inline previews' image proxy, snippet text and "is this a site upload".
    val siteBase = deps.apiBaseUrl

    val slots = ChatSlots(
        composer = { shown, thread ->
            BufferComposer(
                shown = shown,
                thread = thread,
                controller = controller,
                deps = deps,
                actions = BufferComposerActions(
                    banList = { c, b -> routes += SessionRoute.BanList(c, b) },
                    search = { c, b -> routes += SessionRoute.Search(c, b) },
                    ignoreList = { routes += SessionRoute.BlockedUsers },
                    signOut = sessionActions::signOut,
                ),
            )
        },
        media = { client, buffer, line ->
            val links = mediaLinks.get(line.text) ?: MediaLinks.items(line.text, siteBase).also { mediaLinks.put(line.text, it) }
            val policy = InlineMedia.Policy.of(
                images = controller.effective(BufferPrefsStore.Key.INLINE_IMAGES, client, buffer),
                files = controller.effective(BufferPrefsStore.Key.INLINE_FILES, client, buffer),
                pastes = controller.effective(BufferPrefsStore.Key.INLINE_PASTES, client, buffer),
                embedOnCellular = device.embedOnCellular,
                pathExpensive = metered,
            )
            InlineMedia.visible(links, siteBase, buffer.hiddenMedia, policy)
        },
        mediaContent = { _, row, textWidth ->
            InlineMediaCard(
                item = row.item,
                textWidth = textWidth,
                media = deps.media,
                onOpen = { item ->
                    when (val tap = mediaTap(item, device.imagesInBrowser, siteBase)) {
                        is MediaTap.Viewer -> viewer = tap
                        is MediaTap.Link -> LinkOpener.open(context, tap.url, device.openLinksInApp)
                    }
                },
                siteBase = siteBase,
            )
        },
    )

    Box(Modifier.fillMaxSize()) {
        ShellScreen(controller, deps.api, deps.accountStores, deps.deviceSettings, slots)
        val route = routes.lastOrNull()
        if (route != null) {
            val pop: () -> Unit = { routes.remove(route) }
            BackHandler(onBack = pop)
            key(route) {
                Surface(Modifier.fillMaxSize()) { RouteScreen(route, deps, controller, sessionActions, pop) }
            }
        }
    }
    viewer?.let { v -> ImageViewerScreen(v.url, v.source, deps.media, onClose = { viewer = null }) }
}

@Composable
private fun RouteScreen(
    route: SessionRoute,
    deps: Deps,
    controller: ShellController,
    sessionActions: SessionActions,
    pop: () -> Unit,
) {
    val context = LocalContext.current
    when (route) {
        SessionRoute.Settings -> SettingsFlow(
            api = deps.api,
            baseUrl = deps.apiBaseUrl,
            accountSettings = deps.accountSettings,
            deviceSettings = deps.deviceSettings,
            displaySettings = deps.displaySettings,
            notificationPrefs = deps.notificationPrefs,
            notificationPolicy = deps.notificationPolicy,
            blockList = deps.blockList,
            sessionActions = sessionActions,
            onClose = pop,
        )
        is SessionRoute.Network -> NetworkEditorScreen(
            api = deps.api,
            network = route.info,
            onSaved = controller::networksChanged,
            onClose = pop,
            prefill = route.prefill,
        )
        is SessionRoute.BrowseChannels -> ChannelListScreen(route.client, onClose = pop)
        is SessionRoute.Whois -> WhoisScreen(
            route.client, route.nick, IrcSessionManager.networkKey(route.client), onClose = pop,
        )
        is SessionRoute.DisplayOptions -> DisplayOptionsScreen(
            route.client, route.buffer, deps.accountSettings, deps.bufferPrefs, deps.deviceSettings, onClose = pop,
        )
        SessionRoute.ConversationCleanup -> ConversationCleanupScreen(controller.clients, onClose = pop)
        is SessionRoute.ChannelInfo -> ChannelInfoScreen(
            route.client,
            route.buffer,
            onClose = pop,
            onOpenLink = { link ->
                when (link) {
                    is ChatLink.Url -> LinkOpener.open(context, link.url, deps.deviceSettings.settings.value.openLinksInApp)
                    is ChatLink.Channel -> controller.open(BufferLink.Join(route.client.settings.networkId, link.name))
                }
            },
        )
        is SessionRoute.Reorder -> ReorderScreen(route.title, route.items, onSave = route.save, onClose = pop)
        is SessionRoute.BanList -> ChannelModeListScreen(route.client, route.buffer, 'b', onBack = pop)
        is SessionRoute.Search -> BufferSearchScreen(
            client = route.client,
            buffer = route.buffer,
            blockList = deps.blockList,
            accountSettings = deps.accountSettings,
            deviceSettings = deps.deviceSettings,
            onSelect = { line ->
                pop()
                controller.reveal(line)
            },
            onClose = pop,
        )
        SessionRoute.BlockedUsers -> BlockedUsersScreen(deps.blockList, onBack = pop)
    }
}

/** Message texts whose link scan is kept (rows are rebuilt on every new line). */
private const val MEDIA_CACHE_SIZE = 512
