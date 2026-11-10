package com.kevinpostal.fiberirc.data.voice

/** Signaling credentials must not escape in SDK exception messages, native logs or crash breadcrumbs. */
object VoicePrivacy {
    private val urlQuery = Regex("(?i)((?:https?|wss?)://[^\\s?]+)\\?[^\\s]+")
    private val jwt = Regex("[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}")
    private val credential = Regex("(?i)(\\b(?:access_token|token|authorization|cookie|apiSecret|apiKey)\\b[\\s:=]+)[^\\s,;]+")
    fun redact(value: String): String = credential.replace(jwt.replace(urlQuery.replace(value, "$1?[redacted]"), "[redacted-jwt]"), "$1[redacted]")
    fun sensitiveKey(key: String): Boolean = key.lowercase().let { it.contains("token") || it.contains("secret") || it.contains("authorization") || it.contains("cookie") || it == "body" || it == "data" }
}
