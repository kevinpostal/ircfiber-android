package com.kevinpostal.fiberirc.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.api.attempt
import com.kevinpostal.fiberirc.data.diagnostics.AppLog
import com.kevinpostal.fiberirc.ui.theme.Appearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → Export Diagnostics (ports `Diagnostics.swift`'s export): app and device facts plus this launch's
 * log ([AppLog], newest 2000 lines) as one text file in the cache dir, offered through the share sheet. Nothing
 * leaves the device unless the user shares it. iOS's MetricKit section has no Android counterpart and is left out.
 */
object Diagnostics {
    /** FileProvider authority declared in the manifest (`res/xml/file_paths.xml`: the whole cache dir). */
    val authority: String get() = "${BuildConfig.APPLICATION_ID}.fileprovider"

    /** App and device facts for the report. */
    fun header(context: Context, appearance: Appearance, showAvatars: Boolean): List<Pair<String, String>> = listOf(
        "App version" to BuildConfig.VERSION_NAME,
        "Build" to BuildConfig.VERSION_CODE.toString(),
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Device model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Locale" to (context.resources.configuration.locales[0] ?: Locale.getDefault()).toLanguageTag(),
        "Appearance" to appearance.name.lowercase(),
        "Show avatars" to if (showAvatars) "on" else "off",
    )

    fun report(header: List<Pair<String, String>>, logLines: List<String>): String {
        val out = mutableListOf("== FiberIRC diagnostics ==")
        out += header.map { "${it.first}: ${it.second}" }
        out += listOf("", "== Log (this launch) ==")
        out += logLines.ifEmpty { listOf("(none)") }
        return out.joinToString("\n") + "\n"
    }

    /** `yyyyMMdd'T'HHmmss'Z'` in UTC (file names sort by age). */
    fun stamp(time: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(time.withZoneSameInstant(ZoneOffset.UTC))

    /** Writes the report off the main thread, then opens the share sheet; a failure is returned for the caller's alert. */
    suspend fun export(context: Context, header: List<Pair<String, String>>): Result<Unit> = attempt {
        val file = withContext(Dispatchers.IO) {
            val f = File(context.cacheDir, "fiberirc-diagnostics-${stamp()}.txt")
            f.writeText(report(header, AppLog.lines()))
            f
        }
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
