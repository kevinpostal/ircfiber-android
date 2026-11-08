package com.kevinpostal.fiberirc.data.irc

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IrcConnection] over real loopback sockets: the session's ordered events, an immediate local disconnect whose
 * socket close never runs on the caller (the main thread on Android, where it crashed with
 * NetworkOnMainThreadException), stale-attempt fencing, and a bounded connecting phase.
 */
class IrcConnectionTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, IO_THREAD).apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 50, loopback)
    private val host = loopback.hostAddress!!
    private val port = server.localPort
    private val caller = Thread.currentThread()
    private val probes = LinkedBlockingQueue<ProbeSocket>()

    /**
     * Fails socket I/O on [forbidden] like Android's main-thread policy, records closing threads and the connect
     * timeout, and holds `connect` until [gate] opens.
     */
    private class ProbeSocket(private val forbidden: Thread, private val gate: CountDownLatch?) : Socket() {
        val connecting = CountDownLatch(1)
        val closedOn = LinkedBlockingQueue<Thread>()
        @Volatile var connectTimeout = -1

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            connectTimeout = timeout
            connecting.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            super.connect(endpoint, timeout)
        }

        override fun close() {
            check(Thread.currentThread() !== forbidden) { "socket closed on the calling (main) thread" }
            closedOn.offer(Thread.currentThread())
            super.close()
        }
    }

    /** Fresh [ProbeSocket] per attempt; only the first waits on [gate]. */
    private fun probeSockets(gate: CountDownLatch? = null): () -> Socket {
        var first = true
        return {
            ProbeSocket(caller, gate.takeIf { first }).also {
                first = false
                probes.add(it)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        pool.shutdownNow()
        server.close()
    }

    private fun IrcConnection.next(): ConnectionEvent = runBlocking { withTimeout(5_000) { events.first() } }

    private fun IrcConnection.assertQuiet() = assertNull(runBlocking { withTimeoutOrNull(300) { events.first() } })

    private fun accept(): Socket = server.accept().apply { soTimeout = 5_000 }

    private fun Socket.say(line: String) = getOutputStream().run {
        write("$line\r\n".toByteArray())
        flush()
    }

    @Test
    fun disconnectEndsSessionOnceAndReconnectDeliversOnlyTheNewSession() {
        val conn = IrcConnection(scope, newSocket = probeSockets())
        conn.connect(host, port, tls = false)
        val a = accept()
        assertEquals(ConnectionEvent.Connected, conn.next())
        a.say("PING :a")
        assertEquals(ConnectionEvent.Line("PING :a"), conn.next())

        conn.disconnect()
        assertEquals(ConnectionEvent.Disconnected(null), conn.next())
        assertFalse(conn.trySend("PONG :a"))
        assertEquals(-1, a.getInputStream().read()) // the old link really closes
        conn.disconnect()
        conn.assertQuiet() // no second Disconnected, nothing late from the old reader

        conn.connect(host, port, tls = false)
        val b = accept()
        assertEquals(ConnectionEvent.Connected, conn.next())
        assertTrue(conn.trySend("PONG :b"))
        assertEquals("PONG :b", b.getInputStream().bufferedReader().readLine())
        b.say("PING :b")
        assertEquals(ConnectionEvent.Line("PING :b"), conn.next())
        b.close()
        val end = conn.next()
        assertTrue("$end", end is ConnectionEvent.Disconnected && end.cause is IrcConnectionException)
        conn.assertQuiet()
    }

    @Test
    fun disconnectClosesSocketOffTheCallingThread() {
        val conn = IrcConnection(scope, newSocket = probeSockets())
        conn.connect(host, port, tls = false)
        accept()
        assertEquals(ConnectionEvent.Connected, conn.next())

        conn.disconnect() // throws here if the close runs on this (main) thread
        assertEquals(ConnectionEvent.Disconnected(null), conn.next())
        val closer = probes.take().closedOn.poll(5, TimeUnit.SECONDS)
        assertNotNull("socket never closed", closer)
        assertFalse("socket close blocked the caller", closer === Thread.currentThread())
    }

    @Test
    fun disconnectWhileConnectingAbortsTheAttemptWithoutLateEvents() {
        val gate = CountDownLatch(1)
        val conn = IrcConnection(scope, newSocket = probeSockets(gate))
        conn.connect(host, port, tls = false)
        val attempt = probes.take()
        assertTrue(attempt.connecting.await(5, TimeUnit.SECONDS))

        conn.disconnect()
        assertEquals(ConnectionEvent.Disconnected(null), conn.next())
        assertNotNull("connecting socket never closed", attempt.closedOn.poll(5, TimeUnit.SECONDS))
        gate.countDown()
        conn.assertQuiet() // the aborted attempt reports neither Connected nor another Disconnected

        conn.connect(host, port, tls = false)
        accept()
        assertEquals(ConnectionEvent.Connected, conn.next())
    }

    @Test
    fun tcpConnectUsesTheBoundedTimeout() {
        val conn = IrcConnection(scope, newSocket = probeSockets(), connectTimeoutMs = 1_234)
        conn.connect(host, port, tls = false)
        accept()
        assertEquals(ConnectionEvent.Connected, conn.next())
        assertEquals(1_234, probes.take().connectTimeout)
    }

    @Test
    fun silentTlsPeerEndsTheAttemptInsteadOfConnectingForever() {
        val conn = IrcConnection(scope, handshakeTimeoutMs = 200)
        conn.connect(host, port, tls = true)
        val peer = accept() // accepts TCP, never answers the ClientHello
        val end = conn.next()
        assertTrue("$end", end is ConnectionEvent.Disconnected && end.cause != null)
        conn.assertQuiet()
        peer.close()
    }

    private companion object {
        const val IO_THREAD = "irc-io-test"
    }
}
