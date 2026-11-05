package com.kevinpostal.fiberirc.ui.encryption

import android.content.Context
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.ui.login.LoginViewModel
import com.kevinpostal.fiberirc.ui.login.openCustomTab

/**
 * Encryption settings → "Sign In with Your Organization": the existing system-auth flow
 * (`/auth/org-<domain>?app=fiberirc` in a Custom Tab, back on `fiberirc://auth?code=`) with a local, account-bound
 * pending action `installOrgCertificate`. The session redeems the code ([com.kevinpostal.fiberirc.ui.shell.SessionHost]);
 * only the same immutable user id may continue to the certificate install. The server's org sign-in marker lasts
 * 10 minutes, so an older pending action is void. Non-secret: account id, origin and start time only.
 */
object E2eeOrgSignIn {
    private const val PREFS = "e2ee_pending_actions"
    private const val KEY_USER = "orgCert.userId"
    private const val KEY_ORIGIN = "orgCert.apiOrigin"
    private const val KEY_AT = "orgCert.startedAt"
    const val MAX_AGE_MS = 10 * 60 * 1000L

    /** A started org sign-in waiting for its `fiberirc://auth` return. */
    data class Pending(val userId: String, val apiOrigin: String, val startedAt: Long)

    /** Records the pending action for this account and opens the organization's sign-in; false without a browser. */
    fun start(context: Context, api: SiteApi, service: E2eeService, domain: String): Boolean {
        val userId = service.userId ?: return false
        prefs(context).edit()
            .putString(KEY_USER, userId)
            .putString(KEY_ORIGIN, service.apiOrigin)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        val opened = openCustomTab(context, LoginViewModel.appSignInUrl(api, listOf("auth", "org-$domain")))
        if (!opened) clear(context)
        return opened
    }

    /** The pending action of exactly this account and origin while it can still succeed, else null. */
    fun pending(context: Context, apiOrigin: String, userId: String, now: Long = System.currentTimeMillis()): Pending? {
        val p = prefs(context)
        val pending = Pending(
            p.getString(KEY_USER, null) ?: return null,
            p.getString(KEY_ORIGIN, null) ?: return null,
            p.getLong(KEY_AT, 0),
        )
        return pending.takeIf { valid(it, apiOrigin, userId, now) }
    }

    /** Pure: [p] belongs to this account and origin and is younger than the server's org sign-in marker. */
    fun valid(p: Pending, apiOrigin: String, userId: String, now: Long): Boolean =
        p.userId == userId && p.apiOrigin == apiOrigin && now >= p.startedAt && now - p.startedAt <= MAX_AGE_MS

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_USER).remove(KEY_ORIGIN).remove(KEY_AT).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
