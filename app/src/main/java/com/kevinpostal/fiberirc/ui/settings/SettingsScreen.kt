package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.data.prefs.DisplaySettingsStore
import com.kevinpostal.fiberirc.data.prefs.NotificationPolicyStore
import com.kevinpostal.fiberirc.data.prefs.NotificationPrefsStore
import com.kevinpostal.fiberirc.ui.shell.SessionActions
import com.kevinpostal.fiberirc.ui.encryption.EncryptionSettingsScreen
import com.kevinpostal.fiberirc.data.update.AppUpdates
import com.kevinpostal.fiberirc.data.update.UpdateState
import com.kevinpostal.fiberirc.ui.update.UpdateTexts
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.ui.theme.Appearance
import com.kevinpostal.fiberirc.ui.theme.Brand
import com.kevinpostal.fiberirc.ui.theme.FiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.nickColor
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.roundToInt

/** Every Settings screen, for [SettingsFlow]'s back stack. */
enum class SettingsRoute { ROOT, ACCOUNT, CHANGE_PASSWORD, SESSIONS, BOUNCER, THIS_DEVICE, DISPLAY, NOTIFICATIONS, HIGHLIGHTS, BLOCKED, ENCRYPTION }

/**
 * Gear → Settings as one self-contained flow: the root list and every screen it pushes, with Back popping
 * (system back included). [onClose] leaves Settings from the root. Notifications opens the v2 policy screen when the
 * server has a notification policy, else the v1 screen (older servers).
 */
@Composable
fun SettingsFlow(
    api: SiteApi,
    baseUrl: String,
    accountSettings: AccountSettingsStore,
    deviceSettings: DeviceSettingsStore,
    displaySettings: DisplaySettingsStore,
    notificationPrefs: NotificationPrefsStore,
    notificationPolicy: NotificationPolicyStore,
    blockList: BlockListStore,
    sessionActions: SessionActions,
    /** Shared app-update checker: Check for updates runs a manual check; the root notice host shows its result. */
    appUpdates: AppUpdates,
    onClose: () -> Unit,
    /** The first screen: Settings' root, or one screen pushed over it (Back still returns to the root). */
    start: SettingsRoute = SettingsRoute.ROOT,
) {
    var stack by rememberSaveable { mutableStateOf(if (start == SettingsRoute.ROOT) listOf(start) else listOf(SettingsRoute.ROOT, start)) }
    val push: (SettingsRoute) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) else onClose() }
    val openLinksInApp = { deviceSettings.settings.value.openLinksInApp }
    val policyView by notificationPolicy.view.collectAsStateWithLifecycle()
    when (stack.last()) {
        SettingsRoute.ROOT -> SettingsScreen(
            api, baseUrl, accountSettings, deviceSettings, displaySettings, notificationPrefs, notificationPolicy, blockList,
            sessionActions, appUpdates, onNavigate = push, onClose = pop,
        )
        SettingsRoute.ACCOUNT -> AccountScreen(api, onChangePassword = { push(SettingsRoute.CHANGE_PASSWORD) }, onBack = pop)
        SettingsRoute.CHANGE_PASSWORD -> ChangePasswordScreen(api, baseUrl, openLinksInApp(), onBack = pop)
        SettingsRoute.SESSIONS -> LoginSessionsScreen(api, onBack = pop)
        SettingsRoute.BOUNCER -> BouncerScreen(api, onBack = pop)
        SettingsRoute.THIS_DEVICE -> ThisDeviceScreen(api, onBack = pop)
        SettingsRoute.DISPLAY -> DisplaySettingsScreen(displaySettings, accountSettings, deviceSettings, onBack = pop)
        SettingsRoute.NOTIFICATIONS -> if (policyView != null) {
            val highlightWords by accountSettings.highlightWords.collectAsStateWithLifecycle()
            NotificationPolicyScreen(
                notificationPolicy,
                highlightWordCount = highlightWords.size,
                baseUrl = baseUrl,
                openLinksInApp = openLinksInApp(),
                onOpenHighlightWords = { push(SettingsRoute.HIGHLIGHTS) },
                onBack = pop,
            )
        } else {
            NotificationSettingsScreen(notificationPrefs, onBack = pop)
        }
        SettingsRoute.HIGHLIGHTS -> HighlightWordsScreen(accountSettings, notificationPolicy, onBack = pop)
        SettingsRoute.BLOCKED -> BlockedUsersScreen(blockList, onBack = pop)
        SettingsRoute.ENCRYPTION -> EncryptionSettingsScreen(sessionActions.e2ee, api, onBack = pop)
    }
}

private enum class Destination(val title: String, val icon: ImageVector, val route: SettingsRoute) {
    SESSIONS("Login Sessions", Icons.Filled.Devices, SettingsRoute.SESSIONS),
    BOUNCER("Bouncer", Icons.Filled.SettingsInputAntenna, SettingsRoute.BOUNCER),
    THIS_DEVICE("This Device", Icons.Filled.PhoneAndroid, SettingsRoute.THIS_DEVICE),
    DISPLAY("Display", Icons.Filled.FormatSize, SettingsRoute.DISPLAY),
    NOTIFICATIONS("Notifications", Icons.Filled.NotificationsActive, SettingsRoute.NOTIFICATIONS),
    HIGHLIGHTS("Highlight Words", Icons.Filled.BorderColor, SettingsRoute.HIGHLIGHTS),
    BLOCKED("Blocked Users", Icons.Filled.PanTool, SettingsRoute.BLOCKED),
    ENCRYPTION("Encryption", Icons.Filled.Lock, SettingsRoute.ENCRYPTION);

    fun tint(c: FiberIrcColors): Color = when (this) {
        SESSIONS -> c.accent
        BOUNCER -> Brand.violet
        THIS_DEVICE -> c.submitGreen
        DISPLAY -> c.fiber
        NOTIFICATIONS -> c.error
        HIGHLIGHTS -> Brand.amber
        BLOCKED -> Brand.teal
        ENCRYPTION -> c.submitGreen
    }

    val glyph: Color get() = if (this == DISPLAY) Color(0xFF06222A) else Color.White
}

private enum class FooterItem(val title: String, val icon: ImageVector?) {
    SUPPORT("Contact Support", Icons.AutoMirrored.Filled.HelpOutline),
    TERMS("Terms of Use", Icons.AutoMirrored.Filled.Article),
    PRIVACY("Privacy Policy", Icons.Filled.PrivacyTip),
    DIAGNOSTICS("Export Diagnostics", Icons.Filled.Share),
    VERSION("Version", null),
    CHECK_FOR_UPDATES(UpdateTexts.CHECK_TITLE, Icons.Filled.SystemUpdate),
}

private sealed interface Profile {
    data object Loading : Profile
    data object SignedOut : Profile
    data class SignedIn(val username: String, val email: String) : Profile
}

fun Appearance.title(): String = when (this) {
    Appearance.SYSTEM -> "System"
    Appearance.LIGHT -> "Light"
    Appearance.DARK -> "Dark"
}

/** Case- and diacritic-insensitive "contains" (`.caseInsensitive, .diacriticInsensitive`). */
internal fun foldedContains(text: String, query: String): Boolean = fold(text).contains(fold(query))

private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * Settings root (ports `SettingsViewController`): a search field, the profile row (→ Account once signed in), a
 * colour-coded grid of destination tiles, a footer list (Contact Support, Terms of Use, Privacy Policy, Export
 * Diagnostics, Version, Check for updates) and the destructive list (Sign Out, Delete Account…). Check for updates
 * only starts the shared manual check and shows its status; `UpdateNoticeHost` presents the outcome.
 */
@Composable
fun SettingsScreen(
    api: SiteApi,
    baseUrl: String,
    accountSettings: AccountSettingsStore,
    deviceSettings: DeviceSettingsStore,
    displaySettings: DisplaySettingsStore,
    notificationPrefs: NotificationPrefsStore,
    notificationPolicy: NotificationPolicyStore,
    blockList: BlockListStore,
    sessionActions: SessionActions,
    appUpdates: AppUpdates,
    onNavigate: (SettingsRoute) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    var profile by remember { mutableStateOf<Profile>(Profile.Loading) }
    var notificationsAllowed by remember { mutableStateOf<Boolean?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    val appearance by displaySettings.appearance.collectAsStateWithLifecycle()
    val highlightWords by accountSettings.highlightWords.collectAsStateWithLifecycle()
    val masks by blockList.masks.collectAsStateWithLifecycle()
    val updateChecking by appUpdates.checking.collectAsStateWithLifecycle()
    val updateState by appUpdates.state.collectAsStateWithLifecycle()

    // Re-read on appear and on return from system settings, where the user may have turned notifications on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LaunchedEffect(Unit) {
        profile = api.me().fold(
            onSuccess = { me ->
                accountSettings.load(me)
                notificationPrefs.load(me)
                notificationPolicy.load(me)
                Profile.SignedIn(me.username, me.email)
            },
            onFailure = { Profile.SignedOut },
        )
    }

    val e2eeState = sessionActions.e2ee?.state?.collectAsStateWithLifecycle()?.value

    fun value(d: Destination): String? = when (d) {
        Destination.DISPLAY -> appearance.title()
        Destination.NOTIFICATIONS -> notificationsAllowed?.let { if (it) "Allowed" else "Off" }
        Destination.HIGHLIGHTS -> highlightWords.size.toString()
        Destination.BLOCKED -> masks.mapNotNull(BlockListStore::nickPattern).size.toString()
        Destination.ENCRYPTION -> when (e2eeState) {
            is E2eeServiceState.Running -> if (e2eeState.status.locked) "Locked" else if (e2eeState.status.ready) "On" else "Setup"
            is E2eeServiceState.Unavailable, is E2eeServiceState.RecoveryRequired -> "Problem"
            else -> null
        }
        else -> null
    }

    fun openSite(path: String) = LinkOpener.open(context, "${baseUrl.trimEnd('/')}/$path", deviceSettings.settings.value.openLinksInApp)

    fun select(item: FooterItem) {
        when (item) {
            FooterItem.SUPPORT -> openSite("support")
            FooterItem.TERMS -> openSite("terms")
            FooterItem.PRIVACY -> openSite("privacy")
            FooterItem.DIAGNOSTICS -> scope.launch {
                Diagnostics.export(context, Diagnostics.header(context, appearance, accountSettings.display.value.showUserIcons))
                    .onFailure { alerts.show("Couldn't export diagnostics", it) }
            }
            FooterItem.VERSION -> Unit
            // The caller's scope only waits: the shared check outlives Settings and the root host shows the result.
            FooterItem.CHECK_FOR_UPDATES -> scope.launch { appUpdates.check(manual = true) }
        }
    }

    val trimmed = query.trim()
    val searching = trimmed.isNotEmpty()
    fun matches(title: String) = !searching || foldedContains(title, trimmed)
    val destinations = Destination.entries.filter { (it != Destination.ENCRYPTION || sessionActions.e2ee != null) && matches(it.title) }
    val footerItems = FooterItem.entries.filter { matches(it.title) }
    val signOutTitle = "Sign Out"
    val deleteTitle = "Delete Account…"
    val destructive = listOf(signOutTitle, deleteTitle).filter { matches(it) }
    val noResults = searching && destinations.isEmpty() && footerItems.isEmpty() && destructive.isEmpty()

    SettingsScaffold(title = "Settings", onBack = onClose, message = if (noResults) "No results" else null) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search Settings") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                enabled = !busy,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.groupedCell,
                    unfocusedContainerColor = c.groupedCell,
                    focusedTextColor = c.chatText,
                    unfocusedTextColor = c.chatText,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = Grid.gutter),
            )
        }
        if (!searching) {
            settingsSection(
                key = "profile",
                rows = listOf {
                    val p = profile
                    SettingsRow(
                        title = when (p) {
                            Profile.Loading -> "…"
                            Profile.SignedOut -> "Not signed in"
                            is Profile.SignedIn -> p.username
                        },
                        subtitle = (p as? Profile.SignedIn)?.email?.takeIf { it.isNotEmpty() },
                        leading = (p as? Profile.SignedIn)?.let { { LetterAvatar(it.username) } },
                        onClick = if (p is Profile.SignedIn && !busy) ({ onNavigate(SettingsRoute.ACCOUNT) }) else null,
                        trailing = if (p is Profile.SignedIn) ({ Chevron() }) else null,
                    )
                },
            )
        }
        if (destinations.isNotEmpty()) {
            item(key = "destinations") {
                Column(Modifier.padding(top = Grid.gutter), verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                    for (row in destinations.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                            for (d in row) {
                                DestinationTile(
                                    title = d.title,
                                    icon = d.icon,
                                    tint = d.tint(c),
                                    glyph = d.glyph,
                                    value = value(d),
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onNavigate(d.route) },
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (footerItems.isNotEmpty()) {
            settingsSection(
                key = "footer",
                rows = footerItems.map { item ->
                    {
                        if (item == FooterItem.CHECK_FOR_UPDATES) {
                            SettingsRow(
                                title = item.title,
                                modifier = Modifier.testTag(CHECK_FOR_UPDATES_TAG),
                                leading = { Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = c.link) },
                                value = updateStatus(updateChecking, updateState),
                                // A running check disables only this action.
                                onClick = if (updateChecking || busy) null else ({ select(item) }),
                            )
                        } else {
                            SettingsRow(
                                title = item.title,
                                leading = item.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = c.link) } },
                                value = if (item == FooterItem.VERSION) "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" else null,
                                onClick = if (item == FooterItem.VERSION || busy) null else ({ select(item) }),
                            )
                        }
                    }
                },
            )
        }
        if (destructive.isNotEmpty()) {
            settingsSection(
                key = "destructive",
                footer = "Permanently erases your account, networks, history and NickServ registration on every device.",
                rows = destructive.map { title ->
                    {
                        SettingsRow(
                            title = title,
                            titleColor = c.error,
                            centered = true,
                            onClick = if (busy) null else ({
                                if (title == signOutTitle) {
                                    // No touches while the server calls run (`isUserInteractionEnabled = false`); a
                                    // sign-out cancelled at the encryption-keys question gives the screen back.
                                    busy = true
                                    scope.launch {
                                        sessionActions.signOut()
                                        busy = false
                                    }
                                } else {
                                    confirmation = Confirmation(
                                        title = "Delete Account",
                                        message = "Delete your IRC Fiber account? This permanently erases your account, networks, " +
                                            "history and NickServ registration on every device.",
                                        button = "Delete",
                                    ) {
                                        busy = true
                                        scope.launch {
                                            sessionActions.deleteAccount().onFailure { alerts.show("Account not deleted", it) }
                                            busy = false
                                        }
                                    }
                                }
                            }),
                        )
                    }
                },
            )
        }
    }
    ConfirmDialog(confirmation) { confirmation = null }
    ErrorDialog(alerts)
}

/** Test tag of the Check for updates row (iOS `settings.checkForUpdates`). */
const val CHECK_FOR_UPDATES_TAG = "settings.checkForUpdates"

/** The Check for updates row's status, from the shared update state. */
private fun updateStatus(checking: Boolean, state: UpdateState): String? = when {
    checking -> "Checking…"
    else -> when (state) {
        UpdateState.Idle, UpdateState.Checking -> null
        UpdateState.UpToDate -> "Up to date"
        is UpdateState.Available ->
            state.release.versionName?.let { "Version $it available" } ?: "Build ${state.release.versionCode} available"
        is UpdateState.Downloading -> state.progress?.let { "Downloading ${(it * 100).roundToInt()}%" } ?: "Downloading…"
        is UpdateState.Downloaded -> "Ready to install"
        is UpdateState.Installing -> "Installing…"
        is UpdateState.RequiresOs -> "Needs newer Android"
        UpdateState.Unavailable -> "Couldn't check"
    }
}

/** A colour-coded destination tile: icon badge, title, current-state value. */
@Composable
private fun DestinationTile(
    title: String,
    icon: ImageVector,
    tint: Color,
    glyph: Color,
    value: String?,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .height(100.dp)
            .clip(shape)
            .background(c.groupedCell)
            .border(1.dp, c.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = Grid.margin, start = Grid.unit * 2, end = Grid.unit * 2, bottom = Grid.unit),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            color = c.chatText,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = Grid.unit * 2),
        )
        if (value != null) {
            Text(value, color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Letter avatar in the user's nick colour. */
@Composable
internal fun LetterAvatar(name: String) {
    val c = LocalFiberIrcColors.current
    Box(Modifier.size(Grid.leadingColumn).clip(CircleShape).background(nickColor(name, c.dark)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = c.avatarText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = LocalFiberIrcColors.current.textTertiary)
}
