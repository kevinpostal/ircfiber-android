package com.kevinpostal.fiberirc.ui.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.irc.MediaLinks
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.media.MediaLoadError
import com.kevinpostal.fiberirc.data.media.PhotoLibrary
import com.kevinpostal.fiberirc.ui.theme.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * A chat image full screen (ports `ImageViewerViewController`, `ios/UI/Media/ImageViewerViewController.swift`):
 * black, pinch to zoom (1×–4×), double-tap toggles 2× at the tapped point, a single tap shows or hides the toolbar,
 * swipe down closes (only at 1×, for a mostly vertical downward drag). Animated GIFs play. The toolbar shares the
 * image and its link, saves it to the Pictures collection, opens the link in a Custom Tab, deletes it (when
 * [onDelete] is set — File Uploads), or closes.
 *
 * [url] is the link in the chat (shared, opened in the browser); [source] where the bytes load from (the image
 * proxy for external images). Shown as its own full-screen dialog over whatever presents it.
 */
@Composable
fun ImageViewerScreen(url: String, source: String, media: MediaApi, onClose: () -> Unit, onDelete: (() -> Unit)? = null) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImageViewerContent(url, source, media, onClose, onDelete)
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Bytes(val data: ByteArray, val mime: String) : Loaded
    data class Failed(val message: String) : Loaded
}

@Composable
private fun ImageViewerContent(url: String, source: String, media: MediaApi, onClose: () -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var loaded by remember(source) { mutableStateOf<Loaded>(Loaded.Loading) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    var saveDenied by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val dragY = remember { Animatable(0f) }

    LaunchedEffect(source) {
        loaded = media.bytes(source).fold(
            { bytes -> Loaded.Bytes(bytes, sniffMime(bytes) ?: "image/jpeg") },
            { Loaded.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    fun save(data: Loaded.Bytes) {
        scope.launch {
            val name = saveName(url, data.mime)
            val result = withContext(Dispatchers.IO) { PhotoLibrary.save(context, name, data.mime) { data.data.inputStream() } }
            result.onSuccess { saved = true }.onFailure { saveError = it.message ?: it.javaClass.simpleName }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val data = loaded as? Loaded.Bytes
        if (granted && data != null) save(data) else if (!granted) saveDenied = true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = max(0.3f, 1f - dragY.value / max(1f, height)))))
        when (val l = loaded) {
            Loaded.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            is Loaded.Failed -> FailureText(l.message, Modifier.align(Alignment.Center))
            is Loaded.Bytes -> {
                // Decoded at most 2× the screen's pixels (enough for the 2× double-tap), never the full camera bitmap.
                val maxPixel = (max(width, height) * 2).toInt()
                val request = remember(l) {
                    ImageRequest.Builder(context).data(l.data).size(maxPixel).precision(Precision.INEXACT).build()
                }
                val painter = rememberAsyncImagePainter(request)
                val state by painter.state.collectAsState()
                if (state is AsyncImagePainter.State.Error) {
                    FailureText(MediaLoadError.Undecodable.message.orEmpty(), Modifier.align(Alignment.Center))
                } else {
                    Image(
                        painter,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = MediaLinks.lastPathComponent(url) }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y + dragY.value
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { toolbarVisible = !toolbarVisible },
                                    onDoubleTap = { p ->
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2f
                                            val center = Offset(width / 2, height / 2)
                                            offset = clampOffset((center - p) * (scale - 1f), scale, width, height)
                                        }
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                val dismissDistance = 120.dp.toPx()
                                val dismissVelocity = 900.dp.toPx()
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val tracker = VelocityTracker()
                                    var decided = false
                                    var dismissing = false
                                    var startedZoomed = scale > 1f
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        if (pressed >= 2 || startedZoomed) {
                                            startedZoomed = true
                                            scale = (scale * zoom).coerceIn(1f, 4f)
                                            offset = if (scale <= 1f) Offset.Zero else clampOffset(offset + pan, scale, width, height)
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        } else if (pressed == 1) {
                                            val change = event.changes.first { it.pressed }
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            if (!decided && pan != Offset.Zero) {
                                                decided = true
                                                dismissing = pan.y > 0 && abs(pan.y) > abs(pan.x)
                                            }
                                            if (dismissing) {
                                                scope.launch { dragY.snapTo(max(0f, dragY.value + pan.y)) }
                                                change.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (dismissing) {
                                        val v = tracker.calculateVelocity().y
                                        if (dragY.value > dismissDistance || v > dismissVelocity) {
                                            onClose()
                                        } else {
                                            scope.launch { dragY.animateTo(0f) }
                                        }
                                    }
                                }
                            },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = toolbarVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(horizontal = Grid.gutter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { context.startActivity(shareIntent(context, url, loaded as? Loaded.Bytes)) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White)
                }
                val data = loaded as? Loaded.Bytes
                IconButton(
                    enabled = data != null && !saved,
                    onClick = {
                        if (data == null) return@IconButton
                        if (PhotoLibrary.needsPermission(context)) {
                            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            save(data)
                        }
                    },
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = "Save to Photos",
                        tint = if (data != null && !saved) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                }
                IconButton(onClick = { LinkOpener.openInCustomTab(context, url) }) {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = "Open in Browser", tint = Color.White)
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                }
                TextButton(onClick = onClose) { Text("Done", color = Color.White) }
            }
        }
    }

    if (saveDenied) {
        AlertDialog(
            onDismissRequest = { saveDenied = false },
            title = { Text("Can't Save to Photos") },
            text = { Text("Allow IRC Fiber to store photos in Settings → Apps → IRC Fiber → Permissions.") },
            confirmButton = { TextButton(onClick = { saveDenied = false }) { Text("OK") } },
        )
    }
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text("Couldn't save the image") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun FailureText(message: String, modifier: Modifier) {
    Text(
        "Couldn't load the image.\n$message",
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = Grid.section),
    )
}

/** Keeps a zoomed picture covering its frame: at most `(scale-1)/2` of the frame off each side. */
private fun clampOffset(o: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = (scale - 1f) * width / 2f
    val maxY = (scale - 1f) * height / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/** The image's type from its header (`BitmapFactory` bounds decode), or null when it isn't one. */
private fun sniffMime(bytes: ByteArray): String? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return opts.outMimeType
}

/** The link's file name, given an extension matching [mime] when it lacks one. */
private fun saveName(url: String, mime: String): String {
    val last = MediaLinks.lastPathComponent(url).takeIf { it.isNotEmpty() && it != "/" } ?: "image"
    if ('.' in last) return last
    val ext = mime.substringAfter('/', "jpg").let { if (it == "jpeg") "jpg" else it }
    return "$last.$ext"
}

/** The image (through the FileProvider's cache root) and its link, or just the link before the bytes are in. */
private fun shareIntent(context: Context, url: String, data: Loaded.Bytes?): Intent {
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url)
    if (data != null) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, saveName(url, data.mime)).apply { writeBytes(data.data) }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        send.setType(data.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        send.setType("text/plain")
    }
    return Intent.createChooser(send, null)
}
