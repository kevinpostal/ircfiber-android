package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.LenientBufferPrefsSerializer
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-buffer chat settings saved to the account and shared with the web's channel menu (ports `BufferPrefs.swift`),
 * keyed `<networkId>:<buffer>` (case-insensitive). An edit applies at once and is saved; a failed save puts the
 * buffer's previous values back unless a newer edit happened. Main-thread confined.
 */
class BufferPrefsStore(
    private val prefs: SharedPreferences,
    private val save: (suspend (network: String, channel: String, prefs: Map<String, Boolean>) -> Unit)?,
) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        save = { network, channel, changes ->
            val body = buildJsonObject {
                put("network", network)
                put("channel", channel)
                put("prefs", JsonObject(changes.mapValues { JsonPrimitive(it.value) }))
            }
            api.postJson("api/me/buffer-prefs", body).getOrThrow()
        },
    )

    /** The web client's buffer-pref keys (`prefs.bufferPrefs`). */
    enum class Key(val wire: String) {
        /** Show nick changes, joins and parts. */
        SHOW_JOIN_PART("showJoinPart"),
        /** Show joins and parts one by one instead of collapsed. */
        EXPAND_JOIN_PART("expandJoinPart"),
        REPLY_COLLAPSE("replyCollapse"),
        TYPING("typing"),
        INLINE_FILES("inlineFiles"),
        INLINE_IMAGES("inlineImages"),
        INLINE_PASTES("inlinePastes"),
        FORMAT_COLOR("formatColor"),
        MUTE("mute"),
        NOTIFY_ALL("notifyAll"),
        MARK_AS_READ("markAsRead"),
        SHOW_UNREAD_COUNT("showUnreadCount"),
        NICK_SUGGESTIONS("nickSuggestions"),
    }

    private val _values = MutableStateFlow(
        prefs.getString(KEY, null)
            ?.let { runCatching { SiteJson.decodeFromString(LenientBufferPrefsSerializer, it) }.getOrNull() }
            ?: emptyMap(),
    )
    val values: StateFlow<Map<String, Map<String, Boolean>>> = _values.asStateFlow()

    /** Lower-cased buffer key → saves in flight / edit counter. */
    private val inFlight = HashMap<String, Int>()
    private val revisions = HashMap<String, Int>()

    /** The account's stored value; null when the buffer has none (or [networkId] is empty). */
    fun value(k: Key, networkId: String, buffer: String): Boolean? {
        if (networkId.isEmpty()) return null
        return _values.value[storedKey(networkId, buffer)]?.get(k.wire)
    }

    /** The value a buffer uses: its own setting, else the device or account default (web defaults for the rest). */
    fun effective(k: Key, networkId: String, buffer: String, device: DeviceSettings, formatColors: Boolean): Boolean {
        value(k, networkId, buffer)?.let { return it }
        return when (k) {
            Key.SHOW_JOIN_PART -> device.showJoinPartDefault
            Key.EXPAND_JOIN_PART -> !device.collapseJoinPart
            Key.INLINE_FILES -> device.embedUploads
            Key.INLINE_IMAGES -> device.embedExternalMedia
            Key.FORMAT_COLOR -> formatColors
            Key.REPLY_COLLAPSE, Key.MUTE, Key.NOTIFY_ALL -> false
            Key.TYPING, Key.INLINE_PASTES, Key.MARK_AS_READ, Key.SHOW_UNREAD_COUNT, Key.NICK_SUGGESTIONS -> true
        }
    }

    /** Applies [changes] to one buffer at once and saves only those keys in one request. */
    suspend fun set(changes: Map<Key, Boolean>, networkId: String, buffer: String) {
        if (networkId.isEmpty()) return
        val key = storedKey(networkId, buffer)
        val old = _values.value[key]
        val next = (old ?: emptyMap()).toMutableMap()
        for ((k, on) in changes) next[k.wire] = on
        if (next == (old ?: emptyMap<String, Boolean>())) return
        val id = key.lowercase()
        val revision = (revisions[id] ?: 0) + 1
        revisions[id] = revision
        inFlight[id] = (inFlight[id] ?: 0) + 1
        try {
            put(key, next)
            val save = save ?: return
            // The server joins network and channel with ":" into the same key.
            val channel = key.drop(networkId.length + 1)
            try {
                save(networkId, channel, changes.entries.associate { it.key.wire to it.value })
            } catch (e: Exception) {
                if (revision == revisions[id]) put(key, old)
                throw e
            }
        } finally {
            inFlight[id] = (inFlight[id] ?: 1) - 1
        }
    }

    /** Adopts `/api/me`'s map (already lenient-decoded); buffers with a save in flight keep their current values. */
    fun load(payload: Map<String, Map<String, Boolean>>?) {
        if (payload == null) return
        val next = payload.toMutableMap()
        for ((key, prefs) in _values.value) {
            if ((inFlight[key.lowercase()] ?: 0) <= 0) continue
            next.keys.removeAll { sameKey(it, key) }
            next[key] = prefs
        }
        if (next == _values.value) return
        _values.value = next
        persist()
    }

    /** Sign-out and account deletion: nothing of the account stays cached. */
    fun reset() {
        for (id in revisions.keys.toList()) revisions[id] = revisions.getValue(id) + 1
        _values.value = emptyMap()
        prefs.edit().remove(KEY).apply()
    }

    /** An existing entry's key in any case, else `<networkId>:<buffer>`: updates the web's entry instead of adding one. */
    private fun storedKey(networkId: String, buffer: String): String {
        val key = "$networkId:$buffer"
        return _values.value.keys.firstOrNull { sameKey(it, key) } ?: key
    }

    private fun put(key: String, prefs: Map<String, Boolean>?) {
        _values.value = if (prefs == null) _values.value - key else _values.value + (key to prefs)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, SiteJson.encodeToString(LenientBufferPrefsSerializer, _values.value)).apply()
    }

    companion object {
        const val KEY = "buffer_prefs"
    }
}
