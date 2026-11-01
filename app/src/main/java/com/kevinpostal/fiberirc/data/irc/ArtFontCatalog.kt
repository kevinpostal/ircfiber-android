package com.kevinpostal.fiberirc.data.irc

import android.content.res.AssetManager
import java.io.IOException

/**
 * Where the bundled art fonts (the `.flf` files under `assets/figlet`, `assets/TdfFonts.bin`) are read from. Installed once at app
 * start (`ArtFontAssets.install(assets)` in `FiberIrcApp.onCreate`); unit tests install a filesystem source over
 * `src/main/assets`. Until installed, every FIGlet/TheDraw render fails and the pipeline drops that art (iOS reads
 * the same files from `Bundle.main`, which needs no install step).
 */
object ArtFontAssets {
    interface Source {
        /** File names directly under [dir]. */
        fun list(dir: String): List<String>

        /** Bytes of [path], or null when missing. */
        fun open(path: String): ByteArray?
    }

    @Volatile
    private var source: Source? = null

    fun install(source: Source) {
        this.source = source
    }

    fun install(assets: AssetManager) = install(object : Source {
        override fun list(dir: String): List<String> =
            try { assets.list(dir)?.toList().orEmpty() } catch (_: IOException) { emptyList() }

        override fun open(path: String): ByteArray? =
            try { assets.open(path).use { it.readBytes() } } catch (_: IOException) { null }
    })

    fun list(dir: String): List<String> = source?.list(dir).orEmpty()

    fun open(path: String): ByteArray? = source?.open(path)
}

/** Port of iOS `ArtFontCatalog.swift`: one catalogue over both art-font families for the Style Studio picker. */
enum class ArtFontKind { FIGLET, TDF }

enum class FontSortOrder { ROWS, NAME }

data class ArtFontEntry(
    val kind: ArtFontKind,
    /** Value stored in [FontMode.Figlet] / [FontMode.Tdf]. */
    val name: String,
    /** Glyph rows one line of text occupies (0 = unknown). */
    val rows: Int,
    /** Secondary label — the TheDraw source file; empty for FIGlet. */
    val note: String,
)

object ArtFontCatalog {
    private val cache = java.util.concurrent.ConcurrentHashMap<ArtFontKind, List<ArtFontEntry>>()

    /** Every font of [kind], in name order; parsed once per process (once the assets are installed). */
    fun list(kind: ArtFontKind): List<ArtFontEntry> {
        cache[kind]?.let { return it }
        val entries = when (kind) {
            ArtFontKind.FIGLET -> FigletCatalog.names.map { ArtFontEntry(ArtFontKind.FIGLET, it, FigletCatalog.rows(it), "") }
            ArtFontKind.TDF -> runCatching { TdfCatalog.listFonts() }.getOrDefault(emptyList())
                .map { ArtFontEntry(ArtFontKind.TDF, it.name, it.height, it.file) }
        }
        if (entries.isNotEmpty()) cache[kind] = entries
        return entries
    }

    /** Row size first (shortest fonts fit a chat window), then name. */
    fun sort(entries: List<ArtFontEntry>, by: FontSortOrder): List<ArtFontEntry> =
        entries.sortedWith { a, b ->
            if (by == FontSortOrder.ROWS && a.rows != b.rows) a.rows.compareTo(b.rows)
            else String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
        }

    /** Case-insensitive match on name and note; empty query keeps everything. */
    fun filter(entries: List<ArtFontEntry>, query: String): List<ArtFontEntry> {
        val q = query.trimUnicodeWhitespace().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { it.name.lowercase().contains(q) || it.note.lowercase().contains(q) }
    }

    fun fontMode(entry: ArtFontEntry): FontMode =
        if (entry.kind == ArtFontKind.FIGLET) FontMode.Figlet(entry.name) else FontMode.Tdf(entry.name)

    /**
     * Renders [text] in one font — plain lines for FIGlet, mIRC-coloured lines for TheDraw. [width] is the FIGlet
     * wrap column; TheDraw glyphs are fixed-size so it has no effect there.
     */
    fun renderSample(entry: ArtFontEntry, text: String, width: Int = 400): List<String> = when (entry.kind) {
        ArtFontKind.FIGLET -> runCatching { renderFiglet(text, entry.name, width) }.getOrDefault("").split("\n")
        ArtFontKind.TDF -> runCatching { TdfCatalog.render(text, entry.name) }.getOrDefault(emptyList())
    }
}
