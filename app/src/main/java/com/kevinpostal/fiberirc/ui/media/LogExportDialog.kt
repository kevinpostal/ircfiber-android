package com.kevinpostal.fiberirc.ui.media

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcEncryptionConfig
import com.kevinpostal.fiberirc.data.irc.LogExport
import com.kevinpostal.fiberirc.ui.theme.Grid

/**
 * Export Log (ports `LogExport.run`): "Loading history…" while [IrcClient.loadAllHistory] walks [buffer] back, then
 * the share sheet with the text file; a write failure shows "Couldn't export <buffer>". [onDone] once it's over. On an
 * encrypting network the user first confirms that the file holds decrypted text without encryption.
 */
@Composable
fun LogExportDialog(client: IrcClient, buffer: IrcBuffer, onDone: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var disclosed by remember { mutableStateOf(client.encryption !is IrcEncryptionConfig.Encrypted) }
    var loading by remember { mutableStateOf(true) }

    if (!disclosed) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Export Decrypted Messages?") },
            text = {
                Text(
                    "${buffer.name} may contain end-to-end encrypted messages. The exported file holds their decrypted " +
                        "text and isn't encrypted; anyone you share it with can read it.",
                )
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { disclosed = true }) { Text("Export") } },
        )
        return
    }

    LaunchedEffect(client, buffer) {
        LogExport.prepare(context, client, buffer)
            .onSuccess {
                loading = false
                context.startActivity(LogExport.shareIntent(context, it))
                onDone()
            }
            .onFailure {
                loading = false
                error = it.message ?: it.javaClass.simpleName
            }
    }

    if (loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Loading history…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Grid.margin))
                    Text(buffer.name)
                }
            },
            confirmButton = {},
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Couldn't export ${buffer.name}") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        )
    }
}
