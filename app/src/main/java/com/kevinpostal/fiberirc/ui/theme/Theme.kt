package com.kevinpostal.fiberirc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Design tokens ported from iOS FiberIRC/UI/Theme.swift (itself mirrored from the ircfiber web client).
// Dark = the web's midnight theme (parity); light = GitHub Primer-derived. The iOS high-contrast
// (`hcDark`/`hcLight`) variants are omitted: Android has no system "Increase Contrast" signal to drive them.

/** Device-local appearance override (iOS DisplaySettings.appearance). Missing/unknown decodes to [SYSTEM]. */
enum class Appearance { SYSTEM, LIGHT, DARK }

/** Every dynamic colour token of `Theme.swift`, resolved for one appearance. */
@Immutable
data class FiberIrcColors(
    // Surfaces
    val background: Color,
    val panel: Color,
    val panelAlt: Color,
    val inputArea: Color,
    val inputPill: Color,
    val inputPillBorder: Color,
    val field: Color,
    val hover: Color,
    val border: Color,
    val borderDark: Color,
    val groupedBackground: Color,
    val groupedCell: Color,
    val rowHighlight: Color,
    val selfRow: Color,
    val noticeRow: Color,
    val unreadBar: Color,
    val unreadBarText: Color,
    val seenDividerLine: Color,
    val seenDividerText: Color,
    val drawerBackground: Color,
    val networkRow: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val heading: Color,
    val chatText: Color,
    val logText: Color,
    val logArrow: Color,
    val noticeText: Color,
    val bufferText: Color,
    val bufferUnreadText: Color,
    val bufferInactiveText: Color,
    // Brand / accent / status
    val link: Color,
    val fiber: Color,
    val submitGreen: Color,
    val online: Color,
    val offlineError: Color,
    val eventNick: Color,
    val eventTopic: Color,
    val artBackground: Color,
    val badge: Color,
    val accent: Color,
    val accentActive: Color,
    /** Letter on a nick-coloured avatar (nick colours are darkened in light appearance). */
    val avatarText: Color,
    /** Which appearance these tokens resolve: feeds [legible], [nickColor], [prefixColor]. */
    val dark: Boolean,
) {
    /** Navigation bar: same values as [networkRow] (IRCCloud Midnight / Dawn bar colour). */
    val navBar: Color get() = networkRow
    val offline: Color get() = offlineError
    val error: Color get() = offlineError
}

private fun hex(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

val FiberIrcDarkColors = FiberIrcColors(
    background = hex(0x000000),
    panel = hex(0x1a1d21),
    panelAlt = hex(0x1b2432),
    inputArea = hex(0x2a2d33),
    inputPill = hex(0x1e2126),
    inputPillBorder = hex(0x1e2126),
    field = hex(0x0d1117),
    hover = hex(0x1a1a1a),
    border = hex(0x2c2f35),
    borderDark = hex(0x1a1c20),
    groupedBackground = hex(0x000000),
    groupedCell = hex(0x1b2432),
    rowHighlight = hex(0x2b2b2b),
    selfRow = hex(0x1f1f1f),
    noticeRow = hex(0x262626),
    unreadBar = hex(0x595959),
    unreadBarText = hex(0xcccccc),
    seenDividerLine = hex(0x4d4d4d),
    seenDividerText = hex(0xd2d8e0),
    drawerBackground = hex(0x000000),
    networkRow = hex(0x262626),
    textPrimary = hex(0xd1d5db),
    textSecondary = hex(0x8b949e),
    textTertiary = hex(0x6e7681),
    heading = hex(0xecf2f8),
    chatText = hex(0xcccccc),
    logText = hex(0x737373),
    logArrow = hex(0xb3b3b3),
    noticeText = hex(0xbfbfbf),
    bufferText = hex(0xd9d9d9),
    bufferUnreadText = hex(0xf2f2f2),
    bufferInactiveText = hex(0x999999),
    link = hex(0x58a6ff),
    fiber = hex(0x67e8f9),
    submitGreen = hex(0x238636),
    online = hex(0x3fb950),
    offlineError = hex(0xf85149),
    eventNick = hex(0xf59e0b),
    eventTopic = hex(0x67e8f9),
    artBackground = hex(0x000000),
    badge = hex(0xff1f1a),
    accent = hex(0x1e72ff),
    accentActive = hex(0x1158cc),
    avatarText = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.85f),
    dark = true,
)

val FiberIrcLightColors = FiberIrcColors(
    background = hex(0xffffff),
    panel = hex(0xf6f8fa),
    panelAlt = hex(0xeef1f5),
    inputArea = hex(0xf6f8fa),
    inputPill = hex(0xffffff),
    inputPillBorder = hex(0xd0d7de),
    field = hex(0xffffff),
    hover = hex(0xf3f4f6),
    border = hex(0xd0d7de),
    borderDark = hex(0xe5e8eb),
    groupedBackground = hex(0xf2f2f7),
    groupedCell = hex(0xffffff),
    rowHighlight = hex(0xddf4ff),
    selfRow = hex(0xe2edff),
    noticeRow = hex(0xd9e7ff),
    unreadBar = hex(0xd9e8ff),
    unreadBarText = hex(0x002179),
    seenDividerLine = hex(0xd0d7de),
    seenDividerText = hex(0x57606a),
    drawerBackground = hex(0xd9e7ff),
    networkRow = hex(0xf2f7fc),
    textPrimary = hex(0x1f2328),
    textSecondary = hex(0x59636e),
    textTertiary = hex(0x6e7781),
    heading = hex(0x1f2328),
    chatText = hex(0x24292f),
    logText = hex(0x6e7781),
    logArrow = hex(0x57606a),
    noticeText = hex(0x57606a),
    bufferText = hex(0x1d40ff),
    bufferUnreadText = hex(0x1d40ff),
    bufferInactiveText = hex(0x9cc7ff),
    link = hex(0x0969da),
    fiber = hex(0x0891b2),
    submitGreen = hex(0x1f883d),
    online = hex(0x1a7f37),
    offlineError = hex(0xcf222e),
    eventNick = hex(0xa36907),
    eventTopic = hex(0x07809e),
    artBackground = hex(0x000000),
    badge = hex(0xff1f1a),
    accent = hex(0x1e72ff),
    accentActive = hex(0x1158cc),
    avatarText = Color(red = 1f, green = 1f, blue = 1f, alpha = 0.92f),
    dark = false,
)

fun fiberIrcColors(dark: Boolean): FiberIrcColors = if (dark) FiberIrcDarkColors else FiberIrcLightColors

/** Static brand colours, identical in both appearances. */
object Brand {
    val brandCyan = hex(0x67e8f9) // logo tile
    val fiberHi = hex(0xa5f3fc)
    val fiberDeep = hex(0x0e7490)
    val violet = hex(0x8b6ef8) // Bouncer tile
    val amber = hex(0xf59e0b) // Highlight Words tile
    val teal = hex(0x2dd4bf) // Blocked Users tile
}

val LocalFiberIrcColors = staticCompositionLocalOf { FiberIrcDarkColors }

/** True when [appearance] resolves to dark (SYSTEM follows the OS). */
@Composable
@ReadOnlyComposable
fun isDarkAppearance(appearance: Appearance): Boolean = when (appearance) {
    Appearance.SYSTEM -> isSystemInDarkTheme()
    Appearance.LIGHT -> false
    Appearance.DARK -> true
}

/** Provides [LocalFiberIrcColors] for [appearance] plus a Material3 scheme built from the same tokens. */
@Composable
fun FiberIrcTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val colors = fiberIrcColors(isDarkAppearance(appearance))
    val scheme = if (colors.dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = Color.White,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.groupedCell, onSurface = colors.textPrimary,
            surfaceVariant = colors.panel, onSurfaceVariant = colors.textSecondary,
            outline = colors.border, error = colors.offlineError,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = Color.White,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.groupedCell, onSurface = colors.textPrimary,
            surfaceVariant = colors.panel, onSurfaceVariant = colors.textSecondary,
            outline = colors.border, error = colors.offlineError,
        )
    }
    CompositionLocalProvider(LocalFiberIrcColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Text colour for a `*status` connection-log NOTICE tagged with an engine phase. `null` for an untagged
 * notice or any other tag (e.g. `"motd"`): the caller falls back to `noticeText`/`logText`.
 */
fun serverLogColor(tag: String?, c: FiberIrcColors): Color? = when (tag) {
    "connecting", "registering", "sasl_start" -> c.eventTopic
    "welcome", "sasl_done" -> c.online
    "warn", "retry" -> c.eventNick
    "error", "disabled", "sasl_fail", "fail" -> c.offlineError
    else -> null
}

// MARK: Contrast

/** Exact 8-bit sRGB components as Doubles (UIKit `getRed` on a hex colour), not the Float accessors. */
private fun Color.rgb(): DoubleArray {
    val argb = toArgb()
    return doubleArrayOf(((argb shr 16) and 0xff) / 255.0, ((argb shr 8) and 0xff) / 255.0, (argb and 0xff) / 255.0)
}

private fun luminance(c: Color): Double = c.rgb().let { luminance(it[0], it[1], it[2]) }

/** WCAG 2.x contrast ratio (1…21) of two colours (alpha ignored). */
fun contrastRatio(a: Color, b: Color): Double = contrastRatio(luminance(a), luminance(b))

private fun contrastRatio(la: Double, lb: Double): Double = (max(la, lb) + 0.05) / (min(la, lb) + 0.05)

private fun luminance(r: Double, g: Double, b: Double): Double {
    fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}

/** UIKit `getHue(_:saturation:brightness:alpha:)`: HSB components in 0…1. */
internal fun rgbToHsb(r: Double, g: Double, b: Double): DoubleArray {
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val s = if (mx == 0.0) 0.0 else d / mx
    var h = when {
        d == 0.0 -> 0.0
        mx == r -> ((g - b) / d) / 6.0
        mx == g -> ((b - r) / d + 2.0) / 6.0
        else -> ((r - g) / d + 4.0) / 6.0
    }
    if (h < 0) h += 1.0
    return doubleArrayOf(h, s, mx)
}

/** UIKit `UIColor(hue:saturation:brightness:alpha:)`: RGB components in 0…1. */
internal fun hsbToRgb(h: Double, s: Double, v: Double): DoubleArray {
    if (s == 0.0) return doubleArrayOf(v, v, v)
    val hh = (h - floor(h)) * 6.0
    val i = floor(hh).toInt() % 6
    val f = hh - floor(hh)
    val p = v * (1 - s)
    val q = v * (1 - s * f)
    val t = v * (1 - s * (1 - f))
    return when (i) {
        0 -> doubleArrayOf(v, t, p)
        1 -> doubleArrayOf(q, v, p)
        2 -> doubleArrayOf(p, v, t)
        3 -> doubleArrayOf(p, q, v)
        4 -> doubleArrayOf(t, p, v)
        else -> doubleArrayOf(v, p, q)
    }
}

private fun rgbColor(rgb: DoubleArray, alpha: Float, quantise: (Double) -> Int = { it.roundToInt() }): Color = Color(
    red = quantise(rgb[0] * 255),
    green = quantise(rgb[1] * 255),
    blue = quantise(rgb[2] * 255),
    alpha = (alpha * 255).roundToInt(),
)

private val legibleCache = ConcurrentHashMap<Int, Color>()

/**
 * A colour tuned for the black canvas (mIRC, nick, prefix colours) made readable in both appearances:
 * dark returns [c] unchanged (web parity); light keeps the hue, raises saturation to ≥0.6 (when ≥0.15)
 * and darkens brightness in 0.02 steps until it reaches 4.5:1 on white. Port of `Theme.legible`.
 */
fun legible(c: Color, dark: Boolean): Color {
    if (dark) return c
    val key = c.toArgb() and 0xffffff
    return legibleCache.getOrPut(key) { lightVariant(c) }
}

private fun lightVariant(c: Color): Color {
    val hsb = c.rgb().let { rgbToHsb(it[0], it[1], it[2]) }
    val h = hsb[0]
    var s = hsb[1]
    var b = hsb[2]
    if (s >= 0.15) s = max(s, 0.6)
    var v = hsbToRgb(h, s, b)
    while (contrastRatio(luminance(v[0], v[1], v[2]), 1.0) < 4.5 && b > 0) {
        b = max(0.0, b - 0.02)
        v = hsbToRgb(h, s, b)
    }
    // iOS keeps the exact CGFloat colour; Compose stores 8 bits per channel. Rounding can tip a colour that
    // just reached 4.5:1 back under it (e.g. green 128.4999… → 129), so round down (darker) in that case.
    val rounded = rgbColor(v, c.alpha)
    return if (contrastRatio(rounded, Color.White) >= 4.5 || b <= 0) rounded
    else rgbColor(v, c.alpha) { floor(it).toInt() }
}

/** [c] at the given HSB brightness factor, same hue/saturation/alpha. */
internal fun scaleBrightness(c: Color, factor: Double): Color {
    val hsb = c.rgb().let { rgbToHsb(it[0], it[1], it[2]) }
    return rgbColor(hsbToRgb(hsb[0], hsb[1], hsb[2] * factor), c.alpha)
}

// MARK: Members

// Member prefix colours (web MemberList.svelte:169-174), raw; [prefixColor] applies [legible].
private val prefixPalette: Map<Char, Color> = mapOf(
    '!' to hex(0xff5f52), '*' to hex(0xff5f52), '~' to hex(0xf2bd2b), '&' to hex(0xb083f0),
    '@' to hex(0xf2585b), '%' to hex(0xe08a2e), '+' to hex(0x3fd12a),
)

/** Colour for a member's highest prefix (first char of [prefixes]); no/unknown prefix → `chatText`. */
fun prefixColor(prefixes: String, c: FiberIrcColors): Color =
    prefixes.firstOrNull()?.let { prefixPalette[it] }?.let { legible(it, c.dark) } ?: c.chatText

/**
 * Member-list rank band by highest prefix (iOS `Theme.memberCategory`). [title] is the group header text.
 * Colours: IRCCloud's member group bands (`UsersTableView.m`), Midnight (dark) / Dawn (light).
 */
enum class MemberCategory(
    val title: String,
    private val bgDark: Int,
    private val bgLight: Int,
    private val headingDark: Int,
    private val headingLight: Int,
) {
    OPERS("Opers", 0x43260f, 0xffffd1, 0xfdbeb4, 0xe02305),
    OWNERS("Owners", 0x3f3722, 0xffffd1, 0xffebb4, 0xe7aa00),
    ADMINS("Admins", 0x281c31, 0xedccff, 0xc872ff, 0x6500a5),
    OPS("Ops", 0x451c1c, 0xfef2f2, 0xf4a9aa, 0xba1719),
    HALFOPS("Halfops", 0x372e20, 0xfffaf2, 0xffbf82, 0xb55900),
    VOICED("Voiced", 0x27331f, 0xf4ffed, 0x99ff7e, 0x25b100),
    MEMBERS("Members", 0x1b2432, 0xd9e7ff, 0x88b2dd, 0x666666),
    ;

    /** Group header band. */
    fun background(dark: Boolean): Color = hex(if (dark) bgDark else bgLight)

    /** Group header title and count colour; the light heading goes through [legible]. */
    fun heading(dark: Boolean): Color = if (dark) hex(headingDark) else legible(hex(headingLight), dark = false)

    companion object {
        fun of(prefixes: String): MemberCategory = when (prefixes.firstOrNull()) {
            '!', '*' -> OPERS
            '~' -> OWNERS
            '&' -> ADMINS
            '@' -> OPS
            '%' -> HALFOPS
            '+' -> VOICED
            else -> MEMBERS
        }
    }
}
