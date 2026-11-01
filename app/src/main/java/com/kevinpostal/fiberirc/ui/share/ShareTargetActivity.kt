package com.kevinpostal.fiberirc.ui.share

import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.kevinpostal.fiberirc.FiberIrcApp
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.api.CookieJarStore
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import com.kevinpostal.fiberirc.data.media.FileTooLarge
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.media.UploadFile
import com.kevinpostal.fiberirc.data.prefs.DeviceIdentity
import com.kevinpostal.fiberirc.data.prefs.ShareTarget
import com.kevinpostal.fiberirc.data.prefs.ShareTargetsStore
import com.kevinpostal.fiberirc.ui.settings.foldedContains
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A shared file, copied into the cache dir (the sender's grant on the Uri ends with this activity). */
internal data class SharedFile(val file: File, val name: String, val mime: String, val thumbnail: Bitmap?)

/** The pure parts of the share form (ports `ShareFormViewController`'s rules, `ShareViewController.swift:178-246`). */
internal object ShareForm {
    /**
     * The conversation Direct Share picked: `"<networkId>:<buffer>"` (the conversation shortcut id / LocusId,
     * buffer lower-cased), split at the first colon, buffer compared case-insensitively (`preselected`).
     */
    fun preselected(targets: List<ShareTarget>, conversationId: String?): ShareTarget? {
        val id = conversationId ?: return null
        val colon = id.indexOf(':')
        if (colon < 0) return null
        val network = id.substring(0, colon)
        val buffer = id.substring(colon + 1)
        return targets.firstOrNull { it.networkId == network && it.buffer.equals(buffer, ignoreCase = true) }
    }

    /** The shared texts and links, trimmed, empties and repeats dropped, joined by a space. */
    fun messageText(parts: List<String>): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")

    /** Buffer or network name contains the (whitespace-trimmed) query, case- and diacritic-insensitively. */
    fun filter(targets: List<ShareTarget>, query: String): List<ShareTarget> {
        val q = query.trim()
        if (q.isEmpty()) return targets
        return targets.filter { foldedContains(it.buffer, q) || foldedContains(it.networkName, q) }
    }

    /** Post: a target, something to send (a file or non-blank text), and nothing loading or sending. */
    fun canPost(selected: ShareTarget?, hasFile: Boolean, message: String, loading: Boolean, sending: Boolean): Boolean =
        selected != null && (hasFile || message.isNotBlank()) && !loading && !sending
}

/**
 * IRC Fiber in the system share sheet (ports `FiberIRCShare/ShareViewController.swift`): shared text, a link, or one
 * photo, video or file goes to a channel or conversation. The file is uploaded to ircfiber.com (POST /api/upload) and
 * its link sent after the message (POST /api/networks/<id>/messages). The buffers are the ones the app published
 * ([ShareTargetsStore], pinned and recent first); a conversation the share sheet's Direct Share row picked
 * ([ShortcutManagerCompat.EXTRA_SHORTCUT_ID] / [Intent.EXTRA_LOCUS_ID]) is preselected, else the first target.
 *
 * `ACTION_SEND` (plain text, images, videos, any file) and `ACTION_SEND_MULTIPLE` (only the first attachment:
 * iOS takes at most one file). Same process as the app, so it uses the app's [Deps] graph: the shared cookie jar
 * (one in-memory [CookieJarStore]), this install's [DeviceIdentity], [SiteApi], [MediaApi] and the published
 * [ShareTargetsStore] targets.
 */
class ShareTargetActivity : ComponentActivity() {
    private lateinit var share: ShareSheetController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = (application as FiberIrcApp).deps
        share = ShareSheetController(this, deps.api, deps.media, deps.shareTargets.targets, lifecycleScope) { sent ->
            setResult(if (sent) RESULT_OK else RESULT_CANCELED)
            finish()
        }
        share.load(intent)
        val appearance = deps.displaySettings.appearance
        setContent {
            val look by appearance.collectAsState()
            FiberIrcTheme(look) { ShareSheet(share) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        share.dispose()
    }
}

/**
 * The share form's state, input and Post, owned by [ShareTargetActivity] (its [scope] is the activity's lifecycle
 * scope) but independent of it, so [ShareSheet] runs over any [SiteApi]/[MediaApi]/targets wiring. [finish] ends the
 * sheet: `true` once the message is posted, `false` on Cancel.
 */
internal class ShareSheetController(
    private val context: Context,
    private val api: SiteApi,
    private val media: MediaApi,
    val targets: StateFlow<List<ShareTarget>>,
    private val scope: CoroutineScope,
    private val finish: (sent: Boolean) -> Unit,
) {
    var selected by mutableStateOf<ShareTarget?>(null)
    var message by mutableStateOf("")
    var query by mutableStateOf("")
    var file by mutableStateOf<SharedFile?>(null)
        private set
    var fileError by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var sending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)

    /** The cache-dir copy, set on the IO thread as soon as it exists; deleted by whichever of the copy and
     * [dispose] sees the other second (both volatile, so at least one of them does). */
    @Volatile private var copied: File? = null
    @Volatile private var destroyed = false

    /** Preselects the Direct Share conversation (else the first target) and reads [intent]'s shared item. */
    fun load(intent: Intent) {
        val current = targets.value
        selected = ShareForm.preselected(current, conversationId(intent)) ?: current.firstOrNull()
        loadInput(intent)
    }

    /** The sheet is gone: drop the cached copy of the shared file. */
    fun dispose() {
        destroyed = true
        copied?.delete()
    }

    // region Input

    /** The Direct Share conversation: the shortcut id, else (API 29+) the LocusId. */
    private fun conversationId(intent: Intent): String? {
        intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)?.let { return it }
        if (Build.VERSION.SDK_INT >= 29) {
            return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_LOCUS_ID, LocusId::class.java)?.id
        }
        return null
    }

    /** Reads the shared item: the first attachment (copied) and the text for the message (`loadInput`). */
    private fun loadInput(intent: Intent) {
        val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
        val texts: List<String> = if (multiple) {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }
                ?: listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        } else {
            listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }
        message = ShareForm.messageText(texts)
        val uri: Uri? = if (multiple) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            loading = false
            return
        }
        scope.launch {
            try {
                file = withContext(Dispatchers.IO) { copy(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileTooLarge) {
                fileError = e.message
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the shared file: ${e.javaClass.simpleName}")
                fileError = "Couldn't read the shared file"
            } finally {
                loading = false
            }
        }
    }

    /** [uri]'s bytes in the cache dir, its name kept; images get a thumbnail. */
    private fun copy(uri: Uri): SharedFile {
        val resolver = context.contentResolver
        val (displayName, size) = UploadFile.describe(resolver, uri)
        if (size != null && UploadFile.isTooLarge(size)) throw FileTooLarge()
        val name = displayName?.takeIf { it.isNotBlank() } ?: "file"
        val mime = resolver.getType(uri) ?: UploadFile.mimeType(name, "application/octet-stream")
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != name } ?: "bin"
        val out = UploadFile.copyToCache(context, uri, ext)
        copied = out
        if (destroyed) out.delete()
        val thumbnail = if (mime.startsWith("image/")) runCatching { UploadFile.decodeScaled(out, 288) }.getOrNull() else null
        return SharedFile(out, name, mime, thumbnail)
    }

    // endregion

    // region Send

    fun send() {
        val target = selected ?: return
        if (sending) return
        sending = true
        val text = message.trim()
        val shared = file
        scope.launch {
            val result = post(target, text, shared)
            result.onSuccess {
                shared?.file?.delete()
                finish(true)
            }.onFailure {
                sending = false
                sendError = it.message ?: "Something went wrong."
            }
        }
    }

    /** Upload (if any) then POST /api/networks/<id>/messages `{"target","text"}`. */
    private suspend fun post(target: ShareTarget, message: String, shared: SharedFile?): Result<Unit> {
        var text = message
        if (shared != null) {
            val upload = media.upload(shared.file, shared.name, shared.mime, target.networkId, target.buffer)
                .getOrElse { return Result.failure(it) }
            text = UploadFile.message(text, upload.url)
        }
        return api.postJson(
            listOf("api", "networks", target.networkId, "messages"),
            buildJsonObject {
                put("target", target.buffer)
                put("text", text)
            },
        ).map { }
    }

    fun cancel() = finish(false)

    // endregion

    private companion object {
        const val TAG = "FiberIRC.share"
    }
}

/** The sheet over the sending app: Cancel / Post, the message (and file preview), then the searchable targets. */
@Composable
internal fun ShareSheet(share: ShareSheetController) {
    val colors = LocalFiberIrcColors.current
    val targets by share.targets.collectAsState()
    val query = share.query
    val rows = remember(targets, query) { ShareForm.filter(targets, query) }
    val sending = share.sending
    val canPost = ShareForm.canPost(share.selected, share.file != null, share.message, share.loading, sending)
    val noIndication = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = noIndication, indication = null) { if (!share.sending) share.cancel() }
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = share::cancel, enabled = !sending) { Text("Cancel") }
                    Text(
                        "IRC Fiber",
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.heading,
                    )
                    if (sending) {
                        Box(Modifier.size(64.dp, 40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        TextButton(onClick = share::send, enabled = canPost) {
                            Text("Post", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = colors.borderDark)
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = share.message,
                        onValueChange = { share.message = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        minLines = 3,
                        maxLines = 5,
                        enabled = !sending,
                    )
                    val file = share.file
                    val thumbnail = file?.thumbnail
                    when {
                        share.loading -> Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        thumbnail != null -> Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = file.name,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                share.fileError?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        color = colors.offlineError,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (targets.isEmpty()) {
                    Text(
                        "Open IRC Fiber and sign in first.",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        textAlign = TextAlign.Center,
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { share.query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        placeholder = { Text("Search channels and conversations") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    )
                    LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        items(rows, key = { "${it.networkId}:${it.buffer}" }) { target ->
                            TargetRow(target, target == share.selected, enabled = !sending) { share.selected = target }
                            HorizontalDivider(color = colors.borderDark)
                        }
                    }
                }
            }
        }
    }
    share.sendError?.let { error ->
        AlertDialog(
            onDismissRequest = { share.sendError = null },
            title = { Text("Couldn't send") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { share.sendError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun TargetRow(target: ShareTarget, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalFiberIrcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(target.buffer, color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(target.networkName, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (checked) Icon(Icons.Default.Check, contentDescription = "Selected", tint = colors.accent)
    }
}
