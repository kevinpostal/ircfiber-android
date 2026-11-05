package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.e2ee.E2eeExternalPolicy.Reason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** External plaintext is allowed only when the fresh snapshot and fresh server state both say the target is off. */
class E2eeExternalPolicyTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun me(prefs: String = "{}", id: String = "u1") = json("""{"id":"$id","username":"tester","bufferPrefs":$prefs}""")
    private val policy = json("""{"suite":"hybrid","dmDefault":false,"requireUnlock":false}""")

    private fun snapshot(
        perTarget: Map<String, String> = mapOf("net1:#chan" to "off", "net1:alice" to "off"),
        dmDefault: Boolean = false,
        autoLockIncoming: Boolean = false,
        userId: String = "u1",
    ) = E2eePolicySnapshot(
        userId = userId,
        apiOrigin = "https://ircfiber.com",
        writtenAt = 0,
        dmDefault = dmDefault,
        autoLockIncoming = autoLockIncoming,
        perTarget = perTarget,
    )

    private fun reason(
        snapshot: E2eePolicySnapshot? = snapshot(),
        me: JsonObject? = me(),
        policy: JsonObject? = this.policy,
        target: String = "#chan",
    ) = E2eeExternalPolicy.blockReason(snapshot, me, policy, "net1", target)

    @Test
    fun positivelyOffChannelAndDmMayBePlaintext() {
        assertNull(reason())
        assertNull(reason(target = "#CHAN"))
        assertNull(reason(target = "alice"))
    }

    @Test
    fun missingFreshServerStateContinuesInApp() {
        assertEquals(Reason.NoAccount, reason(me = null))
        assertEquals(Reason.NoAccount, reason(me = json("""{"username":"tester","bufferPrefs":{}}""")))
        assertEquals(Reason.PolicyUnavailable, reason(policy = null))
        assertEquals(Reason.PolicyUnavailable, reason(policy = json("""{"dmDefault":"yes"}""")))
        assertEquals(Reason.PrefsUnavailable, reason(me = json("""{"id":"u1","username":"tester"}""")))
    }

    @Test
    fun missingOrOtherAccountSnapshotContinuesInApp() {
        assertEquals(Reason.NoSnapshot, reason(snapshot = null))
        assertEquals(Reason.SnapshotOtherAccount, reason(snapshot = snapshot(userId = "u2")))
    }

    @Test
    fun localOnPendingOrUnknownTargetContinuesInApp() {
        assertEquals(Reason.TargetOn, reason(snapshot = snapshot(perTarget = mapOf("net1:#chan" to "on"))))
        assertEquals(Reason.TargetPending, reason(snapshot = snapshot(perTarget = mapOf("net1:#chan" to "pending"))))
        assertEquals(Reason.TargetUnknown, reason(target = "#other"))
    }

    @Test
    fun serverLockWinsOverAnOffSnapshot() {
        assertEquals(Reason.ServerLocked, reason(me = me("""{"net1:#Chan":{"e2ee":true}}""")))
        assertEquals(Reason.PrefsUnavailable, reason(me = me("""{"net1:#chan":{"e2ee":"true"}}""")))
        assertNull(reason(me = me("""{"net1:#chan":{"e2ee":false,"e2eeMode":"pgp"}}""")))
    }

    @Test
    fun dmDefaultsAndIncomingAutoLockKeepDmsInApp() {
        val dmPolicy = json("""{"dmDefault":true}""")
        assertEquals(Reason.DmDefault, reason(policy = dmPolicy, target = "alice"))
        assertEquals(Reason.DmDefault, reason(snapshot = snapshot(dmDefault = true), target = "alice"))
        // An explicit server off for that DM is the user's choice over the default…
        assertNull(reason(policy = dmPolicy, me = me("""{"net1:alice":{"e2ee":false}}"""), target = "alice"))
        // …but an incoming encrypted DM may have re-locked it since.
        assertEquals(
            Reason.DmAutoLock,
            reason(snapshot = snapshot(autoLockIncoming = true), me = me("""{"net1:alice":{"e2ee":false}}"""), target = "alice"),
        )
        // Channels are unaffected by DM rules.
        assertNull(reason(policy = dmPolicy, snapshot = snapshot(dmDefault = true, autoLockIncoming = true)))
    }
}
