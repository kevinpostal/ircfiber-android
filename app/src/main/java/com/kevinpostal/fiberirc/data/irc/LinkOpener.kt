package com.kevinpostal.fiberirc.data.irc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log

/**
 * Every link the app opens goes through here (ports `ios/UI/Media/LinkOpener.swift`): web links in a Custom Tab
 * when Settings → Open Links In App is on ([com.kevinpostal.fiberirc.data.prefs.DeviceSettings.openLinksInApp]),
 * else `ACTION_VIEW` (the default browser or the app that owns the link); other schemes (`mailto:`, `tel:`) always
 * go to the system.
 */
object LinkOpener {
    private const val TAG = "FiberIRC.ui"

    /** Whether [url] opens in the in-app browser: an http(s) link while [openLinksInApp] is on. */
    fun opensInApp(url: String, openLinksInApp: Boolean): Boolean {
        if (!openLinksInApp) return false
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    fun open(context: Context, url: String, openLinksInApp: Boolean) {
        if (opensInApp(url, openLinksInApp)) openInCustomTab(context, url) else openExternally(context, url)
    }

    /** A Custom Tab whatever the setting (the image viewer's Open in Browser, Send Feedback). */
    fun openInCustomTab(context: Context, url: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        if (context !is android.app.Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            intent.launchUrl(context, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            openExternally(context, url)
        }
    }

    /** `ACTION_VIEW`, without a package-visibility preflight. Other link callers deliberately ignore failure. */
    fun openExternally(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            runCatching { Log.w(TAG, "No app opens a ${Uri.parse(url).scheme} link") }
            false
        }
    }
}
