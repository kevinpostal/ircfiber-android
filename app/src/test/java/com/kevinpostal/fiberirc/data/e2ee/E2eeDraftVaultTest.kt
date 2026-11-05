package com.kevinpostal.fiberirc.data.e2ee

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

/**
 * Pending drafts: sealed at rest, readable only by their own account, deleted only on consume/discard. Uses a
 * software AES key in place of the Android Keystore key (same AES-GCM sealing).
 */
class E2eeDraftVaultTest {
    private val root: File = Files.createTempDirectory("drafts").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealer = object : DraftSealer {
        override fun seal(plain: ByteArray, aad: ByteArray) = sealGcm(key, plain, aad)
        override fun open(sealed: ByteArray, aad: ByteArray) = openGcm(key, sealed, aad)
    }
    private var clock = 1_000L
    private val vault = E2eeDraftVault(root, sealer) { clock++ }
    private val origin = "https://ircfiber.com"
    private val marker = "draft-plaintext-marker-✓"

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun roundTripsForTheSameAccountWithoutPlaintextOnDisk() {
        val d = vault.create(origin, "u1", "net1", "#chan", marker, replyTo = "msg-1")
        assertTrue(E2eeDraftVault.isDraftId(d.id))
        assertEquals(d, vault.load(d.id, origin, "u1"))
        assertEquals("msg-1", d.replyTo)
        val bytes = root.walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList()
        assertEquals(1, bytes.size)
        assertFalse(String(bytes.single(), Charsets.ISO_8859_1).contains("draft-plaintext-marker"))
        assertFalse(String(bytes.single(), Charsets.ISO_8859_1).contains("#chan"))
    }

    @Test
    fun anotherAccountOrOriginCannotReachIt() {
        val d = vault.create(origin, "u1", "net1", "#chan", marker, replyTo = null)
        assertNull(vault.load(d.id, origin, "u2"))
        assertNull(vault.load(d.id, "https://other.example", "u1"))
        assertTrue(vault.list(origin, "u2").isEmpty())
        // Discarding as another account deletes nothing.
        vault.delete(d.id, origin, "u2")
        assertEquals(d, vault.load(d.id, origin, "u1"))
    }

    @Test
    fun aFileMovedToAnotherAccountOrNameDoesNotOpen() {
        val d = vault.create(origin, "u1", "net1", "#chan", marker, replyTo = null)
        val other = vault.create(origin, "u2", "net1", "#x", "placeholder", replyTo = null)
        val src = File(File(root, e2eeAccountScope(origin, "u1")), d.id)
        // Swap u1's sealed file in place of u2's draft: the AAD (scope + id) no longer matches.
        src.copyTo(File(File(root, e2eeAccountScope(origin, "u2")), other.id), overwrite = true)
        assertNull(vault.load(other.id, origin, "u2"))
        // Tampered ciphertext fails closed too.
        val bytes = src.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        src.writeBytes(bytes)
        assertNull(vault.load(d.id, origin, "u1"))
    }

    @Test
    fun listIsOldestFirstAndConsumeOrDiscardDeletes() {
        val a = vault.create(origin, "u1", "net1", "#a", "first", replyTo = null)
        val b = vault.create(origin, "u1", "net1", "#b", "second", replyTo = null)
        assertEquals(listOf(a, b), vault.list(origin, "u1"))
        vault.delete(a.id, origin, "u1")
        assertEquals(listOf(b), vault.list(origin, "u1"))
        vault.removeAccount(origin, "u1")
        assertTrue(vault.list(origin, "u1").isEmpty())
    }

    @Test
    fun routeIdsThatAreNotDraftIdsAreRefused() {
        vault.create(origin, "u1", "net1", "#a", "first", replyTo = null)
        assertNull(vault.load("../${e2eeAccountScope(origin, "u1")}", origin, "u1"))
        assertNull(vault.load("not-a-uuid", origin, "u1"))
        assertFalse(E2eeDraftVault.isDraftId("A0000000-0000-0000-0000-000000000000"))
    }
}
