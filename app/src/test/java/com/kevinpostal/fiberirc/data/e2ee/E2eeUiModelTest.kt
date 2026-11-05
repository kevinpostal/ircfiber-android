package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import com.kevinpostal.fiberirc.ui.encryption.E2eeOrgSignIn
import com.kevinpostal.fiberirc.ui.encryption.QrCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eeUiModelTest {
    private fun status(json: String) = E2eeStatus(Json.parseToJsonElement(json) as JsonObject)

    private fun running(json: String) = E2eeServiceState.Running(1, status(json))

    private val policy = """{"suite":"hybrid","dmDefault":false,"requireUnlock":false,"backupMode":"encouraged","archiveEnabled":false,"orgDomain":""}"""

    @Test
    fun composerNeverShowsOffUnlessTheSendPathIsOff() {
        val ready = running("""{"ui":{"ready":true,"unlocked":true},"policy":$policy}""")
        // The message path's own decision rules: only OFF is plaintext, whatever the status says.
        assertEquals(ComposerLock.Off, E2eeComposerLock.of(IrcLockDecision.OFF, ready, "n:#a"))
        assertEquals(ComposerLock.On, E2eeComposerLock.of(IrcLockDecision.ENCRYPT, ready, "n:#a"))
        // No decision yet (no policy, service still starting or down) is never off.
        assertEquals(ComposerLock.Pending(cancellable = false), E2eeComposerLock.of(IrcLockDecision.PENDING, ready, "n:#a"))
        assertEquals(ComposerLock.Pending(cancellable = false), E2eeComposerLock.of(IrcLockDecision.PENDING, E2eeServiceState.Starting, "n:#a"))
        val down = E2eeComposerLock.of(IrcLockDecision.PENDING, E2eeServiceState.Unavailable("x", webViewUpdate = true), "n:#a")
        assertEquals("Update Android System WebView to use encryption", E2eeComposerLock.label(down, null))
    }

    @Test
    fun composerShowsSetupLockedPartialAndNoPeer() {
        val pending = running("""{"ui":{"ready":true,"lockPending":{"n:#a":true}},"policy":$policy}""")
        assertEquals(ComposerLock.Pending(cancellable = true), E2eeComposerLock.of(IrcLockDecision.ENCRYPT, pending, "n:#a"))
        val locked = running("""{"ui":{"ready":true,"requireUnlock":true,"unlocked":false},"policy":$policy}""")
        assertEquals(ComposerLock.Locked, E2eeComposerLock.of(IrcLockDecision.ENCRYPT, locked, "n:#a"))
        val partial = running("""{"ui":{"ready":true,"overrides":{"n:#a":"partial"},"readers":{"n:#a":{"n":3,"m":5}}},"policy":$policy}""")
        val p = E2eeComposerLock.of(IrcLockDecision.ENCRYPT, partial, "n:#a")
        assertEquals(ComposerLock.Partial(E2eeReaders(3, 5)), p)
        // Partial coverage never claims everyone can read.
        assertEquals("Encrypted · 3 of 5 members can read", E2eeComposerLock.label(p, null))
        val nopeer = running("""{"ui":{"ready":true,"overrides":{"n:bob":"nopeer"}},"policy":$policy}""")
        val np = E2eeComposerLock.of(IrcLockDecision.ENCRYPT, nopeer, "n:bob")
        assertEquals(ComposerLock.NoPeer, np)
        assertEquals("bob can't receive encrypted messages", E2eeComposerLock.label(np, "bob"))
    }

    @Test
    fun audienceReadersAndChipFollowTheWeb() {
        val members = listOf("Alice", "bob", "carol", "*", "")
        assertEquals(listOf("alice", "bob", "carol", "me"), E2eeAudience.readers(members, E2eeAudience.EVERYONE, "Me"))
        val except = E2eeAudience(E2eeAudience.Mode.EXCEPT, listOf("bob"))
        assertEquals(listOf("alice", "carol", "me"), E2eeAudience.readers(members, except, "me"))
        val only = E2eeAudience(E2eeAudience.Mode.ONLY, listOf("carol", "dave"))
        assertEquals(listOf("carol", "me"), E2eeAudience.readers(members, only, "me"))
        assertNull(E2eeAudience.EVERYONE.chip)
        assertEquals("except bob", except.chip)
        assertEquals("only carol, dave", only.chip)
        assertEquals("only 3", E2eeAudience(E2eeAudience.Mode.ONLY, listOf("a", "b", "c")).chip)
        assertEquals("except 0", E2eeAudience(E2eeAudience.Mode.EXCEPT, emptyList()).chip)
        // Round trip through the runtime's JSON lower-cases accounts.
        assertEquals(E2eeAudience(E2eeAudience.Mode.ONLY, listOf("carol")), E2eeAudience.of(E2eeAudience(E2eeAudience.Mode.ONLY, listOf("Carol")).toJson()))
    }

    @Test
    fun ownSafetyNumberMatchesTheWebSettingsPage() {
        val devices = listOf(E2eeDevice("d1-uuid", "QUJD+/==", 3, false), E2eeDevice("d2", "WFla", 4, false))
        val payload = E2eeOwnSafety.payload("alice", devices)
        // Fixture computed with the web's renderSafety (JSON.stringify + SHA-256) in Node.
        assertEquals("""{"account":"alice","devices":[{"deviceId":"d1-uuid","credential":"QUJD+/=="},{"deviceId":"d2","credential":"WFla"}]}""", payload)
        assertEquals("22052 30828 26991 47524 47374 15007 32591 49543 15411 99617 95261 75507", E2eeOwnSafety.number(payload))
    }

    @Test
    fun linkCodeQrRoundTripsThroughAnImage() {
        val code = """{"v":2,"account":"alice","deviceId":"0b6f6a5e-3c1d-4f7a-9f3e-2b8c7d6e5f40","suite":79,""" +
            """"credentialHash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}"""
        val modules = QrCodes.encode(code)
        val scale = 4
        val size = modules.size * scale
        val pixels = IntArray(size * size) { i ->
            if (modules[(i / size) / scale][(i % size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(code, QrCodes.decode(size, size, pixels))
        assertNull(QrCodes.decode(size, size, IntArray(size * size) { 0xFFFFFFFF.toInt() }))
    }

    @Test
    fun linkProgressWordsEveryState() {
        fun p(json: String) = E2eeLinkProgress.of(Json.parseToJsonElement(json))!!
        assertEquals("Receiving history… 2/9", p("""{"role":"receive","state":"transferring","done":2,"total":9}""").text)
        assertEquals("Sending history… 0/…", p("""{"role":"send","state":"transferring","done":0,"total":0}""").text)
        assertEquals("History imported: 41 messages", p("""{"role":"receive","state":"complete","done":3,"total":3,"archivedRows":41}""").text)
        assertTrue(p("""{"role":"send","state":"complete","done":3,"total":3,"archivedRows":7,"omittedRows":2}""").text.contains("stayed on this device: 2"))
        assertEquals("History transfer incomplete: timed out", p("""{"role":"send","state":"incomplete","done":1,"total":3,"error":"timed out"}""").text)
        assertNull(E2eeLinkProgress.of(Json.parseToJsonElement("""{"role":"send","state":"weird"}""")))
    }

    @Test
    fun defaultsAndPolicyParseWithWebBaseValues() {
        assertEquals(E2eeDefaults(cacheDecrypted = false, dmDefault = false, autoLockIncoming = true, requireUnlock = false), E2eeDefaults.parse(null))
        assertEquals(E2eeDefaults(requireUnlock = true, autoLockIncoming = true), E2eeDefaults.parse("""{"requireUnlock":true}"""))
        assertEquals(E2eeDefaults(), E2eeDefaults.parse("not json"))
        val org = E2eePolicy.of(Json.parseToJsonElement("""{"backupMode":"forbidden","requireUnlock":true,"orgDomain":"acme.test","orgIdp":true}"""))!!
        assertEquals(E2eePolicy.BackupMode.FORBIDDEN, org.backupMode)
        assertTrue(org.requireUnlock && org.orgIdp)
        // A status without a policy decision has none (sends needing one stay blocked).
        assertNull(status("""{"ui":{"ready":true},"policy":null}""").policy)
    }

    @Test
    fun recoveryKeyShapeAndErrorWording() {
        val key = "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23-4567-ABCD-EFGH-IJKL-MNOP-QRST"
        assertTrue(E2eeRecoveryKey.isWellFormed(key))
        assertTrue(E2eeRecoveryKey.isWellFormed(key.lowercase().replace("-", " ")))
        assertFalse(E2eeRecoveryKey.isWellFormed("ABCD-EFGH"))
        assertEquals("Identity was already reset in the last 24 hours", E2eeTexts.error(E2eeBridgeException("http", "reset_rate_limited", 429)))
        assertEquals(E2eeTexts.PRF_UNSUPPORTED, E2eeTexts.error(E2eeBridgeException("passkey_unsupported", "no PRF")))
        assertEquals("Unlock encryption first", E2eeTexts.error(E2eeBridgeException("locked", "unlock encryption first")))
    }

    @Test
    fun pgpImportOutcomesKeepOnlyStoredKeys() {
        val ok = E2eePgpImport.of(Json.parseToJsonElement("""{"issue":"ok","fingerprint":"abcd","algorithm":"ed25519"}"""))!!
        assertTrue(ok.stored)
        val pub = E2eePgpImport.of(Json.parseToJsonElement("""{"issue":"public_only"}"""))!!
        assertFalse(pub.stored)
        assertEquals("That's a public key. Interop mode needs your private key.", pub.text)
        assertTrue(E2eePgpImport.of(Json.parseToJsonElement("""{"issue":"no_passphrase","fingerprint":"ab"}"""))!!.stored)
    }

    @Test
    fun previousPgpKeysAreFingerprintsOnly() {
        assertEquals(listOf("aa11", "bb22"), E2eeOldPgpKeys.fingerprints(Json.parseToJsonElement("""["aa11","bb22",""]""")))
        // Anything but the `string[]` contract (e.g. key material objects) never reaches the screen.
        assertEquals(emptyList<String>(), E2eeOldPgpKeys.fingerprints(Json.parseToJsonElement("""[{"armored":"-----BEGIN PGP PRIVATE KEY BLOCK-----"}]""")))
        assertEquals(emptyList<String>(), E2eeOldPgpKeys.fingerprints(Json.parseToJsonElement("""null""")))
    }

    @Test
    fun orgSignInPendingActionIsAccountBoundAndShortLived() {
        val p = E2eeOrgSignIn.Pending("u1", "https://ircfiber.com", 1_000)
        assertTrue(E2eeOrgSignIn.valid(p, "https://ircfiber.com", "u1", 1_000 + E2eeOrgSignIn.MAX_AGE_MS))
        assertFalse(E2eeOrgSignIn.valid(p, "https://ircfiber.com", "u2", 2_000))
        assertFalse(E2eeOrgSignIn.valid(p, "https://scratch.test", "u1", 2_000))
        assertFalse(E2eeOrgSignIn.valid(p, "https://ircfiber.com", "u1", 1_001 + E2eeOrgSignIn.MAX_AGE_MS))
    }
}
