package com.kevinpostal.fiberirc.ui.shell

import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports `FiberIRCTests/BufferListModelTests.swift`. */
class BufferListModelTest {
    private val server1 = IrcBuffer("Net1", IrcBufferKind.SERVER)
    private val server2 = IrcBuffer("Net2", IrcBufferKind.SERVER)

    private fun ch(name: String) = IrcBuffer(name, IrcBufferKind.CHANNEL)
    private fun q(name: String) = IrcBuffer(name, IrcBufferKind.QUERY)
    private fun net(id: String, vararg buffers: IrcBuffer) = BufferListClient(id, buffers.toList())

    private fun rows(
        clients: List<BufferListClient>,
        pins: List<String> = emptyList(),
        archived: Set<String> = emptySet(),
        collapsed: Set<String> = emptySet(),
        networkOrder: List<String> = emptyList(),
        expandedArchives: Set<String> = emptySet(),
        connected: List<Boolean>? = null,
    ) = BufferListModel.rows(clients, pins, archived, collapsed, networkOrder, expandedArchives, connected ?: clients.map { true })

    /** Rows as short strings: "pinned", "pin #a", "net 0", "#a", "archives 0 +"/"-", "arch #a", "join 0", "add". */
    private fun describe(rows: List<BufferRow>): List<String> = rows.map { row ->
        when (row) {
            BufferRow.Audio -> "audio"
            is BufferRow.AudioRoom -> "audio room ${row.room.id}"
            is BufferRow.AudioPerson -> "audio participant ${row.participant.accountId}"
            BufferRow.PinnedHeader -> "pinned"
            is BufferRow.Pinned -> "pin ${row.buffer.name}"
            is BufferRow.Network -> "net ${row.client}"
            is BufferRow.Buffer -> row.buffer.name
            is BufferRow.ArchivesHeader -> "archives ${row.client} ${if (row.expanded) "-" else "+"}"
            is BufferRow.Archived -> "arch ${row.buffer.name}"
            is BufferRow.JoinChannel -> "join ${row.client}"
            BufferRow.AddNetwork -> "add"
        }
    }

    @Test
    fun pinsComeFirstUnderAHeader() {
        val a = ch("#a")
        val b = ch("#b")
        val r = rows(listOf(net("n1", server1, a, b)), pins = listOf("n1:#b"))
        assertEquals(listOf("pinned", "pin #b", "net 0", "#a", "#b", "add"), describe(r))
        assertEquals(BufferRow.Pinned(0, b), r[1])
        assertEquals("no pins: no header", listOf("net 0", "#a", "add"), describe(rows(listOf(net("n1", server1, a)))))
    }

    @Test
    fun channelsSortIgnoringTheirPrefixAndQueriesFollow() {
        val r = rows(listOf(net("n1", server1, q("zed"), ch("#Beta"), ch("##alpha"), q("Amy"), ch("&gamma"))))
        assertEquals(listOf("net 0", "##alpha", "#Beta", "&gamma", "Amy", "zed", "add"), describe(r))
    }

    @Test
    fun archivedBuffersLeaveTheListAndShowOnlyWhenExpanded() {
        val a = ch("#a")
        val old = ch("#Old")
        val bob = q("bob")
        val clients = listOf(net("n1", server1, a, old, bob))
        val archived = setOf("n1:#old", "n1:bob")
        assertEquals(listOf("net 0", "#a", "archives 0 +", "add"), describe(rows(clients, archived = archived)))
        val open = rows(clients, archived = archived, expandedArchives = setOf("n1"))
        assertEquals(listOf("net 0", "#a", "archives 0 -", "arch #Old", "arch bob", "add"), describe(open))
        assertEquals(BufferRow.Archived(0, old), open[3])
        assertEquals(
            "another network's archive does not match",
            listOf("net 0", "#a", "#Old", "bob", "add"),
            describe(rows(clients, archived = setOf("n2:#a"))),
        )
    }

    @Test
    fun collapsedNetworkShowsOnlyItsNetworkRow() {
        val r = rows(listOf(net("n1", server1, ch("#a")), net("n2", server2, ch("#b"))), archived = setOf("n1:#a"), collapsed = setOf("n1"))
        assertEquals(listOf("net 0", "net 1", "#b", "add"), describe(r))
        assertEquals("no join row either", listOf("net 0", "add"), describe(rows(listOf(net("n1", server1)), collapsed = setOf("n1"))))
    }

    @Test
    fun joinChannelOnlyForAConnectedNetworkWithOnlyItsServerBuffer() {
        val clients = listOf(net("n1", server1), net("n2", server2, ch("#b")))
        assertEquals(listOf("net 0", "join 0", "net 1", "#b", "add"), describe(rows(clients, connected = listOf(true, true))))
        assertEquals(listOf("net 0", "net 1", "#b", "add"), describe(rows(clients, connected = listOf(false, true))))
    }

    @Test
    fun networkOrderComesFirstThenBouncerOrder() {
        val clients = listOf(net("n1", server1), net("n2", server2), net("n3", IrcBuffer("Net3", IrcBufferKind.SERVER)))
        val r = rows(clients, networkOrder = listOf("n3", "gone", "n2"), connected = listOf(false, false, false))
        assertEquals(listOf("net 2", "net 1", "net 0", "add"), describe(r))
    }
}
