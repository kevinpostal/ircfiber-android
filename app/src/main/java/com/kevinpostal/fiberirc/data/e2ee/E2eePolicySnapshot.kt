package com.kevinpostal.fiberirc.data.e2ee

import android.content.Context
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The main app's nonsecret encryption-lock snapshot for external sends (notification Reply, share sheet): the
 * shared `{v:1,userId,apiOrigin,writtenAt,dmDefault,autoLockIncoming,perTarget}` shape. [perTarget] maps
 * [E2eeStore.bufferKey] (`networkId:lower-case buffer`) to [E2eePolicySnapshots.ON], [E2eePolicySnapshots.OFF] or
 * [E2eePolicySnapshots.PENDING]; a target absent from it is unknown. [writtenAt] is epoch milliseconds.
 */
@Serializable
data class E2eePolicySnapshot(
    @Required val v: Int = E2eePolicySnapshots.VERSION,
    val userId: String,
    val apiOrigin: String,
    val writtenAt: Long,
    val dmDefault: Boolean,
    val autoLockIncoming: Boolean,
    val perTarget: Map<String, String>,
)

/**
 * The snapshot file: one app-private file (`files/e2ee/policy-snapshot.json`, inside the backup-excluded encryption
 * directory) for the signed-in account, rewritten atomically by the main app on every lock/default/account change
 * and before suspension, removed on sign-out. A reader asking for another account or origin, a snapshot older than
 * [MAX_AGE_MS] (or dated in the future beyond [CLOCK_SKEW_MS]) or a malformed file gets null: unknown, never off.
 */
object E2eePolicySnapshots {
    const val VERSION = 1
    const val ON = "on"
    const val OFF = "off"
    const val PENDING = "pending"

    const val MAX_AGE_MS = 5 * 60 * 1000L
    const val CLOCK_SKEW_MS = 60 * 1000L

    private const val FILE_NAME = "policy-snapshot.json"
    private val json = Json { encodeDefaults = true }
    private val lock = Any()

    /**
     * One target's snapshot state. [e2eePref] is the account's server buffer pref `e2ee` (null when unset);
     * [lockPending] a lock change still settling. An unset DM follows [dmDefault]; an unset channel is off.
     */
    fun targetState(e2eePref: Boolean?, lockPending: Boolean, isChannel: Boolean, dmDefault: Boolean): String = when {
        lockPending -> PENDING
        e2eePref == true -> ON
        e2eePref == false -> OFF
        !isChannel && dmDefault -> ON
        else -> OFF
    }

    /** Atomically replaces the snapshot (temp file, fsync, rename). Any thread; blocking I/O. */
    @Throws(IOException::class)
    fun write(context: Context, snapshot: E2eePolicySnapshot) = write(file(context), snapshot)

    /** The snapshot of exactly this account and origin while fresh, else null (unknown). Blocking I/O. */
    fun read(context: Context, apiOrigin: String, userId: String, nowMs: Long = System.currentTimeMillis()): E2eePolicySnapshot? =
        read(file(context), apiOrigin, userId, nowMs)

    /** Sign-out: removes the snapshot when it belongs to this account (another account's stays). Blocking I/O. */
    fun remove(context: Context, apiOrigin: String, userId: String) = remove(file(context), apiOrigin, userId)

    /**
     * The account the last snapshot of [apiOrigin] was written for, regardless of age: binds a pending draft when
     * ircfiber.com can't be asked. Never a policy decision. Null when there is none (signed out, never written).
     */
    fun lastAccountUserId(context: Context, apiOrigin: String): String? = lastAccountUserId(file(context), apiOrigin)

    internal fun file(context: Context) = File(File(context.applicationContext.filesDir, E2EE_FILES_DIR), FILE_NAME)

    @Throws(IOException::class)
    internal fun write(file: File, snapshot: E2eePolicySnapshot) {
        require(snapshot.v == VERSION) { "snapshot version must be $VERSION" }
        require(snapshot.userId.isNotEmpty() && snapshot.apiOrigin.isNotEmpty()) { "snapshot needs userId and apiOrigin" }
        require(snapshot.perTarget.values.all(::validState)) { "perTarget values must be on, off or pending" }
        val bytes = json.encodeToString(E2eePolicySnapshot.serializer(), snapshot).encodeToByteArray()
        synchronized(lock) {
            val dir = file.parentFile ?: throw IOException("snapshot has no directory")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create the snapshot directory")
            val tmp = File(dir, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not replace the snapshot")
            }
        }
    }

    internal fun read(file: File, apiOrigin: String, userId: String, nowMs: Long): E2eePolicySnapshot? {
        val s = parse(file) ?: return null
        if (s.userId != userId || s.apiOrigin != apiOrigin) return null
        if (s.writtenAt > nowMs + CLOCK_SKEW_MS || nowMs - s.writtenAt > MAX_AGE_MS) return null
        return s
    }

    internal fun remove(file: File, apiOrigin: String, userId: String) {
        synchronized(lock) {
            val s = parse(file)
            if (s == null || (s.userId == userId && s.apiOrigin == apiOrigin)) file.delete()
        }
    }

    internal fun lastAccountUserId(file: File, apiOrigin: String): String? =
        parse(file)?.takeIf { it.apiOrigin == apiOrigin }?.userId

    private fun parse(file: File): E2eePolicySnapshot? {
        val text = synchronized(lock) {
            try {
                if (!file.isFile) return null
                file.readText()
            } catch (_: IOException) {
                return null
            }
        }
        val s = try {
            json.decodeFromString(E2eePolicySnapshot.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (s.v != VERSION || s.userId.isEmpty() || s.apiOrigin.isEmpty()) return null
        if (!s.perTarget.values.all(::validState)) return null
        return s
    }

    private fun validState(state: String) = state == ON || state == OFF || state == PENDING
}
