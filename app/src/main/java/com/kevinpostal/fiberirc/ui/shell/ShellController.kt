package com.kevinpostal.fiberirc.ui.shell

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.channelMembers
import com.kevinpostal.fiberirc.data.api.deleteNetwork
import com.kevinpostal.fiberirc.data.api.disconnectNetwork
import com.kevinpostal.fiberirc.data.api.networks
import com.kevinpostal.fiberirc.data.api.reconnectNetwork
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcClientListener
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.irc.IrcState
import com.kevinpostal.fiberirc.data.irc.mentions
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.data.prefs.ShareTarget
import com.kevinpostal.fiberirc.data.prefs.ShareTargetsStore
import com.kevinpostal.fiberirc.ui.chat.HighlightMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/** Which drawer: the channel list (left) or the channel's members (right). */
enum class DrawerSide { LEFT, RIGHT }

/** An `irc(s)://` link's host for Add a Network when the account has no network on it. */
data class NetworkPrefill(val host: String, val port: Int?, val tls: Boolean)

/** The buffer in the chat: [unreadSince] is the read marker when it was opened with unread rows (New messages). */
class ShownBuffer(val client: IrcClient, val buffer: IrcBuffer, val unreadSince: Instant?)

/** What the shell's drawer layout does for the controller ([ShellScreen] implements it). */
interface DrawerHost {
    /** The open drawer, or null when closed. */
    val openSide: DrawerSide?
    fun open(side: DrawerSide)
    fun close()
}

/**
 * Screens other milestones own, opened from the drawer, the shell's shortcuts and links. [SessionHost] pushes each
 * (M9 management screens, M11 settings) over the shell.
 */
class ShellNavigation(
    val openSettings: () -> Unit,
    /** Add a Network ([prefill] from an `irc(s)://` link, else empty). */
    val addNetwork: (prefill: NetworkPrefill?) -> Unit,
    /** Edit Connection with the network's `GET /api/networks` row. */
    val editNetwork: (NetworkInfo) -> Unit,
    /** The server's channel list (`ChannelListScreen`) for the client. */
    val browseChannels: (IrcClient) -> Unit,
    val whois: (IrcClient, nick: String) -> Unit,
    val displayOptions: (IrcClient, IrcBuffer) -> Unit,
    /** Delete Active Conversations… (`ConversationCleanupScreen`). */
    val conversationCleanup: () -> Unit,
    /** A channel's Channel Info (the chat title's tap). */
    val channelInfo: (IrcClient, IrcBuffer) -> Unit,
    /** `ReorderScreen(title, items (id, title), save)`. */
    val reorder: (title: String, items: List<Pair<String, String>>, save: suspend (List<String>) -> Unit) -> Unit,
)

/** A small dialog the shell shows (`Prompts.swift`, confirmations, errors). */
sealed interface ShellPrompt {
    /** "Which network?" before [then]. */
    class ChooseNetwork(val clients: List<IrcClient>, val then: (IrcClient) -> Unit) : ShellPrompt
    class JoinChannel(val client: IrcClient) : ShellPrompt
    class SendMessage(val client: IrcClient) : ShellPrompt
    class Invite(val client: IrcClient, val channel: IrcBuffer) : ShellPrompt
    class Confirm(val title: String, val message: String, val action: String, val perform: () -> Unit) : ShellPrompt
    class Error(val title: String, val message: String) : ShellPrompt
}

/** A request from the shell to the shown chat's composer ([ShellController.composerRequest]). */
sealed interface ComposerRequest {
    /** Member sheet "Mention": an empty composer gets `"nick: "`, otherwise `"nick "` at the cursor. */
    class Mention(val nick: String) : ComposerRequest

    /** Double tap on someone's message: a reply to [nick] (same insertion rule as [Mention]). */
    class ReplyToSender(val nick: String) : ComposerRequest

    /** Message menu "Edit": load [line] into the composer as a `+draft/edit`. */
    class Edit(val line: IrcLine) : ComposerRequest

    /** Message menu "Reply" inside a thread: focus the composer. */
    data object Focus : ComposerRequest
}

/**
 * The signed-in session's UI state (ports the non-layout half of `SessionViewController.swift`): the shown buffer,
 * back/forward history, the buffer to restore at launch, links waiting for their buffer, the menu button's unread
 * state, share-target publishing, the drawer's row actions and the foreground refresh. Compose reads its
 * `mutableStateOf` fields; everything runs on the main thread.
 *
 * @param sessionPrefs where the last shown buffer persists (`session.lastBuffer` on iOS).
 */
class ShellController(
    val manager: IrcSessionManager,
    private val api: SiteApi,
    private val stores: AccountStores,
    private val deviceSettings: DeviceSettingsStore,
    private val shareTargets: ShareTargetsStore,
    private val sessionPrefs: SharedPreferences,
    private val scope: CoroutineScope,
    /** Screens other milestones own (settings, management sheets). */
    val navigation: ShellNavigation,
) : IrcClientListener {
    constructor(
        context: Context,
        manager: IrcSessionManager,
        api: SiteApi,
        stores: AccountStores,
        deviceSettings: DeviceSettingsStore,
        shareTargets: ShareTargetsStore,
        scope: CoroutineScope,
        navigation: ShellNavigation,
    ) : this(manager, api, stores, deviceSettings, shareTargets,
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE), scope, navigation)

    var drawer: DrawerHost? = null

    var current by mutableStateOf<ShownBuffer?>(null)
        private set

    /** The thread (parent msgid) shown over the chat, or null; Back returns to the buffer. */
    var thread by mutableStateOf<String?>(null)
        private set
    var menuState by mutableStateOf(MenuState.NONE)
        private set

    /** Landscape docking: the channel list sits beside the chat (set by [ShellScreen]). */
    var docked by mutableStateOf(false)
        private set
    var prompt by mutableStateOf<ShellPrompt?>(null)

    /** Network ids whose Archives group is open (this session only). */
    var expandedArchives by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Clients whose link last dropped with an error (warning icon on the network row). */
    var failed by mutableStateOf<Set<IrcClient>>(emptySet())
        private set

    val history = BufferHistory()

    private var restoreKey: BufferKey? = sessionPrefs.getString(KEY_LAST_BUFFER, null)?.let(BufferKey::parse)
    private var restorePending = restoreKey != null
    private val finishedReplay = HashSet<IrcClient>()
    private var userPicked = false

    /** The shown buffer is the stand-in server buffer, replaced by the first channel as soon as one exists. */
    private var showingFallback = false

    /** A link that named a buffer or network not here yet; retried as buffers arrive. */
    private var pendingLink: BufferLink? = null
    private var started = false
    private var tornDown = false

    val clients: List<IrcClient> get() = manager.clients.value

    init {
        manager.configure = ::wire
        manager.addListener(this)
    }

    /**
     * The session's rules on a client: highlights include the account's highlight words; a muted buffer never
     * counts highlights; "Mark As Read Automatically" decides whether showing a buffer reads it.
     */
    private fun wire(c: IrcClient) {
        c.isHighlight = { line: IrcLine, nick: String ->
            line.mentions(nick) || HighlightMatcher.matches(line, stores.accountSettings.highlightWords.value, nick)
        }
        c.isMuted = { b -> effective(BufferPrefsStore.Key.MUTE, c, b) }
        c.autoMarksRead = { b -> effective(BufferPrefsStore.Key.MARK_AS_READ, c, b) }
    }

    /** A buffer pref as [b] on [c] uses it (its own value, else the device/account default). */
    fun effective(k: BufferPrefsStore.Key, c: IrcClient, b: IrcBuffer): Boolean = stores.bufferPrefs.effective(
        k, c.settings.networkId, b.name, deviceSettings.settings.value, stores.accountSettings.display.value.formatColors,
    )

    /** The session appeared (`viewDidLoad`): pick the buffer, follow a waiting link, refresh account and networks. */
    fun start() {
        if (started || tornDown) return
        started = true
        restoreSelection()
        pendingLink?.let { pendingLink = null; open(it) }
        refreshAccount(syncing = false)
        refreshNetworks()
    }

    /** Leaving for the sign-in screen: stops listening (the owner stops [manager]). */
    fun tearDown() {
        if (tornDown) return
        tornDown = true
        manager.removeListener(this)
        drawer = null
    }

    // region Drawers

    fun updateDocked(dock: Boolean) {
        if (dock == docked) return
        docked = dock
        if (dock && drawer?.openSide == DrawerSide.LEFT) drawer?.close()
    }

    /** The channel list floats unless docked; the members drawer needs a joined channel. */
    fun isAvailable(side: DrawerSide): Boolean = when (side) {
        DrawerSide.LEFT -> !docked
        DrawerSide.RIGHT -> current?.let { it.buffer.kind == IrcBufferKind.CHANNEL && it.buffer.joined } ?: false
    }

    fun toggleDrawer(side: DrawerSide) {
        if (drawer?.openSide == side) closeDrawer() else openDrawer(side)
    }

    fun openDrawer(side: DrawerSide) {
        if (isAvailable(side)) drawer?.open(side)
    }

    fun closeDrawer() {
        if (drawer?.openSide != null) drawer?.close()
    }

    /** Two-finger swipe: right goes back, left goes forward; skips entries whose buffer is gone. */
    fun twoFingerSwipe(back: Boolean) {
        if (thread != null || drawer?.openSide != null) return
        val cur = current ?: return
        val curKey = BufferKey.of(cur.client, cur.buffer)
        while (true) {
            val target = (if (back) history.goBack(curKey) else history.goForward(curKey)) ?: return
            val found = resolve(target) ?: continue
            show(found.second, found.first, recordHistory = false)
            return
        }
    }

    // endregion

    // region Pushed screens

    /** The thread of [parentMsgid] in the shown buffer, over the chat. */
    fun openThread(parentMsgid: String) {
        if (current == null) return
        closeDrawer()
        thread = parentMsgid
    }

    fun closeThread() {
        thread = null
    }

    fun openSettings() {
        closeDrawer()
        navigation.openSettings()
    }

    /** Edit Connection for [c]'s network: its `GET /api/networks` row, fetched first when not loaded yet. */
    fun editConnection(c: IrcClient) {
        val id = c.settings.networkId
        if (id.isEmpty()) return
        closeDrawer()
        manager.networkInfos.value.firstOrNull { it.id == id }?.let { return navigation.editNetwork(it) }
        scope.launch {
            api.networks().fold(
                onSuccess = { infos ->
                    if (tornDown) return@fold
                    infos.firstOrNull { it.id == id }?.let(navigation.editNetwork)
                },
                onFailure = { showError("Couldn't load ${c.settings.networkName}", it) },
            )
        }
    }

    /** A network was reconnected, disconnected, deleted or saved: fresh network rows and clients. */
    fun networksChanged() {
        refreshNetworks()
        syncNetworks()
    }

    // endregion

    // region Buffers

    private fun resolve(k: BufferKey): Pair<IrcClient, IrcBuffer>? {
        val c = clients.firstOrNull { IrcSessionManager.networkKey(it) == k.network } ?: return null
        val b = c.buffer(k.buffer) ?: return null
        return c to b
    }

    /** The buffer in the chat. */
    val shownBuffer: BufferKey? get() = current?.let { BufferKey.of(it.client, it.buffer) }

    /**
     * Shows [buffer] in the chat: starts it at its unread rows, marks it read (when it reads automatically), and
     * points the member drawer at it. A pick by the user also closes the drawer, records history and becomes the
     * buffer to restore next launch. Picking the buffer already shown returns to its chat (a thread closes).
     * The composer keeps each buffer's draft in [IrcBuffer.draft] itself.
     */
    fun show(buffer: IrcBuffer, client: IrcClient, userInitiated: Boolean = true, recordHistory: Boolean = true) {
        if (userInitiated) {
            userPicked = true
            restorePending = false
        }
        val cur = current
        if (cur != null && cur.buffer === buffer) {
            if (userInitiated) {
                thread = null
                closeDrawer()
            }
            return
        }
        var switched = false
        if (cur != null && recordHistory && userInitiated) {
            history.visit(BufferKey.of(cur.client, cur.buffer))
            switched = true
        }
        showingFallback = false
        thread = null
        val since = if (buffer.unreadCount > 0) buffer.readMarker else null
        current = ShownBuffer(client, buffer, since)
        if (client.autoMarksRead(buffer)) client.markRead(buffer)
        if (userInitiated || (!isAvailable(DrawerSide.RIGHT) && drawer?.openSide == DrawerSide.RIGHT)) closeDrawer()
        updateMenuState()
        val k = BufferKey.of(client, buffer)
        if (userInitiated || !restorePending) sessionPrefs.edit().putString(KEY_LAST_BUFFER, k.string).apply()
        if (buffer.kind != IrcBufferKind.SERVER) shareTargets.noteRecent(k.network, k.buffer)
        scheduleShareTargets()
        refreshMembers()
        if (switched) showTip(FirstRunTip.TWO_FINGER)
    }

    /** Shows [t] at the top of the chat for 2.5 s unless it was shown before on this install. */
    fun showTip(t: FirstRunTip) {
        if (sessionPrefs.getBoolean(t.key, false)) return
        sessionPrefs.edit().putBoolean(t.key, true).apply()
        tip = t
    }

    /** The first-run tip showing now ([ShellScreen] clears it after 2.5 s). */
    var tip by mutableStateOf<FirstRunTip?>(null)

    /**
     * What the shown chat's composer should do next (the composer slot applies it, then calls
     * [consumeComposerRequest]): a mention from the member sheet, a reply-to-sender from a double tap, an edit or a
     * focus from the message menu.
     */
    var composerRequest by mutableStateOf<ComposerRequest?>(null)
        private set

    fun requestComposer(r: ComposerRequest) {
        composerRequest = r
    }

    /** The composer applied [r]; a newer request stays. */
    fun consumeComposerRequest(r: ComposerRequest) {
        if (composerRequest === r) composerRequest = null
    }

    /** A search result for the shown chat to scroll to and flash ([BufferScreen] applies it, then [consumeReveal]). */
    var reveal by mutableStateOf<IrcLine?>(null)
        private set

    /** Search picked [line] in the shown buffer: back to the buffer's chat, scrolled to it. */
    fun reveal(line: IrcLine) {
        thread = null
        reveal = line
    }

    fun consumeReveal(line: IrcLine) {
        if (reveal === line) reveal = null
    }

    /**
     * The buffer to open while the user hasn't picked one: the last session's buffer once it exists, else the
     * first channel, else (until one appears) the first client's server buffer.
     */
    private fun restoreSelection() {
        if (!started || userPicked) return
        val k = restoreKey
        if (restorePending && k != null) {
            resolve(k)?.let { (c, b) ->
                restorePending = false
                show(b, c, userInitiated = false, recordHistory = false)
                return
            }
        }
        if (current != null && !showingFallback) return
        val c = clients.firstOrNull { cl -> cl.buffers.any { it.kind == IrcBufferKind.CHANNEL } }
        val b = c?.buffers?.firstOrNull { it.kind == IrcBufferKind.CHANNEL }
        if (c != null && b != null) {
            show(b, c, userInitiated = false, recordHistory = false)
        } else if (current == null) {
            val first = clients.firstOrNull() ?: return
            show(first.serverBuffer, first, userInitiated = false, recordHistory = false)
            showingFallback = true
        }
    }

    /**
     * The shown buffer went away (left, closed, network removed): back to the previous buffer, else the client's
     * (or the first client's) server buffer.
     */
    private fun showReplacement(client: IrcClient) {
        current = null
        thread = null
        var target: Pair<IrcClient, IrcBuffer>? = null
        while (target == null) {
            val k = history.popBack() ?: break
            target = resolve(k)
        }
        if (target == null) {
            val c = clients.firstOrNull { it === client } ?: clients.firstOrNull()
            if (c != null) target = c to c.serverBuffer
        }
        val (c, b) = target ?: return
        show(b, c, userInitiated = false, recordHistory = false)
    }

    /** Opens [link]; one naming a network or buffer that isn't here yet waits for it. */
    fun open(link: BufferLink) {
        if (!started || current == null) {
            pendingLink = link
            return
        }
        pendingLink = resolveLink(link)
    }

    /** Follows [link] as far as it can; returns what is still to wait for (null: done, or nothing to wait for). */
    private fun resolveLink(link: BufferLink): BufferLink? = when (link) {
        is BufferLink.Buffer -> if (openBuffer(link.networkId, link.name)) null else link
        is BufferLink.Key -> {
            val k = BufferKey.parse(link.id)
            if (k == null) null else if (openBuffer(k.network, k.buffer)) null else link
        }
        is BufferLink.Join -> {
            val c = clients.firstOrNull { it.settings.networkId == link.networkId }
            if (c == null) {
                link
            } else {
                val name = if (link.channel.startsWith("#") || link.channel.startsWith("&")) link.channel else "#${link.channel}"
                if (c.buffer(name)?.joined != true) c.joinChannel(name)
                val b = c.buffer(name)
                if (b == null) link else { show(b, c); null }
            }
        }
        is BufferLink.Web -> {
            val route = WebBufferRoute.parse(link.url)
            if (route == null) {
                null
            } else {
                val c = clients.firstOrNull { it.settings.networkName.equals(route.networkName, ignoreCase = true) }
                when {
                    c == null -> link
                    route.buffer == null -> { show(c.serverBuffer, c); null }
                    openBuffer(IrcSessionManager.networkKey(c), route.buffer) -> null
                    else -> link
                }
            }
        }
        is BufferLink.Irc -> resolveIrcLink(link)
    }

    private fun resolveIrcLink(link: BufferLink.Irc): BufferLink? {
        val route = IrcLinkRoute.parse(link.url) ?: return null
        if (!manager.networksLoaded.value) return link
        val info = manager.networkInfos.value.firstOrNull { it.host?.equals(route.host, ignoreCase = true) == true }
        if (info == null) {
            closeDrawer()
            navigation.addNetwork(NetworkPrefill(route.host, route.port, route.tls))
            return null
        }
        val c = clients.firstOrNull { it.settings.networkId == info.id } ?: return link
        val first = route.channels.firstOrNull()
        if (first == null) {
            show(c.serverBuffer, c)
            return null
        }
        for (name in route.channels.drop(1)) if (c.buffer(name)?.joined != true) c.joinChannel(name)
        return resolveLink(BufferLink.Join(info.id, first))
    }

    /** Selects the buffer; an unknown nick gets a new conversation. */
    private fun openBuffer(network: String, name: String): Boolean {
        val c = clients.firstOrNull { IrcSessionManager.networkKey(it) == network } ?: return false
        val isChannel = name.startsWith("#") || name.startsWith("&")
        val b = c.buffer(name) ?: (if (isChannel) null else c.queryBuffer(name)) ?: return false
        show(b, c)
        return true
    }

    /** HIGHLIGHT when another buffer has unread highlights, else UNREAD when one has unread messages. */
    fun updateMenuState() {
        var state = MenuState.NONE
        scan@ for (c in clients) {
            for (b in c.buffers) {
                if (b === current?.buffer) continue
                if (b.highlightCount > 0) {
                    state = MenuState.HIGHLIGHT
                    break@scan
                }
                if (b.unreadCount > 0) state = MenuState.UNREAD
            }
        }
        menuState = state
    }

    // endregion

    // region Drawer model and keyboard navigation

    /** The drawer's rows ([BufferListModel.rows]) for the current clients and account layout. */
    fun drawerRows(): List<BufferRow> {
        val cs = clients
        return BufferListModel.rows(
            clients = cs.map { BufferListClient(it.settings.networkId, it.buffers) },
            pins = stores.pinList.keys.value,
            archived = stores.archiveList.keys.value.toSet(),
            collapsed = stores.networkLayout.collapsed.value,
            networkOrder = stores.networkLayout.networkOrder.value,
            expandedArchives = expandedArchives,
            connected = cs.map { it.state == IrcState.CONNECTED },
        )
    }

    /** The client and buffer a row stands for: the buffer itself, or a network's server buffer. */
    fun target(row: BufferRow): Pair<IrcClient, IrcBuffer>? {
        val cs = clients
        return when (row) {
            is BufferRow.Pinned -> cs.getOrNull(row.client)?.let { it to row.buffer }
            is BufferRow.Buffer -> cs.getOrNull(row.client)?.let { it to row.buffer }
            is BufferRow.Archived -> cs.getOrNull(row.client)?.let { it to row.buffer }
            is BufferRow.Network -> cs.getOrNull(row.client)?.let { it to it.serverBuffer }
            else -> null
        }
    }

    /** The buffers of the drawer's rows, top to bottom, each once (a pinned buffer at its pin). */
    fun navigationOrder(): List<Pair<IrcClient, IrcBuffer>> {
        val seen = HashSet<Int>()
        return drawerRows().mapNotNull(::target).filter { seen.add(System.identityHashCode(it.second)) }
    }

    /** Quick switcher entries: every buffer, the drawer's first, then those it hides (collapsed, closed Archives). */
    fun quickSwitchEntries(): List<QuickSwitchEntry> {
        val order = navigationOrder().toMutableList()
        val listed = order.mapTo(HashSet()) { System.identityHashCode(it.second) }
        for (c in clients) {
            for (b in listOf(c.serverBuffer) + c.buffers) {
                if (listed.add(System.identityHashCode(b))) order.add(c to b)
            }
        }
        return order.map { (c, b) -> QuickSwitchEntry(c, b, if (b.kind == IrcBufferKind.SERVER) c.settings.networkName else b.name) }
    }

    /** Shows the buffer [delta] rows away in the drawer order (the nearest unread one with [unreadOnly]). */
    fun step(delta: Int, unreadOnly: Boolean) {
        val order = navigationOrder()
        var i = order.indexOfFirst { it.second === current?.buffer }.takeIf { it >= 0 } ?: if (delta > 0) -1 else order.size
        while (true) {
            i += delta
            val e = order.getOrNull(i) ?: return
            if (!unreadOnly || e.second.unreadCount > 0 || e.second.highlightCount > 0) {
                show(e.second, e.first)
                return
            }
        }
    }

    fun markChannelRead() {
        val cur = current ?: return
        cur.client.markRead(cur.buffer)
        updateMenuState()
    }

    fun markAllChannelsRead() {
        for (c in clients) for (b in c.buffers) c.markRead(b)
        updateMenuState()
    }

    fun toggleArchives(networkId: String) {
        expandedArchives = if (networkId in expandedArchives) expandedArchives - networkId else expandedArchives + networkId
    }

    // endregion

    // region Drawer row actions (BufferListViewController context menus)

    /** Collapses or expands [c]'s network (the row's chevron and long-press item); no-op without a network id. */
    fun toggleCollapsed(c: IrcClient) {
        val id = c.settings.networkId
        if (id.isEmpty()) return
        val collapsed = id in stores.networkLayout.collapsed.value
        val name = c.settings.networkName
        run(if (collapsed) "Couldn't expand $name" else "Couldn't collapse $name", refreshNetworks = false) {
            stores.networkLayout.setCollapsed(!collapsed, id)
        }
    }

    fun reconnectNetwork(c: IrcClient) = run("Couldn't reconnect ${c.settings.networkName}") {
        api.reconnectNetwork(c.settings.networkId).getOrThrow()
    }

    fun disconnectNetwork(c: IrcClient) = run("Couldn't disconnect ${c.settings.networkName}") {
        api.disconnectNetwork(c.settings.networkId).getOrThrow()
    }

    fun setPinned(pinned: Boolean, c: IrcClient, b: IrcBuffer) =
        run(if (pinned) "Couldn't pin ${b.name}" else "Couldn't unpin ${b.name}", refreshNetworks = false) {
            stores.pinList.setPinned(pinned, c.settings.networkId, b.name)
        }

    fun setArchived(archived: Boolean, c: IrcClient, b: IrcBuffer) =
        run(if (archived) "Couldn't archive ${b.name}" else "Couldn't unarchive ${b.name}", refreshNetworks = false) {
            if (archived) stores.archiveList.archive(c.settings.networkId, b.name)
            else stores.archiveList.unarchive(c.settings.networkId, b.name)
        }

    /** Leave asks first: through the bouncer the PART leaves on every device. The channel stays, parted. */
    fun confirmLeave(c: IrcClient, b: IrcBuffer) {
        val message = if (c.settings.viaBouncer) {
            "You'll leave ${b.name} on every device signed in to your account."
        } else {
            "You'll leave ${b.name}."
        }
        prompt = ShellPrompt.Confirm("Leave ${b.name}?", message, "Leave") { c.partBuffer(b) }
    }

    fun confirmDelete(c: IrcClient, b: IrcBuffer) {
        val message = if (b.kind == IrcBufferKind.QUERY) {
            "The conversation with ${b.name} is removed from the list."
        } else {
            "${b.name} is removed from the list."
        }
        prompt = ShellPrompt.Confirm("Delete ${b.name}?", message, "Delete") { c.close(b) }
    }

    fun confirmDeleteNetwork(c: IrcClient) {
        val name = c.settings.networkName
        val id = c.settings.networkId
        prompt = ShellPrompt.Confirm(
            "Delete $name?",
            "The network, its channels and its history are removed from your account.",
            "Delete",
        ) { run("Couldn't delete $name") { api.deleteNetwork(id).getOrThrow() } }
    }

    /** The account's networks in drawer order, for Reorder Connections. */
    fun networkItems(): List<Pair<String, String>> = drawerRows().mapNotNull { row ->
        val c = (row as? BufferRow.Network)?.let { clients.getOrNull(it.client) } ?: return@mapNotNull null
        if (c.settings.networkId.isEmpty()) null else c.settings.networkId to c.settings.networkName
    }

    fun reorderConnections() {
        navigation.reorder("Reorder Connections", networkItems()) { stores.networkLayout.setNetworkOrder(it) }
    }

    fun reorderPins() {
        val items = stores.pinList.keys.value.map { key -> key to key.substringAfter(':', key) }
        navigation.reorder("Reorder Pins", items) { stores.pinList.setOrder(it) }
    }

    /** Runs a save; a failure shows [failureTitle]. Network calls then refresh the network rows and clients. */
    private fun run(failureTitle: String, refreshNetworks: Boolean = true, work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
                if (refreshNetworks) networksChanged()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showError(failureTitle, e)
            }
        }
    }

    fun showError(title: String, e: Throwable) {
        prompt = ShellPrompt.Error(title, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    // endregion

    // region Prompts (Prompts.swift)

    /** Join a Channel: on [preferred] when given, else the only network, else after asking which one. */
    fun promptJoinChannel(preferred: IrcClient? = null) = chooseNetwork(preferred) { prompt = ShellPrompt.JoinChannel(it) }

    /** Send a Message: a nickname on [preferred] (or the chosen network); opens that conversation. */
    fun promptSendMessage(preferred: IrcClient? = null) = chooseNetwork(preferred) { prompt = ShellPrompt.SendMessage(it) }

    /** Invite to Channel: asks for a nickname; the server's answer shows in the channel. */
    fun promptInvite(client: IrcClient, channel: IrcBuffer) {
        prompt = ShellPrompt.Invite(client, channel)
    }

    private fun chooseNetwork(preferred: IrcClient?, next: (IrcClient) -> Unit) {
        if (preferred != null) return next(preferred)
        val cs = clients
        if (cs.size <= 1) {
            cs.firstOrNull()?.let(next)
            return
        }
        prompt = ShellPrompt.ChooseNetwork(cs, next)
    }

    /** The Join dialog's Join: a key joins here first, since a [BufferLink.Join] carries none. */
    fun join(client: IrcClient, name: String, key: String) {
        if (name.isEmpty()) return
        if (key.isEmpty()) {
            open(BufferLink.Join(client.settings.networkId, name))
        } else {
            val channel = if (name.startsWith("#") || name.startsWith("&")) name else "#$name"
            client.joinChannel(channel, key)
            open(BufferLink.Buffer(IrcSessionManager.networkKey(client), channel))
        }
    }

    // endregion

    // region Share targets

    private fun scheduleShareTargets() {
        if (tornDown) return
        shareTargets.schedule {
            clients.filter { it.settings.networkId.isNotEmpty() }.flatMap { c ->
                c.buffers.filter { it.kind != IrcBufferKind.SERVER }.map { b ->
                    ShareTarget(
                        networkId = c.settings.networkId,
                        networkName = c.settings.networkName,
                        buffer = b.name,
                        kind = if (b.kind == IrcBufferKind.CHANNEL) ShareTarget.CHANNEL else ShareTarget.QUERY,
                    )
                }
            }
        }
    }

    // endregion

    // region Account and networks

    /** Back in the foreground (`willEnterForeground`): account, networks and the shown channel's members. */
    fun onForeground() {
        if (tornDown || !started) return
        refreshAccount(syncing = true)
        refreshNetworks()
        refreshMembers()
    }

    /**
     * Pins, per-channel settings and highlight words changed on the web (every store but the block list); with
     * [syncing], also networks added or removed there. On failure the last values stay.
     */
    private fun refreshAccount(syncing: Boolean) {
        scope.launch {
            val me = api.me().getOrNull() ?: return@launch
            if (tornDown) return@launch
            stores.reloadFromMe(me)
            if (syncing) syncNetworks(me)
        }
    }

    private fun refreshNetworks() {
        scope.launch {
            manager.refreshNetworks()
            if (tornDown) return@launch
            pendingLink?.let(::open)
        }
    }

    /** Real names, accounts and hosts of the shown channel's members (the bouncer's names list has nicks only). */
    private fun refreshMembers() {
        val cur = current ?: return
        if (cur.buffer.kind != IrcBufferKind.CHANNEL || cur.client.settings.networkId.isEmpty()) return
        val c = cur.client
        val b = cur.buffer
        scope.launch {
            val rows = api.channelMembers(c.settings.networkId, b.name).getOrNull()
            if (rows.isNullOrEmpty() || tornDown) return@launch
            b.applyDetails(rows)
            onMembersUpdated(c, b)
        }
    }

    /** Brings the clients in line with the account's networks (after an add, edit or delete, and on foreground). */
    fun syncNetworks() {
        scope.launch {
            val me = api.me().getOrNull() ?: return@launch
            syncNetworks(me)
        }
    }

    private suspend fun syncNetworks(me: Me) {
        if (tornDown) return
        val removed = manager.syncNetworks(me)
        if (tornDown) return
        val cur = current
        if (cur != null && removed.any { it === cur.client }) showReplacement(cur.client)
        failed = failed.filterTo(HashSet()) { f -> removed.none { it === f } }
        pendingLink?.let(::open)
        restoreSelection()
        updateMenuState()
        scheduleShareTargets()
        manager.refreshNetworks()
    }

    // endregion

    // region IrcClientListener

    /** Bumped when [current]'s members change, so the member drawer recomposes. */
    var membersRevision by mutableStateOf(0)
        private set

    override fun onStateChanged(client: IrcClient, state: IrcState, error: Throwable?) {
        failed = when {
            state == IrcState.DISCONNECTED && error != null -> failed + client
            state != IrcState.DISCONNECTED -> failed - client
            else -> failed
        }
    }

    override fun onBufferAdded(client: IrcClient, buffer: IrcBuffer) {
        restoreSelection()
        pendingLink?.let(::open)
        updateMenuState()
        scheduleShareTargets()
    }

    override fun onBufferRemoved(client: IrcClient, buffer: IrcBuffer) {
        if (current?.buffer === buffer) showReplacement(client)
        updateMenuState()
        scheduleShareTargets()
    }

    override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) {
        if (buffer !== current?.buffer) updateMenuState()
    }

    override fun onMembersUpdated(client: IrcClient, buffer: IrcBuffer) {
        if (buffer === current?.buffer) membersRevision++
    }

    override fun onBufferUpdated(client: IrcClient, buffer: IrcBuffer) {
        updateMenuState()
        if (buffer === current?.buffer && drawer?.openSide == DrawerSide.RIGHT && !isAvailable(DrawerSide.RIGHT)) closeDrawer()
    }

    override fun onAttachReplayFinished(client: IrcClient) {
        finishedReplay.add(client)
        if (clients.all { it in finishedReplay }) restorePending = false
    }

    // endregion

    companion object {
        const val PREFS_FILE = "session"
        const val KEY_LAST_BUFFER = "session.lastBuffer"
    }
}
