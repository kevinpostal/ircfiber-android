package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.ArtRow
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.ui.theme.Grid
import kotlin.math.roundToInt

/** Art is authored for black; the light appearance keeps it (Theme `artBackground`, #000000 in both). */
val ArtBackground = Color(0xFF000000)

/**
 * Grouping of consecutive art lines from one sender: every row of a block shares the cell width derived from the
 * widest row, so the picture keeps its aspect and columns line up. Port of iOS `ArtLayout`.
 */
data class ArtLayout(val row: ArtRow, val blockColumns: Int, val blockStart: Boolean)

/** One art cell's size; height = 2 × width snapped to device pixels. */
data class ArtCellSize(val width: Dp, val height: Dp)

object ArtGeometry {
    /** Never upscale past 14pt-mono columns. */
    val MaxCellWidth = 8.4.dp

    /**
     * Port of `BufferViewController.swift:1192-1196`: fit the block's widest row to the chat width minus both
     * margins, capped at [MaxCellWidth]; 1:2 cells make ▀/▄ half-blocks square, as img2irc art assumes.
     * [containerWidth] is the chat list's (safe-area) width; [density] is pixels per dp.
     */
    fun cellSize(containerWidth: Dp, blockColumns: Int, density: Float): ArtCellSize {
        val available = containerWidth - Grid.margin * 2
        val width = minOf(MaxCellWidth, available / maxOf(blockColumns, 1).toFloat())
        val height = ((width.value * 2 * density).roundToInt() / density).dp
        return ArtCellSize(width, height)
    }

    /** Sextant U+1FB00–1FB3B → 6-bit mask; bit k = sub-cell (col k % 2, row k / 2). Skips ▌ (21) and ▐ (42). */
    fun sextantBits(scalar: Int): Int {
        var p = scalar - 0x1FB00 + 1
        if (p >= 21) p += 1
        if (p >= 42) p += 1
        return p
    }
}

/**
 * Draws one [ArtRow] as a fixed grid (port of iOS `ArtLineView.draw`): every cell edge is snapped to device pixels
 * so neighbouring cells and stacked rows meet without seams. Block elements and sextants are drawn as rectangles;
 * any other glyph is drawn monospace, scaled to the cell width. The canvas spans [blockColumns] cells so every row
 * of a block forms one black rectangle.
 */
@Composable
fun ArtCanvas(
    row: ArtRow,
    cellWidth: Dp,
    cellHeight: Dp,
    blockColumns: Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // Monospace advance width in px per sp of font size ("M" at 100sp / 100).
    val advancePerSp = remember(measurer) {
        measurer.measure("M", TextStyle(fontFamily = FontFamily.Monospace, fontSize = 100.sp)).size.width / 100f
    }
    Canvas(
        modifier
            .size(cellWidth * maxOf(blockColumns, row.columns), cellHeight)
            .background(ArtBackground),
    ) {
        val cw = cellWidth.toPx()
        val h = size.height
        val fontSize = (cw / advancePerSp).sp
        val regular = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize)
        val bold = regular.copy(fontWeight = FontWeight.Bold)

        for ((i, g) in row.glyphs.withIndex()) {
            // Device-pixel snapping: DrawScope units are pixels already.
            val x0 = (i * cw).roundToInt().toFloat()
            val x1 = ((i + 1) * cw).roundToInt().toFloat()
            g.bg?.let { drawRect(it, Offset(x0, 0f), Size(x1 - x0, h)) }
            if (g.char.isEmpty() || g.char == " ") continue
            val v = g.char.codePointAt(0)

            fun fill(fx0: Float, fy0: Float, fx1: Float, fy1: Float, alpha: Float = 1f) {
                val l = (x0 + fx0 * (x1 - x0)).roundToInt().toFloat()
                val r = (x0 + fx1 * (x1 - x0)).roundToInt().toFloat()
                val t = (fy0 * h).roundToInt().toFloat()
                val b = (fy1 * h).roundToInt().toFloat()
                if (r <= l || b <= t) return
                drawRect(g.fg.copy(alpha = alpha), Offset(l, t), Size(r - l, b - t))
            }
            val ul = { fill(0f, 0f, 0.5f, 0.5f) }
            val ur = { fill(0.5f, 0f, 1f, 0.5f) }
            val ll = { fill(0f, 0.5f, 0.5f, 1f) }
            val lr = { fill(0.5f, 0.5f, 1f, 1f) }

            when (v) {
                0x2580 -> fill(0f, 0f, 1f, 0.5f)
                in 0x2581..0x2587 -> fill(0f, 1f - (v - 0x2580) / 8f, 1f, 1f)
                0x2588 -> fill(0f, 0f, 1f, 1f)
                in 0x2589..0x258F -> fill(0f, 0f, (0x2590 - v) / 8f, 1f)
                0x2590 -> fill(0.5f, 0f, 1f, 1f)
                0x2591 -> fill(0f, 0f, 1f, 1f, alpha = 0.25f)
                0x2592 -> fill(0f, 0f, 1f, 1f, alpha = 0.5f)
                0x2593 -> fill(0f, 0f, 1f, 1f, alpha = 0.75f)
                0x2594 -> fill(0f, 0f, 1f, 1f / 8f)
                0x2595 -> fill(7f / 8f, 0f, 1f, 1f)
                0x2596 -> ll()
                0x2597 -> lr()
                0x2598 -> ul()
                0x2599 -> { ul(); ll(); lr() }
                0x259A -> { ul(); lr() }
                0x259B -> { ul(); ur(); ll() }
                0x259C -> { ul(); ur(); lr() }
                0x259D -> ur()
                0x259E -> { ur(); ll() }
                0x259F -> { ur(); ll(); lr() }
                in 0x1FB00..0x1FB3B -> {
                    val bits = ArtGeometry.sextantBits(v)
                    for (k in 0 until 6) {
                        if (bits and (1 shl k) == 0) continue
                        val c = (k % 2).toFloat()
                        val r = (k / 2).toFloat()
                        fill(c / 2f, r / 3f, (c + 1f) / 2f, (r + 1f) / 3f)
                    }
                }
                else -> drawGlyphText(g.char, if (g.bold) bold else regular, g.fg, g.underline, x0, x1, h, measurer)
            }
        }
    }
}

private fun DrawScope.drawGlyphText(
    text: String,
    style: TextStyle,
    color: Color,
    underline: Boolean,
    x0: Float,
    x1: Float,
    h: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val layout = measurer.measure(
        text,
        if (underline) style.copy(textDecoration = TextDecoration.Underline) else style,
        softWrap = false,
        maxLines = 1,
    )
    val w = layout.size.width
    drawText(layout, color = color, topLeft = Offset(x0 + (x1 - x0 - w) / 2f, (h - layout.size.height) / 2f))
}

/**
 * Chat-log item for one art line (port of iOS `ArtCell`): sender header on the block's first row, then the grid
 * flush with the rows above and below (no vertical padding) so stacked rows form one image.
 */
@Composable
fun ArtLineItem(
    sender: String,
    text: String,
    layout: ArtLayout,
    cellSize: ArtCellSize,
    headerColor: Color,
    headerStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(start = Grid.margin, end = Grid.margin, top = if (layout.blockStart) Grid.gutter else 0.dp)
            .testTag("artRow")
            .semantics { contentDescription = "$sender: ${IrcFormatting.strip(text)}" },
        verticalArrangement = Arrangement.spacedBy(Grid.unit),
    ) {
        if (layout.blockStart) Text(sender, color = headerColor, style = headerStyle, maxLines = 1)
        ArtCanvas(layout.row, cellSize.width, cellSize.height, layout.blockColumns)
    }
}
