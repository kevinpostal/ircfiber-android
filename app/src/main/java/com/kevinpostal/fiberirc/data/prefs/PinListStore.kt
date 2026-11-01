package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An account-wide list of `<networkId>:<buffer>` keys matched case-insensitively, cached under [cacheKey]
 * (shared by [PinListStore] and [ArchiveListStore], which port `PinList.swift`/`ArchiveList.swift`). Adding or
 * removing applies at once and is saved; a failed save undoes that change only, while no newer edit touched the
 * same key. Main-thread confined.
 */
abstract class AccountKeyListStore(
    private val prefs: SharedPreferences,
    private val cacheKey: String,
    private val add: (suspend (network: String, channel: String) -> Unit)?,
    private val remove: (suspend (network: String, channel: String) -> Unit)?,
) {
    protected val _keys = MutableStateFlow(prefs.getStringList(cacheKey) ?: emptyList())
    val keys: StateFlow<List<String>> = _keys.asStateFlow()

    /** Lower-cased key → edit counter (bumped by [reset] too). */
    private val revisions = HashMap<String, Int>()
    /** Saves in flight: [load] keeps the list the user is changing. */
    protected var inFlight = 0

    /** Adopts the account's list from `/api/me`; null (auth responses) keeps the cache, as does a save in flight. */
    fun load(keys: List<String>?) {
        if (keys == null || inFlight != 0 || keys == _keys.value) return
        setKeys(keys)
    }

    protected fun contains(networkId: String, buffer: String): Boolean =
        rank(buffer, networkId, _keys.value) != null

    /** Adds (last) or removes every spelling of the key; removal tells the server each spelling as stored. */
    protected suspend fun setMember(member: Boolean, networkId: String, buffer: String) {
        if (networkId.isEmpty() || member == contains(networkId, buffer)) return
        val key = "$networkId:$buffer"
        val id = key.lowercase()
        val removed = _keys.value.withIndex().filter { sameKey(it.value, key) }
        val edit = (revisions[id] ?: 0) + 1
        revisions[id] = edit
        inFlight++
        try {
            setKeys(if (member) _keys.value + key else _keys.value.filterNot { sameKey(it, key) })
            try {
                if (member) {
                    add?.invoke(networkId, buffer)
                } else if (remove != null) {
                    for ((_, stored) in removed) remove.invoke(networkId, stored.drop(networkId.length + 1))
                }
            } catch (e: Exception) {
                if (edit == revisions[id]) {
                    val next = _keys.value.filterNot { sameKey(it, key) }.toMutableList()
                    for ((offset, stored) in removed) next.add(minOf(offset, next.size), stored)
                    setKeys(next)
                }
                throw e
            }
        } finally {
            inFlight--
        }
    }

    /** Sign-out and account deletion: nothing of the account stays cached. */
    fun reset() {
        for (id in revisions.keys.toList()) revisions[id] = revisions.getValue(id) + 1
        _keys.value = emptyList()
        prefs.edit().remove(cacheKey).apply()
    }

    protected fun setKeys(next: List<String>) {
        _keys.value = next
        prefs.edit().putStringList(cacheKey, next).apply()
    }

    companion object {
        /** Position of `<networkId>:<buffer>` in [keys], null when absent or [networkId] is empty. */
        fun rank(buffer: String, networkId: String, keys: List<String>): Int? {
            if (networkId.isEmpty()) return null
            val key = "$networkId:$buffer"
            return keys.indexOfFirst { sameKey(it, key) }.takeIf { it >= 0 }
        }
    }
}

/**
 * The account's pinned buffers (`prefs.pinnedChannels`, shared with the web), in the web's drag order. A new pin
 * goes last. Empty `networkId` (the direct-ircd fallback) can't be pinned.
 */
class PinListStore(
    prefs: SharedPreferences,
    pin: (suspend (String, String) -> Unit)?,
    unpin: (suspend (String, String) -> Unit)?,
    private val saveOrder: (suspend (List<String>) -> Unit)?,
) : AccountKeyListStore(prefs, KEY, pin, unpin) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        pin = { network, channel ->
            api.postJson("api/me/pins", buildJsonObject { put("network", network); put("channel", channel) }).getOrThrow()
        },
        unpin = { network, channel -> api.deletePath(listOf("api", "me", "pins", network, channel)).getOrThrow() },
        saveOrder = { order ->
            api.postJson("api/me/pin-order", buildJsonObject { put("order", JsonArray(order.map(::JsonPrimitive))) }).getOrThrow()
        },
    )

    fun isPinned(networkId: String, buffer: String): Boolean = contains(networkId, buffer)

    /** Pins (last in the order) or unpins [buffer] on [networkId]; a failed save undoes this change only. */
    suspend fun setPinned(pinned: Boolean, networkId: String, buffer: String) = setMember(pinned, networkId, buffer)

    suspend fun pin(networkId: String, buffer: String) = setPinned(true, networkId, buffer)

    suspend fun unpin(networkId: String, buffer: String) = setPinned(false, networkId, buffer)

    /**
     * Reorder Pins: the pins [order] names first, in that order, then any pin it leaves out in its current order;
     * keys no longer pinned are dropped. A failed save puts the old order back unless the pins changed meanwhile.
     */
    suspend fun setOrder(order: List<String>) {
        val previous = _keys.value
        val rest = previous.toMutableList()
        val next = ArrayList<String>()
        for (k in order) {
            next += rest.filter { sameKey(it, k) }
            rest.removeAll { sameKey(it, k) }
        }
        next += rest
        if (next == previous) return
        inFlight++
        try {
            setKeys(next)
            try {
                saveOrder?.invoke(next)
            } catch (e: Exception) {
                if (_keys.value == next) setKeys(previous)
                throw e
            }
        } finally {
            inFlight--
        }
    }

    companion object {
        const val KEY = "pinned_channels"
    }
}
