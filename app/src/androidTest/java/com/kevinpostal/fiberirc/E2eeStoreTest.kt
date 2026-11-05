package com.kevinpostal.fiberirc

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.e2ee.E2EE_FILES_DIR
import com.kevinpostal.fiberirc.data.e2ee.E2eeArchiveRow
import com.kevinpostal.fiberirc.data.e2ee.E2eeRecoveryRequired
import com.kevinpostal.fiberirc.data.e2ee.E2eeStore
import com.kevinpostal.fiberirc.data.e2ee.E2eeStoreRejected
import com.kevinpostal.fiberirc.data.e2ee.e2eeAccountScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The account-scoped encrypted store on this device's real SQLite and Android Keystore: committed round trips,
 * AAD binding (a sealed value moved to another key, table or account fails), no plaintext on disk, account
 * isolation, atomic recovery import, oldest-first pruning, and recovery-required (never a silent reset) when the
 * Keystore key is gone or a record is corrupt.
 *
 * Run: `ANDROID_SERIAL=<device> ./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.kevinpostal.fiberirc.E2eeStoreTest`.
 */
@RunWith(AndroidJUnit4::class)
class E2eeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = "https://store-test.invalid"
    private val userA = "store-test-a"
    private val userB = "store-test-b"
    private val marker = "PLAINTEXT-MARKER-7f3c"

    private fun dbFile(user: String) = File(File(File(context.filesDir, E2EE_FILES_DIR), e2eeAccountScope(origin, user)), "store.db")

    private fun wipe(user: String) {
        File(File(context.filesDir, E2EE_FILES_DIR), e2eeAccountScope(origin, user)).deleteRecursively()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "fiberirc.e2ee." + e2eeAccountScope(origin, user)
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun open(user: String) = E2eeStore.forAccount(context, origin, user).also { runBlocking { it.open() } }

    private fun row(text: String) = buildJsonObject {
        put("text", text)
        put("e2e", buildJsonObject { put("state", "ok"); put("epoch", 2) })
    }

    /** Every byte of the store's files (database, WAL, SHM). */
    private fun diskBytes(user: String): ByteArray =
        dbFile(user).parentFile!!.listFiles()!!.filter { it.isFile }.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    @Before
    fun setUp() {
        wipe(userA)
        wipe(userB)
    }

    @After
    fun tearDown() {
        wipe(userA)
        wipe(userB)
    }

    @Test
    fun roundTripsCommitAndSealEverything() = runBlocking {
        val store = open(userA)
        store.setMeta("deviceId", JsonPrimitive("dev-1"))
        store.setMeta("pgp:peer:n1:bob", buildJsonObject { put("armored", marker); put("fingerprint", "F1") })
        store.setMeta("pgp:ownKey", buildJsonObject { put("fingerprint", "OWN") })
        store.setMeta("bytes", buildJsonObject { put("\$u8", "AAEC") })
        store.saveMlsState("AQIDBA==")
        store.archivePut("msg-1", "n1", "#Chan", row(marker))
        store.setLocalPref("ircfiber:e2ee:defaults", """{"requireUnlock":true}""")

        assertEquals(JsonPrimitive("dev-1"), store.getMeta("deviceId"))
        assertEquals(listOf("pgp:ownKey", "pgp:peer:n1:bob"), store.listMeta("pgp:").map { it.first })
        assertEquals(listOf("pgp:peer:n1:bob"), store.listMeta("pgp:peer:").map { it.first })
        assertEquals(buildJsonObject { put("\$u8", "AAEC") }, store.getMeta("bytes"))
        assertEquals("AQIDBA==", store.loadMlsState())
        assertEquals(row(marker), store.archiveGet("msg-1"))
        val exported = store.archiveExportAll().single()
        assertEquals("n1:#chan", exported.bufferKey)
        assertEquals("msg-1", exported.msgid)
        assertTrue(store.hasData())
        store.setMeta("bytes", JsonNull)
        assertNull(store.getMeta("bytes"))
        store.deleteMeta("deviceId")
        assertNull(store.getMeta("deviceId"))
        store.close()

        // Committed: a new instance (new process) reads it back; nothing on disk is plaintext.
        val reopened = open(userA)
        assertEquals("AQIDBA==", reopened.loadMlsState())
        assertEquals(mapOf("ircfiber:e2ee:defaults" to """{"requireUnlock":true}"""), reopened.localPrefs())
        reopened.close()
        val disk = diskBytes(userA)
        assertFalse("marker on disk", disk.contains(marker.encodeToByteArray()))
        assertFalse("pref on disk", disk.contains("requireUnlock".encodeToByteArray()))
        assertTrue(E2eeStore.exists(context, origin, userA))
        assertFalse(E2eeStore.exists(context, origin, userB))
    }

    @Test
    fun accountsAreIsolated() = runBlocking {
        val a = open(userA)
        a.saveMlsState("AQID")
        a.setMeta("deviceId", JsonPrimitive("a-dev"))
        a.close()
        val b = open(userB)
        assertNull(b.loadMlsState())
        assertNull(b.getMeta("deviceId"))
        b.close()
        // A's sealed record copied into B's database does not open under B's key and AAD.
        val sealed = SQLiteDatabase.openDatabase(dbFile(userA).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT sealed FROM mls WHERE slot = 'state'", null).use { it.moveToFirst(); it.getBlob(0) }
        }
        SQLiteDatabase.openDatabase(dbFile(userB).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT OR REPLACE INTO mls (slot, sealed) VALUES ('state', ?)", arrayOf<Any>(sealed))
        }
        val reopened = open(userB)
        expectRecovery { reopened.loadMlsState() }
    }

    @Test
    fun aadBindsRecordsToTheirKeys() = runBlocking {
        val store = open(userA)
        store.setMeta("deviceId", JsonPrimitive("dev-1"))
        store.setMeta("pgp:ownKey", buildJsonObject { put("fingerprint", "OWN") })
        store.archivePut("m1", "n1", "#a", row("one"))
        store.archivePut("m2", "n1", "#b", row("two"))
        store.close()
        SQLiteDatabase.openDatabase(dbFile(userA).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Swap a meta value under another key, and move an archive row to another buffer.
            db.execSQL("UPDATE meta SET sealed = (SELECT sealed FROM meta WHERE key = 'pgp:ownKey') WHERE key = 'deviceId'")
            db.execSQL("UPDATE archive SET bufferKey = 'n1:#b' WHERE msgid = 'm1'")
        }
        val reopened = open(userA)
        expectRecovery { reopened.getMeta("deviceId") }
        // Archive rows are a cache: an unreadable one reads as missing and is left out of exports, like the web.
        assertNull(reopened.archiveGet("m1"))
        assertEquals(listOf("m2"), reopened.archiveExportAll().map(E2eeArchiveRow::msgid))
        reopened.close()
    }

    @Test
    fun missingKeystoreKeyRequiresRecoveryAndKeepsTheStore() = runBlocking {
        val store = open(userA)
        store.saveMlsState("AQID")
        store.close()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry("fiberirc.e2ee." + e2eeAccountScope(origin, userA))
        val again = E2eeStore.forAccount(context, origin, userA)
        expectRecovery { again.open() }
        // Never a silent reset: the database stays, keeps reporting recovery, and no new key was made.
        assertTrue(dbFile(userA).exists())
        expectRecovery { E2eeStore.forAccount(context, origin, userA).open() }
        assertFalse(ks.containsAlias("fiberirc.e2ee." + e2eeAccountScope(origin, userA)))
        // Only the explicit choice removes it.
        again.destroy()
        assertFalse(dbFile(userA).exists())
    }

    @Test
    fun corruptDatabaseRequiresRecovery() = runBlocking {
        val store = open(userA)
        store.saveMlsState("AQID")
        store.close()
        // Overwrite the header page: SQLite can no longer open it.
        dbFile(userA).writeBytes(ByteArray(4096) { 0x5a })
        File(dbFile(userA).path + "-wal").delete()
        expectRecovery { E2eeStore.forAccount(context, origin, userA).open() }
        assertTrue(dbFile(userA).exists())
    }

    @Test
    fun recoveryImportIsAtomicAndKeepsLocalTrust() = runBlocking {
        val store = open(userA)
        val localOwn = buildJsonObject {
            put("armoredPrivate", "p"); put("armoredPublic", "P"); put("fingerprint", "LOCAL"); put("passphrase", "")
        }
        store.setMeta("pgp:ownKey", localOwn)
        store.setMeta("pgp:peer:n1:bob", pin("BOB-LOCAL"))
        store.setMeta("linkRx:t1:0", JsonPrimitive("part0"))
        store.setMeta("linkRx:t1:1", JsonPrimitive("part1"))
        store.setMeta("linkRx:t2:0", JsonPrimitive("other"))

        val backupOwn = buildJsonObject {
            put("armoredPrivate", "q"); put("armoredPublic", "Q"); put("fingerprint", "BACKUP"); put("passphrase", "")
        }
        fun payload(archive: JsonArray) = buildJsonObject {
            put("archive", archive)
            put(
                "pgp",
                buildJsonObject {
                    put("ownKey", backupOwn)
                    put("oldKeys", JsonArray(emptyList()))
                    put(
                        "peers",
                        buildJsonArray {
                            add(buildJsonObject { put("networkId", "n1"); put("peer", "Bob"); put("pin", pin("BOB-BACKUP")) })
                            add(buildJsonObject { put("networkId", "n1"); put("peer", "carol"); put("pin", pin("CAROL")) })
                        },
                    )
                },
            )
            put("linkTransferId", "t1")
        }
        val good = buildJsonArray {
            add(buildJsonObject { put("msgid", "h1"); put("row", row("hello")); put("bufferKey", "n1:#a"); put("t", 5) })
        }
        // One malformed row: nothing at all is written.
        val bad = buildJsonArray {
            addAll(good)
            add(buildJsonObject { put("msgid", "h2"); put("bufferKey", "n1:#a"); put("t", 6) })
        }
        try {
            store.importRecovery(payload(bad))
            fail("malformed payload imported")
        } catch (_: E2eeStoreRejected) {
        }
        assertNull(store.archiveGet("h1"))
        assertEquals(2, store.listMeta("linkRx:t1:").size)

        val result = store.importRecovery(payload(good))
        assertEquals(1, result.archivedRows)
        assertEquals(listOf("pgp:ownKey", "pgp:peer:n1:bob"), result.pgpConflicts)
        assertEquals(localOwn, store.getMeta("pgp:ownKey"))
        assertEquals(listOf("BACKUP"), (store.getMeta("pgp:oldKeys") as JsonArray).map { fp(it) })
        assertEquals("BOB-LOCAL", fp(store.getMeta("pgp:peer:n1:bob")!!))
        assertEquals("CAROL", fp(store.getMeta("pgp:peer:n1:carol")!!))
        assertEquals(row("hello"), store.archiveGet("h1"))
        assertTrue(store.listMeta("linkRx:t1:").isEmpty())
        assertEquals(1, store.listMeta("linkRx:t2:").size)
        assertTrue(store.getMeta("linkDone:t1") is JsonObject)
        // Idempotent.
        val again = store.importRecovery(payload(good))
        assertEquals(listOf("BACKUP"), (store.getMeta("pgp:oldKeys") as JsonArray).map { fp(it) })
        assertEquals(1, again.archivedRows)
        store.close()
    }

    @Test
    fun pruneDropsOldestOfOneBuffer() = runBlocking {
        val store = open(userA)
        store.archiveImport((1..5).map { E2eeArchiveRow("p$it", row("r$it"), "n1:#a", it.toLong()) })
        store.archiveImport(listOf(E2eeArchiveRow("q1", row("other"), "n1:#b", 0)))
        store.archivePrune("n1", "#A", keep = 2)
        assertEquals(setOf("p4", "p5", "q1"), store.archiveExportAll().map { it.msgid }.toSet())
        store.close()
    }

    @Test
    fun destroyRemovesFilesAndKeyThenStartsFresh() = runBlocking {
        val store = open(userA)
        store.saveMlsState("AQID")
        store.destroy()
        assertFalse(dbFile(userA).parentFile!!.exists())
        val fresh = open(userA)
        assertNull(fresh.loadMlsState())
        assertFalse(fresh.hasData())
        fresh.close()
    }

    private fun pin(fingerprint: String) = buildJsonObject {
        put("armored", "k"); put("fingerprint", fingerprint); put("verified", false); put("pinnedAt", 1)
    }

    private fun fp(e: kotlinx.serialization.json.JsonElement) = ((e as JsonObject)["fingerprint"] as JsonPrimitive).content

    private suspend fun expectRecovery(block: suspend () -> Unit) {
        try {
            block()
            fail("expected recovery required")
        } catch (_: E2eeRecoveryRequired) {
        }
    }
}
