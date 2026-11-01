package com.kevinpostal.fiberirc.data.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point (the Android side of the iOS Notification Service extension and `didRegisterForRemoteNotifications`):
 * a rotated token registers again; each data push becomes a conversation notification, except one for the buffer the
 * user is reading while the app is in the foreground. Reaches the app through [PushRegistrarHolder].
 */
class FiberIrcMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // registration tokens, see PushRegistrar.fetchToken
    override fun onNewToken(token: String) {
        PushRegistrarHolder.instance?.tokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val p = PushPayload.parse(message.data) ?: return
        if (PushRegistrarHolder.instance?.isShowing(p) == true) return
        PushNotifications.post(this, p)
    }
}
