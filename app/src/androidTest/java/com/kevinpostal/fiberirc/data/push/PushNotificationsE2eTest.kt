package com.kevinpostal.fiberirc.data.push

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real production posting and OS-delivered content; no Firebase or authenticated session is needed for rendering. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PushNotificationsE2eTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefix = "smart-notify-render-${UUID.randomUUID()}"
    private val tags = mutableSetOf<String>()
    private var grantedForTest = false
    private var eventTime = 1730000000123L

    @Before
    fun setUp() {
        // The leased testlab/managed-device emulator is the only place this test may grant permission or post.
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("emulator")) {
            "Notification render tests require a disposable emulator"
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            grantedForTest = true
        }
        PushNotifications.registerChannels(context)
        assertTrue("Posting permission must be effective", NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    @After
    fun tearDown() {
        tags.forEach { manager.cancel(it, 1) }
        if (grantedForTest && Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test
    fun fullToSenderToNoneDiscardsHistoryAndConversationIdentity() {
        val first = payload(body = "alice: old secret")
        postAndAwait(first)
        val second = payload(body = "alice: newest secret")
        val full = postAndAwait(second)
        val history = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(full)!!
        assertEquals(listOf("old secret", "newest secret"), history.messages.map { it.text.toString() })
        assertEquals("#private", history.conversationTitle.toString())
        assertNotNull(history.messages.last().person?.icon)
        assertEquals(second.threadId, full.shortcutId)
        assertEquals(second.threadId, full.locusId?.id)
        assertNotNull(full.actions.single().remoteInputs)

        val sender = payload(preview = "sender", title = "alice in #private", body = "New message")
        assertRedacted(postAndAwait(sender), sender)
        val none = payload(preview = "none", title = "IRC Fiber", body = "New message")
        val hidden = postAndAwait(none)
        assertRedacted(hidden, none)
        assertEquals("IRC Fiber", hidden.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(hidden.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("alice"))
        assertFalse(hidden.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("#private"))

        // Going back to full starts with only the newly delivered content, not pre-redaction history.
        val restored = postAndAwait(payload(body = "alice: newly allowed"))
        val restoredStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(restored)!!
        assertEquals(listOf("newly allowed"), restoredStyle.messages.map { it.text.toString() })
    }

    @Test
    fun privateMessageAndInviteRedactionNeverFallBackToRoutingIdentity() {
        for (category in listOf(PushPayload.CATEGORY_BUFFER_MESSAGE, PushPayload.CATEGORY_CHANNEL_INVITE)) {
            val p = payload(preview = "none", title = "", body = "New notification", suffix = category)
                .copy(category = category, buffer = "alice", subtitle = "private network")
            val shown = postAndAwait(p)
            assertRedacted(shown, p)
            assertEquals("", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(category, shown.channelId)
            assertEquals(if (category == PushPayload.CATEGORY_BUFFER_MESSAGE) "Reply" else "Join", shown.actions.single().title)
        }
    }

    @Test
    fun policySilentQuietAndReplyMuteWithoutChangingChannels() {
        val channelIds = listOf(PushPayload.CATEGORY_BUFFER_MESSAGE, PushPayload.CATEGORY_CHANNEL_INVITE)
        val before = channelIds.associateWith { channelSnapshot(it) }
        val audible = postAndAwait(payload(suffix = "audible"))
        assertEquals(Notification.GROUP_ALERT_ALL, audible.groupAlertBehavior)
        for (category in channelIds) {
            for (mode in listOf("policy", "quiet", "reply")) {
                val p = payload(suffix = "$category-$mode").copy(category = category, silent = mode == "policy")
                val shown = postAndAwait(p, quiet = mode == "quiet", reply = if (mode == "reply") "own private reply" else null)
                assertEquals(category, shown.channelId)
                assertEquals(Notification.GROUP_ALERT_SUMMARY, shown.groupAlertBehavior)
                assertNull(shown.sound)
                assertNull(shown.vibrate)
                assertEquals(0, shown.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
            }
        }
        assertEquals(before, channelIds.associateWith { channelSnapshot(it) })
    }

    @Test
    fun redactedReplyCannotReintroduceReplyTextOrRetainedFullContent() {
        postAndAwait(payload(body = "alice: original secret"))
        val redacted = payload(preview = "none", title = "IRC Fiber", body = "New message")
        val shown = postAndAwait(redacted, reply = "own private reply")
        assertRedacted(shown, redacted)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, shown.groupAlertBehavior)
    }

    private fun assertRedacted(shown: Notification, p: PushPayload) {
        assertEquals(p.title, shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(p.body, shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(p.body, shown.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown))
        assertFalse(shown.extras.containsKey(Notification.EXTRA_MESSAGES))
        assertFalse(shown.extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES))
        assertFalse(shown.extras.containsKey(Notification.EXTRA_PEOPLE))
        assertFalse(shown.extras.containsKey(Notification.EXTRA_PEOPLE_LIST))
        assertNull(shown.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        assertNull(shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(shown.getLargeIcon())
        assertNull(shown.shortcutId)
        assertNull(shown.locusId)
        assertEquals(p.category, shown.channelId)
        assertNotNull(shown.contentIntent)
    }

    private fun payload(
        preview: String = "full",
        title: String = "alice in #private",
        body: String = "alice: message",
        suffix: String = "conversation",
    ): PushPayload = PushPayload.parse(mapOf(
        "networkId" to "$prefix-network", "buffer" to "#private", "sender" to "alice", "ownNick" to "my-secret-nick",
        "msgid" to "msg-${eventTime}", "t" to (++eventTime).toString(), "title" to title,
        "subtitle" to "private network", "body" to body, "threadId" to "$prefix:$suffix",
        "category" to PushPayload.CATEGORY_BUFFER_MESSAGE, "badge" to "2", "userId" to "test-account",
        "apiOrigin" to "https://example.invalid", "preview" to preview, "eventId" to "event-$eventTime",
        "groupId" to "window-1", "silent" to "false",
    ))!!

    private fun postAndAwait(p: PushPayload, reply: String? = null, quiet: Boolean = false): Notification {
        tags += p.threadId
        PushNotifications.post(context, p, reply = reply, quiet = quiet)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val shown = manager.activeNotifications.firstOrNull { it.tag == p.threadId && it.id == 1 }?.notification
            if (shown != null && shown.`when` == p.timeMs) return shown
            SystemClock.sleep(20)
        }
        throw AssertionError("Production post did not appear in NotificationManager: ${p.eventId}")
    }

    private fun channelSnapshot(id: String): List<Any?> {
        val channel = manager.getNotificationChannel(id) ?: error("Missing existing channel $id")
        return listOf(channel.id, channel.importance, channel.sound, channel.audioAttributes,
            channel.shouldVibrate(), channel.vibrationPattern?.toList(), channel.lockscreenVisibility)
    }
}
