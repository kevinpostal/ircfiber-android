package com.kevinpostal.fiberirc.data.e2ee

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Directory under `filesDir` holding every account store (and, from phase 6, sealed pending drafts under
 *  [E2EE_DRAFTS_DIR]); excluded from cloud backup and device transfer in both backup rule sets. */
const val E2EE_FILES_DIR = "e2ee"

/** Encrypted pending-draft files (phase 6), inside [E2EE_FILES_DIR] so the same backup exclusion covers them. */
const val E2EE_DRAFTS_DIR = "$E2EE_FILES_DIR/drafts"

/** Oldest-first archive prune threshold per buffer (`ARCHIVE_MAX_ROWS_PER_BUFFER` in the site's `storage.ts`). */
const val ARCHIVE_MAX_ROWS_PER_BUFFER = 50_000

/**
 * The secure account scope: lower-case hex SHA-256 of `apiOrigin NUL userId` (immutable user id, never the
 * username), naming the store directory and its Keystore key.
 */
fun e2eeAccountScope(apiOrigin: String, userId: String): String {
    require(apiOrigin.isNotEmpty() && userId.isNotEmpty()) { "account scope needs origin and user id" }
    return sha256Hex("$apiOrigin\u0000$userId")
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/**
 * The encrypted own-send record's meta key (`sendMappingKey` in the site's `mobile.ts`): `sent:` + SHA-256 hex of
 * `networkId NUL lowercased-target NUL prefix+assembledBase64` (chunk headers removed). The message pipeline
 * computes it from a reassembled self echo to recover its own plaintext.
 */
fun e2eeSendMappingKey(networkId: String, target: String, ciphertext: String): String =
    "sent:" + sha256Hex("$networkId\u0000${target.lowercase()}\u0000$ciphertext")

/**
 * The store exists but can't be read as committed: its Keystore key is gone, a record or the key check fails
 * authentication, SQLite reports corruption, or the schema can't be migrated. Never answered by regenerating
 * keys; only an explicit user choice ([E2eeStore.destroy]) discards it.
 */
class E2eeRecoveryRequired(detail: String, cause: Throwable? = null) : Exception(detail, cause)

/** A storage request the store refuses as malformed (bad key, bad row); nothing was written. */
class E2eeStoreRejected(detail: String) : Exception(detail)

/** One archive row as the runtime exports/imports it (`ArchiveExportRow`): `{msgid,row,bufferKey,t}`. */
data class E2eeArchiveRow(val msgid: String, val row: JsonObject, val bufferKey: String, val t: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("msgid", msgid)
        put("row", row)
        put("bufferKey", bufferKey)
        put("t", t)
    }
}

/** `importRecovery`'s answer (`RecoveryImportResult`). */
data class E2eeRecoveryResult(val archivedRows: Int, val pgpConflicts: List<String>)

/**
 * One signed-in account's encrypted local store (the native side of the runtime's `storage.*` host calls and the
 * device-local encryption preferences), in `filesDir/e2ee/<scope>/store.db`.
 *
 * SQLite tables `meta(key,sealed)`, `mls(slot,sealed)`, `archive(msgid,bufferKey,t,sealed)` (indexed by buffer)
 * and `local(key,sealed)`; every value is AES-256-GCM sealed (`iv ‖ ciphertext+tag`, fresh random 96-bit IV) with
 * AAD `ircfiber-e2ee/v1 NUL scope NUL table NUL key` (archive: `msgid NUL bufferKey`), so a record can't be
 * swapped into another key, table or account. The record key is a random 256-bit data key kept only in the
 * `header` table wrapped by this account's non-exportable Android Keystore AES-GCM key (envelope encryption: one
 * Keystore operation per open instead of one per record); a sealed check value proves the pair on open.
 *
 * All operations run on this store's one storage thread and return only after the SQLite commit (`synchronous=FULL`
 * in WAL mode); failures throw. Missing store → a new one; present store whose key, check or records fail →
 * [E2eeRecoveryRequired], never a silent reset. Nothing lives in SharedPreferences.
 */
class E2eeStore private constructor(
    private val app: Context,
    val scope: String,
) {
    // One storage thread at a time (max 1), which exits when idle so a closed account's store leaks no thread.
    private val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "e2ee-store").apply { isDaemon = true }
    }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val dir = accountDir(app, scope)
    private val dbFile = File(dir, DB_NAME)
    private val keystoreAlias = KEY_ALIAS_PREFIX + scope
    private val random = SecureRandom()

    private var helper: Helper? = null
    private var db: SQLiteDatabase? = null
    private var dataKey: SecretKey? = null
    private var unrecoverable: E2eeRecoveryRequired? = null
    private var closed = false

    // ── lifecycle ──────────────────────────────────────────────────────────

    /** Opens or creates the store. Throws [E2eeRecoveryRequired] for an existing unreadable store. */
    suspend fun open(): Unit = io { openLocked() }

    /** Closes the database; later operations fail. Idempotent. */
    suspend fun close(): Unit = io { closeLocked() }

    /**
     * Deletes this account's database files and Keystore key (explicit local-key removal or sign-out "remove",
     * account deletion, or the user's choice to discard an unrecoverable store). Callers stop the runtime's writes
     * first. The store is closed afterwards.
     */
    suspend fun destroy(): Unit = io {
        closeLocked()
        deleteFiles(dir)
        deleteKeystoreKey(keystoreAlias)
    }

    /** Whether the store holds any identity, key or archive record (sign-out keep/remove choice). */
    suspend fun hasData(): Boolean = io {
        val d = requireDb()
        listOf("meta", "mls", "archive").any { table ->
            d.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
        }
    }

    // ── meta ───────────────────────────────────────────────────────────────

    /** The JSON value at [key] (byte arrays as the runtime's `{"$u8": base64}`), or null when absent. */
    suspend fun getMeta(key: String): JsonElement? = io { readMeta(requireDb(), checkKey(key)) }

    suspend fun setMeta(key: String, value: JsonElement): Unit = io {
        val d = requireDb()
        checkKey(key)
        if (value is JsonNull) {
            d.delete("meta", "key = ?", arrayOf(key))
        } else {
            replace(d, "meta", "key", key, seal(value.toString().encodeToByteArray(), aad("meta", key)))
        }
    }

    suspend fun deleteMeta(key: String): Unit = io { requireDb().delete("meta", "key = ?", arrayOf(checkKey(key))) }

    /** Entries whose key starts with [prefix], in key order. */
    suspend fun listMeta(prefix: String): List<Pair<String, JsonElement>> = io {
        val d = requireDb()
        prefixedKeys(d, "meta", "key", prefix).map { key -> key to readMeta(d, key)!! }
    }

    // ── MLS state ──────────────────────────────────────────────────────────

    /** The MLS state blob (standard base64), or null on a fresh device. */
    suspend fun loadMlsState(): String? = io {
        val sealed = d1Blob(requireDb(), "SELECT sealed FROM mls WHERE slot = ?", MLS_SLOT) ?: return@io null
        Base64.encodeToString(unseal(sealed, aad("mls", MLS_SLOT), "MLS state"), Base64.NO_WRAP)
    }

    suspend fun saveMlsState(stateB64: String): Unit = io {
        val bytes = decodeBase64(stateB64) ?: throw E2eeStoreRejected("MLS state is not base64")
        if (bytes.isEmpty()) throw E2eeStoreRejected("empty MLS state")
        replace(requireDb(), "mls", "slot", MLS_SLOT, seal(bytes, aad("mls", MLS_SLOT)))
    }

    // ── archive ────────────────────────────────────────────────────────────

    /** One archived row `{text,e2e}`; null when missing or unreadable (cached plaintext, like the web). */
    suspend fun archiveGet(msgid: String): JsonObject? = io {
        val d = requireDb()
        d.rawQuery("SELECT bufferKey, sealed FROM archive WHERE msgid = ?", arrayOf(checkKey(msgid))).use { c ->
            if (!c.moveToFirst()) return@io null
            openArchiveRow(msgid, c.getString(0), c.getBlob(1))
        }
    }

    suspend fun archivePut(msgid: String, networkId: String, buffer: String, row: JsonObject): Unit = io {
        val d = requireDb()
        checkArchiveRow(row)
        writeArchive(d, E2eeArchiveRow(checkKey(msgid), row, bufferKey(networkId, buffer), System.currentTimeMillis()))
    }

    /** Every readable archive row (unreadable rows skipped, like the web's export). */
    suspend fun archiveExportAll(): List<E2eeArchiveRow> = io {
        val out = ArrayList<E2eeArchiveRow>()
        var skipped = 0
        requireDb().rawQuery("SELECT msgid, bufferKey, t, sealed FROM archive", null).use { c ->
            while (c.moveToNext()) {
                val row = openArchiveRow(c.getString(0), c.getString(1), c.getBlob(3))
                if (row == null) skipped++ else out.add(E2eeArchiveRow(c.getString(0), row, c.getString(1), c.getLong(2)))
            }
        }
        if (skipped > 0) Log.w(TAG, "archive export skipped $skipped unreadable rows")
        out
    }

    /** Bulk import in one transaction (all rows validated first). */
    suspend fun archiveImport(rows: List<E2eeArchiveRow>): Unit = io {
        rows.forEach { checkExportRow(it) }
        transaction(requireDb()) { d -> rows.forEach { writeArchive(d, it) } }
    }

    /** Oldest-first prune of one buffer's rows beyond [keep]. */
    suspend fun archivePrune(networkId: String, buffer: String, keep: Int = ARCHIVE_MAX_ROWS_PER_BUFFER): Unit = io {
        if (keep < 0) throw E2eeStoreRejected("keep must be non-negative")
        val key = bufferKey(networkId, buffer)
        transaction(requireDb()) { d ->
            val count = d.rawQuery("SELECT COUNT(*) FROM archive WHERE bufferKey = ?", arrayOf(key)).use {
                it.moveToFirst()
                it.getLong(0)
            }
            val excess = count - keep
            if (excess > 0) {
                d.execSQL(
                    "DELETE FROM archive WHERE msgid IN " +
                        "(SELECT msgid FROM archive WHERE bufferKey = ? ORDER BY t ASC, msgid ASC LIMIT ?)",
                    arrayOf<Any>(key, excess),
                )
            }
        }
    }

    // ── recovery import ────────────────────────────────────────────────────

    /**
     * Imports a backup/link payload `{archive, pgp:{ownKey,oldKeys,peers}, linkTransferId?, linkCompletion?}` in one
     * transaction across archive and meta, after validating all of it (`importRecovery` in `storage.ts`): an
     * existing own key is kept (the payload's becomes decrypt-only), distinct old keys are added, existing peer
     * pins are never overwritten (fingerprint mismatches are reported), and a link transfer's `linkRx:<id>:*`
     * staging is replaced by its `linkDone:<id>` marker. Idempotent by msgid/fingerprint.
     */
    suspend fun importRecovery(payload: JsonObject): E2eeRecoveryResult = io {
        val parsed = parseRecovery(payload)
        val conflicts = ArrayList<String>()
        transaction(requireDb()) { d ->
            parsed.archive.forEach { writeArchive(d, it) }

            var own = readMeta(d, PGP_OWN_KEY_META) as? JsonObject
            val old = ((readMeta(d, PGP_OLD_KEYS_META) as? JsonArray) ?: JsonArray(emptyList())).toMutableList()
            val candidates = listOfNotNull(parsed.ownKey) + parsed.oldKeys
            val ownPayload = parsed.ownKey
            if (own == null && ownPayload != null) {
                own = ownPayload
                writeMeta(d, PGP_OWN_KEY_META, ownPayload)
            } else if (own != null && ownPayload != null && fingerprint(ownPayload) != fingerprint(own)) {
                conflicts.add(PGP_OWN_KEY_META)
            }
            var oldChanged = false
            for (k in candidates) {
                val fp = fingerprint(k)
                if (fp == own?.let { fingerprint(it) } || old.any { o -> (o as? JsonObject)?.let { fingerprint(it) } == fp }) continue
                old.add(k)
                oldChanged = true
            }
            if (oldChanged) writeMeta(d, PGP_OLD_KEYS_META, JsonArray(old))

            for (peer in parsed.peers) {
                val existing = readMeta(d, peer.key) as? JsonObject
                if (existing == null) {
                    writeMeta(d, peer.key, peer.pin)
                } else if (fingerprint(existing) != fingerprint(peer.pin)) {
                    conflicts.add(peer.key)
                }
            }

            parsed.linkTransferId?.let { id ->
                for (key in prefixedKeys(d, "meta", "key", "$LINK_RX_META_PREFIX$id:")) {
                    d.delete("meta", "key = ?", arrayOf(key))
                }
                val completion = parsed.linkCompletion ?: buildJsonObject { put("completedAt", System.currentTimeMillis()) }
                writeMeta(d, "$LINK_DONE_META_PREFIX$id", completion)
            }
        }
        E2eeRecoveryResult(parsed.archive.size, conflicts)
    }

    // ── device-local encryption preferences (`prefs.setLocal`, nonsecret but sealed) ──

    /** Every local preference (`ircfiber:e2ee:*` audience/default strings) for the runtime context. */
    suspend fun localPrefs(): Map<String, String> = io {
        val d = requireDb()
        val out = LinkedHashMap<String, String>()
        d.rawQuery("SELECT key, sealed FROM local ORDER BY key", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                out[key] = unseal(c.getBlob(1), aad("local", key), "local preference").decodeToString()
            }
        }
        out
    }

    /** Writes ([value] non-null) or removes one local preference. */
    suspend fun setLocalPref(key: String, value: String?): Unit = io {
        val d = requireDb()
        checkKey(key)
        if (value == null) {
            d.delete("local", "key = ?", arrayOf(key))
        } else {
            replace(d, "local", "key", key, seal(value.encodeToByteArray(), aad("local", key)))
        }
    }

    // ── internals: open ────────────────────────────────────────────────────

    private fun openLocked() {
        unrecoverable?.let { throw it }
        if (closed) throw IllegalStateException("encryption store closed")
        if (db != null) return
        try {
            dir.mkdirs()
            val existed = dbFile.exists()
            val h = Helper(app, dbFile) { markCorrupt() }
            val d = try {
                h.writableDatabase
            } catch (e: SQLiteException) {
                h.close()
                if (!existed) throw e
                throw E2eeRecoveryRequired("encryption store cannot be opened", e)
            } catch (e: MigrationFailed) {
                h.close()
                throw E2eeRecoveryRequired("encryption store schema cannot be migrated", e)
            }
            helper = h
            db = d
            val wrapped = d1Blob(d, "SELECT value FROM header WHERE name = ?", HEADER_DATA_KEY)
            dataKey = if (wrapped == null) initialize(d) else unlockExisting(d, wrapped)
        } catch (e: E2eeRecoveryRequired) {
            closeQuietly()
            unrecoverable = e
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw e
        }
    }

    /** A new store, or one whose creation never committed its key header (then it can hold no records). */
    private fun initialize(d: SQLiteDatabase): SecretKey {
        val hasRecords = listOf("meta", "mls", "archive", "local").any { table ->
            d.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
        }
        if (hasRecords) throw E2eeRecoveryRequired("encryption store has records but no key")
        deleteKeystoreKey(keystoreAlias)
        val wrapper = createKeystoreKey(keystoreAlias)
        val raw = ByteArray(32).also(random::nextBytes)
        val key = SecretKeySpec(raw, "AES")
        val wrapped = keystoreSeal(wrapper, raw, aad("header", HEADER_DATA_KEY))
        raw.fill(0)
        dataKey = key
        val check = seal(CHECK_VALUE, aad("header", HEADER_CHECK))
        transaction(d) {
            replace(it, "header", "name", HEADER_DATA_KEY, wrapped, valueColumn = "value")
            replace(it, "header", "name", HEADER_CHECK, check, valueColumn = "value")
        }
        return key
    }

    private fun unlockExisting(d: SQLiteDatabase, wrapped: ByteArray): SecretKey {
        val wrapper = try {
            loadKeystoreKey(keystoreAlias)
        } catch (e: GeneralSecurityException) {
            throw E2eeRecoveryRequired("this device's encryption key is unreadable", e)
        } ?: throw E2eeRecoveryRequired("this device's encryption key is missing")
        val raw = try {
            keystoreOpen(wrapper, wrapped, aad("header", HEADER_DATA_KEY))
        } catch (e: GeneralSecurityException) {
            throw E2eeRecoveryRequired("this device's encryption key cannot open the store", e)
        }
        if (raw.size != 32) throw E2eeRecoveryRequired("stored data key has the wrong size")
        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        dataKey = key
        val check = d1Blob(d, "SELECT value FROM header WHERE name = ?", HEADER_CHECK)
            ?: throw E2eeRecoveryRequired("encryption store key check is missing")
        if (!unseal(check, aad("header", HEADER_CHECK), "key check").contentEquals(CHECK_VALUE)) {
            throw E2eeRecoveryRequired("encryption store key check mismatch")
        }
        return key
    }

    private fun markCorrupt() {
        unrecoverable = unrecoverable ?: E2eeRecoveryRequired("encryption store is corrupt")
    }

    private fun closeLocked() {
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { helper?.close() }
        helper = null
        db = null
        dataKey = null
    }

    private fun requireDb(): SQLiteDatabase {
        unrecoverable?.let { throw it }
        if (closed) throw IllegalStateException("encryption store closed")
        if (db == null) openLocked()
        return db!!
    }

    // ── internals: records ─────────────────────────────────────────────────

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (e: SQLiteException) {
            unrecoverable?.let { throw it }
            throw e
        }
    }

    private fun aad(table: String, key: String): ByteArray = "$AAD_PREFIX\u0000$scope\u0000$table\u0000$key".encodeToByteArray()

    private fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val key = dataKey ?: throw IllegalStateException("encryption store not open")
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    private fun unseal(sealed: ByteArray, aad: ByteArray, what: String): ByteArray = tryOpen(sealed, aad)
        ?: throw E2eeRecoveryRequired("$what failed authentication")

    private fun tryOpen(sealed: ByteArray, aad: ByteArray): ByteArray? {
        val key = dataKey ?: throw IllegalStateException("encryption store not open")
        if (sealed.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun readMeta(d: SQLiteDatabase, key: String): JsonElement? {
        val sealed = d1Blob(d, "SELECT sealed FROM meta WHERE key = ?", key) ?: return null
        val text = unseal(sealed, aad("meta", key), "meta record").decodeToString()
        return try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw E2eeRecoveryRequired("meta record is not JSON", e)
        }
    }

    private fun writeMeta(d: SQLiteDatabase, key: String, value: JsonElement) =
        replace(d, "meta", "key", key, seal(value.toString().encodeToByteArray(), aad("meta", key)))

    private fun openArchiveRow(msgid: String, bufferKey: String, sealed: ByteArray): JsonObject? {
        val plain = tryOpen(sealed, aad("archive", "$msgid\u0000$bufferKey")) ?: return null
        return runCatching { Json.parseToJsonElement(plain.decodeToString()) as? JsonObject }.getOrNull()
    }

    private fun writeArchive(d: SQLiteDatabase, r: E2eeArchiveRow) {
        val values = ContentValues().apply {
            put("msgid", r.msgid)
            put("bufferKey", r.bufferKey)
            put("t", r.t)
            put("sealed", seal(r.row.toString().encodeToByteArray(), aad("archive", "${r.msgid}\u0000${r.bufferKey}")))
        }
        d.insertWithOnConflict("archive", null, values, SQLiteDatabase.CONFLICT_REPLACE).also {
            if (it == -1L) throw SQLiteException("archive write failed")
        }
    }

    private fun replace(
        d: SQLiteDatabase,
        table: String,
        keyColumn: String,
        key: String,
        sealed: ByteArray,
        valueColumn: String = "sealed",
    ) {
        val values = ContentValues().apply {
            put(keyColumn, key)
            put(valueColumn, sealed)
        }
        if (d.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw SQLiteException("$table write failed")
        }
    }

    private inline fun transaction(d: SQLiteDatabase, block: (SQLiteDatabase) -> Unit) {
        d.beginTransaction()
        try {
            block(d)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // ── recovery payload validation (`checkRecovery`) ──────────────────────

    private class PeerEntry(val key: String, val pin: JsonObject)

    private class Recovery(
        val archive: List<E2eeArchiveRow>,
        val ownKey: JsonObject?,
        val oldKeys: List<JsonObject>,
        val peers: List<PeerEntry>,
        val linkTransferId: String?,
        val linkCompletion: JsonElement?,
    )

    private fun parseRecovery(p: JsonObject): Recovery {
        fun invalid(what: String): Nothing = throw E2eeStoreRejected("invalid recovery payload: $what")
        val archive = (p["archive"] as? JsonArray ?: invalid("shape")).map { e ->
            parseArchiveRow(e as? JsonObject ?: invalid("archive row")) ?: invalid("archive row")
        }
        val pgp = p["pgp"] as? JsonObject ?: invalid("shape")
        val ownKey = when (val k = pgp["ownKey"]) {
            null -> invalid("own key")
            is JsonNull -> null
            else -> checkOwnKey(k) ?: invalid("own key")
        }
        val oldKeys = (pgp["oldKeys"] as? JsonArray ?: invalid("pgp lists")).map { checkOwnKey(it) ?: invalid("old key") }
        val seen = HashSet<String>()
        val peers = (pgp["peers"] as? JsonArray ?: invalid("pgp lists")).map { e ->
            val o = e as? JsonObject ?: invalid("peer")
            val networkId = o.str("networkId")?.takeIf(String::isNotEmpty) ?: invalid("peer")
            val peer = o.str("peer")?.takeIf(String::isNotEmpty) ?: invalid("peer")
            val pin = o["pin"] as? JsonObject ?: invalid("peer pin")
            if (pin.str("armored") == null || pin.str("fingerprint").isNullOrEmpty() ||
                (pin["verified"] as? JsonPrimitive)?.booleanOrNull == null ||
                (pin["pinnedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == null
            ) {
                invalid("peer pin")
            }
            val key = "$PGP_PEER_META_PREFIX$networkId:${peer.lowercase()}"
            if (!seen.add(key)) invalid("duplicate peer $key")
            PeerEntry(key, pin)
        }
        val linkTransferId = when (val id = p["linkTransferId"]) {
            null, is JsonNull -> null
            else -> (id as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() && ':' !in it && it.length <= MAX_KEY_LENGTH }
                ?: invalid("link transfer id")
        }
        val completion = p["linkCompletion"]?.takeIf { it !is JsonNull }
        return Recovery(archive, ownKey, oldKeys, peers, linkTransferId, completion)
    }

    private fun checkOwnKey(e: JsonElement): JsonObject? {
        val o = e as? JsonObject ?: return null
        if (o.str("armoredPrivate") == null || o.str("armoredPublic") == null || o.str("fingerprint").isNullOrEmpty() ||
            o.str("passphrase") == null
        ) {
            return null
        }
        return o
    }

    private fun fingerprint(o: JsonObject): String? = o.str("fingerprint")

    private fun checkExportRow(r: E2eeArchiveRow) {
        checkKey(r.msgid)
        if (r.bufferKey.isEmpty() || r.bufferKey.length > MAX_KEY_LENGTH) throw E2eeStoreRejected("bad bufferKey")
        checkArchiveRow(r.row)
    }

    private fun checkArchiveRow(row: JsonObject) {
        val e2e = row["e2e"] as? JsonObject
        if (row.str("text") == null || e2e?.str("state") == null) throw E2eeStoreRejected("archive row needs text and e2e.state")
    }

    companion object {
        private const val TAG = "E2eeStore"
        private const val DB_NAME = "store.db"
        private const val DB_VERSION = 1
        private const val KEY_ALIAS_PREFIX = "fiberirc.e2ee."
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val AAD_PREFIX = "ircfiber-e2ee/v1"
        private const val MLS_SLOT = "state"
        private const val HEADER_DATA_KEY = "dataKey"
        private const val HEADER_CHECK = "check"
        private val CHECK_VALUE = "ircfiber-e2ee-store-check".encodeToByteArray()
        private const val MAX_KEY_LENGTH = 1024

        const val PGP_OWN_KEY_META = "pgp:ownKey"
        const val PGP_OLD_KEYS_META = "pgp:oldKeys"
        const val PGP_PEER_META_PREFIX = "pgp:peer:"
        const val LINK_RX_META_PREFIX = "linkRx:"
        const val LINK_DONE_META_PREFIX = "linkDone:"

        /** The store of one account (not opened yet). */
        fun forAccount(context: Context, apiOrigin: String, userId: String): E2eeStore =
            E2eeStore(context.applicationContext, e2eeAccountScope(apiOrigin, userId))

        /** Whether this account has a store on this device, without opening it (sign-out keep/remove choice). */
        fun exists(context: Context, apiOrigin: String, userId: String): Boolean =
            File(accountDir(context, e2eeAccountScope(apiOrigin, userId)), DB_NAME).exists()

        /** `networkId:lowercased-buffer`, as the web's `bufferKey`. */
        fun bufferKey(networkId: String, buffer: String): String {
            if (networkId.isEmpty() || buffer.isEmpty()) throw E2eeStoreRejected("networkId and buffer are required")
            return "$networkId:${buffer.lowercase()}"
        }

        /** Parses one `ArchiveExportRow`; null when malformed. */
        fun parseArchiveRow(o: JsonObject): E2eeArchiveRow? {
            val msgid = o.str("msgid")?.takeIf { it.isNotEmpty() } ?: return null
            val bufferKey = o.str("bufferKey")?.takeIf { it.isNotEmpty() } ?: return null
            val t = (o["t"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
            val row = o["row"] as? JsonObject ?: return null
            if (row.str("text") == null || (row["e2e"] as? JsonObject)?.str("state") == null) return null
            return E2eeArchiveRow(msgid, row, bufferKey, t.toLong())
        }

        private fun accountDir(context: Context, scope: String) = File(File(context.filesDir, E2EE_FILES_DIR), scope)

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun checkKey(key: String): String {
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) throw E2eeStoreRejected("bad record key")
            return key
        }

        private fun decodeBase64(text: String): ByteArray? = try {
            Base64.decode(text, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun d1Blob(d: SQLiteDatabase, sql: String, arg: String): ByteArray? =
            d.rawQuery(sql, arrayOf(arg)).use { if (it.moveToFirst()) it.getBlob(0) else null }

        /** Keys of [table] starting with [prefix], ascending: SQLite's BINARY order keeps a prefix contiguous. */
        private fun prefixedKeys(d: SQLiteDatabase, table: String, column: String, prefix: String): List<String> {
            val out = ArrayList<String>()
            d.rawQuery("SELECT $column FROM $table WHERE $column >= ? ORDER BY $column", arrayOf(prefix)).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0)
                    if (!key.startsWith(prefix)) break
                    out.add(key)
                }
            }
            return out
        }

        private fun deleteFiles(dir: File) {
            if (!dir.exists()) return
            if (!dir.deleteRecursively()) throw java.io.IOException("could not delete the encryption store")
        }

        private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun loadKeystoreKey(alias: String): SecretKey? = (keystore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

        private fun deleteKeystoreKey(alias: String) {
            val ks = keystore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }

        private fun createKeystoreKey(alias: String): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        /** Keystore AES-GCM with a Keystore-chosen IV: `iv ‖ ciphertext+tag`. */
        private fun keystoreSeal(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val ct = cipher.doFinal(plain)
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected Keystore IV size" }
            return iv + ct
        }

        private fun keystoreOpen(key: SecretKey, sealed: ByteArray, aad: ByteArray): ByteArray {
            if (sealed.size < IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("sealed key too short")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    }

    private class MigrationFailed(detail: String) : RuntimeException(detail)

    /** SQLite at an absolute path; the corruption handler never deletes (the default one would). */
    private class Helper(context: Context, file: File, onCorrupt: () -> Unit) : SQLiteOpenHelper(
        context,
        file.absolutePath,
        null,
        DB_VERSION,
        DatabaseErrorHandler { onCorrupt() },
    ) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // Commit means on disk: the runtime's persist-before-reply rule rests on this acknowledgment.
            db.execSQL("PRAGMA synchronous = FULL")
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE header (name TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL)")
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, sealed BLOB NOT NULL)")
            db.execSQL("CREATE TABLE mls (slot TEXT PRIMARY KEY NOT NULL, sealed BLOB NOT NULL)")
            db.execSQL(
                "CREATE TABLE archive (msgid TEXT PRIMARY KEY NOT NULL, bufferKey TEXT NOT NULL, " +
                    "t INTEGER NOT NULL, sealed BLOB NOT NULL)",
            )
            db.execSQL("CREATE INDEX archive_buffer ON archive (bufferKey, t)")
            db.execSQL("CREATE TABLE local (key TEXT PRIMARY KEY NOT NULL, sealed BLOB NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw MigrationFailed("no migration from schema $oldVersion to $newVersion")
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw MigrationFailed("store schema $oldVersion is newer than this app ($newVersion)")
        }
    }
}
