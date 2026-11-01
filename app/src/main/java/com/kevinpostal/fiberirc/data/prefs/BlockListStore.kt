package com.kevinpostal.fiberirc.data.prefs

import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The account's ignore list (`prefs.ignores`, shared with the web), ports `BlockList.swift`. Masks are
 * `nick!user@host` globs; the app only knows nicks, so it applies just the masks whose user and host parts are
 * absent or `*` (`nick`, `nick!*`, `nick!*@*`); narrower masks stay web-only and are saved back untouched.
 *
 * Deliberately different from the other account stores: not cached on disk, not cleared on sign-out, loaded only
 * at login (not on foreground refresh), and edits save first and apply only on success.
 */
class BlockListStore(private val save: suspend (List<String>) -> Unit) {
    constructor(api: SiteApi) : this({ masks ->
        api.postJson("api/me/ignores", buildJsonObject { put("ignores", JsonArray(masks.map(::JsonPrimitive))) }).getOrThrow()
    })

    private val _masks = MutableStateFlow<List<String>>(emptyList())
    val masks: StateFlow<List<String>> = _masks.asStateFlow()

    fun load(masks: List<String>?) {
        _masks.value = masks ?: emptyList()
    }

    fun isBlocked(nick: String): Boolean = _masks.value.any { mask -> nickPattern(mask)?.let { glob(it, nick) } ?: false }

    /** Nicks blocked by app-applicable masks, in list order. */
    val blockedNicks: List<String> get() = _masks.value.mapNotNull(::nickPattern)

    suspend fun block(nick: String) {
        if (isBlocked(nick)) return
        commit(_masks.value + "$nick!*@*")
    }

    suspend fun unblock(nick: String) {
        val kept = _masks.value.filter { mask -> nickPattern(mask)?.let { !it.equals(nick, ignoreCase = true) } ?: true }
        if (kept.size == _masks.value.size) return
        commit(kept)
    }

    private suspend fun commit(next: List<String>) {
        save(next)
        _masks.value = next
    }

    companion object {
        /** The nick part of a mask the app can apply, or null for user/host-specific masks. */
        fun nickPattern(mask: String): String? {
            val bang = mask.indexOf('!')
            val nick = if (bang < 0) mask else mask.substring(0, bang)
            if (nick.isEmpty()) return null
            if (bang < 0) return nick
            val rest = mask.substring(bang + 1)
            val at = rest.indexOf('@')
            val user = if (at < 0) rest else rest.substring(0, at)
            val host = if (at < 0) "" else rest.substring(at + 1)
            if (user != "*" || (host.isNotEmpty() && host != "*")) return null
            return nick
        }

        /** Case-insensitive glob: `*` any run, `?` one character. */
        fun glob(pattern: String, text: String): Boolean {
            val p = pattern.lowercase()
            val t = text.lowercase()
            var pi = 0
            var ti = 0
            var star = -1
            var mark = 0
            while (ti < t.length) {
                if (pi < p.length && (p[pi] == '?' || p[pi] == t[ti])) {
                    pi++; ti++
                } else if (pi < p.length && p[pi] == '*') {
                    star = pi; mark = ti; pi++
                } else if (star >= 0) {
                    pi = star + 1; mark++; ti = mark
                } else {
                    return false
                }
            }
            while (pi < p.length && p[pi] == '*') pi++
            return pi == p.length
        }
    }
}
