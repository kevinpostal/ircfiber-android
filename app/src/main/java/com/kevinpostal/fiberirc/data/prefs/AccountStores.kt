package com.kevinpostal.fiberirc.data.prefs

import com.kevinpostal.fiberirc.data.api.Me

/**
 * The stores `/api/me` feeds, and the order they load and reset in (`LoginViewController.connect`,
 * `SessionViewController.refreshAccount`, `SessionActions.forgetAccount`).
 */
class AccountStores(
    val accountSettings: AccountSettingsStore,
    val notificationPrefs: NotificationPrefsStore,
    val pinList: PinListStore,
    val archiveList: ArchiveListStore,
    val blockList: BlockListStore,
    val networkLayout: NetworkLayoutStore,
    val bufferPrefs: BufferPrefsStore,
) {
    /** Sign-in: every store, the block list included (it loads only here). */
    fun loadAtLogin(me: Me) {
        blockList.load(me.ignores)
        reloadFromMe(me)
    }

    /** Foreground/session refresh after `GET /api/me`: every store except [blockList]. */
    fun reloadFromMe(me: Me) {
        pinList.load(me.pinnedChannels)
        archiveList.load(me.archivedChannels)
        networkLayout.load(me)
        accountSettings.load(me)
        notificationPrefs.load(me)
        bufferPrefs.load(me.bufferPrefs)
    }

    /** Sign-out and account deletion: nothing of the account stays cached. [blockList] is left as is (iOS too). */
    fun resetAccount() {
        accountSettings.reset()
        notificationPrefs.reset()
        pinList.reset()
        archiveList.reset()
        networkLayout.reset()
        bufferPrefs.reset()
    }
}
