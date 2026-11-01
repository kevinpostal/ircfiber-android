package com.kevinpostal.fiberirc.data.api

import android.os.Build
import com.kevinpostal.fiberirc.BuildConfig
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

const val DEVICE_HEADER = "X-IRCFiber-Device"
const val DEVICE_MODEL_HEADER = "X-IRCFiber-Device-Model"
const val DEVICE_OS_HEADER = "X-IRCFiber-Device-OS"
const val DEVICE_APP_VERSION_HEADER = "X-IRCFiber-Device-App-Version"
const val USER_AGENT = "FiberIRC Android"

/**
 * Headers on every ircfiber.com request (ports `SiteAPI.addHeaders`): JSON, the app's User-Agent, this install's
 * settings profile (`X-IRCFiber-Device*`), and on every non-GET an `Origin`, without which the gateway refuses
 * cookie-carrying writes (web/common.d `rejectForeignOrigin`).
 */
fun fiberIrcHeaderInterceptor(
    deviceId: () -> String,
    baseUrl: String = BuildConfig.API_BASE_URL,
): Interceptor {
    val origin = originOf(baseUrl.toHttpUrl())
    val model = deviceModel(Build.MANUFACTURER, Build.MODEL)
    val os = deviceOsVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    return Interceptor { chain ->
        val r = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header(DEVICE_HEADER, deviceId())
            .header(DEVICE_MODEL_HEADER, model)
            .header(DEVICE_OS_HEADER, os)
            .header(DEVICE_APP_VERSION_HEADER, appVersion)
        if (chain.request().method != "GET") r.header("Origin", origin)
        chain.proceed(r.build())
    }
}

/** The one client every ircfiber.com call (API, images, uploads) shares: no redirects followed, 20 s timeouts. */
fun fiberIrcHttpClient(cookieJar: CookieJar, deviceId: () -> String): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .cookieJar(cookieJar)
    .addInterceptor(fiberIrcHeaderInterceptor(deviceId))
    .build()

/** `<scheme>://<host>[:port]`, the port only when it isn't the scheme's default. */
fun originOf(url: HttpUrl): String {
    val port = if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
    return "${url.scheme}://${url.host}$port"
}

/** `<manufacturer>_<model>` within the server's `[A-Za-z0-9,._-]{1,32}` (`isDeviceModel`). */
fun deviceModel(manufacturer: String?, model: String?): String {
    val raw = "${manufacturer.orEmpty()}_${model.orEmpty()}".replace(" ", "_")
    val clean = raw.filter { it.isAsciiAlphaNum() || it == ',' || it == '.' || it == '_' || it == '-' }.take(32)
    return clean.trim('_').ifEmpty { "Android" }
}

/** The release string within the server's `[0-9.]{1,16}` (`isDeviceOsVersion`), else the API level. */
fun deviceOsVersion(release: String?, sdkInt: Int): String =
    release.orEmpty().filter { it == '.' || it in '0'..'9' }.trim('.').take(16).ifEmpty { sdkInt.toString() }

private fun Char.isAsciiAlphaNum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
