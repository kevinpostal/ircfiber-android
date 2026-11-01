package com.kevinpostal.fiberirc.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A screen presented over whatever shows it (a UIKit modal in a navigation controller): full-bleed, its own back
 * handling via [onDismiss]. Composition locals (theme, colours) carry through.
 */
@Composable
internal fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        content = content,
    )
}
