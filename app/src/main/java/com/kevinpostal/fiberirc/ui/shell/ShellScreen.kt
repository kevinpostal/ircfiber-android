package com.kevinpostal.fiberirc.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.data.prefs.ShareTargetsStore
import com.kevinpostal.fiberirc.ui.actions.ModerationDialogs
import com.kevinpostal.fiberirc.ui.actions.rememberModerationController
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the chat pane rests: slid right over the channel list, centred, or slid left over the members. */
enum class DrawerPosition { Left, Center, Right }

/** Drawer width (iOS `drawerWidth` 240pt) and the landscape-docked channel list's (`dockedWidth` 200pt). */
private val DrawerWidth = 240.dp
private val DockedWidth = 200.dp

/** Two-finger horizontal travel that counts as a back/forward swipe. */
private val TwoFingerThreshold = 60.dp

/**
 * The signed-in session's controller over [manager], created once per session: starts listening, picks the first
 * buffer and connects every client; stops listening when it leaves composition. Stopping [manager] (sign-out,
 * rejected credentials) is the session owner's job.
 */
@Composable
fun rememberShellController(
    manager: IrcSessionManager,
    api: SiteApi,
    stores: AccountStores,
    deviceSettings: DeviceSettingsStore,
    shareTargets: ShareTargetsStore,
    navigation: ShellNavigation,
): ShellController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(manager) {
        ShellController(context, manager, api, stores, deviceSettings, shareTargets, scope, navigation)
    }
    DisposableEffect(controller) {
        controller.start()
        manager.start()
        onDispose { controller.tearDown() }
    }
    return controller
}

/**
 * The signed-in session (IRCCloud's sliding container, ports the layout half of `SessionViewController.swift`): one
 * chat pane that slides 240dp right to reveal the channel list or left to reveal the channel's members, by a
 * horizontal drag or the bar buttons. In landscape on a wide screen (≥700dp, wider than tall, Show Sidebars In
 * Landscape) the channel list docks at 200dp beside the chat. Two-finger swipes walk the buffer history; hardware
 * keyboards get IRCCloud's shortcuts (Ctrl in place of ⌘). Links handed to [AppRouter] open here.
 */
@Composable
fun ShellScreen(
    controller: ShellController,
    api: SiteApi,
    stores: AccountStores,
    deviceSettings: DeviceSettingsStore,
    slots: ChatSlots,
    modifier: Modifier = Modifier,
) {
    val c = LocalFiberIrcColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val device by deviceSettings.settings.collectAsState()
    val showMemberPrefixes by stores.accountSettings.showMemberPrefixes.collectAsState()
    controller.manager.revision.collectAsState().value   // joined/kind changes decide the members drawer
    val moderation = rememberModerationController(api, stores.blockList)
    var switcherOpen by rememberSaveable { mutableStateOf(false) }

    val drawerPx = with(density) { DrawerWidth.toPx() }
    val state = remember { AnchoredDraggableState(initialValue = DrawerPosition.Center, anchors = DraggableAnchors { DrawerPosition.Center at 0f }) }
    val host = remember(state) {
        object : DrawerHost {
            override val openSide: DrawerSide?
                get() = when (state.targetValue) {
                    DrawerPosition.Left -> DrawerSide.LEFT
                    DrawerPosition.Right -> DrawerSide.RIGHT
                    DrawerPosition.Center -> null
                }

            override fun open(side: DrawerSide) {
                scope.launch { state.animateTo(if (side == DrawerSide.LEFT) DrawerPosition.Left else DrawerPosition.Right, Slide) }
            }

            override fun close() {
                scope.launch { state.animateTo(DrawerPosition.Center, Slide) }
            }
        }
    }
    DisposableEffect(controller, host) {
        controller.drawer = host
        onDispose { if (controller.drawer === host) controller.drawer = null }
    }

    // Links from intents, notifications and other screens.
    val pending by AppRouter.pending.collectAsState()
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        controller.open(link)
        AppRouter.consume(link)
    }

    // Back in the foreground (not the launch itself): account, networks and members.
    DisposableEffect(controller) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (first) first = false else controller.onForeground()
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Prevent Auto-Lock while the session is on screen.
    val view = LocalView.current
    DisposableEffect(view, device.preventAutoLock) {
        view.keepScreenOn = device.preventAutoLock
        onDispose { view.keepScreenOn = false }
    }

    val hasBuffer = controller.current != null
    LaunchedEffect(hasBuffer) { if (hasBuffer) controller.showTip(FirstRunTip.SIDEBARS) }

    BackHandler(enabled = state.targetValue != DrawerPosition.Center || controller.thread != null) {
        if (state.targetValue != DrawerPosition.Center) controller.closeDrawer() else controller.closeThread()
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(c.background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || switcherOpen || controller.prompt != null) return@onPreviewKeyEvent false
                shortcut(controller, e.key, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed) { switcherOpen = true }
            },
    ) {
        val docked = device.sidebarsInLandscape && maxWidth > maxHeight && maxWidth >= 700.dp
        LaunchedEffect(docked) { controller.updateDocked(docked) }

        val leftAvailable = controller.isAvailable(DrawerSide.LEFT)
        val rightAvailable = controller.isAvailable(DrawerSide.RIGHT)
        LaunchedEffect(leftAvailable, rightAvailable, drawerPx) {
            state.updateAnchors(
                DraggableAnchors {
                    DrawerPosition.Center at 0f
                    if (leftAvailable) DrawerPosition.Left at drawerPx
                    if (rightAvailable) DrawerPosition.Right at -drawerPx
                },
            )
        }
        val offset = if (state.offset.isNaN()) 0f else state.offset
        val open = offset != 0f

        val members: @Composable () -> Unit = {
            val shown = controller.current
            controller.membersRevision
            MembersDrawer(
                client = shown?.client,
                buffer = shown?.buffer,
                showMemberPrefixes = showMemberPrefixes,
                moderation = moderation,
                onWhois = { cl, nick ->
                    controller.closeDrawer()
                    controller.navigation.whois(cl, nick)
                },
                onSendMessage = { cl, nick -> controller.open(BufferLink.Buffer(IrcSessionManager.networkKey(cl), nick)) },
                onMention = { nick ->
                    controller.closeDrawer()
                    controller.requestComposer(ComposerRequest.Mention(nick))
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val chat: @Composable (Modifier) -> Unit = { m ->
            Box(
                m
                    // Pointer input goes after `offset` so it slides with the chat: before it, it kept covering the
                    // whole screen above the drawers (zIndex 2) and swallowed every tap meant for a drawer row.
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .twoFingerSwipe(with(density) { TwoFingerThreshold.toPx() }) { back -> controller.twoFingerSwipe(back) }
                    .anchoredDraggable(
                        state = state,
                        orientation = Orientation.Horizontal,
                        enabled = controller.thread == null || open,
                        flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                            state = state,
                            positionalThreshold = { distance -> distance * 0.5f },
                            animationSpec = Slide,
                        ),
                    )
                    .shadow(if (open) 6.dp else 0.dp)
                    .background(c.background),
            ) {
                Box(if (open) Modifier.clearAndSetSemantics {} else Modifier) {
                    BufferScreen(
                        controller = controller,
                        stores = stores,
                        deviceSettings = deviceSettings,
                        moderation = moderation,
                        slots = slots,
                        onQuickSwitch = { switcherOpen = true },
                    )
                }
                if (open) {
                    // Covers the slid-away chat while a drawer is open: a tap closes it (drags still move it).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("closeSidebar")
                            .semantics {
                                contentDescription = "Close sidebar"
                                role = Role.Button
                            }
                            .clickable(interactionSource = null, indication = null) { controller.closeDrawer() },
                    )
                }
            }
        }

        if (docked) {
            Row(Modifier.fillMaxSize()) {
                BufferListDrawer(controller, stores, Modifier.width(DockedWidth).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(DrawerWidth)
                            .fillMaxHeight()
                            .then(if (offset < 0f) Modifier else Modifier.clearAndSetSemantics {}),
                    ) { members() }
                    chat(Modifier.fillMaxSize())
                }
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(DrawerWidth)
                    .fillMaxHeight()
                    .zIndex(if (offset > 0f) 1f else 0f)
                    .then(if (offset > 0f) Modifier else Modifier.clearAndSetSemantics {}),
            ) { BufferListDrawer(controller, stores, Modifier.fillMaxSize()) }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(DrawerWidth)
                    .fillMaxHeight()
                    .zIndex(if (offset < 0f) 1f else 0f)
                    .then(if (offset < 0f) Modifier else Modifier.clearAndSetSemantics {}),
            ) { members() }
            chat(Modifier.fillMaxSize().zIndex(2f))
        }
    }

    if (switcherOpen) {
        QuickSwitcher(
            entries = remember { controller.quickSwitchEntries() },
            onPick = { cl, b -> controller.show(b, cl) },
            onDismiss = { switcherOpen = false },
        )
    }
    ShellPrompts(controller)
    ModerationDialogs(moderation)
}

/** The drawer slide: 0.25 s, easing out (iOS `.curveEaseOut`). */
private val Slide = tween<Float>(durationMillis = 250, easing = LinearOutSlowInEasing)

/**
 * IRCCloud's shortcuts (`SessionViewController.keyCommands`), Ctrl standing in for ⌘: Ctrl+K jump to channel,
 * Ctrl+↑/↓ previous/next channel, Ctrl+Shift+↑/↓ or Alt(+Shift)+↑/↓ previous/next unread, Ctrl+R mark channel read,
 * Ctrl+Shift+R mark all read, Ctrl+, Settings.
 */
private fun shortcut(
    controller: ShellController,
    key: Key,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    openSwitcher: () -> Unit,
): Boolean {
    val up = key == Key.DirectionUp
    val down = key == Key.DirectionDown
    when {
        ctrl && !shift && !alt && key == Key.K -> openSwitcher()
        ctrl && !shift && !alt && up -> controller.step(-1, unreadOnly = false)
        ctrl && !shift && !alt && down -> controller.step(1, unreadOnly = false)
        ((ctrl && shift && !alt) || (alt && !ctrl)) && up -> controller.step(-1, unreadOnly = true)
        ((ctrl && shift && !alt) || (alt && !ctrl)) && down -> controller.step(1, unreadOnly = true)
        ctrl && !shift && !alt && key == Key.R -> controller.markChannelRead()
        ctrl && shift && !alt && key == Key.R -> controller.markAllChannelsRead()
        ctrl && !shift && !alt && key == Key.Comma -> controller.openSettings()
        else -> return false
    }
    return true
}

/**
 * Two-finger horizontal swipes (IRCCloud): past [thresholdPx], [onSwipe] with `back = true` for a swipe right.
 * While two or more fingers are down their events are consumed, so the drawer drag and the chat's scrolling stay
 * single-finger (iOS `maximumNumberOfTouches = 1`).
 */
private fun Modifier.twoFingerSwipe(thresholdPx: Float, onSwipe: (back: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start: Offset? = null
        var fired = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size < 2) {
                if (start != null) break   // a finger lifted: the two-finger gesture is over
                continue
            }
            var sum = Offset.Zero
            for (p in pressed) sum += p.position
            val centroid = sum / pressed.size.toFloat()
            val origin = start ?: centroid.also { start = it }
            event.changes.forEach { it.consume() }
            val d = centroid - origin
            if (!fired && abs(d.x) > thresholdPx && abs(d.x) > abs(d.y)) {
                fired = true
                onSwipe(d.x > 0)
            }
        }
    }
}
