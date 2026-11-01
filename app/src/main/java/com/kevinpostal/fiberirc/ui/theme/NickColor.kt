package com.kevinpostal.fiberirc.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// The colour a nick is drawn in, the same on every client (IRCCloud palette, web utils.ts).
// Port of iOS Shared/NickColor.swift + Theme.nickColor.

/** IRCCloud dark nick palette (web utils.ts NICK_COLORS), raw `0xRRGGBB`. */
val NickPalette: List<Int> = listOf(
    0xdeb887, 0xffd700, 0xff9166, 0xfa8072, 0xff8c00, 0x00ff00, 0xffff00, 0xbdb76b, 0x9acd32,
    0x32cd32, 0x8fbc8f, 0x3cb371, 0x66cdaa, 0x20b2aa, 0x40e0d0, 0x00ffff, 0x00bfff, 0x87ceeb,
    0x339cff, 0x6495ed, 0xb2a9e5, 0xff69b4, 0xda70d6, 0xee82ee, 0xd68fff, 0xff00ff, 0xffb6c1,
)

private val paletteColors: List<Color> = NickPalette.map { Color(0xFF000000.toInt() or it) }

/**
 * Palette index for [nick]: port of utils.ts `nickColorIndex` as iOS does it — JS int32 shifts of the
 * truncated accumulator, but the accumulator itself is a double (Int64 here), so `- n` is not wrapped.
 * Hashes the lowercased UTF-16 code units.
 */
fun nickColorIndex(nick: String): Int {
    var n = 0L
    for (u in nick.lowercase()) {
        val i32 = n.toInt()
        n = u.code.toLong() + (i32 shl 6).toLong() + (i32 shl 16).toLong() - n
    }
    return abs(n % NickPalette.size).toInt()
}

/** [nick]'s palette colour made [legible] for the appearance. */
fun nickColor(nick: String, dark: Boolean): Color = legible(paletteColors[nickColorIndex(nick)], dark)

/**
 * A letter avatar's fill in notifications: the palette colour at 80 % HSB brightness (IRCCloud's
 * light-theme avatars), dark enough for a white letter. Appearance-independent.
 */
fun notificationFill(nick: String): Color = scaleBrightness(paletteColors[nickColorIndex(nick)], 0.8)
