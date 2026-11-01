package com.kevinpostal.fiberirc.data.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.kevinpostal.fiberirc.MainActivity
import com.kevinpostal.fiberirc.R
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.data.prefs.ShareTarget
import com.kevinpostal.fiberirc.ui.shell.AppRouter

/**
 * The account's channels and conversations as dynamic conversation shortcuts (ports `SpotlightIndexer.swift`'s
 * source list, not its CoreSpotlight API: Android has no system search index for in-app content). They power the
 * share sheet's Direct Share row (M13), launcher long-press shortcuts, and conversation styling for push
 * notifications (M12). Siri's `INSendMessageIntent` donation has no Android equivalent and isn't ported.
 *
 * Each shortcut's id and [LocusIdCompat] are [threadId] — the push payload's `threadId`, so a delivered
 * notification's `setShortcutId(threadId)` finds its shortcut — and it opens [AppRouter.bufferUri] in [MainActivity].
 *
 * Wire [publish] to [com.kevinpostal.fiberirc.data.prefs.ShareTargetsStore.onPublished], which already debounces
 * and orders the targets (pinned, then recent, then the rest). Main-thread confined like that store.
 */
class ConversationShortcuts(context: Context) {
    private val context = context.applicationContext

    /**
     * Replaces the published shortcuts with the first [ShortcutManagerCompat.getMaxShortcutCountPerActivity] of
     * [targets], ranked in list order; dynamic shortcuts for buffers no longer listed are removed.
     */
    fun publish(targets: List<ShareTarget>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            val shortcuts = targets.distinctBy { threadId(it.networkId, it.buffer) }.take(max)
                .mapIndexed { rank, target -> shortcut(target, rank) }
            val ids = shortcuts.mapTo(HashSet()) { it.id }
            val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filterNot { it in ids }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            shortcuts.forEach { ShortcutManagerCompat.pushDynamicShortcut(context, it) }
        }.onFailure { AppLog.e(TAG, "publishing conversation shortcuts failed", it) }
    }

    private fun shortcut(target: ShareTarget, rank: Int): ShortcutInfoCompat {
        val id = threadId(target.networkId, target.buffer)
        val person = Person.Builder()
            .setName(target.buffer)
            .setKey(id)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppRouter.bufferUri(target.networkId, target.buffer)))
            .setClass(context, MainActivity::class.java)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(target.buffer)
            .setLongLabel("${target.buffer} — ${target.networkName}")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setLocusId(LocusIdCompat(id))
            .setCategories(setOf(CATEGORY_CONVERSATION))
            .setIntent(intent)
            .setLongLived(true)
            .setPerson(person)
            .setRank(rank)
            .build()
    }

    companion object {
        private const val TAG = "FiberIRC.Shortcuts"

        /** Matched by the share target's `<share-target>` in `res/xml/shortcuts.xml` (Direct Share). */
        const val CATEGORY_CONVERSATION = "com.kevinpostal.fiberirc.category.CONVERSATION"

        /** `"<networkId>:<lower-cased buffer>"`: shortcut id, locus id and push `threadId` (be `push/policy.d`). */
        fun threadId(networkId: String, buffer: String): String = "$networkId:${buffer.lowercase()}"

        /**
         * Signed out or account deleted: nothing of the account stays as a shortcut — the dynamic ones and the
         * long-lived copies the system cached for notifications/Direct Share (ports `SpotlightIndexer.deleteAll`).
         */
        fun clearAll(context: Context) {
            runCatching {
                val ids = ShortcutManagerCompat.getShortcuts(
                    context,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
                ).map { it.id }
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
            }.onFailure { AppLog.e(TAG, "clearing conversation shortcuts failed", it) }
        }
    }
}
