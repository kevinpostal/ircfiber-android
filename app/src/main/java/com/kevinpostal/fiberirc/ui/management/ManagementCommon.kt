package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcClientListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A management sheet's chrome: a top bar with [title], a leading [navigation] control and trailing [actions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManagementScaffold(
    title: String,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = navigation,
                actions = actions,
            )
        },
        content = content,
    )
}

/** A text bar button ("Done", "Cancel", "Save"). */
@Composable
internal fun BarButton(text: String, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = if (enabled) color else Color.Unspecified)
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SectionFooter(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A centered status line ("Loading channels…", "No bans"). */
@Composable
internal fun StatusMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A centered spinner (the iOS screens' `UIActivityIndicatorView` background view). */
@Composable
internal fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Registers [listener] with [client] while composed (the iOS screens' `addObserver` in `viewDidLoad`). */
@Composable
internal fun ObserveClient(client: IrcClient, listener: IrcClientListener) {
    DisposableEffect(client, listener) {
        client.addObserver(listener)
        onDispose { client.removeObserver(listener) }
    }
}

/** Medium date and short time, as IRCCloud shows topic and list-entry times (`ChannelInfoText.format`). */
internal fun mediumDateTime(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(t)
