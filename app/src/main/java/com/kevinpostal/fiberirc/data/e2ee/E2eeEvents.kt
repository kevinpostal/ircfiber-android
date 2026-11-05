package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.api.originOf
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The MLS control-plane frame types the runtime's `handleWsEvent` accepts; every other `/ws` frame is ignored. */
val MLS_EVENT_TYPES: Set<String> = setOf(
    "mls_commit",
    "mls_welcome",
    "mls_join_request",
    "mls_device_removed",
    "mls_device_reset",
    "mls_link",
)

/** Largest `/ws` frame accepted: the server's 12 MiB MLS handshake limit plus JSON overhead. */
const val E2EE_EVENTS_MAX_FRAME_BYTES = 16 * 1024 * 1024

/** Reconnect delays after a failed/closed events socket, reset by a successful connection. */
val E2EE_EVENTS_BACKOFF_MS: List<Long> = listOf(1_000, 3_000, 8_000, 15_000, 30_000)

/** The delay before reconnect attempt [failures] (1-based count of consecutive failures). */
fun e2eeEventsBackoff(failures: Int): Long = E2EE_EVENTS_BACKOFF_MS[(failures - 1).coerceIn(0, E2EE_EVENTS_BACKOFF_MS.lastIndex)]

/**
 * The MLS events of one `/ws` text frame: `type` among [MLS_EVENT_TYPES] (an object, or each object of an array
 * frame). Non-MLS frames (the initial state dump, chat backlog, settings) are skipped without parsing when they
 * can't contain one. Pure: unit-tested on the JVM.
 */
fun mlsEventsOf(frame: String): List<JsonObject> {
    if (!frame.contains("\"mls_")) return emptyList()
    val root = try {
        Json.parseToJsonElement(frame)
    } catch (_: SerializationException) {
        return emptyList()
    }
    fun one(e: JsonElement): JsonObject? {
        val o = e as? JsonObject ?: return null
        val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return o.takeIf { type in MLS_EVENT_TYPES }
    }
    return when (root) {
        is JsonArray -> root.mapNotNull(::one)
        else -> listOfNotNull(one(root))
    }
}

/** `<api base>/ws?device=<settings device id>`, `https→wss`/`http→ws` (OkHttp upgrades the HTTP(S) URL itself). */
fun e2eeEventsUrl(baseUrl: String, settingsDeviceId: String): HttpUrl =
    baseUrl.toHttpUrl().newBuilder().addPathSegment("ws").addQueryParameter("device", settingsDeviceId).build()

/**
 * The account's cookie-authenticated MLS event stream: the gateway's `/ws` WebSocket over the app's shared
 * ircfiber.com client (its cookie jar and `X-IRCFiber-Device*` headers), with the matching `Origin` on the
 * handshake. IRC chat stays on the bouncer connection: only [MLS_EVENT_TYPES] frames reach [Listener.onEvents].
 * These are Redis pub/sub, not a replayable stream: after every (re)connect [Listener.onOpen] is the cue to
 * reconcile from durable state. Reconnects back off 1/3/8/15/30 s, reset after a successful open; a 401 stops for
 * good ([Listener.onUnauthorized]). Frames over [E2EE_EVENTS_MAX_FRAME_BYTES] close the socket (1009) and
 * reconnect. Foreground-only: the service [start]s it on foreground and [stop]s it on background/sign-out.
 *
 * Callbacks arrive on the main thread, in frame order.
 */
class E2eeEvents(
    httpClient: OkHttpClient,
    private val baseUrl: String,
    private val settingsDeviceId: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        /** Connected (first time or again): reconcile state, since events in between are lost. */
        fun onOpen()

        /** MLS events of one frame, in order. */
        fun onEvents(events: List<JsonObject>)

        /** The handshake was refused with 401: the site session is gone. No more retries. */
        fun onUnauthorized()
    }

    private val client = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val origin = originOf(baseUrl.toHttpUrl())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var running = false
    private var socket: WebSocket? = null
    private var failures = 0
    private var retry: Job? = null
    private var unauthorized = false

    val isRunning: Boolean get() = running

    /** Connects (main thread); no-op when running or after a 401. */
    fun start() {
        if (running || unauthorized) return
        running = true
        failures = 0
        connect()
    }

    /** Closes the socket and cancels retries (main thread). Restartable. */
    fun stop() {
        running = false
        retry?.cancel()
        retry = null
        socket?.close(1000, null)
        socket = null
    }

    /** Terminal: [stop] and release the scope. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun connect() {
        val request = Request.Builder()
            .url(e2eeEventsUrl(baseUrl, settingsDeviceId()))
            .header("Origin", origin)
            .build()
        // Callbacks post to the main thread; by the time they run, `socket` holds this socket (assigned right here).
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onMain(webSocket) {
                failures = 0
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Character count ≤ UTF-8 size; the exact size is computed only for frames that could exceed.
                if (text.length > E2EE_EVENTS_MAX_FRAME_BYTES ||
                    (text.length > E2EE_EVENTS_MAX_FRAME_BYTES / 3 && text.encodeToByteArray().size > E2EE_EVENTS_MAX_FRAME_BYTES)
                ) {
                    webSocket.close(1009, "frame too large")
                    return
                }
                val events = mlsEventsOf(text)
                if (events.isNotEmpty()) onMain(webSocket) { listener.onEvents(events) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain(webSocket) { scheduleRetry() }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                response?.close()
                onMain(webSocket) {
                    if (code == 401) {
                        unauthorized = true
                        running = false
                        socket = null
                        Log.w(TAG, "events handshake refused: 401")
                        listener.onUnauthorized()
                    } else {
                        Log.w(TAG, "events socket failed: ${code ?: t.javaClass.simpleName}")
                        scheduleRetry()
                    }
                }
            }
        })
    }

    /** Runs [block] on the main thread if [ws] is still this stream's live socket. */
    private fun onMain(ws: WebSocket, block: () -> Unit) {
        scope.launch { if (running && socket === ws) block() }
    }

    private fun scheduleRetry() {
        socket = null
        if (!running) return
        failures += 1
        val wait = e2eeEventsBackoff(failures)
        retry?.cancel()
        retry = scope.launch {
            delay(wait)
            if (running && socket == null) connect()
        }
    }

    companion object {
        private const val TAG = "E2eeEvents"
    }
}
