package com.kevinpostal.fiberirc.data.e2ee

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The external-send snapshot file: exact shared shape, account/origin binding, five-minute expiry, atomic replace. */
class E2eePolicySnapshotTest {
    private val dir: File = Files.createTempDirectory("snapshot").toFile()
    private val file = File(File(dir, "e2ee"), "policy-snapshot.json")
    private val origin = "https://ircfiber.com"
    private val now = 1_800_000_000_000L

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun snapshot(userId: String = "u1", writtenAt: Long = now, perTarget: Map<String, String> = mapOf("net1:#a" to "off")) =
        E2eePolicySnapshot(
            userId = userId,
            apiOrigin = origin,
            writtenAt = writtenAt,
            dmDefault = true,
            autoLockIncoming = false,
            perTarget = perTarget,
        )

    @Test
    fun writesTheSharedShapeAndReadsItBackForTheSameAccount() {
        E2eePolicySnapshots.write(file, snapshot())
        val keys = Json.parseToJsonElement(file.readText()).jsonObject.keys
        assertEquals(setOf("v", "userId", "apiOrigin", "writtenAt", "dmDefault", "autoLockIncoming", "perTarget"), keys)
        assertEquals(snapshot(), E2eePolicySnapshots.read(file, origin, "u1", now + 1_000))
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun anotherAccountOrOriginIsUnknown() {
        E2eePolicySnapshots.write(file, snapshot())
        assertNull(E2eePolicySnapshots.read(file, origin, "u2", now))
        assertNull(E2eePolicySnapshots.read(file, "https://other.example", "u1", now))
    }

    @Test
    fun olderThanFiveMinutesOrFromTheFutureIsUnknown() {
        E2eePolicySnapshots.write(file, snapshot())
        assertEquals(snapshot(), E2eePolicySnapshots.read(file, origin, "u1", now + E2eePolicySnapshots.MAX_AGE_MS))
        assertNull(E2eePolicySnapshots.read(file, origin, "u1", now + E2eePolicySnapshots.MAX_AGE_MS + 1))
        assertNull(E2eePolicySnapshots.read(file, origin, "u1", now - E2eePolicySnapshots.CLOCK_SKEW_MS - 1))
    }

    @Test
    fun malformedOrUnknownVersionOrStateIsUnknown() {
        file.parentFile!!.mkdirs()
        file.writeText("""{"userId":"u1","apiOrigin":"$origin","writtenAt":$now,"dmDefault":false,"autoLockIncoming":false,"perTarget":{}}""")
        assertNull("v is required", E2eePolicySnapshots.read(file, origin, "u1", now))
        file.writeText("""{"v":2,"userId":"u1","apiOrigin":"$origin","writtenAt":$now,"dmDefault":false,"autoLockIncoming":false,"perTarget":{}}""")
        assertNull(E2eePolicySnapshots.read(file, origin, "u1", now))
        file.writeText("""{"v":1,"userId":"u1","apiOrigin":"$origin","writtenAt":$now,"dmDefault":false,"autoLockIncoming":false,"perTarget":{"n:#a":"maybe"}}""")
        assertNull(E2eePolicySnapshots.read(file, origin, "u1", now))
        file.writeText("not json")
        assertNull(E2eePolicySnapshots.read(file, origin, "u1", now))
    }

    @Test
    fun removeOnlyDeletesThatAccountsSnapshot() {
        E2eePolicySnapshots.write(file, snapshot())
        E2eePolicySnapshots.remove(file, origin, "u2")
        assertTrue(file.exists())
        E2eePolicySnapshots.remove(file, origin, "u1")
        assertFalse(file.exists())
    }

    @Test
    fun lastAccountIgnoresAgeButNotOrigin() {
        E2eePolicySnapshots.write(file, snapshot(writtenAt = 0))
        assertEquals("u1", E2eePolicySnapshots.lastAccountUserId(file, origin))
        assertNull(E2eePolicySnapshots.lastAccountUserId(file, "https://other.example"))
    }

    @Test
    fun targetStateFollowsPrefsPendingAndDmDefault() {
        assertEquals("pending", E2eePolicySnapshots.targetState(e2eePref = false, lockPending = true, isChannel = true, dmDefault = false))
        assertEquals("on", E2eePolicySnapshots.targetState(e2eePref = true, lockPending = false, isChannel = true, dmDefault = false))
        assertEquals("off", E2eePolicySnapshots.targetState(e2eePref = false, lockPending = false, isChannel = false, dmDefault = true))
        assertEquals("on", E2eePolicySnapshots.targetState(e2eePref = null, lockPending = false, isChannel = false, dmDefault = true))
        assertEquals("off", E2eePolicySnapshots.targetState(e2eePref = null, lockPending = false, isChannel = true, dmDefault = true))
    }
}
