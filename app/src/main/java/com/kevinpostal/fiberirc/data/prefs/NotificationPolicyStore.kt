package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.NotificationPolicy
import com.kevinpostal.fiberirc.data.api.NotificationPolicyView
import com.kevinpostal.fiberirc.data.api.PolicyCells
import com.kevinpostal.fiberirc.data.api.PolicyClientOverride
import com.kevinpostal.fiberirc.data.api.PolicyHighlight
import com.kevinpostal.fiberirc.data.api.PolicyHighlightWord
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.api.currentPolicy
import com.kevinpostal.fiberirc.data.api.patchNotificationClient
import com.kevinpostal.fiberirc.data.api.patchNotificationPolicy
import com.kevinpostal.fiberirc.data.api.snoozeNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The account's Notification Policy v2 ([NotificationPolicyView]; null on a server without it, where Settings keeps
 * the v1 screen), ports `NotificationPolicyStore.swift`. Cached in `account_settings` so Settings has it before the
 * first refresh. An edit applies at once and is saved with `If-Match` on the view's `rev`; a 409 adopts the server's
 * `current` view and retries exactly once; the server's answer then wins, and a failed save brings the previous
 * value back (unless a newer edit happened) and rethrows. Main-thread confined.
 */
class NotificationPolicyStore(
    private val prefs: SharedPreferences,
    private val savePolicy: (suspend (patch: JsonObject, rev: Long) -> NotificationPolicyView)?,
    private val saveClient: (suspend (ref: String, patch: JsonObject, rev: Long) -> NotificationPolicyView)?,
    private val saveSnooze: (suspend (body: JsonObject) -> NotificationPolicyView)?,
) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        savePolicy = { patch, rev -> api.patchNotificationPolicy(patch, rev).getOrThrow() },
        saveClient = { ref, patch, rev -> api.patchNotificationClient(ref, patch, rev).getOrThrow() },
        saveSnooze = { body -> api.snoozeNotifications(body).getOrThrow() },
    )

    private val _view = MutableStateFlow(stored(prefs))
    val view: StateFlow<NotificationPolicyView?> = _view.asStateFlow()

    /** Told every time the policy's highlight settings change ([AccountSettingsStore.adoptPolicyHighlight]). */
    var onHighlightChanged: ((PolicyHighlight) -> Unit)? = null

    /** Bumped by every local edit (and [reset]): a save's answer, or its revert, applies only while no newer edit happened. */
    private var revision = 0
    /** Saves in flight: [load] keeps the view the user is changing. */
    private var inFlight = 0

    /**
     * Adopts `/api/me`'s view unless a save is in flight or it is older than the one held. A full `/api/me` without
     * a policy (the v1 `muteAll` key present) is a server without v2: the cached view goes, so Settings shows v1.
     */
    fun load(me: Me) {
        if (inFlight != 0) return
        val v = me.notificationPolicy
        if (v == null) {
            if (me.muteAll != null && _view.value != null) {
                revision++
                set(null)
            }
            return
        }
        val current = _view.value
        if (current != null && v.rev < current.rev) return
        set(v)
    }

    /** PATCH a merge-patch of the account policy (or `{"applyPreset": name}`). */
    suspend fun patchPolicy(patch: JsonObject) =
        write({ applyPolicyPatch(it, patch) }) { rev -> savePolicy?.invoke(patch, rev) }

    /** PATCH one client's overrides; [ref] is `"self"` for this install. */
    suspend fun patchClient(ref: String, patch: JsonObject) =
        write({ applyClientPatch(it, ref, patch) }) { rev -> saveClient?.invoke(ref, patch, rev) }

    /** Sets the account matrix's desktop, sound and mobile cells from preset [name]; email stays as is. */
    suspend fun applyPreset(name: String) = patchPolicy(buildJsonObject { put("applyPreset", name) })

    /**
     * This install's `<cls>.<transport>` cell: an override, or none (`null`) when [on] equals the account cell,
     * so the device keeps following the account.
     */
    suspend fun setDeviceCell(cls: String, transport: String, on: Boolean) {
        val policy = _view.value?.policy ?: return
        val value = if (on == policy.accountCell(cls, transport)) JsonNull else JsonPrimitive(on)
        patchClient(SELF, JsonObject(mapOf("cells" to JsonObject(mapOf("$cls.$transport" to value)))))
    }

    /** Drops every override of this install (cells and its own snooze): it follows the account again. */
    suspend fun resetDevice() {
        val view = _view.value ?: return
        val own = view.selfRef?.let { view.policy.clients[it] } ?: return
        patchClient(
            SELF,
            JsonObject(
                mapOf(
                    "cells" to JsonObject(own.cells.keys.associateWith { JsonNull }),
                    "snoozeUntil" to JsonNull,
                ),
            ),
        )
    }

    /** Replaces the highlight words (with their urgent flags). */
    suspend fun setHighlightWords(words: List<PolicyHighlightWord>) {
        val array = JsonArray(words.map { JsonObject(mapOf("w" to JsonPrimitive(it.w), "urgent" to JsonPrimitive(it.urgent))) })
        patchPolicy(JsonObject(mapOf("highlight" to JsonObject(mapOf("words" to array)))))
    }

    /** Flips [word]'s urgent star (urgent words break through quiet hours). */
    suspend fun toggleUrgent(word: String) {
        val words = _view.value?.policy?.highlight?.words ?: return
        if (words.none { it.w == word }) return
        setHighlightWords(words.map { if (it.w == word) it.copy(urgent = !it.urgent) else it })
    }

    /** POST /api/me/notifications/snooze (no optimistic change: the server computes the end). */
    suspend fun snooze(body: JsonObject) = write({ it }) { saveSnooze?.invoke(body) }

    /** Sign-out and account deletion: nothing cached; answers of saves still in flight are dropped. */
    fun reset() {
        revision++
        _view.value = null
        prefs.edit().remove(KEY_VIEW).apply()
    }

    /** Applies [change] at once, then [send]s it at a `rev` (null answer: no saver, the change stays local). */
    private suspend fun write(
        change: (NotificationPolicyView) -> NotificationPolicyView,
        send: suspend (rev: Long) -> NotificationPolicyView?,
    ) {
        val old = _view.value ?: throw IllegalStateException("No notification policy")
        val revision = ++this.revision
        inFlight++
        try {
            set(change(old))
            var base = old
            try {
                val saved = try {
                    send(old.rev)
                } catch (e: SiteApiError.Conflict) {
                    base = e.currentPolicy() ?: throw e
                    if (revision == this.revision) set(change(base))
                    send(base.rev)
                } ?: return
                if (revision == this.revision) set(saved)
            } catch (e: Exception) {
                if (revision == this.revision) set(base)
                throw e
            }
        } finally {
            inFlight--
        }
    }

    private fun set(value: NotificationPolicyView?) {
        val previous = _view.value
        if (value == previous) return
        _view.value = value
        if (value == null) prefs.edit().remove(KEY_VIEW).apply()
        else prefs.edit().putString(KEY_VIEW, SiteJson.encodeToString(NotificationPolicyView.serializer(), value)).apply()
        if (value != null && value.policy.highlight != previous?.policy?.highlight) onHighlightChanged?.invoke(value.policy.highlight)
    }

    companion object {
        const val KEY_VIEW = "notification_policy"
        /** The client ref the server resolves to the requesting install. */
        const val SELF = "self"

        fun stored(prefs: SharedPreferences): NotificationPolicyView? =
            prefs.getString(KEY_VIEW, null)
                ?.let { runCatching { SiteJson.decodeFromString(NotificationPolicyView.serializer(), it) }.getOrNull() }

        /** RFC 7396: objects merge, `null` deletes, anything else replaces. */
        fun mergePatch(target: JsonElement?, patch: JsonElement): JsonElement {
            if (patch !is JsonObject) return patch
            val out = LinkedHashMap((target as? JsonObject) ?: emptyMap())
            for ((k, v) in patch) {
                if (v is JsonNull) out.remove(k) else out[k] = mergePatch(out[k], v)
            }
            return JsonObject(out)
        }

        /** The preset whose desktop/sound/mobile cells all equal [matrix], else `custom` (the server's `matchPreset`). */
        fun matchPreset(matrix: Map<String, PolicyCells>, presets: Map<String, Map<String, PolicyCells>>): String =
            presets.entries.firstOrNull { (_, cells) ->
                cells.isNotEmpty() && cells.all { (cls, p) ->
                    val m = matrix[cls] ?: PolicyCells()
                    m.desktop == p.desktop && m.sound == p.sound && m.mobile == p.mobile
                }
            }?.key ?: "custom"

        /** [view] with an account policy patch applied the way the server applies it. */
        fun applyPolicyPatch(view: NotificationPolicyView, patch: JsonObject): NotificationPolicyView {
            var policy = view.policy
            val rest = JsonObject(patch - "applyPreset")
            if (rest.isNotEmpty()) {
                val merged = mergePatch(SiteJson.encodeToJsonElement(NotificationPolicy.serializer(), policy), rest)
                policy = runCatching { SiteJson.decodeFromJsonElement(NotificationPolicy.serializer(), merged) }.getOrElse { policy }
            }
            val presetName = (patch["applyPreset"] as? JsonPrimitive)?.contentOrNull
            val preset = presetName?.let { view.presets[it] }
            if (preset != null) {
                val matrix = LinkedHashMap(policy.matrix)
                for ((cls, p) in preset) {
                    val m = matrix[cls] ?: PolicyCells()
                    matrix[cls] = m.copy(desktop = p.desktop, sound = p.sound, mobile = p.mobile)
                }
                policy = policy.copy(matrix = matrix)
            }
            return view.copy(policy = policy, preset = matchPreset(policy.matrix, view.presets))
        }

        /** [view] with a client patch (`null` deletes an override; an empty entry goes) applied to [ref]'s entry. */
        fun applyClientPatch(view: NotificationPolicyView, ref: String, patch: JsonObject): NotificationPolicyView {
            val key = if (ref == SELF) view.selfRef ?: return view else ref
            val existing = view.policy.clients[key] ?: PolicyClientOverride()
            val cells = LinkedHashMap(existing.cells)
            (patch["cells"] as? JsonObject)?.forEach { (k, v) ->
                val on = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (on == null) cells.remove(k) else cells[k] = on
            }
            var snooze = existing.snoozeUntil
            if ("snoozeUntil" in patch) snooze = (patch["snoozeUntil"] as? JsonPrimitive)?.longOrNull
            val clients = LinkedHashMap(view.policy.clients)
            if (cells.isEmpty() && snooze == null) clients.remove(key) else clients[key] = PolicyClientOverride(cells, snooze)
            return view.copy(policy = view.policy.copy(clients = clients))
        }
    }
}
