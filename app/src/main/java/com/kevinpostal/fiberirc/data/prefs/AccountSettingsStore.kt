package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.PolicyHighlight
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class ModeIndicator(val wire: String) {
    DOTS("dots"), SYMBOLS("symbols"), HIDDEN("hidden");

    companion object {
        fun from(wire: String?): ModeIndicator? = entries.firstOrNull { it.wire == wire }
    }
}

enum class TimestampFormat(val wire: String) {
    AUTO("auto"), TWELVE_HOUR("12h"), TWENTY_FOUR_HOUR("24h");

    companion object {
        fun from(wire: String?): TimestampFormat? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * This install's chat display prefs (ports `DisplayPrefs`), kept on the account under its `X-IRCFiber-Device`
 * profile. Wire keys and defaults are the server's (db/preferences.d); missing keys and unknown values decode to
 * the defaults.
 */
data class DisplayPrefs(
    /** Server-managed; never sent. */
    val displayPrefsSet: Boolean = false,
    val showUserIcons: Boolean = true,
    val monospaceFont: Boolean = true,
    val modeIndicator: ModeIndicator = ModeIndicator.DOTS,
    val enlargeEmoji: Boolean = true,
    val coloriseMentions: Boolean = true,
    val formatColors: Boolean = true,
    val showTimestamps: Boolean = true,
    val timestampFormat: TimestampFormat = TimestampFormat.AUTO,
    /** Message text size in percent: 80–140, a multiple of 5 (the app's slider snaps to 10). */
    val chatScalePct: Int = 100,
) {
    /** Chat text size multiplier ([chatScalePct] as a ratio). */
    val chatFontScale: Double get() = chatScalePct / 100.0

    /** The POST /api/me/display-prefs body that turns [old] into this: changed keys only, wire names and values. */
    fun patch(old: DisplayPrefs): JsonObject = buildJsonObject {
        if (showUserIcons != old.showUserIcons) put("showUserIcons", showUserIcons)
        if (monospaceFont != old.monospaceFont) put("monospaceFont", monospaceFont)
        if (modeIndicator != old.modeIndicator) put("modeIndicator", modeIndicator.wire)
        if (enlargeEmoji != old.enlargeEmoji) put("enlargeEmoji", enlargeEmoji)
        if (coloriseMentions != old.coloriseMentions) put("coloriseMentions", coloriseMentions)
        if (formatColors != old.formatColors) put("formatColors", formatColors)
        if (showTimestamps != old.showTimestamps) put("showTimestamps", showTimestamps)
        if (timestampFormat != old.timestampFormat) put("timestampFormat", timestampFormat.wire)
        if (chatScalePct != old.chatScalePct) put("chatScalePct", chatScalePct)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("displayPrefsSet", displayPrefsSet)
        put("showUserIcons", showUserIcons)
        put("monospaceFont", monospaceFont)
        put("modeIndicator", modeIndicator.wire)
        put("enlargeEmoji", enlargeEmoji)
        put("coloriseMentions", coloriseMentions)
        put("formatColors", formatColors)
        put("showTimestamps", showTimestamps)
        put("timestampFormat", timestampFormat.wire)
        put("chatScalePct", chatScalePct)
    }

    companion object {
        fun fromJson(o: JsonObject): DisplayPrefs {
            val d = DisplayPrefs()
            fun bool(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun int(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            return DisplayPrefs(
                displayPrefsSet = bool("displayPrefsSet") ?: d.displayPrefsSet,
                showUserIcons = bool("showUserIcons") ?: d.showUserIcons,
                monospaceFont = bool("monospaceFont") ?: d.monospaceFont,
                modeIndicator = ModeIndicator.from(str("modeIndicator")) ?: d.modeIndicator,
                enlargeEmoji = bool("enlargeEmoji") ?: d.enlargeEmoji,
                coloriseMentions = bool("coloriseMentions") ?: d.coloriseMentions,
                formatColors = bool("formatColors") ?: d.formatColors,
                showTimestamps = bool("showTimestamps") ?: d.showTimestamps,
                timestampFormat = TimestampFormat.from(str("timestampFormat")) ?: d.timestampFormat,
                chatScalePct = int("chatScalePct")?.takeIf { it in CHAT_SCALE_PCT_RANGE && it % 5 == 0 } ?: d.chatScalePct,
            )
        }

        /** The server's accepted `chatScalePct` range. */
        val CHAT_SCALE_PCT_RANGE = 80..140
    }
}

/** How highlight words match (Notification Policy v2 `highlight`; v1 servers: whole word, ignoring case). */
data class HighlightOptions(val wholeWord: Boolean = true, val caseSensitive: Boolean = false)

/**
 * This device's chat settings as the app uses them (ports `AccountSettings.swift`): display prefs and member
 * prefixes (this install's `X-IRCFiber-Device` profile) and the account's highlight words with their match options
 * (the notification policy's when the server has one). Cached in `account_settings` so the chat has them before the
 * first refresh. An edit applies at once and is saved; the server's answer then wins, and a failed save brings the
 * previous value back (unless a newer edit happened) and rethrows. Main-thread confined.
 */
class AccountSettingsStore(
    private val prefs: SharedPreferences,
    private val saveDisplay: (suspend (JsonObject) -> DisplayPrefs)?,
    private val saveHighlightWords: (suspend (List<String>) -> List<String>)?,
    private val saveShowMemberPrefixes: (suspend (Boolean) -> Unit)?,
) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        saveDisplay = { patch ->
            val answer = api.postJson("api/me/display-prefs", patch).getOrThrow()
            val obj = runCatching { answer.jsonObject["displayPrefs"]!!.jsonObject }.getOrElse { throw SiteApiError.Malformed }
            DisplayPrefs.fromJson(obj)
        },
        saveHighlightWords = { words ->
            val answer = api.postJson("api/me/highlight-words", buildJsonObject { put("words", JsonArray(words.map(::JsonPrimitive))) }).getOrThrow()
            runCatching { answer.jsonObject["highlightWords"]!!.jsonArray.map { (it as JsonPrimitive).content } }
                .getOrElse { throw SiteApiError.Malformed }
        },
        saveShowMemberPrefixes = { on ->
            api.postJson("api/me/show-member-prefixes", buildJsonObject { put("showMemberPrefixes", on) }).getOrThrow()
        },
    )

    private val _display = MutableStateFlow(storedDisplay(prefs))
    val display: StateFlow<DisplayPrefs> = _display.asStateFlow()
    private val _highlightWords = MutableStateFlow(prefs.getStringList(KEY_WORDS) ?: emptyList())
    val highlightWords: StateFlow<List<String>> = _highlightWords.asStateFlow()
    private val _highlightOptions = MutableStateFlow(
        HighlightOptions(prefs.getBoolean(KEY_WHOLE_WORD, true), prefs.getBoolean(KEY_CASE_SENSITIVE, false)),
    )
    val highlightOptions: StateFlow<HighlightOptions> = _highlightOptions.asStateFlow()
    private val _showMemberPrefixes = MutableStateFlow(prefs.getBoolean(KEY_PREFIXES, false))
    val showMemberPrefixes: StateFlow<Boolean> = _showMemberPrefixes.asStateFlow()

    /** Bumped by every local edit (and [reset]): a save's answer, or its revert, applies only while no newer edit happened. */
    private var displayRevision = 0
    private var wordsRevision = 0
    private var prefixesRevision = 0
    /** Saves in flight per field: [load] keeps a field the user is changing. */
    private var displayInFlight = 0
    private var wordsInFlight = 0
    private var prefixesInFlight = 0

    /**
     * Adopts `/api/me`'s values; absent fields and fields with a save in flight keep their current value. A
     * notification policy's highlight settings win over the v1 `highlightWords`.
     */
    fun load(me: Me) {
        var changed = false
        val d = me.displayPrefs?.let(DisplayPrefs::fromJson)
        if (d != null && displayInFlight == 0 && d != _display.value) {
            _display.value = d
            changed = true
        }
        val highlight = me.notificationPolicy?.policy?.highlight
        val w = highlight?.words?.map { it.w } ?: me.highlightWords
        if (w != null && wordsInFlight == 0 && w != _highlightWords.value) {
            _highlightWords.value = w
            changed = true
        }
        val o = highlight?.let { HighlightOptions(it.wholeWord, it.caseSensitive) }
        if (o != null && o != _highlightOptions.value) {
            _highlightOptions.value = o
            changed = true
        }
        val p = me.showMemberPrefixes
        if (p != null && prefixesInFlight == 0 && p != _showMemberPrefixes.value) {
            _showMemberPrefixes.value = p
            changed = true
        }
        if (changed) persist()
    }

    /** The notification policy's highlight words and options changed ([NotificationPolicyStore.onHighlightChanged]). */
    fun adoptPolicyHighlight(highlight: PolicyHighlight) {
        val words = highlight.words.map { it.w }
        val options = HighlightOptions(highlight.wholeWord, highlight.caseSensitive)
        if ((wordsInFlight != 0 || words == _highlightWords.value) && options == _highlightOptions.value) return
        if (wordsInFlight == 0) _highlightWords.value = words
        _highlightOptions.value = options
        persist()
    }

    /** Applies [change] at once and saves the changed keys; the server's answer then wins. */
    suspend fun updateDisplay(change: (DisplayPrefs) -> DisplayPrefs) {
        val old = _display.value
        val next = change(old)
        val patch = next.patch(old)
        if (patch.isEmpty()) return
        val revision = ++displayRevision
        displayInFlight++
        try {
            setDisplay(next)
            val save = saveDisplay ?: return
            try {
                val saved = save(patch)
                if (revision == displayRevision) setDisplay(saved)
            } catch (e: Exception) {
                if (revision == displayRevision) setDisplay(old)
                throw e
            }
        } finally {
            displayInFlight--
        }
    }

    /** Replaces the highlight words (optimistic); the stored (trimmed, de-duplicated) list wins. */
    suspend fun setHighlightWords(words: List<String>) {
        val old = _highlightWords.value
        if (words == old) return
        val revision = ++wordsRevision
        wordsInFlight++
        try {
            setWords(words)
            val save = saveHighlightWords ?: return
            try {
                val saved = save(words)
                if (revision == wordsRevision) setWords(saved)
            } catch (e: Exception) {
                if (revision == wordsRevision) setWords(old)
                throw e
            }
        } finally {
            wordsInFlight--
        }
    }

    /** Shows or hides mode glyphs in the member list (optimistic). */
    suspend fun setShowMemberPrefixes(on: Boolean) {
        val old = _showMemberPrefixes.value
        if (on == old) return
        val revision = ++prefixesRevision
        prefixesInFlight++
        try {
            setPrefixes(on)
            val save = saveShowMemberPrefixes ?: return
            try {
                save(on)
            } catch (e: Exception) {
                if (revision == prefixesRevision) setPrefixes(old)
                throw e
            }
        } finally {
            prefixesInFlight--
        }
    }

    /** Sign-out and account deletion: back to the defaults; answers of saves still in flight are dropped. */
    fun reset() {
        displayRevision++
        wordsRevision++
        prefixesRevision++
        _display.value = DisplayPrefs()
        _highlightWords.value = emptyList()
        _highlightOptions.value = HighlightOptions()
        _showMemberPrefixes.value = false
        prefs.edit().remove(KEY_DISPLAY).remove(KEY_WORDS).remove(KEY_WHOLE_WORD).remove(KEY_CASE_SENSITIVE)
            .remove(KEY_PREFIXES).apply()
    }

    private fun setDisplay(value: DisplayPrefs) {
        if (value == _display.value) return
        _display.value = value
        persist()
    }

    private fun setWords(value: List<String>) {
        if (value == _highlightWords.value) return
        _highlightWords.value = value
        persist()
    }

    private fun setPrefixes(value: Boolean) {
        if (value == _showMemberPrefixes.value) return
        _showMemberPrefixes.value = value
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_DISPLAY, _display.value.toJson().toString())
            .putStringList(KEY_WORDS, _highlightWords.value)
            .putBoolean(KEY_WHOLE_WORD, _highlightOptions.value.wholeWord)
            .putBoolean(KEY_CASE_SENSITIVE, _highlightOptions.value.caseSensitive)
            .putBoolean(KEY_PREFIXES, _showMemberPrefixes.value)
            .apply()
    }

    companion object {
        const val KEY_DISPLAY = "display_prefs"
        const val KEY_WORDS = "highlight_words"
        const val KEY_WHOLE_WORD = "highlight_whole_word"
        const val KEY_CASE_SENSITIVE = "highlight_case_sensitive"
        const val KEY_PREFIXES = "show_member_prefixes"

        /** The cached display prefs, defaults when none (Diagnostics reads it too). */
        fun storedDisplay(prefs: SharedPreferences): DisplayPrefs =
            prefs.getString(KEY_DISPLAY, null)
                ?.let { runCatching { DisplayPrefs.fromJson(SiteJson.parseToJsonElement(it).jsonObject) }.getOrNull() }
                ?: DisplayPrefs()
    }
}
