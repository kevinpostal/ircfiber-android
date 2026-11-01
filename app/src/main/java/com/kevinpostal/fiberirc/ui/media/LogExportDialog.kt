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
import com.kevinpostal.fiberirc.data.irc.LogExport
import com.kevinpostal.fiberirc.ui.theme.Grid

/**
 * Export Log (ports `LogExport.run`): "Loading history…" while [IrcClient.loadAllHistory] walks [buffer] back, then
 * the share sheet with the text file; a write failure shows "Couldn't export <buffer>". [onDone] once it's over.
 */
@Composable
fun LogExportDialog(client: IrcClient, buffer: IrcBuffer, onDone: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

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
