package com.kevinpostal.fiberirc

import android.app.Application
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.crash.SentryInit
import com.kevinpostal.fiberirc.data.irc.ArtFontAssets
import com.kevinpostal.fiberirc.data.media.FiberImageLoader
import com.kevinpostal.fiberirc.data.push.FirebaseInit
import com.kevinpostal.fiberirc.data.push.PushRegistrarHolder

/**
 * Owns the process's [Deps] graph (mirroring vanlife/android's `VanlifeApp`, plan M0 §8) and the app-launch work:
 * Firebase (only with an FCM key), the push registrar every system-built entry point reaches through
 * [PushRegistrarHolder], Coil's cookie-carrying image loader and the bundled FIGlet/TheDraw fonts.
 * [MainActivity], [com.kevinpostal.fiberirc.ui.share.ShareTargetActivity] and the push receivers reach [deps]
 * through `application as FiberIrcApp`.
 */
class FiberIrcApp : Application() {
    val deps: Deps by lazy { Deps(this) }

    override fun onCreate() {
        super.onCreate()
        SentryInit.start(this)
        FirebaseInit.start(this)
        PushRegistrarHolder.instance = deps.push
        deps.push.start()
        FiberImageLoader.install(deps.httpClient)
        ArtFontAssets.install(assets)
    }
}
