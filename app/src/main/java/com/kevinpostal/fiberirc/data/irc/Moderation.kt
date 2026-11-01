package com.kevinpostal.fiberirc.data.irc

import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Report/block logic shared by the chat and the member list (ports `ios/UI/Moderation.swift` and
 * `SiteAPI.reportMessage`). The dialogs live in `ui.actions.ModerationDialogs`.
 */
object Moderation {
    fun blockTitle(nick: String): String = "Block $nick?"
    fun blockMessage(nick: String): String = "You won't see messages from $nick in this app or on ircfiber.com."
    fun blockFailedTitle(nick: String): String = "Couldn't block $nick"
    fun unblockFailedTitle(nick: String): String = "Couldn't unblock $nick"

    const val REPORT_TITLE = "Report Message"
    fun reportPrompt(nick: String): String = "Send this message from $nick to the IRC Fiber moderators?"
    const val REPORT_SENT_TITLE = "Report Sent"
    const val REPORT_SENT_MESSAGE = "Report sent. Moderators review reports within 24 hours."
    const val REPORT_FAILED_TITLE = "Report not sent"

    const val USER_AGENT = "FiberIRC Android"
    private const val TITLE_MAX = 120
    private const val BODY_MAX = 5000

    suspend fun block(blockList: BlockListStore, nick: String): Result<Unit> = attempt { blockList.block(nick) }

    suspend fun unblock(blockList: BlockListStore, nick: String): Result<Unit> = attempt { blockList.unblock(nick) }

    /** `POST /api/support/issues` body; [note] is trimmed, blank → "(none)". */
    fun reportPayload(
        nick: String,
        text: String,
        buffer: String,
        network: String,
        time: Instant,
        note: String?,
        appVersion: String = "$USER_AGENT ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    ): JsonObject {
        val trimmed = note?.trim().orEmpty()
        val stamp = DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        val title = "Report: $nick in $buffer".take(TITLE_MAX)
        val body = ("Reported message from $nick in $buffer on $network at $stamp:\n\n$text" +
            "\n\nReporter note: ${trimmed.ifEmpty { "(none)" }}").take(BODY_MAX)
        return buildJsonObject {
            put("kind", "other")
            put("title", title)
            put("body", body)
            put("context", buildJsonObject {
                put("appVersion", appVersion)
                put("bufferName", buffer)
                put("userAgent", USER_AGENT)
            })
        }
    }

    /** Files a moderation report into the staff queue (+ #support). A body-less 429 is [SiteApiError.RateLimited]. */
    suspend fun reportMessage(
        api: SiteApi,
        nick: String,
        text: String,
        buffer: String,
        network: String,
        time: Instant,
        note: String?,
    ): Result<Unit> =
        api.postJson(listOf("api", "support", "issues"), reportPayload(nick, text, buffer, network, time, note))
            .map { }
            .recoverCatching { e -> throw if (e is SiteApiError.Server && e.code == 429) SiteApiError.RateLimited else e }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
