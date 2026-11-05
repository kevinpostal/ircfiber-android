package com.kevinpostal.fiberirc.data.e2ee

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevinpostal.fiberirc.MainActivity
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.originOf
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.data.push.PushPayload
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The account an external send acts for: `GET /api/me`'s immutable id at the configured origin. */
data class E2eeExternalAccount(val apiOrigin: String, val userId: String)

/** Whether an external entry point may post a message as plaintext (`POST /api/networks/<id>/messages`). */
sealed interface E2eeExternalGate {
    /**
     * A positively-off target of [account]: the only way to [E2eeExternalSend.postPlaintext], for exactly this
     * [networkId]/[target]. Only [E2eeExternalSend.check] makes one.
     */
    class Plaintext internal constructor(val account: E2eeExternalAccount, val networkId: String, val target: String) : E2eeExternalGate

    /**
     * Encrypted, pending or unknown: the message must continue in the app as a pending draft. [account] binds that
     * draft (null: no account known on this device, nothing can be kept); [reason] is for logs only.
     */
    class ContinueInApp internal constructor(val account: E2eeExternalAccount?, val reason: E2eeExternalPolicy.Reason) : E2eeExternalGate
}

/**
 * The external-send rule (plan §6): plaintext only for a target positively known off, by both the main app's fresh
 * account-bound [E2eePolicySnapshot] and a fresh authenticated `/api/me` + `/api/mls/policy`. Everything else —
 * missing/stale/other-account snapshot, unknown target, local on/pending, a server `e2ee` lock, an unreadable
 * policy, a DM under a DM default or incoming auto-lock — continues in the app.
 */
object E2eeExternalPolicy {
    enum class Reason {
        NoAccount,
        PolicyUnavailable,
        PrefsUnavailable,
        NoSnapshot,
        SnapshotOtherAccount,
        TargetUnknown,
        TargetOn,
        TargetPending,
        ServerLocked,
        DmDefault,
        DmAutoLock,
    }

    /** Null when [target] on [networkId] may be sent as plaintext; else why not. */
    fun blockReason(
        snapshot: E2eePolicySnapshot?,
        me: JsonObject?,
        policy: JsonObject?,
        networkId: String,
        target: String,
    ): Reason? {
        val userId = me?.string("id")?.takeIf(String::isNotEmpty) ?: return Reason.NoAccount
        if (policy == null) return Reason.PolicyUnavailable
        val policyDmDefault = when (val v = policy["dmDefault"]) {
            null -> false
            else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return Reason.PolicyUnavailable
        }
        val prefs = me["bufferPrefs"] as? JsonObject ?: return Reason.PrefsUnavailable
        snapshot ?: return Reason.NoSnapshot
        if (snapshot.userId != userId) return Reason.SnapshotOtherAccount
        if (networkId.isEmpty() || target.isEmpty()) return Reason.TargetUnknown

        // The account's server lock: any case variant of `networkId:target` with `e2ee` true locks it; a value
        // that isn't a boolean is unknown.
        var serverOff = false
        for ((key, values) in prefs) {
            if (!key.equals("$networkId:$target", ignoreCase = true)) continue
            val e2ee = (values as? JsonObject)?.get("e2ee") ?: continue
            when ((e2ee as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull) {
                true -> return Reason.ServerLocked
                false -> serverOff = true
                null -> return Reason.PrefsUnavailable
            }
        }
        when (snapshot.perTarget[E2eeStore.bufferKey(networkId, target)]) {
            null -> return Reason.TargetUnknown
            E2eePolicySnapshots.ON -> return Reason.TargetOn
            E2eePolicySnapshots.PENDING -> return Reason.TargetPending
        }
        if (!isChannel(target)) {
            // An incoming encrypted DM re-locks the conversation in the app, which may not have seen it yet.
            if (snapshot.autoLockIncoming) return Reason.DmAutoLock
            if ((policyDmDefault || snapshot.dmDefault) && !serverOff) return Reason.DmDefault
        }
        return null
    }

    private fun isChannel(target: String) = target.firstOrNull()?.let { it in "#&+!" } == true

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * The guarded external send of notification Reply and the share sheet: [check] decides with fresh server state,
 * [postPlaintext] posts only through a [E2eeExternalGate.Plaintext], [saveDraft] keeps everything else sealed for
 * the main app ([E2eeDraftContinuation]). Never sends ciphertext or decrypts anything itself.
 */
class E2eeExternalSend(context: Context, private val api: SiteApi) {
    private val app = context.applicationContext
    private val apiOrigin = originOf(api.baseUrl.toHttpUrl())

    suspend fun check(networkId: String, target: String): E2eeExternalGate {
        val me = api.getJson("api/me").getOrNull() as? JsonObject
        val userId = (me?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)
        if (userId == null) {
            // No fresh identity: a draft can still be kept for the account the app last wrote a snapshot for.
            val last = withContext(Dispatchers.IO) { E2eePolicySnapshots.lastAccountUserId(app, apiOrigin) }
            return E2eeExternalGate.ContinueInApp(last?.let { E2eeExternalAccount(apiOrigin, it) }, E2eeExternalPolicy.Reason.NoAccount)
        }
        val account = E2eeExternalAccount(apiOrigin, userId)
        val policy = api.getJson("api/mls/policy").getOrNull() as? JsonObject
        val snapshot = withContext(Dispatchers.IO) { E2eePolicySnapshots.read(app, apiOrigin, userId) }
        val reason = E2eeExternalPolicy.blockReason(snapshot, me, policy, networkId, target)
        return if (reason == null) {
            E2eeExternalGate.Plaintext(account, networkId, target)
        } else {
            E2eeExternalGate.ContinueInApp(account, reason)
        }
    }

    /** `POST /api/networks/<id>/messages` for the gate's target, as a reply to [replyTo] when given. */
    suspend fun postPlaintext(gate: E2eeExternalGate.Plaintext, text: String, replyTo: String?): Result<Unit> {
        val body = buildJsonObject {
            put("target", gate.target)
            put("text", text)
            if (!replyTo.isNullOrEmpty()) put("replyTo", replyTo)
        }
        return api.postJson(listOf("api", "networks", gate.networkId, "messages"), body).map { }
    }

    /** Seals [text] as a pending draft of [account]; committed when this returns. */
    suspend fun saveDraft(
        account: E2eeExternalAccount,
        networkId: String,
        target: String,
        text: String,
        replyTo: String?,
    ): E2eePendingDraft = withContext(Dispatchers.IO) {
        E2eePendingDrafts.create(app, account.apiOrigin, account.userId, networkId, target, text, replyTo)
    }
}

/**
 * Brings a saved pending draft to the main app: an explicit, immutable `MainActivity` intent carrying only
 * `fiberirc://encrypted-draft?id=<UUID>` ([AppRouter.pendingEncryptedDraft]). From the foreground it opens the app
 * directly; from the background (where Android forbids activity starts) it posts a text-free "Continue in IRC Fiber"
 * notification whose tap opens it. The message text never reaches a notification, intent or log.
 */
object E2eeDraftContinuation {
    private const val NOTIFICATION_ID = 2
    private const val TAG_PREFIX = "encrypted-draft:"

    /** The explicit `MainActivity` intent for draft [draftId]. */
    fun intent(context: Context, draftId: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(AppRouter.encryptedDraftUri(draftId)), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pendingIntent(context: Context, draftId: String): PendingIntent = PendingIntent.getActivity(
        context,
        draftId.hashCode(),
        intent(context, draftId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens draft [draftId] in the app when it is in the foreground, else posts the tap-to-continue notification. */
    fun continueInApp(context: Context, draftId: String) {
        val app = context.applicationContext
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) {
            try {
                app.startActivity(intent(app, draftId))
                return
            } catch (e: RuntimeException) {
                AppLog.w(TAG, "draft continuation: activity start refused (${e.javaClass.simpleName})")
            }
        }
        notify(app, draftId)
    }

    /** The text-free "Continue in IRC Fiber" notification for draft [draftId]. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() is false without POST_NOTIFICATIONS
    fun notify(context: Context, draftId: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, PushPayload.CATEGORY_BUFFER_MESSAGE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Continue in IRC Fiber")
            .setContentText("Your message wasn't sent. It's saved securely — tap to review and send it.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent(context, draftId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(TAG_PREFIX + draftId, NOTIFICATION_ID, notification)
    }

    /** The draft's continuation notification is no longer needed (sent, discarded or opened). */
    fun cancel(context: Context, draftId: String) {
        NotificationManagerCompat.from(context).cancel(TAG_PREFIX + draftId, NOTIFICATION_ID)
    }

    private const val TAG = "FiberIRC.e2ee"
}
