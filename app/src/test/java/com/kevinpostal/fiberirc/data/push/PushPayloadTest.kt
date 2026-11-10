package com.kevinpostal.fiberirc.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadTest {
    private fun data(
        buffer: String = "#Chat",
        sender: String = "alice",
        body: String = "alice: hello there",
        category: String = PushPayload.CATEGORY_BUFFER_MESSAGE,
        threadId: String? = "net1:#chat",
    ): Map<String, String> = buildMap {
        put("networkId", "net1")
        put("buffer", buffer)
        put("sender", sender)
        put("ownNick", "me")
        put("msgid", "m1")
        put("t", "1730000000123")
        put("title", "alice in $buffer")
        put("subtitle", "IRC Fiber")
        put("body", body)
        threadId?.let { put("threadId", it) }
        put("category", category)
        put("badge", "3")
    }

    @Test
    fun parsesEveryField() {
        val p = PushPayload.parse(data())!!
        assertEquals("net1", p.networkId)
        assertEquals("#Chat", p.buffer)
        assertEquals("alice", p.sender)
        assertEquals("me", p.ownNick)
        assertEquals("m1", p.msgid)
        assertEquals(1730000000123L, p.timeMs)
        assertEquals("net1:#chat", p.threadId)
        assertEquals(3, p.badge)
        assertEquals(p, PushPayload.parse(p.toData()))
    }

    @Test
    fun parsesOwnershipPrivacyAndDeliveryMetadataThroughActionRoundTrip() {
        val wire = data() + mapOf(
            "userId" to "account-7",
            "apiOrigin" to "https://irc.example:8443",
            "preview" to "sender",
            "eventId" to "event-9",
            "groupId" to "window-2",
            "silent" to "true",
        )
        val p = PushPayload.parse(wire)!!
        assertEquals("account-7", p.userId)
        assertEquals("https://irc.example:8443", p.apiOrigin)
        assertEquals("sender", p.preview)
        assertEquals("event-9", p.eventId)
        assertEquals("window-2", p.groupId)
        assertTrue(p.silent)
        val actionData = p.toData().filterKeys { it in PushPayload.KEYS }
        assertEquals("true", actionData["silent"])
        assertEquals(p, PushPayload.parse(actionData))
        assertFalse(PushPayload.parse(wire + ("silent" to "false"))!!.silent)
        assertEquals("none", PushPayload.parse(wire + ("preview" to "none"))!!.preview)
    }

    @Test
    fun legacyPayloadKeepsDisplayDefaultsWithoutAcquiringOwnership() {
        val p = PushPayload.parse(data())!!
        assertEquals("", p.userId)
        assertEquals("", p.apiOrigin)
        assertEquals("", p.eventId)
        assertEquals("", p.groupId)
        assertEquals("full", p.preview)
        assertFalse(p.silent)
        val restored = PushPayload.parse(p.toData())!!
        assertEquals("", restored.userId)
        assertEquals("", restored.apiOrigin)
        assertEquals("false", restored.toData()["silent"])
    }

    @Test
    fun rejectsUnknownCategoryOrMissingRouting() {
        assertNull(PushPayload.parse(data(category = "other")))
        assertNull(PushPayload.parse(data(sender = "")))
        assertNull(PushPayload.parse(data() - "networkId"))
        assertNull(PushPayload.parse(data() - "buffer"))
    }

    @Test
    fun isChannelComparesBufferAndSenderCaseInsensitively() {
        assertTrue(PushPayload.parse(data())!!.isChannel)
        assertFalse(PushPayload.parse(data(buffer = "Alice", sender = "alice", body = "hi"))!!.isChannel)
    }

    @Test
    fun channelMessageDropsSenderPrefix() {
        assertEquals("hello there", PushPayload.parse(data())!!.messageText)
    }

    @Test
    fun messageTextKeepsBodyOtherwise() {
        // A private message keeps its body even when it looks prefixed.
        assertEquals("alice: hi", PushPayload.parse(data(buffer = "alice", body = "alice: hi"))!!.messageText)
        // An invite keeps its body.
        val invite = data(body = "alice: come join", category = PushPayload.CATEGORY_CHANNEL_INVITE)
        assertEquals("alice: come join", PushPayload.parse(invite)!!.messageText)
        // Nothing after the prefix, or a different sender's prefix: kept as it came.
        assertEquals("alice: ", PushPayload.parse(data(body = "alice: "))!!.messageText)
        assertEquals("bob: hi", PushPayload.parse(data(body = "bob: hi"))!!.messageText)
        // The prefix match is exact (case-sensitive, like Swift's hasPrefix).
        assertEquals("Alice: hi", PushPayload.parse(data(body = "Alice: hi"))!!.messageText)
    }

    @Test
    fun threadIdFallsBackToNetworkAndLowerCasedBuffer() {
        assertEquals("net1:#chat", PushPayload.parse(data(threadId = null))!!.threadId)
        assertEquals("net1:#chat", PushPayload.parse(data(threadId = ""))!!.threadId)
        assertEquals("net1:#chat", ConversationShortcuts.threadId("net1", "#CHAT"))
    }

    @Test
    fun missingOptionalFieldsDefault() {
        val p = PushPayload.parse(data() - "t" - "badge" - "ownNick")!!
        assertEquals(0L, p.timeMs)
        assertEquals(0, p.badge)
        assertEquals("", p.ownNick)
    }
}
