package com.kevinpostal.fiberirc.ui.shell

import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager

/**
 * A buffer across sessions and clients: its network (`networkId`, or the network name on the direct-ircd
 * fallback, which has no id) and its name. [string] is the `"<network>:<buffer>"` form the session persists.
 * Ports `BufferKey` (`ios/UI/Shell/BufferHistory.swift`).
 */
data class BufferKey(val network: String, val buffer: String) {
    val string: String get() = "$network:$buffer"

    /** Same network and, case-insensitively, the same buffer name (IRC names are case-insensitive). */
    fun matches(other: BufferKey): Boolean = network == other.network && buffer.equals(other.buffer, ignoreCase = true)

    companion object {
        /** Parses `"<network>:<buffer>"`; the network part never holds a colon (UUID or network name). */
        fun parse(string: String): BufferKey? {
            val colon = string.indexOf(':')
            if (colon < 0) return null
            val network = string.substring(0, colon)
            val buffer = string.substring(colon + 1)
            if (network.isEmpty() || buffer.isEmpty()) return null
            return BufferKey(network, buffer)
        }

        /** [b] on [c]: keyed by the network id, or the network name when there is none. */
        fun of(c: IrcClient, b: IrcBuffer): BufferKey = BufferKey(IrcSessionManager.networkKey(c), b.name)
    }
}

/** Recently viewed buffers, like a browser's back and forward lists (IRCCloud's two-finger swipes). */
class BufferHistory {
    private val _back = ArrayList<BufferKey>()
    private val _forward = ArrayList<BufferKey>()

    /** Oldest first; the last entry is the back target. */
    val back: List<BufferKey> get() = _back

    /** Oldest first; the last entry is the forward target. */
    val forward: List<BufferKey> get() = _forward

    /** The user left [previous] for another buffer: it becomes the back target and the forward list is cleared. */
    fun visit(previous: BufferKey) {
        _forward.clear()
        push(previous, _back)
    }

    /** The buffer to go back to from [current] (which becomes the forward target), or null at the start. */
    fun goBack(current: BufferKey): BufferKey? {
        val target = _back.removeLastOrNull() ?: return null
        push(current, _forward)
        return target
    }

    /** The buffer to go forward to from [current] (which becomes the back target), or null at the end. */
    fun goForward(current: BufferKey): BufferKey? {
        val target = _forward.removeLastOrNull() ?: return null
        push(current, _back)
        return target
    }

    /** The previous buffer when the current one is gone (closed, left): taken off the back list, nothing goes forward. */
    fun popBack(): BufferKey? = _back.removeLastOrNull()

    /** Appends [key] unless it already ends the list, dropping the oldest entries past [LIMIT]. */
    private fun push(key: BufferKey, list: MutableList<BufferKey>) {
        if (list.lastOrNull()?.matches(key) == true) return
        list.add(key)
        if (list.size > LIMIT) list.subList(0, list.size - LIMIT).clear()
    }

    companion object {
        const val LIMIT = 50
    }
}
