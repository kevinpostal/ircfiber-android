package com.kevinpostal.fiberirc.ui.shell

import android.content.Context
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import com.kevinpostal.fiberirc.data.e2ee.E2eePendingDrafts
import com.kevinpostal.fiberirc.data.e2ee.E2eePolicySnapshots
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.push.ConversationShortcuts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What sign-out does with this device's encryption keys and history (asked only when a store exists). */
enum class SignOutKeysChoice {
    /** Keep the encrypted store (segregated by account) for the next sign-in. */
    KEEP,

    /** The keys were removed (revoked, or the user confirmed a local-only removal) before sign-out continues. */
    REMOVED,

    /** Stay signed in. */
    CANCEL,
}

/**
 * Leaving the account, from Settings or the chat's gear menu (ports `SessionActions.swift`). Either way this
 * install stops getting the account's pushes first, nothing of the account stays cached, and the sign-in screen
 * replaces the session. `DeviceIdentity` is never touched; the block list isn't reset (iOS neither).
 *
 * With an encryption store on this device, [signOut] first asks (through [keysChoice], answered by the session's
 * sign-out dialog) whether to keep this device's encrypted history or remove its keys; removal revokes the device
 * while the session is still authenticated. The account's policy snapshot always goes; its pending drafts go with
 * its keys.
 *
 * @param unregisterPush this install's push unregistration (M12's `PushRegistrar.unregister()`).
 * @param showLogin replaces the session with the sign-in screen, showing `notice` in its status row.
 * @param e2ee the session's encryption service; null without one (tests, direct IRC).
 */
class SessionActions(
    private val context: Context,
    private val api: SiteApi,
    private val stores: AccountStores,
    private val unregisterPush: suspend () -> Unit,
    private val showLogin: (notice: String) -> Unit,
    val e2ee: E2eeService? = null,
) {
    private val _keysChoice = MutableStateFlow<CompletableDeferred<SignOutKeysChoice>?>(null)

    /** A sign-out waiting for the keep/remove answer; the session's dialog completes it. */
    val keysChoice: StateFlow<CompletableDeferred<SignOutKeysChoice>?> = _keysChoice.asStateFlow()

    /**
     * Asks keep/remove when this device holds the account's encrypted store (returns without signing out on Cancel),
     * then: unregister push → GET /logout and forget the cookies → reset the stores and shortcuts → sign-in screen.
     */
    suspend fun signOut() {
        val service = e2ee
        val userId = service?.userId
        if (service != null && service.hasLocalStore()) {
            val pending = CompletableDeferred<SignOutKeysChoice>()
            _keysChoice.value = pending
            val choice = try {
                pending.await()
            } finally {
                _keysChoice.compareAndSet(pending, null)
            }
            if (choice == SignOutKeysChoice.CANCEL) return
            if (choice == SignOutKeysChoice.REMOVED && userId != null) forgetDrafts(service.apiOrigin, userId)
        }
        api.beforeCredentialLoss()
        unregisterPush()
        api.logout()
        forgetAccount(service, userId)
        showLogin("Signed out")
    }

    /**
     * Permanently deletes the account (after the user confirmed it): like [signOut] with `DELETE /api/me`. A
     * refused deletion returns the error and the session stays. Only after the server deleted it does this account's
     * local encrypted store (and its drafts) go.
     */
    suspend fun deleteAccount(): Result<Unit> {
        val service = e2ee
        val userId = service?.userId
        api.beforeCredentialLoss()
        unregisterPush()
        api.deleteAccount().onFailure { return Result.failure(it) }
        if (service != null) {
            runCatching { service.deleteLocalStateForDeletedAccount() }
                .onFailure { Log.e(TAG, "local encryption state not removed after account deletion", it) }
            if (userId != null) forgetDrafts(service.apiOrigin, userId)
        }
        forgetAccount(service, userId)
        showLogin("Your account was deleted.")
        return Result.success(Unit)
    }

    private suspend fun forgetDrafts(apiOrigin: String, userId: String) = withContext(Dispatchers.IO) {
        runCatching { E2eePendingDrafts.removeAccount(context, apiOrigin, userId) }
            .onFailure { Log.e(TAG, "pending encrypted drafts not removed", it) }
    }

    private suspend fun forgetAccount(service: E2eeService?, userId: String?) {
        if (service != null && userId != null) {
            withContext(Dispatchers.IO) { runCatching { E2eePolicySnapshots.remove(context, service.apiOrigin, userId) } }
        }
        stores.resetAccount()
        ConversationShortcuts.clearAll(context)
    }

    private companion object {
        const val TAG = "SessionActions"
    }
}
