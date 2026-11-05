package com.kevinpostal.fiberirc.ui.actions

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import com.kevinpostal.fiberirc.data.media.FileTooLarge
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.media.PhotoLibrary
import com.kevinpostal.fiberirc.data.media.UploadFile
import com.kevinpostal.fiberirc.data.media.UploadPayload
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.ui.media.FullScreenDialog
import com.kevinpostal.fiberirc.ui.media.SnippetEditorScreen
import com.kevinpostal.fiberirc.ui.media.SnippetsScreen
import com.kevinpostal.fiberirc.ui.media.UploadConfirmScreen
import com.kevinpostal.fiberirc.ui.media.UploadsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log

/** Where the upload flow is. */
private sealed interface UploadStep {
    /** The action sheet itself. */
    data object Sheet : UploadStep

    /** A picker or the camera is up, or a picked file is being read and encoded. */
    data object Picking : UploadStep

    /** The confirm screen, uploading while [progress] is non-null. */
    data class Confirm(val payload: UploadPayload, val progress: Float? = null) : UploadStep

    /** The upload failed: Try Again re-sends [payload] (already named) with [message]. */
    data class Failed(val payload: UploadPayload, val message: String, val error: String) : UploadStep

    data object SnippetEditor : UploadStep
    data object Snippets : UploadStep
    data object Uploads : UploadStep
}

/**
 * The composer's upload button (ports `UploadActionSheet`, `ios/UI/Actions/UploadActionSheet.swift`; IRCCloud
 * `MainViewController` `:4861-4916`): Take Photo or Video (with a camera), Choose Photo or Video, Choose Document,
 * Start a Text Snippet, Text Snippets, File Uploads, Cancel. A picked file goes through [UploadConfirmScreen]
 * (name and message), then uploads with a progress bar; the link is sent to [buffer] as `"<message> <url>"`. A
 * failed upload offers Try Again. A file over 50 MB is refused ("File too large (max 50 MB)"). Videos are named
 * `VID_<yyyyMMdd_HHmmss>.<ext>`; GIFs go as they are; other photos (HEIC included) as JPEG at 0.8 with the longest
 * side capped by Settings → Photo Size ([DeviceSettingsStore] `photoMaxDimension`, 0 = original).
 *
 * Shown while the caller's flag is set; [onDismiss] clears it once the whole flow is over.
 */
@Composable
fun UploadActionSheet(
    client: IrcClient,
    buffer: IrcBuffer,
    media: MediaApi,
    deviceSettings: DeviceSettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<UploadStep>(UploadStep.Sheet) }
    var notice by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var upload by remember { mutableStateOf<Job?>(null) }
    /** The camera's two possible outputs (photo, video), created when it opens. */
    var cameraFiles by remember { mutableStateOf<Pair<File, File>?>(null) }
    /** The cache copy being shown or uploaded; deleted when the flow ends. */
    var pending by remember { mutableStateOf<File?>(null) }

    fun finish() {
        upload?.cancel()
        pending?.delete()
        pending = null
        cameraFiles?.let { (p, v) -> p.delete(); v.delete() }
        onDismiss()
    }

    DisposableEffect(Unit) { onDispose { upload?.cancel(); pending?.delete() } }

    /** Reads and encodes off the main thread; nothing picked ends here; a file over 50 MB is refused. */
    fun prepare(make: () -> UploadPayload?) {
        step = UploadStep.Picking
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(make) }
            result.fold(
                { payload ->
                    when {
                        payload == null -> finish()
                        UploadFile.isTooLarge(payload.size) -> {
                            payload.file.delete()
                            notice = "File too large (max 50 MB)" to null
                        }
                        else -> {
                            pending = payload.file
                            step = UploadStep.Confirm(payload)
                        }
                    }
                },
                { error ->
                    notice = if (error is FileTooLarge) {
                        "File too large (max 50 MB)" to null
                    } else {
                        "Couldn't read the file" to (error.message ?: error.javaClass.simpleName)
                    }
                },
            )
        }
    }

    fun send(payload: UploadPayload, message: String) {
        step = UploadStep.Confirm(payload, progress = 0f)
        upload = scope.launch {
            media.upload(payload.file, payload.filename, payload.mime, client.settings.networkId, buffer.name) { p ->
                scope.launch { (step as? UploadStep.Confirm)?.let { step = it.copy(progress = p.toFloat()) } }
            }.onSuccess { result ->
                client.sendInput(UploadFile.message(message, result.url), buffer)
                finish()
            }.onFailure { error ->
                val text = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "Upload failed: $text")
                step = UploadStep.Failed(payload, message, text)
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult finish()
        val mime = context.contentResolver.getType(uri)
        val maxDimension = deviceSettings.settings.value.photoMaxDimension
        prepare {
            if (mime?.startsWith("video/") == true) {
                UploadFile.videoPayload(context, uri, mime)
            } else {
                UploadFile.photoPayload(context, uri, mime, maxDimension)
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult finish()
        prepare { UploadFile.documentPayload(context, uri) }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (photo, video) = cameraFiles ?: return@rememberLauncherForActivityResult finish()
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult finish()
        val returned = result.data?.data
        val settings = deviceSettings.settings.value
        prepare {
            when {
                photo.length() > 0 -> {
                    video.delete()
                    if (settings.saveToCameraRoll && !PhotoLibrary.needsPermission(context)) {
                        PhotoLibrary.save(context, UploadFile.defaultName(), "image/jpeg") { photo.inputStream() }
                    }
                    try {
                        UploadFile.cameraPhotoPayload(context, photo, settings.photoMaxDimension)
                    } finally {
                        photo.delete()
                    }
                }
                video.length() > 0 -> {
                    photo.delete()
                    UploadFile.videoPayload(context, Uri.fromFile(video), "video/mp4").also { video.delete() }
                }
                returned != null -> {
                    photo.delete()
                    video.delete()
                    UploadFile.videoPayload(context, returned, context.contentResolver.getType(returned))
                }
                else -> null
            }
        }
    }

    when (val s = step) {
        UploadStep.Sheet -> {
            val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            val actions = buildList {
                if (hasCamera) {
                    add(
                        SheetAction("Take Photo or Video") {
                            step = UploadStep.Picking
                            val files = UploadFile.cacheFile(context, "jpg") to UploadFile.cacheFile(context, "mp4")
                            cameraFiles = files
                            camera.launch(cameraIntent(context, files.first, files.second))
                        },
                    )
                }
                add(
                    SheetAction("Choose Photo or Video") {
                        step = UploadStep.Picking
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                )
                add(
                    SheetAction("Choose Document") {
                        step = UploadStep.Picking
                        documentPicker.launch(arrayOf("*/*"))
                    },
                )
                add(SheetAction("Start a Text Snippet") { step = UploadStep.SnippetEditor })
                add(SheetAction("Text Snippets") { step = UploadStep.Snippets })
                add(SheetAction("File Uploads") { step = UploadStep.Uploads })
            }
            // A row moves `step` on before the sheet's own dismiss runs: only a dismiss still on the sheet ends the flow.
            ActionSheet(title = null, subtitle = null, actions = actions, onDismiss = { if (step == UploadStep.Sheet) finish() })
        }
        UploadStep.Picking -> Unit
        is UploadStep.Confirm -> FullScreenDialog(onDismiss = ::finish) {
            UploadConfirmScreen(
                payload = s.payload,
                progress = s.progress,
                encrypted = client.lockDecision(buffer.name) != IrcLockDecision.OFF,
                onCancel = ::finish,
                onSend = { filename, message ->
                    val named = s.payload.copy(filename = filename, mime = UploadFile.mimeType(filename, s.payload.mime))
                    send(named, message)
                },
            )
        }
        is UploadStep.Failed -> AlertDialog(
            onDismissRequest = ::finish,
            title = { Text("Upload failed") },
            text = { Text(s.error) },
            dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { send(s.payload, s.message) }) { Text("Try Again") } },
        )
        UploadStep.SnippetEditor ->
            SnippetEditorScreen(client = client, buffer = buffer, media = media, initialBody = "", onDismiss = ::finish)
        UploadStep.Snippets -> FullScreenDialog(onDismiss = ::finish) {
            SnippetsScreen(media = media, openLinksInApp = deviceSettings.settings.value.openLinksInApp, onBack = ::finish)
        }
        UploadStep.Uploads -> FullScreenDialog(onDismiss = ::finish) { UploadsScreen(media = media, onBack = ::finish) }
    }

    notice?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { notice = null; finish() },
            title = { Text(title) },
            text = message?.let { { Text(it) } },
            confirmButton = { TextButton(onClick = { notice = null; finish() }) { Text("OK") } },
        )
    }
}

private const val TAG = "FiberIRC.ui"

/**
 * The system camera, photo or video: a chooser over `ACTION_IMAGE_CAPTURE` with `ACTION_VIDEO_CAPTURE` beside it,
 * each writing to its own cache file through the app's FileProvider (no CAMERA permission: the camera app owns it).
 */
private fun cameraIntent(context: Context, photo: File, video: File): Intent {
    val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
    fun capture(action: String, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority, file)
        return Intent(action)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("", uri) }
    }
    val photoIntent = capture(MediaStore.ACTION_IMAGE_CAPTURE, photo)
    val videoIntent = capture(MediaStore.ACTION_VIDEO_CAPTURE, video)
    return Intent.createChooser(photoIntent, "Take Photo or Video")
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(videoIntent))
}
