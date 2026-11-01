package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This install's push settings (`/api/me` `muteAll`/`notificationSound` under its `X-IRCFiber-Device` profile;
 * server defaults: not muted, with sound), ports `NotificationPrefs.swift`. Only those two keys are ever sent.
 * Main-thread confined.
 */
class NotificationPrefsStore(
    private val prefs: SharedPreferences,
    private val save: (suspend (key: String, on: Boolean) -> Unit)?,
) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        save = { key, on -> api.postJson("api/me/notification-prefs", buildJsonObject { put(key, on) }).getOrThrow() },
    )

    /** Wire names (`POST /api/me/notification-prefs`) and cache keys. */
    enum class Key(val wire: String, val cacheKey: String, val fallback: Boolean) {
        MUTE_ALL("muteAll", "notification_mute_all", false),
        NOTIFICATION_SOUND("notificationSound", "notification_sound", true),
    }

    private val _muteAll = MutableStateFlow(read(Key.MUTE_ALL))
    /** Every notification is off. */
    val muteAll: StateFlow<Boolean> = _muteAll.asStateFlow()
    private val _notificationSound = MutableStateFlow(read(Key.NOTIFICATION_SOUND))
    /** Notifications play the default sound. */
    val notificationSound: StateFlow<Boolean> = _notificationSound.asStateFlow()

    private val revisions = HashMap<Key, Int>()
    private val inFlight = HashMap<Key, Int>()

    fun value(key: Key): Boolean = flow(key).value

    /** Adopts `/api/me`'s values; absent settings and settings with a save in flight keep their current value. */
    fun load(me: Me) {
        var changed = false
        for ((key, server) in listOf(Key.MUTE_ALL to me.muteAll, Key.NOTIFICATION_SOUND to me.notificationSound)) {
            if (server == null || (inFlight[key] ?: 0) != 0 || server == value(key)) continue
            flow(key).value = server
            changed = true
        }
        if (changed) persist()
    }

    /** Applies [on] at once and saves it; on failure the previous value returns (unless a newer edit happened). */
    suspend fun set(key: Key, on: Boolean) {
        val old = value(key)
        if (on == old) return
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        inFlight[key] = (inFlight[key] ?: 0) + 1
        try {
            apply(key, on)
            val save = save ?: return
            try {
                save(key.wire, on)
            } catch (e: Exception) {
                if (revision == revisions[key]) apply(key, old)
                throw e
            }
        } finally {
            inFlight[key] = (inFlight[key] ?: 1) - 1
        }
    }

    /** Sign-out and account deletion: back to the defaults; reverts of saves still in flight are dropped. */
    fun reset() {
        val edit = prefs.edit()
        for (key in Key.entries) {
            revisions[key] = (revisions[key] ?: 0) + 1
            flow(key).value = key.fallback
            edit.remove(key.cacheKey)
        }
        edit.apply()
    }

    private fun flow(key: Key) = when (key) {
        Key.MUTE_ALL -> _muteAll
        Key.NOTIFICATION_SOUND -> _notificationSound
    }

    private fun read(key: Key): Boolean =
        if (prefs.contains(key.cacheKey)) prefs.getBoolean(key.cacheKey, key.fallback) else key.fallback

    private fun apply(key: Key, on: Boolean) {
        if (on == value(key)) return
        flow(key).value = on
        persist()
    }

    private fun persist() {
        val edit = prefs.edit()
        for (key in Key.entries) edit.putBoolean(key.cacheKey, value(key))
        edit.apply()
    }
}
