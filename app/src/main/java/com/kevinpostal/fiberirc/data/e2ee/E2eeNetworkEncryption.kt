package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.irc.IrcEncryptedSendException
import com.kevinpostal.fiberirc.data.irc.IrcEncryption
import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What one lock decision depends on, as the encryption service knows it natively. */
data class E2eeLockInputs(
    /** No decision is possible yet but one is coming: the service is starting, or its running runtime has no
     *  policy decision yet. Not-off sends are held instead of failing. */
    val waiting: Boolean,
    /** An authenticated `/api/me` answered with the account's `bufferPrefs` object. */
    val prefsKnown: Boolean,
    /** The live runtime's current status carries a policy decision (its `currentPolicy()`); never a stale snapshot
     *  of an earlier generation. */
    val policyAvailable: Boolean,
    /** The target's `e2ee` buffer pref: true/false when set, null when unset. */
    val pref: Boolean?,
    /** A lock change of this target is still settling in the runtime. */
    val lockPending: Boolean,
    val channel: Boolean,
    /** Device-local "encrypt DMs by default". */
    val localDmDefault: Boolean,
    /** The live status's effective encrypt-DMs-by-default rule. */
    val policyDmDefault: Boolean,
)

/**
 * The native lock rule, the runtime's `lockState` (`mobile.ts` host): only a positively known off target is
 * [IrcLockDecision.OFF], and nothing is off without both the account's server buffer prefs and the live runtime's
 * policy decision — channels and explicitly-off buffers included. Then:
 * - a buffer locked on, or with a lock change still settling, encrypts;
 * - a channel is off when its prefs leave it unlocked;
 * - a conversation is off when explicitly turned off, or when the policy and the local defaults both leave DMs
 *   unencrypted.
 * Anything not off is [IrcLockDecision.PENDING] (held, never plaintext) while a decision is coming
 * ([E2eeLockInputs.waiting]), else [IrcLockDecision.ENCRYPT] (encrypt or fail).
 */
object E2eeLockRule {
    fun decide(i: E2eeLockInputs): IrcLockDecision {
        val encrypt = if (i.waiting) IrcLockDecision.PENDING else IrcLockDecision.ENCRYPT
        if (!i.prefsKnown || !i.policyAvailable) return encrypt
        if (i.pref == true || i.lockPending) return encrypt
        if (i.channel || i.pref == false) return IrcLockDecision.OFF
        if (i.localDmDefault || i.policyDmDefault) return encrypt
        return IrcLockDecision.OFF
    }
}

/**
 * One bouncer network's [IrcEncryption] over the session's [E2eeService] (network [networkId]). The lock decision
 * ([E2eeLockRule]) comes from the service's native view (server buffer prefs, device-local defaults) and the live
 * runtime's latest status — counted only once its context is current ([E2eeService.contextSettled]: committed after
 * bootstrap and after every foreground/reconnect/rebuild). Without a running runtime nothing is off; a runtime
 * that can't run makes sends fail.
 */
class E2eeNetworkEncryption(private val service: E2eeService, private val networkId: String) : IrcEncryption {
    override fun lockDecision(target: String): IrcLockDecision {
        val state = service.state.value
        val prefsKnown = service.bufferPrefsKnown
        val status = (state as? E2eeServiceState.Running)?.status
        val policy = status?.policyAvailable == true && service.contextSettled
        return E2eeLockRule.decide(
            E2eeLockInputs(
                waiting = state is E2eeServiceState.Idle || state is E2eeServiceState.Starting || (status != null && !policy),
                prefsKnown = prefsKnown,
                policyAvailable = policy,
                pref = if (prefsKnown) service.bufferPref(networkId, target, "e2ee")?.takeUnless { it.isString }?.booleanOrNull else null,
                lockPending = status?.lockPending("$networkId:${target.lowercase()}") == true,
                channel = target.startsWith('#') || target.startsWith('&'),
                localDmDefault = service.localDefault("dmDefault"),
                policyDmDefault = status?.dmDefault == true,
            ),
        )
    }

    /**
     * The runtime's `SendOutcome`. A malformed outcome is an error, never assumed encrypted; what actually reached
     * the wire is settled natively ([com.kevinpostal.fiberirc.data.irc.IrcClient.sendWire] refuses plaintext for any
     * target not positively off here, whatever the runtime decided).
     */
    override suspend fun send(target: String, command: String, text: String, tags: Map<String, String>, label: String): Boolean {
        val outcome = try {
            service.sendBufferLine(networkId, target, text, label, tags, command)
        } catch (e: E2eeBridgeException) {
            throw IrcEncryptedSendException(e.code, failureMessage(e), uncertain = e.code == "uncertain_delivery")
        }
        return ((outcome as? JsonObject)?.get("encrypted") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IrcEncryptedSendException("bad_reply", "Unexpected answer from encryption")
    }

    override suspend fun decrypt(rows: List<JsonObject>): List<JsonObject> {
        val result = service.decryptBatch(networkId, rows)
        return (result as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun failureMessage(e: E2eeBridgeException): String = when (e.code) {
        "locked" -> "Unlock encryption to send"
        "nopeer" -> "No one here can read encrypted messages yet"
        "not_connected" -> "Not connected — nothing was sent"
        "uncertain_delivery" -> "Connection dropped while sending — not resent"
        "plaintext_refused" -> "Encryption settings changed while sending — nothing was sent"
        "send_mapping_unavailable", "persist_failed", "storage_failed", "recovery_required" ->
            "Couldn't store the message securely — nothing was sent"
        "unavailable", "runtime_gone", "not_started", "stale_generation", "shutdown", "transport_unavailable" ->
            ((service.state.value as? E2eeServiceState.Unavailable)?.message ?: "Encryption isn't running") +
                " — nothing was sent"
        else -> e.message?.takeIf { it.isNotEmpty() } ?: "Encryption failed — nothing was sent"
    }
}
