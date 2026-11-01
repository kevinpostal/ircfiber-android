package com.kevinpostal.fiberirc.ui.media

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.media.UploadPayload
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/**
 * Before an upload (ports `UploadViewController`, `ios/UI/Media/UploadViewController.swift:82-203`): a preview, the
 * file size, the file name (editable) and an optional message. Send hands both back (a blank name keeps the
 * suggested one); Cancel dismisses. While [progress] is non-null the upload runs: a bar shows it and Send is off
 * (Cancel stops it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadConfirmScreen(
    payload: UploadPayload,
    progress: Float?,
    onCancel: () -> Unit,
    onSend: (filename: String, message: String) -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    var name by rememberSaveable(payload.file.path) { mutableStateOf(payload.filename) }
    var message by rememberSaveable(payload.file.path) { mutableStateOf("") }
    val messageFocus = remember { FocusRequester() }
    val busy = progress != null
    val send = {
        if (!busy) {
            val trimmed = name.trim()
            onSend(trimmed.ifEmpty { payload.filename }, message)
        }
    }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { runCatching { messageFocus.requestFocus() } }

    Scaffold(
        containerColor = c.groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text("Upload") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel", color = c.link) } },
                actions = { TextButton(onClick = send, enabled = !busy) { Text("Send", color = if (busy) c.textTertiary else c.link) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.groupedBackground, titleContentColor = c.heading),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().padding(Grid.margin),
            verticalArrangement = Arrangement.spacedBy(Grid.gutter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Grid.tapTarget * 2, max = 240.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.panel),
                contentAlignment = Alignment.Center,
            ) {
                val preview = payload.preview
                if (preview != null) {
                    Image(
                        preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    )
                } else {
                    Icon(
                        if (payload.mime.startsWith("video/")) Icons.Outlined.Movie else Icons.Outlined.Description,
                        contentDescription = null,
                        tint = c.textSecondary,
                        modifier = Modifier.padding(vertical = Grid.section).size(48.dp),
                    )
                }
            }
            Text(
                Formatter.formatFileSize(context, payload.size),
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Grid.unit))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("File name", color = c.textTertiary) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { runCatching { messageFocus.requestFocus() } }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                placeholder = { Text("Add a message", color = c.textTertiary) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(messageFocus),
            )
            if (progress != null) {
                Text(
                    "Uploading ${name.trim().ifEmpty { payload.filename }}…",
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = c.accent,
                    trackColor = c.border,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun fieldColors() = LocalFiberIrcColors.current.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.field,
        unfocusedContainerColor = c.field,
        disabledContainerColor = c.field,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.border,
        focusedTextColor = c.heading,
        unfocusedTextColor = c.heading,
        cursorColor = c.accent,
    )
}
