package com.kevinpostal.fiberirc.ui.login

import com.kevinpostal.fiberirc.data.prefs.MemoryPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSignupStoreTest {
    private val base = "https://ircfiber.com/"
    private val start = 1_700_000_000_000L
    private val prefs = MemoryPrefs()
    private var clock = start
    private val store = PendingSignupStore(prefs) { clock }
    private val entry = PendingSignupStore.Entry(base, "alice", "alice@example.test", start)

    @Test
    fun savedEntryRestoresForSameBaseAsOneJsonObjectWithoutSecrets() {
        store.save(entry)
        clock = start + 5_000

        assertEquals(entry, store.load(base))
        val stored = Json.parseToJsonElement(prefs.getString(PendingSignupStore.KEY, null)!!).jsonObject
        assertEquals(setOf("baseURL", "username", "email", "startedAtMs"), stored.keys)
        assertEquals(setOf(PendingSignupStore.KEY), prefs.map.keys)
    }

    @Test
    fun equivalentSpellingsOfTheSameOriginMatch() {
        store.save(entry.copy(baseURL = "HTTPS://IRCFiber.com:443"))

        assertEquals(entry, store.load("https://ircfiber.com"))
    }

    @Test
    fun differentSchemeHostOrPortNeverResumesAndDiscardsTheMarker() {
        for (other in listOf(
            "http://ircfiber.com/",
            "https://staging.ircfiber.com/",
            "https://ircfiber.com:8443/",
            "https://127.0.0.1:58290/",
            "not a url",
        )) {
            store.save(entry)
            assertNull(other, store.load(other))
            assertFalse(other, prefs.contains(PendingSignupStore.KEY))
        }
    }

    @Test
    fun ageIsAcceptedFromZeroUpToButExcludingTwentyFourHours() {
        store.save(entry)

        clock = start
        assertEquals(entry, store.load(base))
        clock = start + PendingSignupStore.MAX_AGE_MS - 1
        assertEquals(entry, store.load(base))
        clock = start + PendingSignupStore.MAX_AGE_MS
        assertNull(store.load(base))
        assertFalse(prefs.contains(PendingSignupStore.KEY))
    }

    @Test
    fun futureOrAbsurdTimestampsAreDiscarded() {
        store.save(entry)
        clock = start - 1
        assertNull(store.load(base))
        assertFalse(prefs.contains(PendingSignupStore.KEY))

        clock = start
        for (startedAtMs in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            store.save(entry.copy(startedAtMs = startedAtMs))
            assertNull("$startedAtMs", store.load(base))
            assertFalse("$startedAtMs", prefs.contains(PendingSignupStore.KEY))
        }
    }

    @Test
    fun corruptMarkersAreDiscardedWithoutTouchingOtherPreferences() {
        prefs.edit().putBoolean("accepted_guidelines", true).putString("other", "kept").apply()
        val corrupt = listOf(
            "not json",
            "[]",
            "null",
            """{"baseURL":"$base","username":"alice","startedAtMs":$start}""",
            """{"baseURL":"$base","username":"alice","email":"a@example.test","startedAtMs":"soon"}""",
            """{"baseURL":"$base","username":"","email":"a@example.test","startedAtMs":$start}""",
            """{"baseURL":"$base","username":"alice","email":" ","startedAtMs":$start}""",
            """{"baseURL":"$base","username":null,"email":"a@example.test","startedAtMs":$start}""",
        )
        for (raw in corrupt) {
            prefs.edit().putString(PendingSignupStore.KEY, raw).apply()
            assertNull(raw, store.load(base))
            assertFalse(raw, prefs.contains(PendingSignupStore.KEY))
        }
        prefs.edit().putLong(PendingSignupStore.KEY, start).apply()
        assertNull(store.load(base))
        assertFalse(prefs.contains(PendingSignupStore.KEY))
        assertEquals(true, prefs.getBoolean("accepted_guidelines", false))
        assertEquals("kept", prefs.getString("other", null))
    }

    @Test
    fun clearRemovesOnlyTheMarker() {
        prefs.edit().putString("other", "kept").apply()
        store.save(entry)

        store.clear()

        assertNull(store.load(base))
        assertFalse(prefs.contains(PendingSignupStore.KEY))
        assertTrue(prefs.contains("other"))
    }

    @Test
    fun savingReplacesThePreviousAttempt() {
        store.save(entry)
        val next = entry.copy(username = "bob", email = "bob@example.test", startedAtMs = start + 1)
        store.save(next)
        clock = start + 2

        assertEquals(next, store.load(base))
    }

    @Test
    fun absentMarkerLoadsNothing() {
        assertNull(store.load(base))
    }
}
