package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.prefs.TimestampFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Message timestamps in the account's format (Settings → Display → Timestamp format), with seconds when the device's
 * Show Seconds is on (ports `MessageTime`, `ChatDisplay.swift:8-56`). Main thread only, like the rows that use it.
 */
object MessageTime {
    /** 12h/24h use fixed patterns in a POSIX-like locale, so "PM" never turns into a localized marker. */
    private val posix = Locale.US
    private val twelveHour = DateTimeFormatter.ofPattern("h:mm a", posix)
    private val twelveHourSeconds = DateTimeFormatter.ofPattern("h:mm:ss a", posix)
    private val twentyFourHour = DateTimeFormatter.ofPattern("HH:mm", posix)
    private val twentyFourHourSeconds = DateTimeFormatter.ofPattern("HH:mm:ss", posix)

    /** `"<skeleton>|<locale>"` → ICU formatter for the automatic setting (iOS `setLocalizedDateFormatFromTemplate`). */
    private val automatic = HashMap<String, android.icu.text.DateFormat?>()
    private val columnWidths = HashMap<String, Dp>()

    fun string(
        time: Instant,
        format: TimestampFormat,
        showSeconds: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = when (format) {
        TimestampFormat.AUTO -> automatic(time, showSeconds, zone, locale)
        TimestampFormat.TWELVE_HOUR -> (if (showSeconds) twelveHourSeconds else twelveHour).format(time.atZone(zone))
        TimestampFormat.TWENTY_FOUR_HOUR ->
            (if (showSeconds) twentyFourHourSeconds else twentyFourHour).format(time.atZone(zone))
    }

    /** Automatic: 12- or 24-hour per the user's region (`"jm"` / `"jms"` skeletons). */
    private fun automatic(time: Instant, showSeconds: Boolean, zone: ZoneId, locale: Locale): String {
        val skeleton = if (showSeconds) "jms" else "jm"
        val f = automatic.getOrPut("$skeleton|${locale.toLanguageTag()}") {
            runCatching { android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale) }.getOrNull()
        }
        if (f != null) {
            f.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
            f.format(Date.from(time))?.let { return it }
        }
        // ICU missing (JVM unit tests): the locale's own short/medium time.
        val style = if (showSeconds) FormatStyle.MEDIUM else FormatStyle.SHORT
        return DateTimeFormatter.ofLocalizedTime(style).withLocale(locale).format(time.atZone(zone))
    }

    /** Whether [format] shows an AM/PM marker (automatic: the region's `"jm"` pattern has one). */
    fun isTwelveHour(format: TimestampFormat, locale: Locale = Locale.getDefault()): Boolean = when (format) {
        TimestampFormat.AUTO ->
            runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "jm") }.getOrNull()
                ?.contains('a') == true
        TimestampFormat.TWELVE_HOUR -> true
        TimestampFormat.TWENTY_FOUR_HOUR -> false
    }

    /**
     * Width of the chat's time column: the widest time in [format] ("88:88 AM", "88:88:88", …) in [style], rounded
     * up; cached by `"$sample|$fontName|$pointSize"` (plus font scale, which changes the measured size).
     */
    fun columnWidth(
        format: TimestampFormat,
        showSeconds: Boolean,
        style: TextStyle,
        measurer: TextMeasurer,
        density: Density,
    ): Dp {
        val sample = (if (showSeconds) "88:88:88" else "88:88") + (if (isTwelveHour(format)) " AM" else "")
        val key = "$sample|${style.fontFamily}|${style.fontWeight}|${style.fontSize}|${density.density}|${density.fontScale}"
        columnWidths[key]?.let { return it }
        val px = measurer.measure(AnnotatedString(sample), style, softWrap = false, maxLines = 1).size.width
        val width = ceil(px / density.density).dp
        columnWidths[key] = width
        return width
    }
}
