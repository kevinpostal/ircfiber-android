package com.kevinpostal.fiberirc.data.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.kevinpostal.fiberirc.BuildConfig

/**
 * Starts Firebase from the `BuildConfig.FCM_*` fields (no google-services plugin, like vanlife's `Push.kt`) when
 * `fiberirc.fcmApiKey` is set; a blank key leaves push inert (no token, nothing registered). Call once from
 * `FiberIrcApp.onCreate`, before [PushRegistrar.start].
 */
object FirebaseInit {
    fun start(context: Context) {
        if (BuildConfig.FCM_API_KEY.isBlank() || started(context)) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build(),
        )
    }

    /** Firebase is up: `FirebaseMessaging.getInstance()` would throw otherwise. */
    fun started(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()
}
