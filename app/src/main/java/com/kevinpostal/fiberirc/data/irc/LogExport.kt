package com.kevinpostal.fiberirc.data.irc

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kevinpostal.fiberirc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Export Log (gear menu, ports `ios/UI/Actions/LogExport.swift`): loads the buffer's whole history, writes it as
 * plain text to a cache file and offers it through the share sheet (`ACTION_SEND` via the app's `FileProvider`,
 * authority `<applicationId>.fileprovider`, cache-path root).
 */
object LogExport {
    /**
     * One line per row, oldest first: `[yyyy-MM-dd HH:mm:ss] <nick> text`, `* nick action`, `-nick- notice` or
     * `*** event`, IRC formatting stripped. Deleted messages are left out.
     */
    fun text(lines: List<IrcLine>, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = formatter("yyyy-MM-dd HH:mm:ss", zone)
        val out = StringBuilder()
        for (l in lines) {
            if (l.redacted) continue
            out.append('[').append(stamp.format(l.time)).append("] ").append(body(l)).append('\n')
        }
        return out.toString()
    }

    /** `<networkName>-<buffer>-<yyyyMMdd>.txt`; path separators in the names become `_`. */
    fun filename(network: String, buffer: String, date: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        fun safe(s: String) = s.replace("/", "_").replace(":", "_")
        return "${safe(network)}-${safe(buffer)}-${formatter("yyyyMMdd", zone).format(date)}.txt"
    }

    /**
     * Walks [buffer] back through [IrcClient.loadAllHistory] (≤20 CHATHISTORY pages; returns at once offline or
     * when nothing older exists), then writes the log to the cache dir. Call on the client's (main) thread.
     */
    suspend fun prepare(context: Context, client: IrcClient, buffer: IrcBuffer): Result<File> {
        suspendCancellableCoroutine { cont -> client.loadAllHistory(buffer) { if (cont.isActive) cont.resume(Unit) } }
        val contents = text(buffer.lines)
        val name = filename(client.settings.networkName, buffer.name, Instant.now())
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                File(dir, name).apply { writeText(contents, Charsets.UTF_8) }
            }
        }
    }

    /** The share sheet for an exported [file]. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    private fun body(l: IrcLine): String {
        val text = IrcFormatting.strip(l.text)
        val nick = l.sender ?: ""
        return when (l.kind) {
            IrcLineKind.MESSAGE -> "<$nick> $text"
            IrcLineKind.ACTION -> "* $nick $text"
            IrcLineKind.NOTICE -> "-$nick- $text"
            IrcLineKind.EVENT, IrcLineKind.ERROR -> "*** $text"
        }
    }

    private fun formatter(pattern: String, zone: ZoneId): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(zone)
}
