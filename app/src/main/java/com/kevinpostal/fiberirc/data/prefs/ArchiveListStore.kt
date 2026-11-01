package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.SiteApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account's archived buffers (`prefs.archivedChannels`, the web's "Archive"), kept in their network's Archives
 * group (ports `ArchiveList.swift`). No ordering.
 */
class ArchiveListStore(
    prefs: SharedPreferences,
    archive: (suspend (String, String) -> Unit)?,
    unarchive: (suspend (String, String) -> Unit)?,
) : AccountKeyListStore(prefs, KEY, archive, unarchive) {
    constructor(context: Context, api: SiteApi) : this(
        prefs = accountPrefs(context),
        archive = { network, channel ->
            api.postJson("api/me/archives", buildJsonObject { put("network", network); put("channel", channel) }).getOrThrow()
        },
        unarchive = { network, channel -> api.deletePath(listOf("api", "me", "archives", network, channel)).getOrThrow() },
    )

    fun isArchived(networkId: String, buffer: String): Boolean = contains(networkId, buffer)

    suspend fun archive(networkId: String, buffer: String) = setMember(true, networkId, buffer)

    suspend fun unarchive(networkId: String, buffer: String) = setMember(false, networkId, buffer)

    companion object {
        const val KEY = "archived_channels"
    }
}
