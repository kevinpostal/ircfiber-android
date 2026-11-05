package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.BufferPrefValue
import com.kevinpostal.fiberirc.data.api.LenientBufferPrefsSerializer
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-buffer chat settings saved to the account and shared with the web's channel menu (ports `BufferPrefs.swift`),
 * keyed `<networkId>:<buffer>` (case-insensitive). Values are booleans or strings ([BufferPrefValue]); keys this app
 * doesn't know are kept and saved back untouched. An edit applies at once and is saved; a failed save puts the
 * buffer's previous values back unless a newer edit happened. Main-thread confined.
 *
 * The encryption keys (`e2ee` boolean, `e2eeMode` `mls`/`pgp`) live here too: the encryption service reads and saves
 * them through this store, never a second map.
 */
class BufferPrefsStore(
    private val prefs: SharedPreferences,
    private val save: (suspend (network: String, channel: String, prefs: Map<String, BufferPrefValue>) -> Unit)?,
) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        save = { network, channel, changes ->
            val body = buildJsonObject {
                put("network", network)
                put("channel", channel)
                put("prefs", JsonObject(changes.mapValues { it.value.toJson() }))
            }
            api.postJson("api/me/buffer-prefs", body).getOrThrow()
        },
    )

    /** The web client's boolean buffer-pref keys (`prefs.bufferPrefs`). */
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

    /** End-to-end encryption mode of a conversation (`e2eeMode`). */
    enum class E2eeMode(val wire: String) {
        MLS("mls"),
        PGP("pgp");

        companion object {
            fun of(wire: String?): E2eeMode? = entries.firstOrNull { it.wire == wire }
        }
    }

    private val _values = MutableStateFlow(
        prefs.getString(KEY, null)
            ?.let { runCatching { SiteJson.decodeFromString(LenientBufferPrefsSerializer, it) }.getOrNull() }
            ?: emptyMap(),
    )
    val values: StateFlow<Map<String, Map<String, BufferPrefValue>>> = _values.asStateFlow()

    /** Lower-cased buffer key → saves in flight / edit counter. */
    private val inFlight = HashMap<String, Int>()
    private val revisions = HashMap<String, Int>()

    /** The account's stored value of [name]; null when the buffer has none (or [networkId] is empty). */
    fun raw(networkId: String, buffer: String, name: String): BufferPrefValue? {
        if (networkId.isEmpty()) return null
        return _values.value[storedKey(networkId, buffer)]?.get(name)
    }

    /** The account's stored boolean; null when the buffer has none (or [networkId] is empty). */
    fun value(k: Key, networkId: String, buffer: String): Boolean? = raw(networkId, buffer, k.wire)?.bool

    /** The buffer's `e2ee` lock: true/false when set, null when unset. */
    fun e2ee(networkId: String, buffer: String): Boolean? = raw(networkId, buffer, E2EE)?.bool

    /** The conversation's `e2eeMode`; null when unset or unknown (the runtime's default, MLS). */
    fun e2eeMode(networkId: String, buffer: String): E2eeMode? = E2eeMode.of(raw(networkId, buffer, E2EE_MODE)?.text)

    /** Whether a save of this buffer's prefs is still running (its values are local, not yet the account's). */
    fun saving(networkId: String, buffer: String): Boolean = (inFlight[storedKey(networkId, buffer).lowercase()] ?: 0) > 0

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

    /** Applies boolean [changes] to one buffer at once and saves only those keys in one request. */
    suspend fun set(changes: Map<Key, Boolean>, networkId: String, buffer: String) =
        setValues(changes.entries.associate { it.key.wire to BufferPrefValue.Bool(it.value) }, networkId, buffer)

    /** Saves the buffer's `e2ee` lock (the encryption service's `prefs.setBuffer`). */
    suspend fun setE2ee(on: Boolean, networkId: String, buffer: String) =
        setValues(mapOf(E2EE to BufferPrefValue.Bool(on)), networkId, buffer)

    /** Saves the conversation's `e2eeMode`. */
    suspend fun setE2eeMode(mode: E2eeMode, networkId: String, buffer: String) =
        setValues(mapOf(E2EE_MODE to BufferPrefValue.Text(mode.wire)), networkId, buffer)

    /**
     * Applies [changes] (wire key → value) to one buffer at once and saves only those keys in one request. A failed
     * save restores the buffer's previous values unless a newer edit happened, and throws.
     */
    suspend fun setValues(changes: Map<String, BufferPrefValue>, networkId: String, buffer: String) {
        if (networkId.isEmpty() || changes.isEmpty()) return
        val key = storedKey(networkId, buffer)
        val old = _values.value[key]
        val next = (old ?: emptyMap()).toMutableMap()
        next.putAll(changes)
        if (next == (old ?: emptyMap<String, BufferPrefValue>())) return
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
                save(networkId, channel, changes)
            } catch (e: Exception) {
                if (revision == revisions[id]) put(key, old)
                throw e
            }
        } finally {
            inFlight[id] = (inFlight[id] ?: 1) - 1
        }
    }

    /** Adopts `/api/me`'s map (already lenient-decoded); buffers with a save in flight keep their current values. */
    fun load(payload: Map<String, Map<String, BufferPrefValue>>?) {
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

    private fun put(key: String, prefs: Map<String, BufferPrefValue>?) {
        _values.value = if (prefs == null) _values.value - key else _values.value + (key to prefs)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, SiteJson.encodeToString(LenientBufferPrefsSerializer, _values.value)).apply()
    }

    companion object {
        const val KEY = "buffer_prefs"
        const val E2EE = "e2ee"
        const val E2EE_MODE = "e2eeMode"
    }
}
