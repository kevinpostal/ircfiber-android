package com.kevinpostal.fiberirc.ui.shell

import android.content.Context
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.push.ConversationShortcuts

/**
 * Leaving the account, from Settings or the chat's gear menu (ports `SessionActions.swift`). Either way this
 * install stops getting the account's pushes first, nothing of the account stays cached, and the sign-in screen
 * replaces the session. `DeviceIdentity` is never touched; the block list isn't reset (iOS neither).
 *
 * @param unregisterPush this install's push unregistration (M12's `PushRegistrar.unregister()`).
 * @param showLogin replaces the session with the sign-in screen, showing `notice` in its status row.
 */
class SessionActions(
    private val context: Context,
    private val api: SiteApi,
    private val stores: AccountStores,
    private val unregisterPush: suspend () -> Unit,
    private val showLogin: (notice: String) -> Unit,
) {
    /** Unregister push → GET /logout and forget the cookies → reset the stores and shortcuts → sign-in screen. */
    suspend fun signOut() {
        unregisterPush()
        api.logout()
        forgetAccount()
        showLogin("Signed out")
    }

    /**
     * Permanently deletes the account (after the user confirmed it): like [signOut] with `DELETE /api/me`. A
     * refused deletion returns the error and the session stays.
     */
    suspend fun deleteAccount(): Result<Unit> {
        unregisterPush()
        api.deleteAccount().onFailure { return Result.failure(it) }
        forgetAccount()
        showLogin("Your account was deleted.")
        return Result.success(Unit)
    }

    private fun forgetAccount() {
        stores.resetAccount()
        ConversationShortcuts.clearAll(context)
    }
}
