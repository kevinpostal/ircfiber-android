package com.kevinpostal.fiberirc.data.crash

import android.content.Context
import com.kevinpostal.fiberirc.BuildConfig
import io.sentry.android.core.SentryAndroid

/**
 * Starts Sentry (reporting to the self-hosted Bugsink instance, via the gateway's
 * reverse proxy -- see ircfiber-infra api/rest.d sentryIngestProxy) from
 * `BuildConfig.SENTRY_DSN` when `fiberirc.sentryDsn` is set; a blank DSN leaves the
 * SDK inert. Call once from `FiberIrcApp.onCreate`, mirroring [com.kevinpostal.fiberirc.data.push.FirebaseInit].
 */
object SentryInit {
    fun start(context: Context) {
        if (BuildConfig.SENTRY_DSN.isBlank()) return
        SentryAndroid.init(context) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
        }
    }
}
