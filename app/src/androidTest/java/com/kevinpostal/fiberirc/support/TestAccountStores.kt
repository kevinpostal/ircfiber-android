package com.kevinpostal.fiberirc.support

import android.app.Application
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.ArchiveListStore
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import com.kevinpostal.fiberirc.data.prefs.BufferPrefsStore
import com.kevinpostal.fiberirc.data.prefs.NetworkLayoutStore
import com.kevinpostal.fiberirc.data.prefs.NotificationPrefsStore
import com.kevinpostal.fiberirc.data.prefs.PinListStore

/**
 * [AccountStores] wired exactly as `com.kevinpostal.fiberirc.data.Deps` wires them to production, except [api]
 * points at a [FakeSiteServer] instead of ircfiber.com. Every store's `SharedPreferences` is the real on-device
 * `accountPrefs` file (shared with any other store built the same way in the process), so tests that care about
 * isolation should clear it in `@Before`/`@After`.
 */
fun testAccountStores(app: Application, api: SiteApi): AccountStores = AccountStores(
    accountSettings = AccountSettingsStore(app, api),
    notificationPrefs = NotificationPrefsStore(app, api),
    pinList = PinListStore(app, api),
    archiveList = ArchiveListStore(app, api),
    blockList = BlockListStore(api),
    networkLayout = NetworkLayoutStore(app, api),
    bufferPrefs = BufferPrefsStore(app, api),
)
