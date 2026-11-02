package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.data.prefs.DeviceSettings
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.data.prefs.DisplayPrefs
import com.kevinpostal.fiberirc.data.prefs.DisplaySettingsStore
import com.kevinpostal.fiberirc.data.prefs.ModeIndicator
import com.kevinpostal.fiberirc.data.prefs.TimestampFormat
import com.kevinpostal.fiberirc.ui.theme.Appearance
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A switch over a [DeviceSettings] flag. */
private class DeviceToggle(val title: String, val get: (DeviceSettings) -> Boolean, val set: (DeviceSettings, Boolean) -> DeviceSettings)

/** A switch over an account display pref. */
private class AccountToggle(val title: String, val get: (DisplayPrefs) -> Boolean, val set: (DisplayPrefs, Boolean) -> DisplayPrefs)

private sealed interface DisplayRow {
    class Device(val toggle: DeviceToggle) : DisplayRow
    class Account(val toggle: AccountToggle) : DisplayRow
    data object MemberPrefixes : DisplayRow
    data object TimestampFormatRow : DisplayRow
    data object ModeIndicatorRow : DisplayRow
    data object OpenLinks : DisplayRow
    data object ImageSize : DisplayRow
}

private fun device(title: String, get: (DeviceSettings) -> Boolean, set: (DeviceSettings, Boolean) -> DeviceSettings) =
    DisplayRow.Device(DeviceToggle(title, get, set))

private fun account(title: String, get: (DisplayPrefs) -> Boolean, set: (DisplayPrefs, Boolean) -> DisplayPrefs) =
    DisplayRow.Account(AccountToggle(title, get, set))

private val messageLayoutRows: List<DisplayRow> = listOf(
    device("Nicknames on Separate Line", { it.nicknamesOnSeparateLine }) { s, v -> s.copy(nicknamesOnSeparateLine = v) },
    device("Show Real Names", { it.showRealNames }) { s, v -> s.copy(showRealNames = v) },
    device("Right Hand Side Timestamps", { it.timestampsOnRight }) { s, v -> s.copy(timestampsOnRight = v) },
    account("Show Avatars", { it.showUserIcons }) { p, v -> p.copy(showUserIcons = v) },
    account("Show Timestamps", { it.showTimestamps }) { p, v -> p.copy(showTimestamps = v) },
    DisplayRow.TimestampFormatRow,
    device("Show Seconds", { it.showSeconds }) { s, v -> s.copy(showSeconds = v) },
    DisplayRow.ModeIndicatorRow,
    device("Compact Spacing", { it.compactSpacing }) { s, v -> s.copy(compactSpacing = v) },
    device("Colourise Nicknames", { it.colouriseNicknames }) { s, v -> s.copy(colouriseNicknames = v) },
    account("Colourise Mentions", { it.coloriseMentions }) { p, v -> p.copy(coloriseMentions = v) },
    device("Convert :emocodes: to Emoji", { it.convertEmoji }) { s, v -> s.copy(convertEmoji = v) },
    account("Enlarge Emoji Messages", { it.enlargeEmoji }) { p, v -> p.copy(enlargeEmoji = v) },
    account("Monospace Font", { it.monospaceFont }) { p, v -> p.copy(monospaceFont = v) },
    account("Format Colours", { it.formatColors }) { p, v -> p.copy(formatColors = v) },
    DisplayRow.MemberPrefixes,
)

private val chatEmbedRows: List<DisplayRow> = listOf(
    device("Show Joins, Parts, Quits", { it.showJoinPartDefault }) { s, v -> s.copy(showJoinPartDefault = v) },
    device("Collapse Joins, Parts, Quits", { it.collapseJoinPart }) { s, v -> s.copy(collapseJoinPart = v) },
    device("Embed Uploaded Files", { it.embedUploads }) { s, v -> s.copy(embedUploads = v) },
    device("Embed External Media", { it.embedExternalMedia }) { s, v -> s.copy(embedExternalMedia = v) },
    device("Embed Using Mobile Data", { it.embedOnCellular }) { s, v -> s.copy(embedOnCellular = v) },
    device("Share Typing Status", { it.shareTyping }) { s, v -> s.copy(shareTyping = v) },
    device("Show Deleted Messages", { it.showDeletedMessages }) { s, v -> s.copy(showDeletedMessages = v) },
    device("Clear Colours After Sending", { it.clearFormattingAfterSending }) { s, v -> s.copy(clearFormattingAfterSending = v) },
)

private val deviceRows: List<DisplayRow> = listOf(
    device("Prevent Auto-Lock", { it.preventAutoLock }) { s, v -> s.copy(preventAutoLock = v) },
    device("Auto-Capitalization", { it.autoCapitalization }) { s, v -> s.copy(autoCapitalization = v) },
    DisplayRow.OpenLinks,
    device("Show Sidebars In Landscape", { it.sidebarsInLandscape }) { s, v -> s.copy(sidebarsInLandscape = v) },
    device("Ask To Post A Snippet", { it.askToPostSnippet }) { s, v -> s.copy(askToPostSnippet = v) },
    device("Open Images in Browser", { it.imagesInBrowser }) { s, v -> s.copy(imagesInBrowser = v) },
    device("Open Videos in Browser", { it.videosInBrowser }) { s, v -> s.copy(videosInBrowser = v) },
)

private val photoRows: List<DisplayRow> = listOf(
    device("Save to Camera Roll", { it.saveToCameraRoll }) { s, v -> s.copy(saveToCameraRoll = v) },
    DisplayRow.ImageSize,
)

private val photoSizes = listOf(512, 1024, 2048, 0)

internal fun TimestampFormat.title(): String = when (this) {
    TimestampFormat.AUTO -> "Automatic"
    TimestampFormat.TWELVE_HOUR -> "12-hour"
    TimestampFormat.TWENTY_FOUR_HOUR -> "24-hour"
}

internal fun ModeIndicator.title(): String = when (this) {
    ModeIndicator.DOTS -> "Colored dots"
    ModeIndicator.SYMBOLS -> "@ / + symbols"
    ModeIndicator.HIDDEN -> "Hidden"
}

internal fun photoSizeTitle(size: Int): String = when (size) {
    512 -> "Small"
    1024 -> "Medium"
    2048 -> "Large"
    else -> "Original"
}

private fun Appearance.icon(): ImageVector = when (this) {
    Appearance.SYSTEM -> Icons.Filled.BrightnessMedium
    Appearance.LIGHT -> Icons.Filled.LightMode
    Appearance.DARK -> Icons.Filled.DarkMode
}

/** "110%". */
internal fun percent(scale: Double): String = "${(scale * 100).roundToInt()}%"

/** Snaps a slider value (percent) to 10 % steps, as a scale (`fontScaleChanged`). */
internal fun snapFontScale(percentValue: Float): Double = ((percentValue / 10f).roundToInt() * 10) / 100.0

/**
 * Settings → Display (ports `DisplaySettingsViewController`), in IRCCloud's sections: this device's appearance,
 * message layout, chat & embeds, device options, font size and photo sharing. Rows bind to [DeviceSettingsStore]
 * (kept on this device), [DisplaySettingsStore] (appearance) or [AccountSettingsStore] (kept on the account under
 * this install's profile: POST /api/me/display-prefs, POST /api/me/show-member-prefixes); each saves at once (the
 * font size slider on release) and a failed account save puts the control back.
 */
@Composable
fun DisplaySettingsScreen(
    displaySettings: DisplaySettingsStore,
    accountSettings: AccountSettingsStore,
    deviceSettings: DeviceSettingsStore,
    onBack: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    val appearance by displaySettings.appearance.collectAsStateWithLifecycle()
    val prefs by accountSettings.display.collectAsStateWithLifecycle()
    val memberPrefixes by accountSettings.showMemberPrefixes.collectAsStateWithLifecycle()
    val device by deviceSettings.settings.collectAsStateWithLifecycle()

    fun save(change: suspend () -> Unit) {
        scope.launch { runCatching { change() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) alerts.show("Couldn't save setting", it) } }
    }

    @Composable
    fun row(r: DisplayRow) {
        when (r) {
            is DisplayRow.Device -> SettingsSwitchRow(r.toggle.title, r.toggle.get(device)) { on ->
                deviceSettings.update { r.toggle.set(it, on) }
            }
            is DisplayRow.Account -> SettingsSwitchRow(r.toggle.title, r.toggle.get(prefs)) { on ->
                save { accountSettings.updateDisplay { r.toggle.set(it, on) } }
            }
            DisplayRow.MemberPrefixes -> SettingsSwitchRow("Member Prefixes", memberPrefixes) { on ->
                save { accountSettings.setShowMemberPrefixes(on) }
            }
            DisplayRow.TimestampFormatRow -> PopUpRow("Timestamp Format", TimestampFormat.entries, prefs.timestampFormat, { it.title() }) { f ->
                save { accountSettings.updateDisplay { it.copy(timestampFormat = f) } }
            }
            DisplayRow.ModeIndicatorRow -> PopUpRow("Mode Indicators", ModeIndicator.entries, prefs.modeIndicator, { it.title() }) { m ->
                save { accountSettings.updateDisplay { it.copy(modeIndicator = m) } }
            }
            DisplayRow.OpenLinks -> PopUpRow(
                "Open Links In",
                listOf(true, false),
                device.openLinksInApp,
                { if (it) "In-App Browser" else "Browser" },
            ) { v -> deviceSettings.update { it.copy(openLinksInApp = v) } }
            DisplayRow.ImageSize -> PopUpRow(
                "Image Size",
                photoSizes,
                device.photoMaxDimension,
                ::photoSizeTitle,
                subtitle = { if (it > 0) "$it px" else null },
            ) { v -> deviceSettings.update { it.copy(photoMaxDimension = v) } }
        }
    }

    SettingsScaffold(title = "Display", onBack = onBack) {
        settingsSection(
            key = "appearance",
            header = "Appearance",
            footer = "This device only.",
            rows = Appearance.entries.map { option ->
                {
                    val selected = option == appearance
                    SettingsRow(
                        option.title(),
                        leading = { Icon(option.icon(), contentDescription = null, tint = c.link) },
                        onClick = { displaySettings.setAppearance(option) },
                        trailing = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.accent) }) else null,
                    )
                }
            },
        )
        settingsSection(
            key = "messageLayout",
            header = "Message Layout",
            footer = "Message layout settings apply to this device only; the web and your other devices keep their own.",
            rows = messageLayoutRows.map { r -> { row(r) } },
        )
        settingsSection(
            key = "chatEmbeds",
            header = "Chat & Embeds",
            footer = "This device only. A channel or conversation with its own setting keeps it.",
            rows = chatEmbedRows.map { r -> { row(r) } },
        )
        settingsSection(
            key = "device",
            header = "Device",
            footer = "This device only.",
            rows = deviceRows.map { r -> { row(r) } },
        )
        settingsSection(
            key = "fontSize",
            header = "Font Size",
            footer = "Chat text on this device, on top of the system font size (Settings → Display → Font size).",
            rows = listOf(
                { FontScaleSlider(prefs.chatFontScale) { s -> save { accountSettings.updateDisplay { it.copy(chatScalePct = (s * 100).roundToInt()) } } } },
                {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = Grid.margin, vertical = Grid.gutter),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "The quick brown fox jumps over the lazy dog.",
                            color = c.chatText,
                            fontFamily = sampleFamily(prefs.monospaceFont),
                            fontSize = (15 * prefs.chatFontScale).sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(percent(prefs.chatFontScale), color = c.textSecondary, modifier = Modifier.padding(start = Grid.gutter))
                    }
                },
            ),
        )
        settingsSection(
            key = "photoSharing",
            header = "Photo Sharing",
            footer = "This device only. Image Size is the longest side of the photos you share.",
            rows = photoRows.map { r -> { row(r) } },
        )
    }
    ErrorDialog(alerts)
}

/** 80–140 % in 10 % steps; dragging snaps, and releasing saves once ([onCommit]), so the sample row follows the save. */
@Composable
private fun FontScaleSlider(scale: Double, onCommit: (Double) -> Unit) {
    val c = LocalFiberIrcColors.current
    val range = DisplayPrefs.CHAT_SCALE_PCT_RANGE
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf((scale * 100).toFloat()) }
    var pending by remember { mutableDoubleStateOf(scale) }
    // Follows the saved value (a commit, a failed save putting it back, another device's change) except mid-drag.
    LaunchedEffect(scale) {
        if (!dragging) {
            value = (scale * 100).toFloat()
            pending = scale
        }
    }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = Grid.margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.TextDecrease, contentDescription = null, tint = c.textSecondary)
        Slider(
            value = value,
            onValueChange = { v ->
                dragging = true
                pending = snapFontScale(v)
                value = (pending * 100).toFloat()
            },
            onValueChangeFinished = {
                dragging = false
                if (pending != scale) onCommit(pending)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = 5,
            colors = SliderDefaults.colors(activeTrackColor = c.accent, thumbColor = c.accent),
            modifier = Modifier.weight(1f).padding(horizontal = Grid.gutter),
        )
        Icon(Icons.Filled.TextIncrease, contentDescription = null, tint = c.textSecondary)
    }
}

/** The sample's font: the chat's (monospace when the account's Monospace Font is on). */
internal fun sampleFamily(monospace: Boolean): FontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
