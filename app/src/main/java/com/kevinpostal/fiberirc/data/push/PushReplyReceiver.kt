package com.kevinpostal.fiberirc.data.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A notification's Reply (iOS `PushAction.reply`): sends the typed text to the buffer through
 * `POST /api/networks/<id>/messages` without opening the app, then updates the notification (your reply added on
 * success; re-posted quietly either way so the system stops its sending spinner). Blank text sends nothing.
 */
class PushReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PushNotifications.ACTION_REPLY) return
        val extras = intent.extras ?: return
        val p = PushPayload.parse(PushPayload.KEYS.mapNotNull { k -> extras.getString(k)?.let { k to it } }.toMap()) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PushNotifications.KEY_REPLY)?.toString()
        val registrar = PushRegistrarHolder.instance
        if (text.isNullOrBlank() || registrar == null) {
            PushNotifications.post(context, p, quiet = true)
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sent = registrar.sendReply(p.networkId, p.buffer, text, p.msgid)
                    .onFailure { AppLog.w(TAG, "push: reply failed", it) }
                    .isSuccess
                PushNotifications.post(app, p, reply = text.takeIf { sent }, quiet = true)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FiberIRC.push"
    }
}
