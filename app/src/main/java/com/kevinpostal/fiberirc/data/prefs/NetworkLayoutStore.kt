package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account's buffer-drawer layout, shared with the web (ports `NetworkLayout.swift`): collapsed networks
 * (`prefs.collapsed`) and the network order (`prefs.networkOrder`). A failed save is undone unless a newer change
 * to the same thing followed. Main-thread confined.
 */
class NetworkLayoutStore(
    private val prefs: SharedPreferences,
    private val saveCollapsed: (suspend (network: String, collapsed: Boolean) -> Unit)?,
    private val saveOrder: (suspend (List<String>) -> Unit)?,
) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        saveCollapsed = { network, collapsed ->
            api.postJson("api/me/collapsed", buildJsonObject { put("network", network); put("collapsed", collapsed) }).getOrThrow()
        },
        saveOrder = { order ->
            api.postJson("api/me/network-order", buildJsonObject { put("order", JsonArray(order.map(::JsonPrimitive))) }).getOrThrow()
        },
    )

    private val _collapsed = MutableStateFlow((prefs.getStringList(KEY_COLLAPSED) ?: emptyList()).toSet())
    /** Ids of the collapsed networks. */
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()
    private val _networkOrder = MutableStateFlow(prefs.getStringList(KEY_ORDER) ?: emptyList())
    /** Network ids, first to last; networks not named follow in the bouncer's order. */
    val networkOrder: StateFlow<List<String>> = _networkOrder.asStateFlow()

    /** Network id → collapse edit counter; [orderRevision] for the order (both bumped by [reset]). */
    private val collapseRevisions = HashMap<String, Int>()
    private var orderRevision = 0
    private var inFlight = 0

    /** Adopts `/api/me`'s layout (only `true` collapse entries count); absent fields and saves in flight keep the cache. */
    fun load(me: Me) {
        if (inFlight != 0) return
        val nextCollapsed = me.collapsed?.filterValues { it }?.keys?.toSet() ?: _collapsed.value
        val nextOrder = me.networkOrder ?: _networkOrder.value
        if (nextCollapsed == _collapsed.value && nextOrder == _networkOrder.value) return
        apply(nextCollapsed, nextOrder)
    }

    suspend fun setCollapsed(isCollapsed: Boolean, networkId: String) {
        if (networkId.isEmpty() || isCollapsed == networkId in _collapsed.value) return
        val edit = (collapseRevisions[networkId] ?: 0) + 1
        collapseRevisions[networkId] = edit
        inFlight++
        try {
            apply(toggled(_collapsed.value, networkId, isCollapsed), _networkOrder.value)
            try {
                saveCollapsed?.invoke(networkId, isCollapsed)
            } catch (e: Exception) {
                if (edit == collapseRevisions[networkId]) {
                    apply(toggled(_collapsed.value, networkId, !isCollapsed), _networkOrder.value)
                }
                throw e
            }
        } finally {
            inFlight--
        }
    }

    /** Replaces the network order (ids, first to last). */
    suspend fun setNetworkOrder(order: List<String>) {
        if (order == _networkOrder.value) return
        val previous = _networkOrder.value
        val edit = ++orderRevision
        inFlight++
        try {
            apply(_collapsed.value, order)
            try {
                saveOrder?.invoke(order)
            } catch (e: Exception) {
                if (edit == orderRevision) apply(_collapsed.value, previous)
                throw e
            }
        } finally {
            inFlight--
        }
    }

    /** Sign-out and account deletion: nothing of the account stays cached. */
    fun reset() {
        for (id in collapseRevisions.keys.toList()) collapseRevisions[id] = collapseRevisions.getValue(id) + 1
        orderRevision++
        _collapsed.value = emptySet()
        _networkOrder.value = emptyList()
        prefs.edit().remove(KEY_COLLAPSED).remove(KEY_ORDER).apply()
    }

    private fun toggled(set: Set<String>, id: String, on: Boolean) = if (on) set + id else set - id

    private fun apply(collapsed: Set<String>, order: List<String>) {
        _collapsed.value = collapsed
        _networkOrder.value = order
        prefs.edit().putStringList(KEY_COLLAPSED, collapsed.sorted()).putStringList(KEY_ORDER, order).apply()
    }

    companion object {
        const val KEY_COLLAPSED = "collapsed_networks"
        const val KEY_ORDER = "network_order"
    }
}
