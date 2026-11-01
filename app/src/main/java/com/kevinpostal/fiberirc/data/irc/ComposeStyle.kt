@file:OptIn(ExperimentalSerializationApi::class)

package com.kevinpostal.fiberirc.data.irc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import java.time.Instant

/**
 * Outgoing-message styling picked in the compose "Aa" sheet. Stored in `DeviceSettingsStore.composeStyle`;
 * applied at send time by [ComposeStylePipeline]. Port of iOS `ComposeStyle.swift` (itself the web
 * client's `composeStyle.ts`) — field names, defaults and the `kind`-discriminated JSON shape match.
 */
@Serializable
enum class UnicodeStyle {
    @SerialName("fullwidth") FULLWIDTH,
    @SerialName("bold") BOLD,
    @SerialName("italic") ITALIC,
    @SerialName("boldItalic") BOLD_ITALIC,
    @SerialName("script") SCRIPT,
    @SerialName("fraktur") FRAKTUR,
    @SerialName("doubleStruck") DOUBLE_STRUCK,
    @SerialName("monospace") MONOSPACE,
    @SerialName("circled") CIRCLED,
    @SerialName("smallCaps") SMALL_CAPS,
    @SerialName("upsideDown") UPSIDE_DOWN,
    ;

    /** The web/iOS raw value ("boldItalic", …). */
    val rawValue: String
        get() = when (this) {
            FULLWIDTH -> "fullwidth"
            BOLD -> "bold"
            ITALIC -> "italic"
            BOLD_ITALIC -> "boldItalic"
            SCRIPT -> "script"
            FRAKTUR -> "fraktur"
            DOUBLE_STRUCK -> "doubleStruck"
            MONOSPACE -> "monospace"
            CIRCLED -> "circled"
            SMALL_CAPS -> "smallCaps"
            UPSIDE_DOWN -> "upsideDown"
        }
}

@Serializable
enum class CaseMode {
    @SerialName("none") NONE,
    @SerialName("upper") UPPER,
    @SerialName("lower") LOWER,
    @SerialName("mocking") MOCKING,
}

/**
 * Word-wrap limit for FIGlet/TheDraw banners. [OFF] sends every art row whole; [WORD] folds between words at
 * `width` columns so no glyph is ever cut mid-character.
 */
@Serializable
enum class ArtWrapMode {
    @SerialName("off") OFF,
    @SerialName("word") WORD,
}

@Serializable
data class ArtWrap(val mode: ArtWrapMode = ArtWrapMode.OFF, val width: Int = 80)

/** Clamp a user wrap width into the sane render range (20–400 columns). */
fun clampArtWrapWidth(width: Int): Int = minOf(400, maxOf(20, width))

@Serializable
@JsonClassDiscriminator("kind")
sealed class FontMode {
    @Serializable @SerialName("none")
    data object None : FontMode()

    /** [font] is a FIGlet font file stem under `assets/figlet` (e.g. "Standard"). */
    @Serializable @SerialName("figlet")
    data class Figlet(val font: String) : FontMode()

    /** [font] is a TheDraw font name from the packed catalogue. */
    @Serializable @SerialName("tdf")
    data class Tdf(val font: String) : FontMode()

    @Serializable @SerialName("unicode")
    data class Unicode(val style: UnicodeStyle) : FontMode()

    val kind: String
        get() = when (this) {
            None -> "none"
            is Figlet -> "figlet"
            is Tdf -> "tdf"
            is Unicode -> "unicode"
        }

    /** The art-font name for [Figlet]/[Tdf]; null otherwise. */
    val fontName: String?
        get() = when (this) {
            is Figlet -> font
            is Tdf -> font
            else -> null
        }
}

@Serializable
enum class RainbowPalette {
    @SerialName("classic") CLASSIC,
    @SerialName("smooth") SMOOTH,
}

@Serializable
@JsonClassDiscriminator("kind")
sealed class ColorMode {
    /** The background code shared by every non-[None] mode; null = no background. */
    abstract val bg: Int?

    @Serializable @SerialName("none")
    data object None : ColorMode() {
        override val bg: Int? get() = null
    }

    /** [fg]/[bg] are mIRC codes 0–98; [bg] null = no background. */
    @Serializable @SerialName("solid")
    data class Solid(val fg: Int, override val bg: Int? = null) : ColorMode()

    @Serializable @SerialName("rainbow")
    data class Rainbow(val palette: RainbowPalette, override val bg: Int? = null) : ColorMode()

    @Serializable @SerialName("gradient")
    data class Gradient(val from: Int, val to: Int, override val bg: Int? = null) : ColorMode()

    /** 1–8 codes, cycled per word. */
    @Serializable @SerialName("wordCycle")
    data class WordCycle(val colors: List<Int> = emptyList(), override val bg: Int? = null) : ColorMode()

    val kind: String
        get() = when (this) {
            None -> "none"
            is Solid -> "solid"
            is Rainbow -> "rainbow"
            is Gradient -> "gradient"
            is WordCycle -> "wordCycle"
        }
}

@Serializable
data class ComposeStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val monospace: Boolean = false,
    val reverse: Boolean = false,
    val caseMode: CaseMode = CaseMode.NONE,
    /** 0–3; 0 = off. */
    val zalgo: Int = 0,
    val color: ColorMode = ColorMode.None,
    val font: FontMode = FontMode.None,
    val artWrap: ArtWrap = ArtWrap(ArtWrapMode.OFF, 80),
) {
    fun isActive(): Boolean =
        bold || italic || underline || strikethrough || monospace || reverse ||
            caseMode != CaseMode.NONE || zalgo > 0 || color.kind != "none" || font.kind != "none"

    companion object {
        val Default = ComposeStyle()
    }
}

val RAINBOW_CLASSIC: List<Int> = listOf(4, 7, 8, 9, 11, 12, 13)
val RAINBOW_SMOOTH: List<Int> = listOf(52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63)

/** ISO-8601 string form of a [java.time.Instant]. */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * One entry in the Style Studio "Recent" row: a previously-applied style plus its label (generated once, at
 * apply time, by [composeStyleSummary]) and when it was applied. Max 8 kept (`DeviceSettingsStore`).
 */
@Serializable
data class ComposeStyleRecent(
    val style: ComposeStyle,
    val label: String,
    @Serializable(with = InstantIsoSerializer::class) val at: Instant,
) {
    val id: String get() = label
}

private val IRC_COLOR_NAMES = listOf(
    "White", "Black", "Navy", "Green", "Red", "Maroon", "Purple", "Olive",
    "Yellow", "Lime", "Teal", "Aqua", "Blue", "Fuchsia", "Gray", "Silver",
)

/** [code]'s display name — one of the 16 classic mIRC names, or the bare number for the extended palette. */
fun composeColorName(code: Int): String = if (code in 0..15) IRC_COLOR_NAMES[code] else code.toString()

private fun caseLabel(mode: CaseMode): String = when (mode) {
    CaseMode.NONE -> ""
    CaseMode.UPPER -> "UPPER"
    CaseMode.LOWER -> "lower"
    CaseMode.MOCKING -> "mOcKiNg"
}

/** Human-readable one-line summary of [style], e.g. "Bold · Colour Red · FIGlet Standard · Wrap 80". */
fun composeStyleSummary(style: ComposeStyle): String {
    val bits = ArrayList<String>()
    if (style.bold) bits += "Bold"
    if (style.italic) bits += "Italic"
    if (style.underline) bits += "Underline"
    if (style.strikethrough) bits += "Strike"
    if (style.monospace) bits += "Mono"
    if (style.reverse) bits += "Reverse"
    if (style.caseMode != CaseMode.NONE) bits += caseLabel(style.caseMode)
    if (style.zalgo > 0) bits += "Zalgo ${style.zalgo}"
    when (val c = style.color) {
        is ColorMode.Solid -> bits += "Colour ${composeColorName(c.fg)}"
        is ColorMode.Rainbow -> bits += "Rainbow ${if (c.palette == RainbowPalette.SMOOTH) "smooth" else "classic"}"
        is ColorMode.Gradient -> bits += "Gradient ${composeColorName(c.from)} → ${composeColorName(c.to)}"
        is ColorMode.WordCycle -> bits += "${c.colors.size} word colours"
        ColorMode.None -> Unit
    }
    when (val f = style.font) {
        is FontMode.Figlet -> bits += "FIGlet ${f.font}"
        is FontMode.Tdf -> bits += "TheDraw ${f.font}"
        is FontMode.Unicode -> bits += "Unicode ${f.style.rawValue}"
        FontMode.None -> Unit
    }
    if ((style.font.kind == "figlet" || style.font.kind == "tdf") && style.artWrap.mode == ArtWrapMode.WORD) {
        bits += "Wrap ${clampArtWrapWidth(style.artWrap.width)}"
    }
    return if (bits.isEmpty()) "No styling — messages send exactly as typed" else bits.joinToString(" · ")
}
