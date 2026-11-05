package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

// The encryption UI's view of the shared runtime's results (`mobile.ts` op results and the `{ui,ktAlarm,deviceId,
// policy}` status snapshot), parsed once here so the screens never read raw JSON. Pure: no Android types.

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

/** The device-local encryption defaults (`E2eeDefaults`, local pref `ircfiber:e2ee:defaults`), with the web's base
 *  values for anything unset. */
data class E2eeDefaults(
    /** Keep decrypted rows of inactive buffers in memory (never on disk). */
    val cacheDecrypted: Boolean = false,
    val dmDefault: Boolean = false,
    val autoLockIncoming: Boolean = true,
    val requireUnlock: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("cacheDecrypted", cacheDecrypted)
        put("dmDefault", dmDefault)
        put("autoLockIncoming", autoLockIncoming)
        put("requireUnlock", requireUnlock)
    }

    companion object {
        /** The stored JSON (null/malformed: the base values). */
        fun parse(raw: String?): E2eeDefaults {
            val obj = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return E2eeDefaults()
            val base = E2eeDefaults()
            return E2eeDefaults(
                cacheDecrypted = obj.bool("cacheDecrypted") ?: base.cacheDecrypted,
                dmDefault = obj.bool("dmDefault") ?: base.dmDefault,
                autoLockIncoming = obj.bool("autoLockIncoming") ?: base.autoLockIncoming,
                requireUnlock = obj.bool("requireUnlock") ?: base.requireUnlock,
            )
        }
    }
}

/** The account's effective encryption policy (`Policy`, `/mls/policy` over the runtime's defaults). */
data class E2eePolicy(
    val dmDefault: Boolean,
    val requireUnlock: Boolean,
    val backupMode: BackupMode,
    val archiveEnabled: Boolean,
    /** "" without an organization. */
    val orgDomain: String,
    /** The organization signs device certificates through its identity provider. */
    val orgIdp: Boolean,
) {
    enum class BackupMode { ENCOURAGED, REQUIRED, FORBIDDEN }

    companion object {
        fun of(json: JsonElement?): E2eePolicy? {
            val o = json as? JsonObject ?: return null
            return E2eePolicy(
                dmDefault = o.bool("dmDefault") == true,
                requireUnlock = o.bool("requireUnlock") == true,
                backupMode = when (o.str("backupMode")) {
                    "required" -> BackupMode.REQUIRED
                    "forbidden" -> BackupMode.FORBIDDEN
                    else -> BackupMode.ENCOURAGED
                },
                archiveEnabled = o.bool("archiveEnabled") == true,
                orgDomain = o.str("orgDomain").orEmpty(),
                orgIdp = o.bool("orgIdp") == true,
            )
        }
    }
}

/** A device-link transfer's progress (`ui.link`). */
data class E2eeLinkProgress(
    /** `receive` (the new device) or `send` (the device sharing its history). */
    val receiving: Boolean,
    val state: State,
    val done: Int,
    val total: Int,
    val peer: String?,
    val archivedRows: Int?,
    val omittedRows: Int?,
    val pgpConflicts: List<String>,
    val error: String?,
) {
    enum class State { WAITING, TRANSFERRING, COMPLETE, INCOMPLETE }

    /** The web's `linkStatus` sentence. */
    val text: String
        get() = when (state) {
            State.WAITING -> "Keep this screen open and online. Waiting for your other device…"
            State.TRANSFERRING -> (if (receiving) "Receiving history… " else "Sending history… ") +
                "$done/${if (total > 0) total.toString() else "…"}"
            State.COMPLETE -> buildString {
                append(if (receiving) "History imported: ${archivedRows ?: 0} messages" else "Device linked. History sent: ${archivedRows ?: 0} messages")
                if ((omittedRows ?: 0) > 0) append(". Older messages over the 32 MiB link limit stayed on this device: $omittedRows.")
                if (pgpConflicts.isNotEmpty()) append(". ").append(E2eeTexts.pgpConflicts(pgpConflicts.size))
            }
            State.INCOMPLETE -> "History transfer incomplete: ${error ?: "connection lost"}"
        }

    companion object {
        fun of(json: JsonElement?): E2eeLinkProgress? {
            val o = json as? JsonObject ?: return null
            val state = when (o.str("state")) {
                "waiting" -> State.WAITING
                "transferring" -> State.TRANSFERRING
                "complete" -> State.COMPLETE
                "incomplete" -> State.INCOMPLETE
                else -> return null
            }
            return E2eeLinkProgress(
                receiving = o.str("role") == "receive",
                state = state,
                done = o.int("done") ?: 0,
                total = o.int("total") ?: 0,
                peer = o.str("peer"),
                archivedRows = o.int("archivedRows"),
                omittedRows = o.int("omittedRows"),
                pgpConflicts = o.strings("pgpConflicts"),
                error = o.str("error"),
            )
        }
    }
}

/** `n` of the `m` intended readers have an encryption device (`ui.readers[key]`). */
data class E2eeReaders(val n: Int, val m: Int)

/** Device setup failed (`ui.bootstrapError`). */
data class E2eeBootstrapError(val message: String, val rateLimited: Boolean) {
    val text: String
        get() = if (rateLimited) "Identity was already reset in the last 24 hours — try again later." else message
}

/** The runtime's buffer key (`networkId:lower-case target`). */
fun e2eeBufferKey(networkId: String, target: String): String = "$networkId:${target.lowercase()}"

private val E2eeStatus.ui: JsonObject? get() = json["ui"] as? JsonObject

/** The effective policy; null while none was fetched (sends needing a decision are blocked). */
val E2eeStatus.policy: E2eePolicy? get() = E2eePolicy.of(json["policy"])

val E2eeStatus.bootstrapError: E2eeBootstrapError?
    get() {
        val o = ui?.get("bootstrapError") as? JsonObject ?: return null
        return E2eeBootstrapError(o.str("message").orEmpty(), o.bool("rateLimited") == true)
    }

val E2eeStatus.link: E2eeLinkProgress? get() = E2eeLinkProgress.of(ui?.get("link"))

/** Key-transparency alarm: the device KT named that this device doesn't know ("" when only the checkpoint failed). */
val E2eeStatus.ktUnknownDevice: String?
    get() = ((json["ktAlarm"] as? JsonObject)?.str("unknownDevice"))?.takeIf { it.isNotEmpty() }

fun E2eeStatus.readers(bufferKey: String): E2eeReaders? {
    val o = (ui?.get("readers") as? JsonObject)?.get(bufferKey) as? JsonObject ?: return null
    return E2eeReaders(o.int("n") ?: return null, o.int("m") ?: return null)
}

/** The organization whose compliance archiver is in this buffer's group; null when none. */
fun E2eeStatus.archivedBy(bufferKey: String): String? =
    ((ui?.get("archived") as? JsonObject)?.get(bufferKey) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** What the composer shows for one buffer's encryption. */
sealed interface ComposerLock {
    /** Plaintext: nothing here encrypts. */
    data object Off : ComposerLock

    /** Encrypted for everyone who can read it. */
    data object On : ComposerLock

    /** Encrypted, but only [readers].n of [readers].m intended readers have a device. */
    data class Partial(val readers: E2eeReaders?) : ComposerLock

    /** Encrypted conversation whose peer has no encryption device: sends fail. */
    data object NoPeer : ComposerLock

    /** Unlock required first: sends and decryption wait. */
    data object Locked : ComposerLock

    /** A lock change or the policy decision is still settling (sends are held, never plaintext). */
    data class Pending(val cancellable: Boolean) : ComposerLock

    /** Encryption can't run; sends to this target fail. */
    data class Unavailable(val message: String, val webViewUpdate: Boolean) : ComposerLock

    val encrypting: Boolean get() = this !is Off
}

object E2eeComposerLock {
    /**
     * The composer's state from the message path's own [decision] (the lock rule that decides sends) refined by the
     * service's live status: only a positively off target shows [ComposerLock.Off].
     */
    fun of(decision: IrcLockDecision, state: E2eeServiceState?, bufferKey: String): ComposerLock {
        if (decision == IrcLockDecision.OFF) return ComposerLock.Off
        return when (state) {
            is E2eeServiceState.Running -> {
                val s = state.status
                when {
                    s.lockPending(bufferKey) -> ComposerLock.Pending(cancellable = true)
                    s.locked -> ComposerLock.Locked
                    decision == IrcLockDecision.PENDING -> ComposerLock.Pending(cancellable = false)
                    s.lockOverride(bufferKey) == "nopeer" -> ComposerLock.NoPeer
                    s.lockOverride(bufferKey) == "partial" -> ComposerLock.Partial(s.readers(bufferKey))
                    else -> ComposerLock.On
                }
            }
            is E2eeServiceState.Unavailable -> ComposerLock.Unavailable(state.message, state.webViewUpdate)
            is E2eeServiceState.RecoveryRequired -> ComposerLock.Unavailable("Encryption storage needs recovery — see Settings → Encryption", false)
            E2eeServiceState.Stopped -> ComposerLock.Unavailable("Encryption isn't running", false)
            null -> ComposerLock.Unavailable("Encryption isn't available on this network", false)
            E2eeServiceState.Idle, E2eeServiceState.Starting -> ComposerLock.Pending(cancellable = false)
        }
    }

    /** The one-line label under the composer; null for [ComposerLock.Off]. */
    fun label(lock: ComposerLock, peer: String?): String? = when (lock) {
        ComposerLock.Off -> null
        ComposerLock.On -> "End-to-end encrypted"
        is ComposerLock.Partial -> lock.readers?.let { "Encrypted · ${it.n} of ${it.m} members can read" }
            ?: "Encrypted · not everyone here can read"
        ComposerLock.NoPeer -> "${peer ?: "They"} can't receive encrypted messages"
        ComposerLock.Locked -> "Encrypted — unlock to read & send"
        is ComposerLock.Pending -> if (lock.cancellable) "Setting up encryption…" else "Waiting for encryption…"
        is ComposerLock.Unavailable -> if (lock.webViewUpdate) "Update Android System WebView to use encryption" else lock.message
    }
}

/** A channel's encrypted audience (`Audience`): everyone in it, everyone except some accounts, or only some. */
data class E2eeAudience(val mode: Mode, val accounts: List<String>) {
    enum class Mode(val wire: String, val title: String) {
        EVERYONE("everyone", "Everyone in group"),
        EXCEPT("except", "Everyone except…"),
        ONLY("only", "Only…"),
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode.wire)
        put("accounts", buildJsonArray { accounts.forEach { add(JsonPrimitive(it.lowercase())) } })
    }

    /** The composer chip (`audienceChip.ts`); null for everyone. */
    val chip: String?
        get() {
            if (mode == Mode.EVERYONE) return null
            val sorted = accounts.sorted()
            val counted = sorted.isEmpty() || sorted.size >= 3
            val names = if (counted) "${sorted.size}" else sorted.joinToString(", ")
            return if (mode == Mode.EXCEPT) "except $names" else "only $names"
        }

    companion object {
        val EVERYONE = E2eeAudience(Mode.EVERYONE, emptyList())

        fun of(json: JsonElement?): E2eeAudience {
            val o = json as? JsonObject ?: return EVERYONE
            val mode = Mode.entries.firstOrNull { it.wire == o.str("mode") } ?: return EVERYONE
            return E2eeAudience(mode, o.strings("accounts").map { it.lowercase() })
        }

        /** Who can read (`deriveReaders`): `except` removes, `only` intersects; the sender always reads. */
        fun readers(memberAccounts: Collection<String>, audience: E2eeAudience, self: String): List<String> {
            val members = memberAccounts.map { it.lowercase() }.filter { it.isNotEmpty() && it != "*" }
            val listed = audience.accounts.toSet()
            val readers = when (audience.mode) {
                Mode.EVERYONE -> members
                Mode.EXCEPT -> members.filter { it !in listed }
                Mode.ONLY -> members.filter { it in listed }
            }.toMutableSet()
            readers.add(self.lowercase())
            return readers.sorted()
        }
    }
}

/** A device of the signed-in account (`listDevices`). */
data class E2eeDevice(val deviceId: String, val credential: String, val ktSeq: Long, val service: Boolean) {
    /** The credential is an organization X.509 certificate rather than a bare key. */
    val orgCertificate: Boolean
        get() = runCatching { String(java.util.Base64.getDecoder().decode(credential)).contains("-----BEGIN CERTIFICATE") }
            .getOrDefault(false)

    companion object {
        fun list(json: JsonElement): List<E2eeDevice> = (json as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            E2eeDevice(
                deviceId = o.str("deviceId") ?: return@mapNotNull null,
                credential = o.str("credential").orEmpty(),
                ktSeq = o.long("ktSeq") ?: 0,
                service = o.bool("service") == true,
            )
        } ?: emptyList()
    }
}

/** The account's server backup (`backupStatus`). */
data class E2eeBackupStatus(val passkey: Boolean, val updatedAt: Long) {
    companion object {
        fun of(json: JsonElement): E2eeBackupStatus? {
            val o = json as? JsonObject ?: return null
            return E2eeBackupStatus(o.str("wrap") == "passkey", o.long("updatedAt") ?: 0)
        }
    }
}

/** A reviewed link code (`acceptLinkOffer {raw, preview:true}`). */
data class E2eeLinkReview(val account: String, val deviceId: String, val fingerprint: String) {
    companion object {
        fun of(json: JsonElement): E2eeLinkReview? {
            val o = json as? JsonObject ?: return null
            return E2eeLinkReview(o.str("account") ?: return null, o.str("deviceId") ?: return null, o.str("fingerprint") ?: return null)
        }
    }
}

/** This device's open link offer (`createLinkOffer {}`): [code] is the QR/paste text. */
data class E2eeLinkOffer(val code: String, val qrPng: ByteArray?, val fingerprint: String) {
    companion object {
        fun of(json: JsonElement): E2eeLinkOffer? {
            val o = json as? JsonObject ?: return null
            val png = o.str("qrDataUrl")?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            return E2eeLinkOffer(o.str("code") ?: return null, png, o.str("fingerprint").orEmpty())
        }
    }
}

/** `importOwnKey`'s outcome. Only [stored] outcomes kept the key. */
data class E2eePgpImport(val issue: String, val fingerprint: String?) {
    val stored: Boolean get() = issue in setOf("ok", "rsa_large", "no_passphrase")
    val error: Boolean get() = !stored

    val text: String
        get() = when (issue) {
            "ok" -> "Key imported"
            "rsa_large" -> "RSA key imported — adds ~441 bytes per reader to every message"
            "no_passphrase" -> "This key has no passphrase. Set one here — it protects the copy stored on this device."
            "public_only" -> "That's a public key. Interop mode needs your private key."
            "no_encryption_subkey" -> "This key can't encrypt (no encryption subkey)."
            "expired" -> "This key has expired."
            else -> "This key can't be used ($issue)."
        }

    companion object {
        fun of(json: JsonElement): E2eePgpImport? {
            val o = json as? JsonObject ?: return null
            return E2eePgpImport(o.str("issue") ?: return null, o.str("fingerprint"))
        }
    }
}

/** Your previous OpenPGP keys kept to decrypt older messages (`oldKeyInfo`): fingerprints only, never key material. */
object E2eeOldPgpKeys {
    /** `oldKeyInfo`'s `string[]` of fingerprints; anything else drops out. */
    fun fingerprints(json: JsonElement): List<String> = (json as? JsonArray)?.mapNotNull { e ->
        (e as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
    } ?: emptyList()
}

/** A pinned DM peer OpenPGP key (`peerKeyInfo`). */
data class E2eePeerKey(val fingerprint: String, val verified: Boolean) {
    companion object {
        fun of(json: JsonElement): E2eePeerKey? {
            val o = json as? JsonObject ?: return null
            return E2eePeerKey(o.str("fingerprint") ?: return null, o.bool("verified") == true)
        }
    }
}

/** `restoreBackup`'s result. */
data class E2eeRestoreResult(val archivedRows: Int, val pgpConflicts: List<String>) {
    companion object {
        fun of(json: JsonElement): E2eeRestoreResult {
            val o = json as? JsonObject
            return E2eeRestoreResult(o?.int("archivedRows") ?: 0, o?.strings("pgpConflicts") ?: emptyList())
        }
    }
}

/** The 52-character base32 recovery key (13 dash-separated groups of 4). */
object E2eeRecoveryKey {
    /** Whether [display] parses (`parseRecoveryKey`: case-insensitive, separators ignored, 52 base32 characters). */
    fun isWellFormed(display: String): Boolean = display.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }.length >= 52
}

/** The account's safety number in Settings (`SettingsEncryption.svelte` `renderSafety`): what a peer compares. */
object E2eeOwnSafety {
    /** `JSON.stringify({account, devices:[{deviceId, credential}]})`: the QR payload. */
    fun payload(account: String, devices: List<E2eeDevice>): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("account", account)
            put(
                "devices",
                buildJsonArray {
                    devices.forEach { d -> add(buildJsonObject { put("deviceId", d.deviceId); put("credential", d.credential) }) }
                },
            )
        },
    )

    /** 12 groups of 5 digits over SHA-256 of [payload]. */
    fun number(payload: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).map { it.toInt() and 0xff }
        return (0 until 12).joinToString(" ") { i ->
            val n = ((d[i * 2] shl 16) or (d[i * 2 + 1] shl 8) or d[(i * 2 + 24) % 32]) % 100_000
            n.toString().padStart(5, '0')
        }
    }
}

/** User-facing sentences for runtime errors (the web's wording where it has one). */
object E2eeTexts {
    const val PRF_UNSUPPORTED =
        "Passkey backup and passkey unlock need Android 9 or later and a passkey provider that supports the PRF " +
            "extension, which this device doesn't have. Use a recovery key, or link this device from another one."

    fun pgpConflicts(n: Int): String =
        "$n OpenPGP keys in the backup differ from this device's. Your existing keys and pins were kept; compare " +
            "fingerprints before trusting the backup's."

    fun error(e: Throwable): String {
        val b = e as? E2eeBridgeException ?: return e.message ?: "Something went wrong"
        val message = b.message.orEmpty()
        return when (b.code) {
            "http" -> when (message) {
                "reset_rate_limited" -> "Identity was already reset in the last 24 hours"
                "backup_forbidden" -> "Your organization doesn't allow encrypted backups"
                "org_auth_required" -> "Sign in with your organization first — the sign-in counts for 10 minutes"
                "device_not_found" -> "This device isn't registered for encryption yet"
                "spki_mismatch" -> "The certificate doesn't match this device's key"
                "org_unavailable", "stepca_unavailable" -> "Your organization's certificate service is unavailable — try again later"
                else -> if (b.status == 401) "Your sign-in has expired" else "ircfiber.com refused: $message"
            }
            "locked" -> if (message.isNotEmpty() && message != "unlock encryption first") message.replaceFirstChar(Char::uppercase) else "Unlock encryption first"
            "passkey_unsupported" -> PRF_UNSUPPORTED
            "passkey_cancelled" -> "The passkey request was cancelled — nothing changed"
            "passkey_no_credential" -> "No IRC Fiber passkey for this account is on this device or its passkey provider"
            "passkey_mismatch" -> "That passkey isn't the one this backup or unlock uses — choose the IRC Fiber passkey for this account"
            "passkey_not_associated" -> "This build of IRC Fiber isn't verified to use ircfiber.com passkeys. Use a recovery key, or link this device from another one."
            "passkey_interrupted" -> "The passkey request was interrupted — try again"
            "passkey_busy" -> "A passkey request is already open"
            "passkey_no_ui" -> "Open IRC Fiber to use your passkey"
            "unavailable", "shutdown", "stale_generation" -> "Encryption isn't running — try again in a moment"
            "network", "timeout" -> "Can't reach ircfiber.com — check your connection"
            "pref_save_failed" -> "Couldn't save the encryption setting"
            "recovery_required" -> "Encryption storage needs recovery — see Settings → Encryption"
            "nopeer" -> "They can't receive encrypted messages yet"
            else -> message.ifEmpty { "Encryption failed (${b.code})" }.replaceFirstChar(Char::uppercase)
        }
    }

    /** The user dismissed the passkey sheet: nothing changed and nothing needs reporting. */
    fun isPasskeyCancellation(e: Throwable): Boolean = e is E2eeBridgeException && e.code == "passkey_cancelled"

    /** Whether [e] is the bridge's [code] (e.g. `http` with server [message]). */
    fun isError(e: Throwable, code: String, message: String? = null): Boolean =
        e is E2eeBridgeException && e.code == code && (message == null || e.message == message)
}
