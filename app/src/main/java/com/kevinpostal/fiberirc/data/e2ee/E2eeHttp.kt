package com.kevinpostal.fiberirc.data.e2ee

/** One allowlisted encryption API request: `/api` + [encodedPath] (`?`[encodedQuery]). */
data class E2eeApiTarget(val method: String, val encodedPath: String, val encodedQuery: String?)

/**
 * The native side of the runtime's `http.request` allowlist (mirrors `checkApiRequest` in the site's `mobile.ts`,
 * stricter where it can be): only the DS/AS encryption API — `/mls/…`, `/kt/…` (including the text `/kt/key`) and
 * `/me/e2ee/…`, relative to the configured `/api` — with GET/POST/PUT/DELETE. Absolute or scheme-relative URLs,
 * traversal (`.`/`..`, any percent-encoded dot, slash or backslash), empty segments, fragments, non-printable or
 * non-ASCII characters are refused before any request exists. Pure: unit-tested on the JVM.
 */
object E2eeApiPolicy {
    private val METHODS = setOf("GET", "POST", "PUT", "DELETE")
    private val PREFIXES = listOf("/mls/", "/kt/", "/me/e2ee/")
    private const val MAX_PATH = 2048
    private val ENCODED_FORBIDDEN = Regex("%(2e|2f|5c|00)", RegexOption.IGNORE_CASE)

    /** The checked target, or [E2eeBridgeException] `bad_request`. [hasBody] refuses a GET with a body. */
    fun check(method: String, path: String, hasBody: Boolean): E2eeApiTarget {
        fun refuse(why: String): Nothing = throw E2eeBridgeException("bad_request", why)
        if (method !in METHODS) refuse("method $method not allowed")
        if (hasBody && method == "GET") refuse("GET has no body")
        if (path.isEmpty() || path.length > MAX_PATH || path[0] != '/') refuse("path outside the encryption API")
        if (path.any { it.code < 0x21 || it.code > 0x7e }) refuse("path must be printable ASCII")
        if ("://" in path || "//" in path || '\\' in path || '#' in path) refuse("path outside the encryption API")
        val q = path.indexOf('?')
        val bare = if (q < 0) path else path.substring(0, q)
        val query = if (q < 0) null else path.substring(q + 1).takeIf { it.isNotEmpty() }
        if (query != null && '?' in query) refuse("malformed query")
        val allowed = bare == "/kt/key" || PREFIXES.any { bare.startsWith(it) && bare.length > it.length }
        if (!allowed) refuse("path outside the encryption API")
        val segments = bare.substring(1).split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || ENCODED_FORBIDDEN.containsMatchIn(it) }) {
            refuse("path outside the encryption API")
        }
        if (query != null && query.contains("%00")) refuse("malformed query")
        return E2eeApiTarget(method, bare, query)
    }
}
