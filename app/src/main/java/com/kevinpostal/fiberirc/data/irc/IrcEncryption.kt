package com.kevinpostal.fiberirc.data.irc

import kotlinx.serialization.json.JsonObject

/**
 * How one network's [IrcClient] treats encryption. There is deliberately no default: production bouncer networks
 * get [Encrypted] from the session's encryption service, and only networks without a site network id (direct IRC)
 * and FakeTransport tests say [Plaintext] explicitly.
 */
sealed interface IrcEncryptionConfig {
    /** Encryption is unavailable here: every send is plaintext and ciphertext bodies show as received. */
    data object Plaintext : IrcEncryptionConfig

    /** Every PRIVMSG/NOTICE send and every encrypted body goes through [hooks]. */
    class Encrypted(val hooks: IrcEncryption) : IrcEncryptionConfig
}

/** The lock decision for one target, taken before any wire byte. */
enum class IrcLockDecision {
    /** Positively known off: today's plaintext path. */
    OFF,

    /** Encrypt or fail; never plaintext. */
    ENCRYPT,

    /** Not decidable yet (encryption bootstrap / preferences pending): hold the send, never plaintext meanwhile. */
    PENDING,
}

/** Why an encrypted send did not complete. [uncertain]: bytes may have reached the wire; never retried automatically. */
class IrcEncryptedSendException(val code: String, message: String, val uncertain: Boolean = false) : Exception(message)

/** One network's view of the session's encryption service (see `E2eeNetworkEncryption`). Main thread. */
interface IrcEncryption {
    /** The lock decision for [target] now. */
    fun lockDecision(target: String): IrcLockDecision

    /**
     * Encrypts and sends one logical line through the shared pipeline (its ordering/retry, the own-send mapping
     * committed before any wire byte, no plaintext fallback). [label] is the optimistic row's id; the ciphertext
     * reaches [IrcClient.sendWire] with it. Returns whether the line went out encrypted (the runtime may still
     * find the target positively off). Throws [IrcEncryptedSendException].
     */
    suspend fun send(target: String, command: String, text: String, tags: Map<String, String>, label: String): Boolean

    /** `decryptBatch`: normalized `IRCMessage` rows in arrival order → the runtime's logical rows. */
    suspend fun decrypt(rows: List<JsonObject>): List<JsonObject>
}

/** What [IrcClient.sendWire] did with a `transport.sendWire` request. */
enum class IrcWireResult {
    /** Every line was accepted by the connection. */
    SENT,

    /** Nothing was written (not connected, or the lines were refused before writing). */
    NOT_WRITTEN,

    /** Plaintext for a target whose lock isn't positively off here: refused, nothing written. */
    PLAINTEXT_REFUSED,

    /** Some lines may have been written: delivery is uncertain. */
    UNCERTAIN,
}

/** E2EE wire bodies (`site/frontend/src/lib/e2ee/wire.ts`): `!MLS1:`/`!PGP1:`, optionally `<id>:<n>/<total>:` chunked. */
object E2eeWire {
    private const val MLS_PREFIX = "!MLS1:"
    private const val PGP_PREFIX = "!PGP1:"
    private val CHUNK = Regex("^(!(?:MLS|PGP)1:)([0-9a-f]{4}):(\\d{1,2})/(\\d{1,2}):(.*)$")

    /** True when a message body is E2EE ciphertext (either prefix, any form). */
    fun isEncrypted(text: String?): Boolean = text != null && (text.startsWith(MLS_PREFIX) || text.startsWith(PGP_PREFIX))

    /** A chunked line's header; null for a single-line body (or plaintext). */
    data class Chunk(val prefix: String, val id: String, val n: Int, val total: Int, val slice: String)

    fun chunk(text: String): Chunk? {
        val m = CHUNK.matchEntire(text) ?: return null
        val n = m.groupValues[3].toInt()
        val total = m.groupValues[4].toInt()
        if (total < 1 || n < 1 || n > total) return null
        return Chunk(m.groupValues[1], m.groupValues[2], n, total, m.groupValues[5])
    }

    /** The complete logical ciphertext of a sent message's wire lines (prefix + slices in order); null if malformed. */
    fun assemble(lines: List<String>): String? {
        if (lines.isEmpty() || lines.any { !isEncrypted(it) }) return null
        val first = chunk(lines[0]) ?: return if (lines.size == 1) lines[0] else null
        val slices = arrayOfNulls<String>(first.total)
        if (lines.size != first.total) return null
        for (l in lines) {
            val c = chunk(l) ?: return null
            if (c.id != first.id || c.total != first.total || c.prefix != first.prefix || slices[c.n - 1] != null) return null
            slices[c.n - 1] = c.slice
        }
        return first.prefix + slices.joinToString("")
    }
}
