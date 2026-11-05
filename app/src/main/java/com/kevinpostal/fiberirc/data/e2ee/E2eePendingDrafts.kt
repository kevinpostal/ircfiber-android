package com.kevinpostal.fiberirc.data.e2ee

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A message an external entry point (notification Reply, share sheet) could not send as plaintext: it waits,
 * sealed, until the main app shows it in its buffer for an explicit Send. [replyTo] is the msgid the message
 * answers (sent as the `+draft/reply` tag); [createdAt] is epoch milliseconds.
 */
@Serializable
data class E2eePendingDraft(
    val id: String,
    val userId: String,
    val apiOrigin: String,
    val networkId: String,
    val target: String,
    val body: String,
    val replyTo: String? = null,
    val createdAt: Long,
)

/**
 * Sealed pending drafts under `files/e2ee/drafts/<account scope>/<id>` (backup-excluded with the encryption
 * directory). Each file is AES-256-GCM under a dedicated Android Keystore key (not any account store's key), with
 * the account scope and draft id as AAD, so a file moved to another account or name fails to open. A draft of
 * another account or origin is unreachable: [load]/[list] only look in the asking account's directory.
 *
 * A draft is deleted only by [consume] (the user's Send succeeded) or [discard] (the user discarded it), or with
 * the account's local encryption state ([removeAccount]). All calls do blocking file/Keystore I/O.
 */
object E2eePendingDrafts {
    /** Seals and commits a new draft (random UUID id) before returning it. */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun create(
        context: Context,
        apiOrigin: String,
        userId: String,
        networkId: String,
        target: String,
        body: String,
        replyTo: String?,
    ): E2eePendingDraft = vault(context).create(apiOrigin, userId, networkId, target, body, replyTo)

    /** The draft [id] of this account; null when absent, another account's, or unreadable. */
    fun load(context: Context, id: String, apiOrigin: String, userId: String): E2eePendingDraft? =
        vault(context).load(id, apiOrigin, userId)

    /** This account's readable drafts, oldest first. */
    fun list(context: Context, apiOrigin: String, userId: String): List<E2eePendingDraft> =
        vault(context).list(apiOrigin, userId)

    /** The user's Send of draft [id] succeeded: delete it. */
    @Throws(IOException::class)
    fun consume(context: Context, id: String, apiOrigin: String, userId: String) = vault(context).delete(id, apiOrigin, userId)

    /** The user discarded draft [id]: delete it. */
    @Throws(IOException::class)
    fun discard(context: Context, id: String, apiOrigin: String, userId: String) = vault(context).delete(id, apiOrigin, userId)

    /** The account's local encryption state is being removed (keys removal, account deletion): its drafts go too. */
    @Throws(IOException::class)
    fun removeAccount(context: Context, apiOrigin: String, userId: String) = vault(context).removeAccount(apiOrigin, userId)

    private fun vault(context: Context) =
        E2eeDraftVault(File(context.applicationContext.filesDir, E2EE_DRAFTS_DIR), KeystoreDraftSealer)
}

/** AES-GCM sealing of draft files: `iv ‖ ciphertext+tag`. */
internal interface DraftSealer {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/** The drafts' own Keystore key, created on first use; usable after first unlock (background Reply). */
internal object KeystoreDraftSealer : DraftSealer {
    private const val ALIAS = "fiberirc.e2ee.drafts"
    private const val KEYSTORE = "AndroidKeyStore"

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = sealGcm(key(create = true)!!, plain, aad)

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray =
        openGcm(key(create = false) ?: throw GeneralSecurityException("no draft key"), sealed, aad)

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/** AES-GCM with a provider-chosen random IV. */
internal fun sealGcm(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD(aad)
    val ct = cipher.doFinal(plain)
    val iv = cipher.iv
    if (iv.size != GCM_IV_BYTES) throw GeneralSecurityException("unexpected IV size")
    return iv + ct
}

internal fun openGcm(key: SecretKey, sealed: ByteArray, aad: ByteArray): ByteArray {
    if (sealed.size < GCM_IV_BYTES + GCM_TAG_BITS / 8) throw GeneralSecurityException("sealed draft too short")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES))
    cipher.updateAAD(aad)
    return cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES)
}

/** The draft files of every account under [root], sealed by [sealer]. */
internal class E2eeDraftVault(
    private val root: File,
    private val sealer: DraftSealer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun create(
        apiOrigin: String,
        userId: String,
        networkId: String,
        target: String,
        body: String,
        replyTo: String?,
    ): E2eePendingDraft {
        require(networkId.isNotEmpty() && target.isNotEmpty()) { "draft needs a network and target" }
        require(body.isNotBlank()) { "draft needs a message" }
        val scope = e2eeAccountScope(apiOrigin, userId)
        val draft = E2eePendingDraft(
            id = UUID.randomUUID().toString(),
            userId = userId,
            apiOrigin = apiOrigin,
            networkId = networkId,
            target = target,
            body = body,
            replyTo = replyTo?.takeIf { it.isNotEmpty() },
            createdAt = now(),
        )
        val sealed = sealer.seal(json.encodeToString(E2eePendingDraft.serializer(), draft).encodeToByteArray(), aad(scope, draft.id))
        synchronized(LOCK) {
            val dir = File(root, scope)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create the drafts directory")
            val file = File(dir, draft.id)
            val tmp = File(dir, "${draft.id}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not save the draft")
            }
        }
        return draft
    }

    fun load(id: String, apiOrigin: String, userId: String): E2eePendingDraft? {
        if (!isDraftId(id)) return null
        val scope = e2eeAccountScope(apiOrigin, userId)
        val sealed = synchronized(LOCK) {
            val file = File(File(root, scope), id)
            try {
                if (!file.isFile) return null
                file.readBytes()
            } catch (_: IOException) {
                return null
            }
        }
        val plain = try {
            sealer.open(sealed, aad(scope, id))
        } catch (_: GeneralSecurityException) {
            return null
        }
        val draft = try {
            json.decodeFromString(E2eePendingDraft.serializer(), plain.decodeToString())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // The AAD already binds scope and id; the fields must agree with them too.
        if (draft.id != id || draft.userId != userId || draft.apiOrigin != apiOrigin) return null
        return draft
    }

    fun list(apiOrigin: String, userId: String): List<E2eePendingDraft> {
        val dir = File(root, e2eeAccountScope(apiOrigin, userId))
        val ids = synchronized(LOCK) { dir.list()?.filter(::isDraftId).orEmpty() }
        return ids.mapNotNull { load(it, apiOrigin, userId) }.sortedBy { it.createdAt }
    }

    fun delete(id: String, apiOrigin: String, userId: String) {
        if (!isDraftId(id)) return
        synchronized(LOCK) {
            val file = File(File(root, e2eeAccountScope(apiOrigin, userId)), id)
            if (file.exists() && !file.delete()) throw IOException("could not delete the draft")
        }
    }

    fun removeAccount(apiOrigin: String, userId: String) {
        synchronized(LOCK) {
            val dir = File(root, e2eeAccountScope(apiOrigin, userId))
            if (dir.exists() && !dir.deleteRecursively()) throw IOException("could not delete the drafts")
        }
    }

    private fun aad(scope: String, id: String) = "ircfiber-draft/v1\u0000$scope\u0000$id".encodeToByteArray()

    companion object {
        private val LOCK = Any()
        private val json = Json
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** A lower-case UUID: the only name a draft file (or a route's id) may have. */
        fun isDraftId(id: String) = UUID_RE.matches(id)
    }
}
