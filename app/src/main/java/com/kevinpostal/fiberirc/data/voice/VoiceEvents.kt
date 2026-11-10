package com.kevinpostal.fiberirc.data.voice

import com.kevinpostal.fiberirc.data.api.originOf
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

internal fun voiceEventsOf(text: String): List<VoiceEvent> {
    if (!text.contains("\"voice_")) return emptyList()
    val value = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    return (if (value is JsonArray) value.toList() else listOf(value)).mapNotNull {
        val kind = ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content
        if (kind != "voice_session" && kind != "voice_directory_changed") null
        else runCatching { VoiceContract.decodeEvent(it) }.getOrNull()
    }
}

/** Account lifetime, including ongoing background calls; unrelated to the MLS foreground socket. */
internal class VoiceEvents(
    http: OkHttpClient, private val base: String, private val device: () -> String,
    private val changed: () -> Unit, private val unauthorized: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var closed = false
    private var retries = 0
    private var retry: Job? = null
    fun start() { if (!closed && socket == null) connect() }
    fun close() { closed = true; socket?.cancel(); socket = null; scope.cancel() }
    private fun connect() {
        val baseUrl = base.toHttpUrl()
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegment("ws").addQueryParameter("device", device()).build())
            .header("Origin", originOf(baseUrl)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onMain(webSocket) { retries = 0; changed() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 1_048_576) { webSocket.close(1009, null); return }
                if (voiceEventsOf(text).isNotEmpty()) onMain(webSocket) { changed() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain(webSocket) { reconnect() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code; response?.close()
                onMain(webSocket) { if (code == 401) { close(); unauthorized() } else reconnect() }
            }
        })
    }
    private fun onMain(ws: WebSocket, action: () -> Unit) { scope.launch { if (!closed && socket === ws) action() } }
    private fun reconnect() {
        socket = null; retry?.cancel()
        retry = scope.launch { delay(listOf(1000L, 3000L, 8000L, 15000L, 30000L)[(retries++).coerceAtMost(4)]); if (!closed) connect() }
    }
}
