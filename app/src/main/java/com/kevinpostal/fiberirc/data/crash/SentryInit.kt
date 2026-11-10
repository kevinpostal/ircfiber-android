package com.kevinpostal.fiberirc.data.crash

import android.content.Context
import com.kevinpostal.fiberirc.BuildConfig
import io.sentry.android.core.SentryAndroid
import com.kevinpostal.fiberirc.data.voice.VoicePrivacy

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
            options.setBeforeBreadcrumb { breadcrumb, _ ->
                breadcrumb.message = breadcrumb.message?.let(VoicePrivacy::redact)
                breadcrumb.data.keys.toList().forEach { key ->
                    if (VoicePrivacy.sensitiveKey(key)) breadcrumb.removeData(key)
                    else (breadcrumb.data[key] as? String)?.let { breadcrumb.setData(key, VoicePrivacy.redact(it)) }
                }
                breadcrumb
            }
            options.setBeforeSend { event, _ ->
                event.request?.let { request ->
                    request.url = request.url?.let(VoicePrivacy::redact)
                    request.queryString = null
                    request.cookies = null
                    request.data = null
                    request.headers = request.headers?.filterKeys { !VoicePrivacy.sensitiveKey(it) }
                }
                event.exceptions?.forEach { it.value = it.value?.let(VoicePrivacy::redact) }
                event.message?.let {
                    it.message = it.message?.let(VoicePrivacy::redact)
                    it.formatted = it.formatted?.let(VoicePrivacy::redact)
                    it.params = it.params?.map(VoicePrivacy::redact)
                }
                event
            }
        }
    }
}
