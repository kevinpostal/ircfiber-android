package com.kevinpostal.fiberirc.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kevinpostal.fiberirc.data.media.FiberImageLoader
import com.kevinpostal.fiberirc.data.media.ThumbnailKind
import com.kevinpostal.fiberirc.data.media.Upload
import com.kevinpostal.fiberirc.data.media.UploadCategory
import com.kevinpostal.fiberirc.data.media.category
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** The corner radius of every File Uploads preview box (tile, list thumbnail, preview screen, filter chip). */
internal val MediaPreviewRadius = 6.dp

/** An upload's type icon: a file's preview without a thumbnail, and a video's or PDF's until its thumbnail shows. */
internal fun uploadIcon(category: UploadCategory): ImageVector = when (category) {
    UploadCategory.Image -> Icons.Outlined.Image
    UploadCategory.Video -> Icons.Outlined.Movie
    UploadCategory.File -> Icons.Outlined.Description
}

/** How [upload]'s thumbnail is decoded, or null when it has none (a file other than a PDF: just the type icon). */
internal fun Upload.thumbnailKind(): ThumbnailKind? = when (category()) {
    UploadCategory.Image -> ThumbnailKind.Image
    UploadCategory.Video -> ThumbnailKind.Video
    UploadCategory.File -> if (mimeType == "application/pdf") ThumbnailKind.Pdf else null
}

/**
 * [upload]'s preview filling this box, decoded at [maxPixel]: an image crop-filled; a video's frame or a PDF's first
 * page drawn over its type icon ([iconSize], centered), which shows while it loads or if it can't be decoded; any
 * other file just the icon, with no request.
 */
@Composable
internal fun BoxScope.UploadPreview(upload: Upload, maxPixel: Int, iconSize: Dp) {
    val context = LocalContext.current
    val kind = upload.thumbnailKind()
    if (kind != ThumbnailKind.Image) {
        Icon(
            uploadIcon(upload.category()),
            contentDescription = null,
            tint = LocalFiberIrcColors.current.textSecondary,
            modifier = Modifier.align(Alignment.Center).size(iconSize),
        )
    }
    if (kind != null) {
        AsyncImage(
            model = remember(upload.url, maxPixel, kind) { FiberImageLoader.request(context, upload.url, maxPixel, kind) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One File Uploads grid cell: a square preview box ([UploadPreview]) with the file name
 * below. While [selecting], a badge top-end shows whether it's [selected].
 */
@Composable
internal fun UploadTile(
    upload: Upload,
    selected: Boolean,
    selecting: Boolean,
    density: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFiberIrcColors.current
    val shape = RoundedCornerShape(MediaPreviewRadius)
    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(c.panel)
                .border(1.dp, if (selected) c.accent else c.border, shape)
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
                .semantics {
                    contentDescription = upload.name
                    if (selecting) this.selected = selected
                },
        ) {
            UploadPreview(upload, FiberImageLoader.maxPixel(maxWidth.value, density), Grid.leadingColumn)
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Grid.unit)
                        .size(20.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.background(c.accent)
                            } else {
                                Modifier.border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            upload.name,
            style = Caption,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(top = Grid.unit),
        )
    }
}
