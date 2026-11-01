package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.irc.ComposeStyle
import com.kevinpostal.fiberirc.data.irc.ComposeStyleRecent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * Settings that stay on this phone, never synced (ports `DeviceSettings.swift`): message layout, chat & embeds,
 * device options and photo sharing. Account-synced chat settings are [AccountSettingsStore] and [BufferPrefsStore].
 */
data class DeviceSettings(
    // Message layout
    val nicknamesOnSeparateLine: Boolean = true,
    val showRealNames: Boolean = true,
    val timestampsOnRight: Boolean = true,
    val compactSpacing: Boolean = false,
    val showSeconds: Boolean = false,
    val colouriseNicknames: Boolean = true,
    val convertEmoji: Boolean = true,
    // Chat & embeds
    /** Show joins, parts, quits where a buffer has no setting of its own. */
    val showJoinPartDefault: Boolean = true,
    /** Collapse joins, parts, quits where a buffer has no setting of its own. */
    val collapseJoinPart: Boolean = true,
    val embedUploads: Boolean = true,
    val embedExternalMedia: Boolean = true,
    val embedOnCellular: Boolean = true,
    val shareTyping: Boolean = true,
    val showDeletedMessages: Boolean = false,
    val clearFormattingAfterSending: Boolean = true,
    /** Outgoing-message styling picked in the composer's "Aa" sheet. */
    val composeStyle: ComposeStyle = ComposeStyle.Default,
    /** The style the "Aa" button last switched off, so re-opening the sheet starts from it. */
    val composeStyleLast: ComposeStyle = ComposeStyle.Default,
    /** Last-used styles, most recent first; at most [MAX_RECENTS]. */
    val composeStyleRecents: List<ComposeStyleRecent> = emptyList(),
    // Device
    /** Keeps the screen on in chat (`FLAG_KEEP_SCREEN_ON`; iOS `isIdleTimerDisabled`). */
    val preventAutoLock: Boolean = false,
    val autoCapitalization: Boolean = true,
    /** Links open in a Custom Tab (else the system browser). */
    val openLinksInApp: Boolean = true,
    val sidebarsInLandscape: Boolean = true,
    val askToPostSnippet: Boolean = true,
    val imagesInBrowser: Boolean = false,
    val videosInBrowser: Boolean = false,
    /** Chat text size multiplier within [CHAT_FONT_SCALE_RANGE]. */
    val chatFontScale: Double = 1.0,
    // Photo sharing
    val saveToCameraRoll: Boolean = true,
    /** Longest side of a shared photo in pixels; 0 sends the original. */
    val photoMaxDimension: Int = 1024,
) {
    companion object {
        val CHAT_FONT_SCALE_RANGE = 0.8..1.4
        const val MAX_RECENTS = 8
    }
}

/** `device_settings` SharedPreferences, keys `device.<name>` (the iOS UserDefaults names). Main-thread confined. */
class DeviceSettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

    /** Applies [change] (clamping scale, dimension and recents) and saves it. */
    fun update(change: (DeviceSettings) -> DeviceSettings) {
        val next = normalized(change(_settings.value))
        if (next == _settings.value) return
        _settings.value = next
        write(next)
    }

    private fun normalized(s: DeviceSettings) = s.copy(
        chatFontScale = s.chatFontScale.coerceIn(DeviceSettings.CHAT_FONT_SCALE_RANGE),
        photoMaxDimension = s.photoMaxDimension.coerceAtLeast(0),
        composeStyleRecents = s.composeStyleRecents.take(DeviceSettings.MAX_RECENTS),
    )

    private fun read(): DeviceSettings {
        val d = DeviceSettings()
        fun b(name: String, fallback: Boolean) = if (prefs.contains(key(name))) prefs.getBoolean(key(name), fallback) else fallback
        return normalized(
            DeviceSettings(
                nicknamesOnSeparateLine = b("nicknamesOnSeparateLine", d.nicknamesOnSeparateLine),
                showRealNames = b("showRealNames", d.showRealNames),
                timestampsOnRight = b("timestampsOnRight", d.timestampsOnRight),
                compactSpacing = b("compactSpacing", d.compactSpacing),
                showSeconds = b("showSeconds", d.showSeconds),
                colouriseNicknames = b("colouriseNicknames", d.colouriseNicknames),
                convertEmoji = b("convertEmoji", d.convertEmoji),
                showJoinPartDefault = b("showJoinPartDefault", d.showJoinPartDefault),
                collapseJoinPart = b("collapseJoinPart", d.collapseJoinPart),
                embedUploads = b("embedUploads", d.embedUploads),
                embedExternalMedia = b("embedExternalMedia", d.embedExternalMedia),
                embedOnCellular = b("embedOnCellular", d.embedOnCellular),
                shareTyping = b("shareTyping", d.shareTyping),
                showDeletedMessages = b("showDeletedMessages", d.showDeletedMessages),
                clearFormattingAfterSending = b("clearFormattingAfterSending", d.clearFormattingAfterSending),
                composeStyle = json("composeStyle", ComposeStyle.serializer()) ?: d.composeStyle,
                composeStyleLast = json("composeStyleLast", ComposeStyle.serializer()) ?: d.composeStyleLast,
                composeStyleRecents = json("composeStyleRecents", RECENTS) ?: d.composeStyleRecents,
                preventAutoLock = b("preventAutoLock", d.preventAutoLock),
                autoCapitalization = b("autoCapitalization", d.autoCapitalization),
                openLinksInApp = b("openLinksInApp", d.openLinksInApp),
                sidebarsInLandscape = b("sidebarsInLandscape", d.sidebarsInLandscape),
                askToPostSnippet = b("askToPostSnippet", d.askToPostSnippet),
                imagesInBrowser = b("imagesInBrowser", d.imagesInBrowser),
                videosInBrowser = b("videosInBrowser", d.videosInBrowser),
                chatFontScale = prefs.getString(key("chatFontScale"), null)?.toDoubleOrNull() ?: d.chatFontScale,
                saveToCameraRoll = b("saveToCameraRoll", d.saveToCameraRoll),
                photoMaxDimension = if (prefs.contains(key("photoMaxDimension"))) prefs.getInt(key("photoMaxDimension"), d.photoMaxDimension) else d.photoMaxDimension,
            ),
        )
    }

    private fun write(s: DeviceSettings) {
        val e = prefs.edit()
        fun b(name: String, v: Boolean) = e.putBoolean(key(name), v)
        b("nicknamesOnSeparateLine", s.nicknamesOnSeparateLine)
        b("showRealNames", s.showRealNames)
        b("timestampsOnRight", s.timestampsOnRight)
        b("compactSpacing", s.compactSpacing)
        b("showSeconds", s.showSeconds)
        b("colouriseNicknames", s.colouriseNicknames)
        b("convertEmoji", s.convertEmoji)
        b("showJoinPartDefault", s.showJoinPartDefault)
        b("collapseJoinPart", s.collapseJoinPart)
        b("embedUploads", s.embedUploads)
        b("embedExternalMedia", s.embedExternalMedia)
        b("embedOnCellular", s.embedOnCellular)
        b("shareTyping", s.shareTyping)
        b("showDeletedMessages", s.showDeletedMessages)
        b("clearFormattingAfterSending", s.clearFormattingAfterSending)
        e.putString(key("composeStyle"), SiteJson.encodeToString(ComposeStyle.serializer(), s.composeStyle))
        e.putString(key("composeStyleLast"), SiteJson.encodeToString(ComposeStyle.serializer(), s.composeStyleLast))
        e.putString(key("composeStyleRecents"), SiteJson.encodeToString(RECENTS, s.composeStyleRecents))
        b("preventAutoLock", s.preventAutoLock)
        b("autoCapitalization", s.autoCapitalization)
        b("openLinksInApp", s.openLinksInApp)
        b("sidebarsInLandscape", s.sidebarsInLandscape)
        b("askToPostSnippet", s.askToPostSnippet)
        b("imagesInBrowser", s.imagesInBrowser)
        b("videosInBrowser", s.videosInBrowser)
        e.putString(key("chatFontScale"), s.chatFontScale.toString())
        b("saveToCameraRoll", s.saveToCameraRoll)
        e.putInt(key("photoMaxDimension"), s.photoMaxDimension)
        e.apply()
    }

    private fun <T> json(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key(name), null)?.let { runCatching { SiteJson.decodeFromString(serializer, it) }.getOrNull() }

    companion object {
        const val FILE = "device_settings"
        private val RECENTS = ListSerializer(ComposeStyleRecent.serializer())
        private fun key(name: String) = "device.$name"
    }
}
