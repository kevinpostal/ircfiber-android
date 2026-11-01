package com.kevinpostal.fiberirc.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.media.MediaUrls
import com.kevinpostal.fiberirc.data.media.Paste
import com.kevinpostal.fiberirc.data.media.Snippet
import com.kevinpostal.fiberirc.ui.settings.ErrorDialog
import com.kevinpostal.fiberirc.ui.settings.SettingsRow
import com.kevinpostal.fiberirc.ui.settings.SettingsScaffold
import com.kevinpostal.fiberirc.ui.settings.SwipeActionRow
import com.kevinpostal.fiberirc.ui.settings.rememberAlertState
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch

/**
 * Text Snippets (the upload sheet; ports `SnippetsViewController`): the account's snippets, newest first, 25 at a
 * time as you scroll. Rows show the name, "N lines" and the date. A tap opens the snippet's page
 * ([LinkOpener], Custom Tab when [openLinksInApp]); swipe to delete.
 */
@Composable
fun SnippetsScreen(media: MediaApi, openLinksInApp: Boolean, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alert = rememberAlertState()
    val list = remember { PagedList<Paste>(scope, { it.id }) { o, l -> media.pastebins(o, l) } }
    LaunchedEffect(Unit) { list.loadMore() }

    SettingsScaffold(title = "Text Snippets", onBack = onBack, message = list.message("No snippets yet.")) {
        pagedRows(list, { it.id }) { paste ->
            SwipeActionRow(
                label = "Delete",
                color = c.offlineError,
                onSwipe = {
                    scope.launch {
                        media.deletePastebin(paste.id)
                            .onSuccess { list.remove(paste) }
                            .onFailure { alert.show("Couldn't delete ${paste.name}", it) }
                    }
                },
            ) {
                val lines = if (paste.lines == 1) "1 line" else "${paste.lines} lines"
                SettingsRow(
                    title = paste.name.ifEmpty { Snippet.DEFAULT_NAME },
                    subtitle = "$lines · ${mediaDate(paste.createdAt)}",
                    leading = { Icon(Icons.Outlined.Description, contentDescription = null, tint = c.textSecondary) },
                    onClick = { LinkOpener.open(context, MediaUrls.pasteUrl(paste.id, media.siteBase), openLinksInApp) },
                )
            }
        }
    }
    ErrorDialog(alert)
}
