package com.kevinpostal.fiberirc.ui.shell

import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.prefs.AccountKeyListStore

/**
 * One row of the channel drawer (IRCCloud's `BuffersTableView`). `client` is the index into the clients passed to
 * [BufferListModel.rows], not the display position. Buffers compare by identity: a row is about that buffer
 * object. Ports `BufferRow` (`ios/UI/BufferList/BufferListModel.swift`).
 */
sealed interface BufferRow {
    data object PinnedHeader : BufferRow
    class Pinned(val client: Int, val buffer: IrcBuffer) : BufferRow {
        override fun equals(other: Any?) = other is Pinned && other.client == client && other.buffer === buffer
        override fun hashCode() = 31 * client + System.identityHashCode(buffer)
    }

    /** The network's server buffer. */
    data class Network(val client: Int) : BufferRow
    class Buffer(val client: Int, val buffer: IrcBuffer) : BufferRow {
        override fun equals(other: Any?) = other is Buffer && other.client == client && other.buffer === buffer
        override fun hashCode() = 31 * client + System.identityHashCode(buffer)
    }
    data class ArchivesHeader(val client: Int, val expanded: Boolean) : BufferRow
    class Archived(val client: Int, val buffer: IrcBuffer) : BufferRow {
        override fun equals(other: Any?) = other is Archived && other.client == client && other.buffer === buffer
        override fun hashCode() = 31 * client + System.identityHashCode(buffer)
    }
    data class JoinChannel(val client: Int) : BufferRow
    data object AddNetwork : BufferRow
}

/** A client as the drawer model sees it: its network id and buffers (server buffer included). */
data class BufferListClient(val networkId: String, val buffers: List<IrcBuffer>)

object BufferListModel {
    /**
     * The drawer's rows, top to bottom:
     * 1. the pins ([pinned] order) under a [BufferRow.PinnedHeader], when there are any;
     * 2. every network, those named in [networkOrder] first in that order, then the rest in [clients] order. Each
     *    has its [BufferRow.Network] row and, unless its id is in [collapsed]: its channels (sorted
     *    case-insensitively, the leading `#`/`&` ignored) then its conversations (by name), leaving out those
     *    archived; a [BufferRow.ArchivesHeader], followed by the [BufferRow.Archived] rows when its id is in
     *    [expandedArchives], if any are archived; and [BufferRow.JoinChannel] when it is connected and holds only
     *    its server buffer;
     * 3. [BufferRow.AddNetwork].
     *
     * [archived] holds `<networkId>:<buffer>` keys, matched case-insensitively; a network without an id has none.
     * `connected[i]` is whether `clients[i]` is connected.
     */
    fun rows(
        clients: List<BufferListClient>,
        pins: List<String>,
        archived: Set<String>,
        collapsed: Set<String>,
        networkOrder: List<String>,
        expandedArchives: Set<String>,
        connected: List<Boolean>,
    ): List<BufferRow> {
        val rows = ArrayList<BufferRow>()
        val pinned = pinned(clients, pins)
        if (pinned.isNotEmpty()) {
            rows.add(BufferRow.PinnedHeader)
            pinned.mapTo(rows) { BufferRow.Pinned(it.first, it.second) }
        }
        val archivedKeys = archived.mapTo(HashSet()) { it.lowercase() }
        for (i in order(clients.map { it.networkId }, networkOrder)) {
            val c = clients[i]
            rows.add(BufferRow.Network(i))
            if (c.networkId in collapsed) continue
            fun isArchived(b: IrcBuffer) = c.networkId.isNotEmpty() && "${c.networkId}:${b.name}".lowercase() in archivedKeys
            val listed = sorted(c.buffers.filter { it.kind != IrcBufferKind.SERVER })
            val open = listed.filterNot(::isArchived)
            val hidden = listed.filter(::isArchived)
            open.mapTo(rows) { BufferRow.Buffer(i, it) }
            if (hidden.isNotEmpty()) {
                val expanded = c.networkId in expandedArchives
                rows.add(BufferRow.ArchivesHeader(i, expanded))
                if (expanded) hidden.mapTo(rows) { BufferRow.Archived(i, it) }
            }
            if (connected.getOrElse(i) { false } && c.buffers.all { it.kind == IrcBufferKind.SERVER }) {
                rows.add(BufferRow.JoinChannel(i))
            }
        }
        rows.add(BufferRow.AddNetwork)
        return rows
    }

    /**
     * Pinned (client index, buffer) pairs in pin order (`PinList.pinned`); server buffers never qualify. Keys that
     * match no open buffer are skipped. Stable: ties (duplicate keys) keep client/buffer order.
     */
    fun pinned(clients: List<BufferListClient>, keys: List<String>): List<Pair<Int, IrcBuffer>> {
        val hits = ArrayList<Triple<Int, Int, IrcBuffer>>()
        clients.forEachIndexed { i, c ->
            for (b in c.buffers) {
                if (b.kind == IrcBufferKind.SERVER) continue
                AccountKeyListStore.rank(b.name, c.networkId, keys)?.let { hits.add(Triple(it, i, b)) }
            }
        }
        return hits.sortedBy { it.first }.map { it.second to it.third }
    }

    /** Client indices: ids in [networkOrder] first, in that order, then the others in their own order. */
    private fun order(ids: List<String>, networkOrder: List<String>): List<Int> {
        val rank = HashMap<String, Int>()
        networkOrder.forEachIndexed { i, id -> rank.putIfAbsent(id, i) }
        return ids.indices.sortedWith { a, b ->
            val ra = rank[ids[a]]
            val rb = rank[ids[b]]
            when {
                ra != null && rb != null -> if (ra == rb) a.compareTo(b) else ra.compareTo(rb)
                ra != null -> -1
                rb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }

    /**
     * Channels before conversations; channels by name without their leading `#`/`&`, conversations by name, both
     * case-insensitively.
     */
    private fun sorted(buffers: List<IrcBuffer>): List<IrcBuffer> {
        fun sortKey(b: IrcBuffer) = if (b.kind == IrcBufferKind.CHANNEL) b.name.dropWhile { it == '#' || it == '&' } else b.name
        val byName = Comparator<IrcBuffer> { a, b ->
            val order = sortKey(a).compareTo(sortKey(b), ignoreCase = true)
            if (order == 0) a.name.compareTo(b.name) else order
        }
        return buffers.filter { it.kind == IrcBufferKind.CHANNEL }.sortedWith(byName) +
            buffers.filter { it.kind == IrcBufferKind.QUERY }.sortedWith(byName)
    }
}
