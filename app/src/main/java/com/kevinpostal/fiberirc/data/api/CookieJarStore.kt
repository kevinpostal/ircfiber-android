package com.kevinpostal.fiberirc.data.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The ircfiber.com session cookie jar, persisted in `SharedPreferences("fiberirc_cookies")` so the session
 * survives process death (iOS keeps it in the App Group's `HTTPCookieStorage`). One entry per
 * `<domain>|<name>`, overwritten by every `Set-Cookie`: the session cookie's `Max-Age` slides forward on each
 * authenticated answer (web/common.d). A cookie set already expired deletes its entry. Thread-safe.
 */
class CookieJarStore(
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val lock = Any()
    private val cookies: MutableMap<String, Cookie> = LinkedHashMap<String, Cookie>().also { map ->
        for ((key, raw) in prefs.all) {
            val cookie = (raw as? String)?.let(::decode) ?: continue
            map[key] = cookie
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val t = now()
        synchronized(lock) {
            val edit = prefs.edit()
            for (c in cookies) {
                val key = key(c)
                if (c.expiresAt <= t) {
                    this.cookies.remove(key)
                    edit.remove(key)
                } else {
                    this.cookies[key] = c
                    edit.putString(key, encode(c))
                }
            }
            edit.apply()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val t = now()
        synchronized(lock) {
            val expired = cookies.filterValues { it.expiresAt <= t }.keys
            if (expired.isNotEmpty()) {
                val edit = prefs.edit()
                for (k in expired) {
                    cookies.remove(k)
                    edit.remove(k)
                }
                edit.apply()
            }
            return cookies.values.filter { it.matches(url) }
        }
    }

    /** Sign-out: forgets every cookie sent to [host] (its own and its parent domains'). */
    fun clear(host: String) {
        synchronized(lock) {
            val gone = cookies.filterValues { domainMatches(host, it) }.keys
            if (gone.isEmpty()) return
            val edit = prefs.edit()
            for (k in gone) {
                cookies.remove(k)
                edit.remove(k)
            }
            edit.apply()
        }
    }

    private fun domainMatches(host: String, c: Cookie): Boolean =
        if (c.hostOnly) host.equals(c.domain, ignoreCase = true)
        else host.equals(c.domain, ignoreCase = true) || host.endsWith(".${c.domain}", ignoreCase = true)

    @Serializable
    private class Stored(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    private fun encode(c: Cookie): String = SiteJson.encodeToString(
        Stored.serializer(),
        Stored(c.name, c.value, c.expiresAt, c.domain, c.path, c.secure, c.httpOnly, c.hostOnly),
    )

    private fun decode(raw: String): Cookie? = runCatching {
        val s = SiteJson.decodeFromString(Stored.serializer(), raw)
        Cookie.Builder().name(s.name).value(s.value).expiresAt(s.expiresAt).path(s.path).apply {
            if (s.hostOnly) hostOnlyDomain(s.domain) else domain(s.domain)
            if (s.secure) secure()
            if (s.httpOnly) httpOnly()
        }.build()
    }.getOrNull()

    private fun key(c: Cookie) = "${c.domain}|${c.name}"

    companion object {
        const val FILE = "fiberirc_cookies"
    }
}
