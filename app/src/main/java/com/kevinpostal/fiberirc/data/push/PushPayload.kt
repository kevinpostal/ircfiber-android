package com.kevinpostal.fiberirc.data.push

/**
 * One ircfiber.com FCM data message (backend `push/fcm.d` `fcmPayloadJson`; every value a string), ported from
 * `Shared/PushPayload.swift`. Read by [FiberIrcMessagingService] and the notification actions.
 *
 * @property threadId `"<networkId>:<lower-cased buffer>"` ([ConversationShortcuts.threadId]): one notification and
 *   one conversation shortcut per buffer.
 * @property buffer the buffer the notification opens: the channel, or the sender for a private message.
 * @property ownNick your nick on that network.
 * @property t event time, unix milliseconds as a decimal string ([timeMs]).
 */
data class PushPayload(
    val networkId: String,
    val buffer: String,
    val sender: String,
    val ownNick: String,
    val msgid: String,
    val t: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val threadId: String,
    val category: String,
    val badge: Int,
) {
    /** A channel message or invite; a private message's buffer is its sender (the server's `isPM ? n : p[0]`). */
    val isChannel: Boolean get() = !buffer.equals(sender, ignoreCase = true)

    /**
     * The body without its sender, for a conversation notification whose header already names the sender and the
     * channel: a channel `buffer_msg` body is "<sender>: text" (kept by the server for plain fallbacks), so that
     * prefix goes. A private message's or an invite's body is kept as it came.
     */
    val messageText: String
        get() {
            val prefix = "$sender: "
            if (category != CATEGORY_BUFFER_MESSAGE || !isChannel || !body.startsWith(prefix) || body.length <= prefix.length) {
                return body
            }
            return body.substring(prefix.length)
        }

    /** Event time in unix milliseconds (0 when absent or unparsable). */
    val timeMs: Long get() = t.toLongOrNull() ?: 0L

    /** The payload back as FCM data, for notification-action intents that rebuild the notification. */
    fun toData(): Map<String, String> = mapOf(
        "networkId" to networkId, "buffer" to buffer, "sender" to sender, "ownNick" to ownNick, "msgid" to msgid,
        "t" to t, "title" to title, "subtitle" to subtitle, "body" to body, "threadId" to threadId,
        "category" to category, "badge" to badge.toString(),
    )

    companion object {
        /** A mention, a private message, or any message in a notify-all buffer: offers Reply. */
        const val CATEGORY_BUFFER_MESSAGE = "buffer_msg"

        /** An INVITE to the channel `buffer`: offers Join. */
        const val CATEGORY_CHANNEL_INVITE = "channel_invite"

        /** The FCM data keys [parse] reads. */
        val KEYS = listOf(
            "networkId", "buffer", "sender", "ownNick", "msgid", "t", "title", "subtitle", "body", "threadId",
            "category", "badge",
        )

        /** Null unless the category is known and `networkId`, `buffer` and `sender` are present. */
        fun parse(data: Map<String, String>): PushPayload? {
            val category = data["category"]?.takeIf { it == CATEGORY_BUFFER_MESSAGE || it == CATEGORY_CHANNEL_INVITE }
                ?: return null
            val networkId = data["networkId"]?.takeIf { it.isNotEmpty() } ?: return null
            val buffer = data["buffer"]?.takeIf { it.isNotEmpty() } ?: return null
            val sender = data["sender"]?.takeIf { it.isNotEmpty() } ?: return null
            return PushPayload(
                networkId = networkId,
                buffer = buffer,
                sender = sender,
                ownNick = data["ownNick"].orEmpty(),
                msgid = data["msgid"].orEmpty(),
                t = data["t"].orEmpty(),
                title = data["title"].orEmpty(),
                subtitle = data["subtitle"].orEmpty(),
                body = data["body"].orEmpty(),
                threadId = data["threadId"]?.takeIf { it.isNotEmpty() } ?: ConversationShortcuts.threadId(networkId, buffer),
                category = category,
                badge = data["badge"]?.toIntOrNull() ?: 0,
            )
        }
    }
}
