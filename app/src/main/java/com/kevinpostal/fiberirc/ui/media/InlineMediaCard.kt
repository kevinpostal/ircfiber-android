package com.kevinpostal.fiberirc.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.irc.InlineMedia
import com.kevinpostal.fiberirc.data.irc.MediaItem
import com.kevinpostal.fiberirc.data.media.FiberImageLoader
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** What tapping an inline preview does (ports `BufferViewController.open(_ item:)`, `BufferViewController.swift:625-634`). */
sealed interface MediaTap {
    /** The full-screen viewer: [url] is the chat's link (shared, opened in the browser), [source] where bytes load from. */
    data class Viewer(val url: String, val source: String) : MediaTap

    /** [com.kevinpostal.fiberirc.data.irc.LinkOpener]: YouTube's watch page, a snippet's or file's page, or an image with Open Images in Browser. */
    data class Link(val url: String) : MediaTap
}

/** An image opens the viewer unless Open Images in Browser is on; everything else opens its link. */
fun mediaTap(item: MediaItem, imagesInBrowser: Boolean, siteBase: String = BuildConfig.API_BASE_URL): MediaTap {
    if (item is MediaItem.Image && !imagesInBrowser) {
        val source = InlineMedia.sourceUrl(item, siteBase)
        if (source != null) return MediaTap.Viewer(item.url, source)
    }
    return MediaTap.Link(item.url)
}

/** Text size of a snippet preview (`Theme.monoSmall`). */
private val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
private val SnippetLineHeight = 14.dp
internal val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * An inline preview under its message (ports `InlineMediaCell`, `ios/UI/Media/InlineMediaCell.swift:112-383`):
 * images and YouTube thumbnails in a box of at most `min(textWidth, 300dp)` × 200dp, aspect-fit, never enlarged,
 * with their caption below; the box reserves 200dp with a spinner until the image is in. A snippet shows its first
 * five lines in monospace; a file its icon and name (no network load). The caller places it at the text column on
 * the parent message's band; [onOpen] gets the tapped item (see [mediaTap]).
 *
 * Images load through Coil's singleton loader, which [FiberImageLoader.install] makes the cookie-carrying one.
 */
@Composable
fun InlineMediaCard(
    item: MediaItem,
    textWidth: Dp,
    media: MediaApi,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    siteBase: String = BuildConfig.API_BASE_URL,
) {
    Column(modifier.padding(vertical = Grid.unit)) {
        when (item) {
            is MediaItem.File -> FileRow(item, textWidth, onOpen)
            is MediaItem.Paste -> PastePreview(item, textWidth, media, siteBase, onOpen)
            is MediaItem.Image, is MediaItem.YouTube -> ImagePreview(item, textWidth, siteBase, onOpen)
        }
    }
}

@Composable
private fun FileRow(item: MediaItem.File, textWidth: Dp, onOpen: (MediaItem) -> Unit) {
    val c = LocalFiberIrcColors.current
    Row(
        Modifier
            .width(textWidth)
            .height(Grid.control)
            .clickable(role = Role.Button) { onOpen(item) }
            .semantics { contentDescription = "File: ${item.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Grid.gutter))
        Text(item.name, color = c.link, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
private fun PreviewBox(width: Dp, height: Dp, onClick: () -> Unit, label: String, content: @Composable BoxScope.() -> Unit) {
    val c = LocalFiberIrcColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .background(c.panel)
            .border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CaptionText(text: String?, width: Dp) {
    if (text == null) return
    Spacer(Modifier.height(Grid.unit))
    Text(
        text,
        style = Caption,
        color = LocalFiberIrcColors.current.link,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun ImagePreview(item: MediaItem, textWidth: Dp, siteBase: String, onOpen: (MediaItem) -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val imageWidth = maxOf(1.dp, minOf(textWidth, InlineMedia.MAX_IMAGE_WIDTH.dp))
    val source = remember(item, siteBase) { InlineMedia.sourceUrl(item, siteBase) }
    val isYouTube = item is MediaItem.YouTube
    val caption = InlineMedia.caption(item, null)
    val label = if (isYouTube) "YouTube video" else "Image: ${caption.orEmpty()}"
    if (source == null) {
        PreviewBox(imageWidth, Grid.tapTarget, { onOpen(item) }, label) { Placeholder(isYouTube) }
        CaptionText(caption, textWidth)
        return
    }
    val maxPixel = FiberImageLoader.maxPixel(imageWidth.value, density)
    val request = remember(source, maxPixel) { FiberImageLoader.request(context, source, maxPixel) }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    when (val s = state) {
        is AsyncImagePainter.State.Success -> {
            val image = s.result.image
            val (w, h) = InlineMedia.imageSize(image.width.toFloat(), image.height.toFloat(), imageWidth.value)
            PreviewBox(w.dp, h.dp, { onOpen(item) }, label) {
                Image(painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(w.dp, h.dp))
                if (isYouTube) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp).shadow(4.dp, RoundedCornerShape(50)),
                    )
                }
            }
        }
        is AsyncImagePainter.State.Error ->
            PreviewBox(imageWidth, Grid.tapTarget, { onOpen(item) }, label) { Placeholder(isYouTube) }
        else -> PreviewBox(imageWidth, InlineMedia.MAX_IMAGE_HEIGHT.dp, { onOpen(item) }, label) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.textTertiary, strokeWidth = 2.dp)
        }
    }
    CaptionText(caption, textWidth)
}

@Composable
private fun Placeholder(isYouTube: Boolean) {
    Icon(
        if (isYouTube) Icons.Outlined.SmartDisplay else Icons.Outlined.Image,
        contentDescription = null,
        tint = LocalFiberIrcColors.current.textTertiary,
    )
}

private sealed interface PasteContent {
    data object Loading : PasteContent
    data class Text(val preview: String, val lines: Int) : PasteContent
    data object Failed : PasteContent
}

@Composable
private fun PastePreview(item: MediaItem.Paste, textWidth: Dp, media: MediaApi, siteBase: String, onOpen: (MediaItem) -> Unit) {
    val c = LocalFiberIrcColors.current
    var content by remember(item) { mutableStateOf<PasteContent>(PasteContent.Loading) }
    LaunchedEffect(item, siteBase) {
        val source = InlineMedia.sourceUrl(item, siteBase)
        content = if (source == null) {
            PasteContent.Failed
        } else {
            media.text(source).fold(
                { val (preview, lines) = InlineMedia.pastePreview(it); PasteContent.Text(preview, lines) },
                { PasteContent.Failed },
            )
        }
    }
    val boxHeight = { lines: Int -> SnippetLineHeight * lines + Grid.gutter * 2 }
    when (val s = content) {
        is PasteContent.Text -> {
            val shown = s.lines.coerceIn(1, InlineMedia.PASTE_PREVIEW_LINES)
            val caption = InlineMedia.caption(item, s.lines).orEmpty()
            PreviewBox(textWidth, boxHeight(shown), { onOpen(item) }, caption) {
                Text(
                    s.preview,
                    style = MonoSmall,
                    color = c.chatText,
                    maxLines = InlineMedia.PASTE_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(Grid.gutter).align(Alignment.TopStart),
                )
            }
            CaptionText(caption, textWidth)
        }
        PasteContent.Loading -> {
            PreviewBox(textWidth, boxHeight(InlineMedia.PASTE_PREVIEW_LINES), { onOpen(item) }, "Text snippet") {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.textTertiary, strokeWidth = 2.dp)
            }
            CaptionText(InlineMedia.caption(item, null), textWidth)
        }
        PasteContent.Failed -> {
            PreviewBox(textWidth, Grid.tapTarget, { onOpen(item) }, "Text snippet") {
                Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null, tint = c.textTertiary)
            }
            CaptionText(InlineMedia.caption(item, null), textWidth)
        }
    }
}
