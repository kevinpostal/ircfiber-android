package com.kevinpostal.fiberirc.data.voice

import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceContractTest {
    private fun fixture(): JsonObject {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("voice-v1.json"))
        return SiteJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private fun rows(root: JsonObject, key: String): List<JsonObject> =
        (root.getValue(key) as JsonArray).map { it.jsonObject }

    @Test
    fun authorizedSnapshotsPreservePrivateFilteringNullRostersAndRevisions() {
        val vectors = rows(fixture(), "snapshots")
        val snapshots = vectors.map { VoiceContract.decodeSnapshot(it.getValue("value")) }
        assertEquals("9007199254740993", snapshots[0].revision)
        val locked = snapshots[0].rooms.first { it.slug == "restricted" }
        assertFalse(locked.canJoin)
        assertNull(locked.participantCount)
        assertNull(locked.participants)
        for ((vector, snapshot) in vectors.zip(snapshots)) {
            for (id in vector["hiddenRoomIds"] as? JsonArray ?: JsonArray(emptyList())) {
                assertFalse(snapshot.rooms.any { it.id == id.jsonPrimitive.content })
            }
        }
        assertEquals(VoiceVisibility.PRIVATE, snapshots[3].rooms.last().visibility)
        assertFalse(snapshots[4].available)
        assertNull(snapshots[4].rooms[0].participants)
        assertNull(snapshots[4].rooms[0].participantCount)
        assertTrue(snapshots[5].rooms.isEmpty())
        assertNull(snapshots[5].session)
        assertTrue(snapshots[6].canManage)
        val owner = checkNotNull(snapshots[1].session)
        val other = checkNotNull(snapshots[2].session)
        assertEquals(owner.id, other.id)
        assertTrue(owner.ownedByThisClient)
        assertFalse(other.ownedByThisClient)
        val otherProjection = SiteJson.encodeToJsonElement(VoiceSession.serializer(), other).jsonObject
        assertFalse("clientInstanceId" in otherProjection)
        assertFalse("token" in otherProjection)
    }

    @Test
    fun sessionStatesOperationsAndBroadcastsRetainTheirDistinctMeaning() {
        val root = fixture()
        val sessions = rows(root, "sessions").map { VoiceContract.decodeSession(it.getValue("value")) }
        assertEquals(listOf(VoiceSessionStatus.JOINING, VoiceSessionStatus.CONNECTED,
            VoiceSessionStatus.RECONNECTING, VoiceSessionStatus.REVOKING), sessions.map { it.status })
        assertEquals(listOf(0L, 1L, 2L, 3L), sessions.map { it.controlRevision })
        val operations = rows(root, "operations").map { VoiceContract.decodeOperation(it.getValue("value")) }
        assertEquals(listOf(VoiceOperationStatus.PENDING, VoiceOperationStatus.COMPLETE), operations.map { it.status })
        assertNull(operations[0].join)
        assertNull(operations[1].join)
        val events = rows(root, "events").map { VoiceContract.decodeEvent(it.getValue("value")) }
        assertEquals("9007199254740994", events[0].revision)
        assertEquals(VoiceEventKind.SESSION, events[1].type)
        assertEquals(sessions[0].id, events[1].sessionId)
        assertEquals(8L, events[1].controlRevision)
        assertEquals("voice_removed", events[1].reason)
        assertFalse("ownedByThisClient" in SiteJson.encodeToJsonElement(VoiceEvent.serializer(), events[1]).jsonObject)
    }

    @Test
    fun completedJoinRequiresTheOriginatingOwner() {
        val session = rows(fixture(), "sessions")[0].getValue("value").jsonObject
        val join = JsonObject(mapOf("session" to session, "liveKitUrl" to kotlinx.serialization.json.JsonPrimitive("wss://voice.invalid"),
            "token" to kotlinx.serialization.json.JsonPrimitive("decoder-test-not-a-credential"),
            "expiresAtMs" to kotlinx.serialization.json.JsonPrimitive(1_800_000_060_000L)))
        val complete = JsonObject(mapOf("status" to kotlinx.serialization.json.JsonPrimitive("complete"), "join" to join))
        val decoded = VoiceContract.decodeOperation(complete)
        assertEquals(session.getValue("id").jsonPrimitive.content, decoded.join?.session?.id)
        assertEquals(1_800_000_060_000L, decoded.join?.expiresAtMs)
        val pending = JsonObject(complete + ("status" to kotlinx.serialization.json.JsonPrimitive("pending")))
        assertTrue(runCatching { VoiceContract.decodeOperation(pending) }.isFailure)
        val other = JsonObject(session + ("ownedByThisClient" to kotlinx.serialization.json.JsonPrimitive(false)))
        val nonOwnerJoin = JsonObject(join + ("session" to other))
        assertTrue(runCatching { VoiceContract.decodeOperation(JsonObject(complete + ("join" to nonOwnerJoin))) }.isFailure)
    }

    @Test
    fun errorsRetainHttpStatusStableCodeAndStructuredParams() {
        for (vector in rows(fixture(), "errors")) {
            val status = vector.getValue("status").jsonPrimitive.int
            val body = vector.getValue("body").jsonObject
            val error = VoiceContract.decodeError(status, body)
            assertEquals(status, error.status)
            assertEquals(body.getValue("code").jsonPrimitive.content, error.code)
            assertEquals(body.getValue("error").jsonPrimitive.content, error.message)
            assertEquals(body.getValue("params"), error.params)
        }
    }

    @Test
    fun accountProjectionNeverCarriesUserSecrets() {
        val input = SiteJson.parseToJsonElement("""{"accountId":"account","username":"member","avatarUrl":null,"email":"not-real","passwordHash":"not-real","roles":["admin"]}""")
        val account = VoiceContract.decodeAccount(input)
        assertEquals(VoiceAccount("account", "member", null), account)
        val projection = kotlinx.serialization.json.Json.encodeToJsonElement(VoiceAccount.serializer(), account).jsonObject
        assertEquals(setOf("accountId", "username", "avatarUrl"), projection.keys)
    }

    @Test
    fun malformedAndLeakingVectorsFailClosed() {
        for (vector in rows(fixture(), "invalid")) {
            val value = vector.getValue("value")
            val rejected = runCatching {
                when (vector.getValue("kind").jsonPrimitive.content) {
                    "snapshot" -> VoiceContract.decodeSnapshot(value)
                    "session" -> VoiceContract.decodeSession(value)
                    "operation" -> VoiceContract.decodeOperation(value)
                    "event" -> VoiceContract.decodeEvent(value)
                    "error" -> VoiceContract.decodeError(409, value)
                    else -> throw AssertionError("Unhandled vector kind")
                }
            }.isFailure
            assertTrue("${vector["name"]}: ${vector["why"]}", rejected)
        }
    }
}
