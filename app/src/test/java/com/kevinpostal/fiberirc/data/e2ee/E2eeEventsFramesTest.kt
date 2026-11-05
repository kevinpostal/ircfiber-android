package com.kevinpostal.fiberirc.data.e2ee

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the MLS event stream and the account scoping: frame filter, backoff, URL, hashed keys. */
class E2eeEventsFramesTest {
    private fun types(frame: String) = mlsEventsOf(frame).map { (it["type"] as JsonPrimitive).content }

    @Test
    fun onlyMlsControlFramesPass() {
        assertEquals(listOf("mls_commit"), types("""{"type":"mls_commit","gid":"g","epoch":3,"commit":"AA"}"""))
        assertEquals(
            listOf("mls_welcome", "mls_link"),
            types("""[{"type":"mls_welcome"},{"type":"buffer_msg","msg":"mls_commit"},{"type":"mls_link","data":"x"}]"""),
        )
        // Chat and state frames are ignored, even when their text mentions an MLS type.
        assertTrue(types("""{"type":"buffer_msg","msg":"\"mls_commit\""}""").isEmpty())
        assertTrue(types("""{"type":"stat_user","prefs":{}}""").isEmpty())
        assertTrue(types("""{"type":"mls_unknown"}""").isEmpty())
        assertTrue(types("""{"type":"mls_commit"""").isEmpty())
        assertTrue(types("""{"type":1,"x":"mls_commit"}""").isEmpty())
        assertEquals(
            setOf("mls_commit", "mls_welcome", "mls_join_request", "mls_device_removed", "mls_device_reset", "mls_link"),
            MLS_EVENT_TYPES,
        )
    }

    @Test
    fun reconnectBacksOffThenCaps() {
        assertEquals(listOf(1_000L, 3_000L, 8_000L, 15_000L, 30_000L, 30_000L), (1..6).map(::e2eeEventsBackoff))
        assertEquals(16 * 1024 * 1024, E2EE_EVENTS_MAX_FRAME_BYTES)
    }

    @Test
    fun eventsUrlCarriesTheSettingsDevice() {
        assertEquals("https://ircfiber.com/ws?device=abc-1", e2eeEventsUrl("https://ircfiber.com", "abc-1").toString())
        assertEquals("http://10.0.2.2:8090/ws?device=d", e2eeEventsUrl("http://10.0.2.2:8090", "d").toString())
    }

    @Test
    fun hashedKeysMatchTheSharedRuntime() {
        // printf 'https://ircfiber.com\0user-123' | shasum -a 256
        assertEquals(
            "b706967a7c86899d936ad6ea64da0622e2a9556bacd0f87724cbfe357334d466",
            e2eeAccountScope("https://ircfiber.com", "user-123"),
        )
        // printf 'net-1\0#chan\0!MLS1:QUJD' | shasum -a 256 — the target is lowercased like mobile.ts sendMappingKey.
        assertEquals(
            "sent:1e4f84e60ccba1efac7e0b176b308b362e668c7163a15de7c957df2b3b5736c9",
            e2eeSendMappingKey("net-1", "#Chan", "!MLS1:QUJD"),
        )
    }
}
