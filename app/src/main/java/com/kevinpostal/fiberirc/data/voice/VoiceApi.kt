package com.kevinpostal.fiberirc.data.voice

import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

@Serializable data class VoiceSettings(val revision: String, val mode: String, val pilotAccountIds: List<String>)
@Serializable data class VoiceMember(
    val accountId: String, val username: String, val avatarUrl: String?, val role: VoiceRole,
    val mayJoin: Boolean?, val maySpeak: Boolean?, val banned: Boolean, val serverMuted: Boolean, val revision: String,
)
@Serializable data class VoiceMembers(val members: List<VoiceMember>, val nextOffset: Int?)

/** Uses only Fiber's authenticated transport, never the SDK's network client. */
class VoiceApi(private val site: SiteApi, val instanceId: String, private val isCurrent: () -> Boolean = { true }) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun path(parts: List<String>) = "https://voice.invalid/".toHttpUrl().newBuilder()
        .apply { parts.forEach { addPathSegment(it) } }.build().encodedPath
    suspend fun request(method: String, vararg parts: String, body: JsonElement? = null,
                        query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): JsonElement {
        if (!isCurrent()) throw kotlinx.coroutines.CancellationException("Voice account changed")
        val encodedQuery = "https://voice.invalid/".toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build().encodedQuery
        val a = site.rawApiRequest(method, path(listOf("voice") + parts), encodedQuery, body,
            headers + ("X-Voice-Client-Instance-Id" to instanceId)).getOrThrow()
        if (!isCurrent()) throw kotlinx.coroutines.CancellationException("Voice account changed")
        val value = if (a.body.isBlank()) JsonNull else runCatching { json.parseToJsonElement(a.body) }.getOrElse {
            if (a.code in 200..299) throw IllegalStateException("Unexpected voice response")
            JsonNull
        }
        if (a.code !in 200..299) {
            val e = runCatching { VoiceContract.decodeError(a.code, value) }.getOrElse {
                VoiceApiError(a.code, "voice_http_error", "Voice request failed (${a.code})", JsonNull)
            }
            throw VoiceApiError(e.status, e.code, e.message, e.params, a.headers["Retry-After"])
        }
        return value
    }
    suspend fun snapshot() = VoiceContract.decodeSnapshot(request("GET"))
    suspend fun join(roomId: String, replacing: String?, requestId: String = UUID.randomUUID().toString(), current: () -> Unit = {}): VoiceJoin {
        val v = request("POST", "sessions", body = buildJsonObject {
            put("roomId", roomId); put("clientInstanceId", instanceId); put("requestId", requestId)
            replacing?.let { put("replaceSessionId", it) }
        })
        current()
        val resolved = awaitResult(v, current)
        return VoiceContract.decodeJoin(resolved.jsonObject.getValue("join"))
    }
    suspend fun control(s: VoiceSession, muted: Boolean, deafened: Boolean): VoiceSession = VoiceContract.decodeSession(
        request("PATCH", "sessions", s.id, "control", body = buildJsonObject {
            put("clientInstanceId", instanceId); put("controlRevision", s.controlRevision)
            put("muted", muted); put("deafened", deafened)
        }))
    suspend fun leave(id: String) { awaitResult(request("DELETE", "sessions", id)) }
    suspend fun awaitResult(initial: JsonElement, current: () -> Unit = {}): JsonElement {
        var value = initial
        val operation = (value as? JsonObject)?.get("operationId")?.jsonPrimitive?.content ?: return value
        while (true) {
            current()
            delay(1_000)
            current()
            value = request("GET", "operations", operation)
            current()
            if (VoiceContract.decodeOperation(value).status == VoiceOperationStatus.COMPLETE) return value
        }
    }
    suspend fun settings() = json.decodeFromJsonElement<VoiceSettings>(request("GET", "settings"))
    suspend fun members(room: String, offset: Int = 0) = json.decodeFromJsonElement<VoiceMembers>(
        request("GET", "rooms", room, "members", query = mapOf("offset" to "$offset", "limit" to "50")))
    suspend fun accounts(query: String): List<VoiceAccount> = request("GET", "accounts", query = mapOf("query" to query))
        .jsonObject.getValue("accounts").jsonArray.map(VoiceContract::decodeAccount)
    suspend fun moderate(room: String, account: String, action: String) { awaitResult(request("POST", "rooms", room, "moderation", body = buildJsonObject {
        put("accountId", account); put("action", action); put("requestId", UUID.randomUUID().toString())
    })) }
}
