package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Typed helpers over the shared runtime's user-facing operations ([E2eeService.request]) for the encryption UI.
// Every call goes through the live generation; errors are [E2eeBridgeException]s ([E2eeTexts.error] words them).

private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

private fun JsonObject.textOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

suspend fun E2eeService.refreshStatus(): E2eeStatus = E2eeStatus(request(E2eeOp.Status) as? JsonObject ?: JsonObject(emptyMap()))

suspend fun E2eeService.setDefaults(defaults: E2eeDefaults): E2eeStatus =
    E2eeStatus(request(E2eeOp.SetDefaults, args { put("defaults", defaults.toJson()) }) as? JsonObject ?: JsonObject(emptyMap()))

suspend fun E2eeService.audience(networkId: String, channel: String): E2eeAudience =
    E2eeAudience.of(request(E2eeOp.GetAudience, args { put("networkId", networkId); put("channel", channel) }))

suspend fun E2eeService.setAudience(networkId: String, channel: String, audience: E2eeAudience) {
    request(E2eeOp.SetAudience, args { put("networkId", networkId); put("channel", channel); put("audience", audience.toJson()) })
}

suspend fun E2eeService.listDevices(): List<E2eeDevice> = E2eeDevice.list(request(E2eeOp.ListDevices))

suspend fun E2eeService.removeDevice(deviceId: String) {
    request(E2eeOp.RemoveDevice, args { put("deviceId", deviceId) })
}

suspend fun E2eeService.resetIdentity() {
    request(E2eeOp.ResetIdentity)
}

/** Explicit "Remove this device's keys" while staying signed in: revokes this device, deletes local keys and
 *  history, and continues with a fresh generation. */
suspend fun E2eeService.removeLocalKeys() {
    request(E2eeOp.RemoveLocalKeys)
}

suspend fun E2eeService.backupStatus(): E2eeBackupStatus? = E2eeBackupStatus.of(request(E2eeOp.BackupStatus))

/** Uploads a recovery-key backup and returns the key's display form, shown once. */
suspend fun E2eeService.backupWithRecoveryKey(): String =
    (request(E2eeOp.BackupWithRecoveryKey) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw E2eeBridgeException("failed", "no recovery key returned")

suspend fun E2eeService.backupWithPasskey() {
    request(E2eeOp.BackupWithPasskey)
}

suspend fun E2eeService.restoreWithRecoveryKey(display: String): E2eeRestoreResult =
    E2eeRestoreResult.of(request(E2eeOp.RestoreBackup, args { put("kind", "recovery"); put("display", display) }))

suspend fun E2eeService.restoreWithPasskey(): E2eeRestoreResult =
    E2eeRestoreResult.of(request(E2eeOp.RestoreBackup, args { put("kind", "passkey") }))

suspend fun E2eeService.deleteBackup() {
    request(E2eeOp.DeleteBackup)
}

/** Opens this (new) device's link offer: it accepts history until [cancelLinkOffer]. */
suspend fun E2eeService.createLinkOffer(): E2eeLinkOffer =
    E2eeLinkOffer.of(request(E2eeOp.CreateLinkOffer)) ?: throw E2eeBridgeException("failed", "no link code returned")

suspend fun E2eeService.cancelLinkOffer() {
    request(E2eeOp.CreateLinkOffer, args { put("cancel", true) })
}

/** Reviews a pasted/scanned link code without side effects. */
suspend fun E2eeService.reviewLinkOffer(raw: String): E2eeLinkReview =
    E2eeLinkReview.of(request(E2eeOp.AcceptLinkOffer, args { put("raw", raw.trim()); put("preview", true) }))
        ?: throw E2eeBridgeException("failed", "That is not an IRC Fiber link code")

/** Adds the reviewed device and sends it this device's history; returns after the new device's final ACK. */
suspend fun E2eeService.acceptLinkOffer(raw: String, fingerprint: String) {
    request(E2eeOp.AcceptLinkOffer, args { put("raw", raw.trim()); put("fingerprint", fingerprint) })
}

/** The `safetyNumber` op: 12 groups of 5 digits over two credential sets (order matters: by account). */
suspend fun E2eeService.safetyNumber(credsA: List<String>, credsB: List<String>): String =
    (request(
        E2eeOp.SafetyNumber,
        args {
            put("credsA", buildJsonArray { credsA.forEach { add(JsonPrimitive(it)) } })
            put("credsB", buildJsonArray { credsB.forEach { add(JsonPrimitive(it)) } })
        },
    ) as? JsonPrimitive)?.content ?: throw E2eeBridgeException("failed", "no safety number")

suspend fun E2eeService.importOwnKey(armored: String, passphrase: String): E2eePgpImport =
    E2eePgpImport.of(request(E2eeOp.ImportOwnKey, args { put("armored", armored); put("passphrase", passphrase) }))
        ?: throw E2eeBridgeException("failed", "not a valid OpenPGP key")

suspend fun E2eeService.ownKeyFingerprint(): String? = (request(E2eeOp.OwnKeyInfo) as? JsonObject)?.textOrNull("fingerprint")

/** Fingerprints of your previous OpenPGP keys, kept only to decrypt older messages. */
suspend fun E2eeService.oldKeyFingerprints(): List<String> = E2eeOldPgpKeys.fingerprints(request(E2eeOp.OldKeyInfo))

suspend fun E2eeService.setLocalPassphrase(passphrase: String) {
    request(E2eeOp.SetLocalPassphrase, args { put("passphrase", passphrase) })
}

suspend fun E2eeService.pinPeerKey(networkId: String, peer: String, armored: String): String =
    (request(E2eeOp.PinPeerKey, args { put("networkId", networkId); put("peer", peer); put("armored", armored) }) as? JsonObject)
        ?.textOrNull("fingerprint") ?: throw E2eeBridgeException("failed", "not a valid OpenPGP key")

suspend fun E2eeService.peerKey(networkId: String, peer: String): E2eePeerKey? =
    E2eePeerKey.of(request(E2eeOp.PeerKeyInfo, args { put("networkId", networkId); put("peer", peer) }))

suspend fun E2eeService.markPeerVerified(networkId: String, peer: String) {
    request(E2eeOp.MarkPeerVerified, args { put("networkId", networkId); put("peer", peer) })
}

suspend fun E2eeService.installOrgCertificate() {
    request(E2eeOp.InstallOrgCertificate)
}

/** Recovery unlock: the runtime proves [display] by opening the account's recovery-wrapped backup. */
suspend fun E2eeService.unlockWithRecoveryKey(display: String): E2eeStatus =
    E2eeStatus(request(E2eeOp.Unlock, args { put("kind", "recovery"); put("display", display) }) as? JsonObject ?: JsonObject(emptyMap()))

/** Passkey unlock with the enrolled PRF credential (phase-7 native passkeys; `passkey_unsupported` without them). */
suspend fun E2eeService.unlockWithPasskey(credentialId: String?): E2eeStatus =
    E2eeStatus(
        request(E2eeOp.Unlock, args { put("kind", "passkey"); if (credentialId != null) put("credentialId", credentialId) }) as? JsonObject
            ?: JsonObject(emptyMap()),
    )

/** A peer account's KT directory credentials (`/api/kt/lookup?account=`) for its safety number. */
data class E2eePeerDirectory(val devices: List<E2eeDevice>) {
    val credentials: List<String> get() = devices.map { it.credential }
    val maxSeq: Long get() = devices.maxOfOrNull { it.ktSeq } ?: 0
    val orgCertificate: Boolean get() = devices.any { it.orgCertificate }
}

suspend fun SiteApi.ktLookup(account: String): Result<E2eePeerDirectory> =
    getJson("api/kt/lookup", mapOf("account" to account)).map { json ->
        E2eePeerDirectory(E2eeDevice.list((json as? JsonObject)?.get("devices") as? JsonArray ?: JsonArray(emptyList())))
    }
