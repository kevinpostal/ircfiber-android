package com.kevinpostal.fiberirc.ui.encryption

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.e2ee.E2eeLinkOffer
import com.kevinpostal.fiberirc.data.e2ee.E2eeLinkProgress
import com.kevinpostal.fiberirc.data.e2ee.E2eeLinkReview
import com.kevinpostal.fiberirc.data.e2ee.E2eeService
import com.kevinpostal.fiberirc.data.e2ee.E2eeServiceState
import com.kevinpostal.fiberirc.data.e2ee.E2eeTexts
import com.kevinpostal.fiberirc.data.e2ee.acceptLinkOffer
import com.kevinpostal.fiberirc.data.e2ee.cancelLinkOffer
import com.kevinpostal.fiberirc.data.e2ee.createLinkOffer
import com.kevinpostal.fiberirc.data.e2ee.link
import com.kevinpostal.fiberirc.data.e2ee.reviewLinkOffer
import com.kevinpostal.fiberirc.ui.settings.SettingsScaffold
import com.kevinpostal.fiberirc.ui.settings.copySensitive
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The transfer's status line (`ui.link`), coloured: incomplete red, complete green, otherwise muted. */
@Composable
private fun LinkStatus(progress: E2eeLinkProgress?) {
    val c = LocalFiberIrcColors.current
    progress ?: return
    Text(
        progress.text,
        color = when (progress.state) {
            E2eeLinkProgress.State.INCOMPLETE -> c.error
            E2eeLinkProgress.State.COMPLETE -> c.submitGreen
            else -> c.textSecondary
        },
        modifier = Modifier.testTag("linkStatus"),
    )
}

/**
 * The new device's side: its v2 link code as a QR and as text to copy, its fingerprint to compare, and the history
 * transfer's progress. History is accepted only while this screen is open; leaving it cancels the offer (incomplete
 * staging is discarded). Complete only after the import committed.
 */
@Composable
fun LinkOfferScreen(service: E2eeService, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by service.state.collectAsState()
    val progress = (state as? E2eeServiceState.Running)?.status?.link?.takeIf { it.receiving }
    var offer by remember { mutableStateOf<E2eeLinkOffer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        offer = try {
            service.createLinkOffer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = E2eeTexts.error(e)
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Closing the screen ends acceptance; works while locked too.
            CoroutineScope(Dispatchers.Main).launch { runCatching { service.cancelLinkOffer() } }
        }
    }

    SettingsScaffold(title = "Link This Device", onBack = onBack) {
        item(key = "offer") {
            Column(
                Modifier.fillMaxWidth().padding(top = Grid.section),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Grid.margin),
            ) {
                Text(
                    "On a device that's already set up, open Settings → Encryption → Add a New Device and scan or paste this code. " +
                        "Keep both devices open and online while your history is sent.",
                    color = c.textSecondary,
                )
                val o = offer
                when {
                    error != null -> {
                        Text(error!!, color = c.error)
                        OutlinedButton(onClick = { attempt++ }) { Text("Try Again") }
                    }
                    o == null -> Text("Preparing link code…", color = c.textSecondary)
                    else -> {
                        if (o.qrPng != null) QrPng(o.qrPng, size = 240.dp) else QrImage(o.code, size = 240.dp)
                        SelectionContainer {
                            Text(o.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.chatText, modifier = Modifier.testTag("linkCode"))
                        }
                        OutlinedButton(onClick = { copySensitive(context, "Link code", o.code) }) { Text("Copy Code") }
                        Text("Device fingerprint", color = c.textSecondary)
                        SafetyDigits(o.fingerprint)
                    }
                }
                LinkStatus(progress)
            }
        }
    }
}

/**
 * The device that already has the history: paste the new device's code (or pick a screenshot/photo of its QR with
 * the system photo picker — no camera permission), review the account, device and fingerprint, confirm, then send
 * the history. Complete only after the new device's final acknowledgement.
 */
@Composable
fun LinkAcceptScreen(service: E2eeService, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by service.state.collectAsState()
    val progress = (state as? E2eeServiceState.Running)?.status?.link?.takeIf { !it.receiving }
    var raw by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<E2eeLinkReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = E2eeTexts.error(e)
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val text = QrCodes.decode(context, uri) ?: throw IllegalStateException("No QR code found in that image")
            raw = text
            review = null
        }
    }

    SettingsScaffold(title = "Add a New Device", onBack = { if (!busy) onBack() }) {
        item(key = "accept") {
            Column(Modifier.fillMaxWidth().padding(top = Grid.section), verticalArrangement = Arrangement.spacedBy(Grid.margin)) {
                Text(
                    "On the new device, open Settings → Encryption → Link This Device. Then paste its code here, or pick a " +
                        "screenshot or photo of its QR code.",
                    color = c.textSecondary,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it; review = null },
                    label = { Text("Link code") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy && !sent,
                    modifier = Modifier.fillMaxWidth().testTag("linkPaste"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                    OutlinedButton(enabled = !busy && !sent, onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrBlank()) error = "The clipboard has no link code" else { raw = text; review = null }
                    }) { Text("Paste") }
                    OutlinedButton(enabled = !busy && !sent, onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Choose QR Image…") }
                }
                val r = review
                if (r == null) {
                    Button(enabled = !busy && raw.isNotBlank(), onClick = { run { review = service.reviewLinkOffer(raw) } }) { Text("Check Device") }
                } else {
                    Text("${r.account} · device ${r.deviceId}", color = c.chatText)
                    Text("Device fingerprint", color = c.textSecondary)
                    SafetyDigits(r.fingerprint)
                    Text(
                        "Add this device only if this fingerprint matches the one shown on the new device. Keep both devices " +
                            "open and online while your history is sent.",
                        color = c.textSecondary,
                    )
                    if (!sent) {
                        Button(enabled = !busy, onClick = {
                            run {
                                service.acceptLinkOffer(raw, r.fingerprint)
                                sent = true
                            }
                        }, modifier = Modifier.testTag("linkAccept")) { Text("Add Device") }
                    }
                }
                if (busy && review != null) Text("Linking… keep this screen open.", color = c.textSecondary)
                LinkStatus(progress)
                error?.let { Text(it, color = c.error, modifier = Modifier.testTag("linkError")) }
            }
        }
    }
}
