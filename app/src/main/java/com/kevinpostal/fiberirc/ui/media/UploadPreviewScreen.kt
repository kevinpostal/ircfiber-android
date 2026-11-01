package com.kevinpostal.fiberirc.ui.media

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevinpostal.fiberirc.data.media.FiberImageLoader
import com.kevinpostal.fiberirc.data.media.Upload
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/**
 * A video or file upload full screen (File Uploads' preview for what [ImageViewerScreen] can't show): its thumbnail
 * (a video's frame, a PDF's first page, else the type icon), name, size and date, with Share (the link) and Delete.
 * No in-app playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadPreviewScreen(upload: Upload, onClose: () -> Unit, onDelete: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val c = LocalFiberIrcColors.current
        val context = LocalContext.current
        val density = LocalDensity.current.density
        val shape = RoundedCornerShape(MediaPreviewRadius)
        Scaffold(
            containerColor = c.groupedBackground,
            topBar = {
                TopAppBar(
                    title = { Text(upload.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.link) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = c.groupedBackground, titleContentColor = c.heading),
                )
            },
        ) { padding ->
            // Gaps as iOS's UploadPreviewViewController: box, margin, name, gutter, detail, margin, buttons.
            Column(
                Modifier.padding(padding).fillMaxSize().padding(horizontal = Grid.section),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(120.dp).clip(shape).background(c.panel).border(1.dp, c.border, shape)) {
                    UploadPreview(upload, FiberImageLoader.maxPixel(120f, density), 48.dp)
                }
                Spacer(Modifier.height(Grid.margin))
                Text(upload.name, color = c.heading, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Grid.gutter))
                Text(
                    "${Formatter.formatFileSize(context, upload.size)} · ${mediaDate(upload.createdAt)}",
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(Grid.margin))
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.section)) {
                    TextButton(onClick = { context.startActivity(shareLink(upload.url)) }) { Text("Share", color = c.link) }
                    TextButton(onClick = onDelete) { Text("Delete", color = c.offlineError) }
                }
            }
        }
    }
}
