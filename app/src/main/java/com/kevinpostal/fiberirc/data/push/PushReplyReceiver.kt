package com.kevinpostal.fiberirc.data.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.data.e2ee.E2eeDraftContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A notification's Reply (iOS `PushAction.reply`), without opening the app. [PushRegistrar.reply] posts it through
 * `POST /api/networks/<id>/messages` only for a target positively known unencrypted; then the notification shows
 * your reply. Otherwise the text is sealed as a pending draft and [E2eeDraftContinuation] brings it to the app for
 * an explicit Send (text-free "Continue in IRC Fiber" notification from the background) — never reported as sent.
 * The notification is re-posted quietly either way so the system stops its sending spinner. Blank text sends nothing.
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
                when (val outcome = registrar.reply(p.networkId, p.buffer, text, p.msgid)) {
                    PushRegistrar.ReplyOutcome.Sent -> PushNotifications.post(app, p, reply = text, quiet = true)
                    is PushRegistrar.ReplyOutcome.Saved -> {
                        PushNotifications.post(app, p, quiet = true)
                        E2eeDraftContinuation.continueInApp(app, outcome.draftId)
                    }
                    is PushRegistrar.ReplyOutcome.Failed -> {
                        AppLog.w(TAG, "push: reply failed", outcome.error)
                        PushNotifications.post(app, p, quiet = true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FiberIRC.push"
    }
}
