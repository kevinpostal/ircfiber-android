package com.kevinpostal.fiberirc.data.irc

import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source

/** What a transport reports, in order: `Connected`, then lines, then exactly one `Disconnected` per connection. */
sealed interface ConnectionEvent {
    data object Connected : ConnectionEvent
    data class Line(val text: String) : ConnectionEvent
    data class Disconnected(val cause: Throwable?) : ConnectionEvent
}

/** Line-oriented transport (ports `ios/IRC/IRCTransport.swift`). [events] has a single collector: the client. */
interface IrcTransport {
    val events: Flow<ConnectionEvent>
    fun connect(host: String, port: Int, tls: Boolean)

    /** Appends `\r\n`. Dropped unless connected. */
    fun send(line: String)

    /** [send], reporting whether the connection accepted the line (false: nothing queued, not connected). */
    fun trySend(line: String): Boolean {
        send(line)
        return true
    }

    fun disconnect()
}

class IrcConnectionException(message: String) : IOException(message) {
    companion object {
        fun closedByServer() = IrcConnectionException("connection closed by server")
    }
}

/**
 * CRLF line framing identical to `IRCConnection.drainLines`: split on `\n`, strip a trailing `\r`, drop empty lines
 * and lines over 8192 bytes, decode UTF-8 falling back to ISO-8859-1, and discard the buffer when more than 16384
 * bytes accumulate with no newline.
 */
class LineFramer {
    private var rx = ByteArray(65536)
    private var size = 0
    private val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<String> {
        if (count <= 0) return emptyList()
        if (size + count > rx.size) rx = rx.copyOf(maxOf(rx.size * 2, size + count))
        System.arraycopy(bytes, 0, rx, size, count)
        size += count
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            var nl = -1
            for (k in start until size) if (rx[k] == NL) { nl = k; break }
            if (nl < 0) break
            var end = nl
            if (end > start && rx[end - 1] == CR) end--
            val n = end - start
            if (n in 1..MAX_LINE_BYTES) out.add(decode(start, n))
            start = nl + 1
        }
        if (start > 0) {
            System.arraycopy(rx, start, rx, 0, size - start)
            size -= start
        } else if (size > MAX_LINE_BYTES * 2) {
            size = 0 // garbage without newline
        }
        return out
    }

    private fun decode(offset: Int, length: Int): String = try {
        utf8.reset()
        utf8.decode(ByteBuffer.wrap(rx, offset, length)).toString()
    } catch (_: CharacterCodingException) {
        String(rx, offset, length, Charsets.ISO_8859_1)
    }

    companion object {
        const val MAX_LINE_BYTES = 8192
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}

/**
 * Socket transport (ports `ios/IRC/IRCConnection.swift`): plain TCP, upgraded to TLS with the system trust store
 * and hostname verification (no pinning, like `NWParameters.tls`). Reads in ≤ 65536-byte chunks on [scope]
 * ([Dispatchers.IO]); one writer coroutine keeps sends ordered.
 *
 * Every event is emitted under [lock] only while its session is current, so a finished or replaced session can never
 * deliver a late `Connected` or line after its `Disconnected`. [disconnect] is called on the main thread: it ends the
 * session and emits `Disconnected` immediately, and the socket close (network I/O: TLS close_notify) runs on [scope].
 *
 * The TCP connect and TLS handshake are bounded by [connectTimeoutMs] and [handshakeTimeoutMs], so an attempt can
 * never stay connecting forever; an established link has no read timeout (the client's idle watchdog owns liveness).
 */
class IrcConnection(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val newSocket: () -> Socket = { Socket() },
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
) : IrcTransport {
    private val channel = Channel<ConnectionEvent>(Channel.UNLIMITED)
    override val events: Flow<ConnectionEvent> = channel.receiveAsFlow()

    /** One connection. [socket] is set under [lock] while the session is current; [finish] then owns closing it. */
    private class Session {
        var socket: Socket? = null
        @Volatile var ready = false
        val outgoing = Channel<String>(Channel.UNLIMITED)
    }

    private val lock = Any()
    private var session: Session? = null

    override fun connect(host: String, port: Int, tls: Boolean) {
        synchronized(lock) {
            if (session != null || port !in 1..65535) return
            val s = Session()
            session = s
            scope.launch { run(s, host, port, tls) }
        }
    }

    private fun run(s: Session, host: String, port: Int, tls: Boolean) {
        var error: Throwable?
        try {
            val raw = newSocket()
            // Attached before connecting, so a disconnect while connecting closes it and aborts the connect.
            if (!attach(s, raw)) return
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val sock = if (tls) upgrade(raw, host, port) else raw
            if (sock !== raw && !attach(s, sock)) return
            val source = sock.source().buffer()
            val sink = sock.sink().buffer()
            if (!whileCurrent(s) {
                    s.ready = true
                    channel.trySend(ConnectionEvent.Connected)
                }
            ) return
            scope.launch { writeLoop(s, sink) }
            val framer = LineFramer()
            val chunk = ByteArray(65536)
            while (true) {
                val n = source.read(chunk)
                if (n < 0) break
                val lines = framer.feed(chunk, n)
                if (lines.isNotEmpty() && !whileCurrent(s) {
                        for (line in lines) channel.trySend(ConnectionEvent.Line(line))
                    }
                ) return
            }
            error = IrcConnectionException.closedByServer()
        } catch (e: CancellationException) {
            error = null
        } catch (e: Throwable) {
            error = e
        }
        finish(s, error)
    }

    /** Runs [block] under [lock] if [s] is still the current session; false (nothing run) once it has finished. */
    private inline fun whileCurrent(s: Session, block: () -> Unit): Boolean = synchronized(lock) {
        if (session !== s) return false
        block()
        true
    }

    /** Hands [sock] to [s] for [finish] to close; if [s] already finished, closes it here (always off the main thread). */
    private fun attach(s: Session, sock: Socket): Boolean {
        if (whileCurrent(s) { s.socket = sock }) return true
        closeQuietly(sock)
        return false
    }

    private fun upgrade(raw: Socket, host: String, port: Int): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = handshakeTimeoutMs
        ssl.startHandshake()
        ssl.soTimeout = 0
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
            ssl.close()
            throw SSLPeerUnverifiedException("certificate does not match $host")
        }
        return ssl
    }

    private suspend fun writeLoop(s: Session, sink: BufferedSink) {
        try {
            for (line in s.outgoing) {
                sink.writeUtf8(line)
                sink.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            finish(s, e)
        }
    }

    /**
     * Ends [s] if it is still current: emits its one `Disconnected` and frees the slot for the next [connect] together,
     * synchronously, and closes its socket on [scope] (callers include the main thread). Stale callers are no-ops.
     */
    private fun finish(s: Session, error: Throwable?) {
        val sock = synchronized(lock) {
            if (session !== s) return
            session = null
            s.ready = false
            val sock = s.socket
            s.socket = null
            // Last: a collector resumed inline here may already call connect() for the next session.
            channel.trySend(ConnectionEvent.Disconnected(error))
            sock
        }
        if (error != null) Log.e(TAG, "connection ended: $error")
        s.outgoing.close()
        if (sock != null) scope.launch { closeQuietly(sock) }
    }

    private fun closeQuietly(sock: Socket) {
        try {
            sock.close()
        } catch (_: IOException) {
        }
    }

    override fun send(line: String) {
        trySend(line)
    }

    override fun trySend(line: String): Boolean {
        val s = synchronized(lock) { session } ?: return false
        if (!s.ready) return false
        return s.outgoing.trySend(line + "\r\n").isSuccess
    }

    override fun disconnect() {
        val s = synchronized(lock) { session } ?: return
        finish(s, null)
    }

    private companion object {
        const val TAG = "FiberIRC/net"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val HANDSHAKE_TIMEOUT_MS = 15_000
    }
}
