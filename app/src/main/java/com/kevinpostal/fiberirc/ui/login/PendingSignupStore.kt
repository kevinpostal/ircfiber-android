package com.kevinpostal.fiberirc.ui.login

import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Nonsecret marker of a native registration awaiting email confirmation, kept in the app's private
 * preferences as one JSON object under [KEY]. It is written only after an accepted 202 with a matching
 * signup-watch cookie; the watch itself lives solely in the HTTP cookie jar, and callers remove it
 * separately. No password, watch value or token is ever stored here.
 *
 * [load] returns an entry only for the exact same base URL (scheme, host and port, compared in OkHttp's
 * canonical form) whose age is within `[0, 24h)`; anything else is discarded.
 */
class PendingSignupStore(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Entry(val baseURL: String, val username: String, val email: String, val startedAtMs: Long)

    /** The pending signup for [baseURL], or null — clearing the stored marker when it is invalid or stale. */
    fun load(baseURL: String): Entry? {
        if (!prefs.contains(KEY)) return null
        // A non-string value under the key throws ClassCastException from real SharedPreferences.
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
        val entry = raw?.let { runCatching { SiteJson.decodeFromString(Entry.serializer(), it) }.getOrNull() }
        val base = canonical(baseURL)
        val age = entry?.let { runCatching { Math.subtractExact(now(), it.startedAtMs) }.getOrNull() }
        val valid = entry != null && base != null && age != null &&
            entry.baseURL == base &&
            entry.username.isNotBlank() && entry.email.isNotBlank() &&
            age >= 0 && age < MAX_AGE_MS
        if (!valid) {
            clear()
            return null
        }
        return entry
    }

    /** Stores [entry], normalising its base URL; throws when that URL is not an http(s) URL. */
    fun save(entry: Entry) {
        val base = requireNotNull(canonical(entry.baseURL)) { "invalid base URL" }
        prefs.edit().putString(KEY, SiteJson.encodeToString(Entry.serializer(), entry.copy(baseURL = base))).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val KEY = "pending_signup_v1"
        const val MAX_AGE_MS = 86_400_000L

        private fun canonical(url: String): String? = url.toHttpUrlOrNull()?.toString()
    }
}
