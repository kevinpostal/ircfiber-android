package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * This install's id for the gateway (`X-IRCFiber-Device`, ports `DeviceIdentity.swift`). The server keeps this
 * device's display, member-list and notification settings under it, apart from the web's; it is also the bouncer
 * client id. Kept in Block Store (not backed up to the cloud) so it survives sign-out and reinstall; the
 * `fiberirc_device_identity` SharedPreferences file (excluded from Auto Backup) holds it only while Block Store
 * refuses. Never cleared on sign-out or account deletion.
 */
class DeviceIdentity(context: Context) {
    private val appContext = context.applicationContext

    /** Lower-case UUID, resolved once (blocking Block Store I/O, off the main thread) on first read. */
    val id: String by lazy {
        val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val slot = BlockStoreSlot(appContext)
        resolve(read = slot::read, write = slot::write, prefs = prefs)
    }

    companion object {
        const val PREFS_FILE = "fiberirc_device_identity"
        /** Prefs key of the id while Block Store refuses it. */
        const val FALLBACK_KEY = "device_identity_id"

        private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Whether [s] is a UUID in canonical 8-4-4-4-12 form (like Foundation's `UUID(uuidString:)`). */
        fun isUuid(s: String?): Boolean = s != null && UUID_RE.matches(s)

        /**
         * The stored id, else a new one; always a lower-case UUID. An id parked in [prefs] moves to Block Store as
         * soon as Block Store takes it (`DeviceIdentity.resolve`, step for step).
         */
        fun resolve(read: () -> String?, write: (String) -> Boolean, prefs: SharedPreferences): String {
            val stored = read()
            if (isUuid(stored)) return stored!!.lowercase()
            val parked = prefs.getString(FALLBACK_KEY, null)
            if (isUuid(parked)) {
                val id = parked!!.lowercase()
                if (write(id)) prefs.edit().remove(FALLBACK_KEY).apply()
                return id
            }
            val id = UUID.randomUUID().toString().lowercase()
            if (!write(id)) prefs.edit().putString(FALLBACK_KEY, id).apply()
            return id
        }
    }
}

/**
 * The install id's one Block Store entry, `setShouldBackupToCloud(false)` (the Keychain's `ThisDeviceOnly`
 * equivalent). Calls block for at most [TIMEOUT_S] each. When the read *failed* (as opposed to finding nothing),
 * writes are refused for this launch, so a transient Block Store error can't replace a stored id with a new one.
 */
private class BlockStoreSlot(context: Context) {
    private val client = Blockstore.getClient(context)
    private var readFailed = false

    fun read(): String? = try {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()
        val response = await(client.retrieveBytes(request))
        response.blockstoreDataMap[KEY]?.bytes?.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        readFailed = true
        Log.w(TAG, "Block Store read failed: ${e.javaClass.simpleName}")
        null
    }

    fun write(id: String): Boolean {
        if (readFailed) return false
        return try {
            val data = StoreBytesData.Builder()
                .setKey(KEY)
                .setBytes(id.toByteArray(Charsets.UTF_8))
                .setShouldBackupToCloud(false)
                .build()
            await(client.storeBytes(data))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Block Store write failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** `Tasks.await` refuses the main thread; there the wait happens on a helper thread instead. */
    private fun <T> await(task: Task<T>): T {
        if (Looper.myLooper() != Looper.getMainLooper()) return Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS)
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit<T> { Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS) }.get()
        } finally {
            executor.shutdown()
        }
    }

    companion object {
        const val KEY = "com.kevinpostal.fiberirc.device-id"
        const val TIMEOUT_S = 5L
        const val TAG = "FiberIRC.identity"
    }
}
