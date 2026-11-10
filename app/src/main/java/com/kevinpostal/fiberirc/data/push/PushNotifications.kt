package com.kevinpostal.fiberirc.data.push

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.kevinpostal.fiberirc.MainActivity
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.theme.notificationFill

/**
 * Full previews are conversation notifications with sender avatars and retained MessagingStyle history.
 * Redacted previews use only the server's title/body, never previous history or conversation enrichment.
 * `buffer_msg` offers Reply (sent by [PushReplyReceiver] without opening the app when the buffer is positively
 * unencrypted, else kept as a sealed draft that continues in the app), `channel_invite` offers Join;
 * tapping opens the buffer.
 */
internal object PushNotifications {
    const val KEY_REPLY = "reply_text"
    const val ACTION_REPLY = "com.kevinpostal.fiberirc.action.PUSH_REPLY"

    /** One notification per tag ([PushPayload.threadId]); the id is constant. */
    private const val ID = 1
    private const val AVATAR_PX = 192

    /** The two channels, one per push category (iOS `PushRegistrar.categories`). */
    fun registerChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(PushPayload.CATEGORY_BUFFER_MESSAGE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Messages")
                    .setDescription("Mentions, private messages and buffers set to notify on every message")
                    .build(),
                NotificationChannelCompat.Builder(PushPayload.CATEGORY_CHANNEL_INVITE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Channel invites")
                    .setDescription("Invitations to join a channel")
                    .build(),
            ),
        )
    }

    /**
     * Shows [p], appended to its buffer's notification. [reply] (a Reply sent from the notification) is added as your
     * own message and the update stays quiet; [quiet] re-posts without alerting (a Reply that failed).
     */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() is false without POST_NOTIFICATIONS
    fun post(context: Context, p: PushPayload, reply: CharSequence? = null, quiet: Boolean = false) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val time = p.timeMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val open = openIntent(context, p)
        val builder = NotificationCompat.Builder(context, p.category)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setNumber(p.badge)
            .setWhen(time)
            .setShowWhen(true)
            .setOnlyAlertOnce(reply != null || quiet)
            .setSilent(p.silent || quiet || reply != null)
        if (p.preview == "full") {
            val me = Person.Builder().setName(p.ownNick.ifEmpty { "Me" })
                .apply { if (p.ownNick.isNotEmpty()) setKey(p.ownNick) }.build()
            val sender = Person.Builder()
                .setName(p.sender)
                .setIcon(letterAvatar(p.sender))
                .setKey("${p.networkId}:${p.sender}")
                .build()
            val style = existingStyle(context, p.threadId) ?: NotificationCompat.MessagingStyle(me)
            style.conversationTitle = if (p.isChannel) p.buffer else null
            style.isGroupConversation = p.isChannel
            val text = p.messageText
            val known = style.messages.any { it.timestamp == time && it.text?.toString() == text && it.person?.key == sender.key }
            if (!known) style.addMessage(text, time, sender)
            if (reply != null) style.addMessage(reply, System.currentTimeMillis(), null as Person?)
            builder.setStyle(style)
                .setContentTitle(p.title.ifEmpty { p.sender })
                .setContentText(text)
                .setSubText(p.subtitle.ifEmpty { null })
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setShortcutId(p.threadId)
                .setLocusId(LocusIdCompat(p.threadId))
        } else {
            // Do not reuse the previous MessagingStyle: even its retained history and people can reveal content.
            builder.setContentTitle(p.title)
                .setContentText(p.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
        }
        when (p.category) {
            PushPayload.CATEGORY_BUFFER_MESSAGE -> builder.addAction(replyAction(context, p))
            PushPayload.CATEGORY_CHANNEL_INVITE -> builder.addAction(
                NotificationCompat.Action.Builder(null as IconCompat?, "Join", open).build(),
            )
        }
        nm.notify(p.threadId, ID, builder.build())
    }

    /** The buffer's notification still showing, so a new message joins its conversation. */
    private fun existingStyle(context: Context, threadId: String): NotificationCompat.MessagingStyle? {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        val active = runCatching { nm.activeNotifications }.getOrNull() ?: return null
        val shown = active.firstOrNull { it.tag == threadId && it.id == ID } ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown.notification)
    }

    /** Opens the buffer (`fiberirc://buffer/…`) in the running `MainActivity`, or starts it. */
    private fun openIntent(context: Context, p: PushPayload): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppRouter.bufferUri(p.networkId, p.buffer)), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            p.threadId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Reply in place: the typed text goes to [PushReplyReceiver] with the payload to rebuild the notification. */
    private fun replyAction(context: Context, p: PushPayload): NotificationCompat.Action {
        val intent = Intent(context, PushReplyReceiver::class.java)
            .setAction(ACTION_REPLY)
            .setData(Uri.parse(AppRouter.bufferUri(p.networkId, p.buffer)))
        p.toData().forEach { (k, v) -> intent.putExtra(k, v) }
        // RemoteInput fills the intent in, so it must be mutable (API 31+); the intent itself is explicit.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, p.threadId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        return NotificationCompat.Action.Builder(null as IconCompat?, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    /** IRCCloud's generated avatar (`LetterAvatar.png`): the nick's darkened palette colour, a white bold first letter. */
    private fun letterAvatar(nick: String): IconCompat {
        val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(notificationFill(nick).toArgb())
        val letter = nick.firstOrNull()?.let { first ->
            val end = if (Character.isHighSurrogate(first) && nick.length > 1) 2 else 1
            nick.substring(0, end).uppercase()
        }.orEmpty()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = AVATAR_PX * 0.65f
            textAlign = Paint.Align.CENTER
        }
        val baseline = AVATAR_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, AVATAR_PX / 2f, baseline, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
