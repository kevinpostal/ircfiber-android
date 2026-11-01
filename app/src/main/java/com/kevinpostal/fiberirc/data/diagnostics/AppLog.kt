package com.kevinpostal.fiberirc.data.diagnostics

import java.time.Instant

/**
 * The app's log sink (ports `Log.swift` + the `OSLogStore` read in `Diagnostics.readLog`): forwards to
 * `android.util.Log` (logcat) and keeps the newest [CAPACITY] lines of this launch whose tag starts with
 * [TAG_PREFIX] in memory, for Settings → Export Diagnostics. Callers import it as `Log`
 * (`import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log`), so call sites read like `android.util.Log`.
 *
 * Privacy rule (`Log.swift`): never log IRC message text, request or response bodies, usernames or passwords.
 * States, HTTP methods, paths and status codes are fine.
 */
object AppLog {
    const val VERBOSE = android.util.Log.VERBOSE
    const val DEBUG = android.util.Log.DEBUG
    const val INFO = android.util.Log.INFO
    const val WARN = android.util.Log.WARN
    const val ERROR = android.util.Log.ERROR
    const val ASSERT = android.util.Log.ASSERT

    /** Every app tag starts with this (`FiberIRC.api`, `FiberIRC/irc`, …); other tags stay out of the report. */
    const val TAG_PREFIX = "FiberIRC"
    const val CAPACITY = 2000

    private val ring = ArrayDeque<String>(CAPACITY)

    fun v(tag: String, msg: String): Int = println(VERBOSE, tag, msg)
    fun d(tag: String, msg: String): Int = println(DEBUG, tag, msg)
    fun i(tag: String, msg: String): Int = println(INFO, tag, msg)
    fun w(tag: String, msg: String): Int = println(WARN, tag, msg)
    fun e(tag: String, msg: String): Int = println(ERROR, tag, msg)

    /** With a throwable: only its class name is recorded (its message may carry user data). */
    fun w(tag: String, msg: String, tr: Throwable?): Int = println(WARN, tag, withType(msg, tr))
    fun e(tag: String, msg: String, tr: Throwable?): Int = println(ERROR, tag, withType(msg, tr))

    private fun withType(msg: String, tr: Throwable?) = if (tr == null) msg else "$msg (${tr.javaClass.simpleName})"

    /** Like `android.util.Log.println`; also records the line when [tag] is the app's. */
    fun println(priority: Int, tag: String, msg: String): Int {
        record(Instant.now(), priority, tag, msg)
        return try {
            android.util.Log.println(priority, tag, msg)
        } catch (_: RuntimeException) {
            0 // JVM unit tests: android.util.Log is a stub
        }
    }

    /** This launch's recorded lines, oldest first (at most [CAPACITY]). */
    fun lines(): List<String> = synchronized(ring) { ring.toList() }

    internal fun record(time: Instant, priority: Int, tag: String, msg: String) {
        if (!tag.startsWith(TAG_PREFIX)) return
        val line = "$time [${levelName(priority)}] $tag: $msg"
        synchronized(ring) {
            if (ring.size == CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
    }

    internal fun clear() = synchronized(ring) { ring.clear() }

    private fun levelName(priority: Int): String = when (priority) {
        VERBOSE -> "verbose"
        DEBUG -> "debug"
        INFO -> "info"
        WARN -> "warn"
        ERROR -> "error"
        ASSERT -> "fault"
        else -> "log"
    }
}
