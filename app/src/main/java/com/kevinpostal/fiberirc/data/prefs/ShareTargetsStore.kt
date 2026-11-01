package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A buffer the share sheet and conversation shortcuts can send to (ports `Shared/ShareTargets.swift`). */
@Serializable
data class ShareTarget(
    val networkId: String,
    val networkName: String,
    val buffer: String,
    /** `"channel"` or `"query"`. */
    val kind: String,
) {
    companion object {
        const val CHANNEL = "channel"
        const val QUERY = "query"
    }
}

/**
 * The app's buffers for the share target (M13) and conversation shortcuts (M14), as a JSON array under
 * `share_targets` in `share_targets` (ports `ShareTargets` + `SessionViewController.publishShareTargets`), plus the
 * recently opened buffers that rank them. Callers [schedule] a publish after any buffer-list change; it runs 2 s
 * after the last call. Not cleared on sign-out (iOS keeps them too). Main-thread confined.
 *
 * @param pinKeys the account's pin order ([PinListStore.keys]) at publish time.
 */
class ShareTargetsStore(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val pinKeys: () -> List<String>,
    private val debounceMs: Long = 2_000,
) {
    constructor(context: Context, scope: CoroutineScope, pinKeys: () -> List<String>) :
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), scope, pinKeys)

    private val _targets = MutableStateFlow(load())
    val targets: StateFlow<List<ShareTarget>> = _targets.asStateFlow()

    /** False under the UI-test fixture (M15): nothing is published. */
    var publishingEnabled = true

    /** Called with every published list (M14's conversation shortcuts hook in here). */
    var onPublished: ((List<ShareTarget>) -> Unit)? = null

    private var pending: Job? = null

    fun load(): List<ShareTarget> =
        prefs.getString(KEY, null)?.let { runCatching { SiteJson.decodeFromString(LIST, it) }.getOrNull() } ?: emptyList()

    /** Moves `<networkId>:<buffer>` to the front of the recent buffers (at most [RECENT_LIMIT]). */
    fun noteRecent(networkId: String, buffer: String) {
        val key = "$networkId:$buffer"
        val keys = (prefs.getStringList(KEY_RECENTS) ?: emptyList()).filterNot { sameKey(it, key) }
        prefs.edit().putStringList(KEY_RECENTS, (listOf(key) + keys).take(RECENT_LIMIT)).apply()
    }

    /**
     * Publishes [candidates] [debounceMs] after the last call. [candidates] lists every non-server buffer in
     * discovery order (client order, then buffer order); evaluated when the publish runs.
     */
    fun schedule(candidates: () -> List<ShareTarget>) {
        if (!publishingEnabled) return
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            pending = null
            publishNow(candidates())
        }
    }

    /** Account networks only, sorted pinned first, then recent, then the rest in discovery order. */
    fun publishNow(candidates: List<ShareTarget>) {
        if (!publishingEnabled) return
        val targets = rank(candidates, pinKeys(), prefs.getStringList(KEY_RECENTS) ?: emptyList())
        prefs.edit().putString(KEY, SiteJson.encodeToString(LIST, targets)).apply()
        _targets.value = targets
        onPublished?.invoke(targets)
    }

    companion object {
        const val FILE = "share_targets"
        const val KEY = "share_targets"
        const val KEY_RECENTS = "recent_buffers"
        const val RECENT_LIMIT = 20
        private val LIST = ListSerializer(ShareTarget.serializer())

        fun rank(candidates: List<ShareTarget>, pins: List<String>, recents: List<String>): List<ShareTarget> {
            fun position(keys: List<String>, t: ShareTarget): Int {
                val key = "${t.networkId}:${t.buffer}"
                return keys.indexOfFirst { sameKey(it, key) }.takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
            return candidates.filter { it.networkId.isNotEmpty() }
                .withIndex()
                .sortedWith(compareBy({ position(pins, it.value) }, { position(recents, it.value) }, { it.index }))
                .map { it.value }
        }
    }
}
