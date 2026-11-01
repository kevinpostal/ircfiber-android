package com.kevinpostal.fiberirc.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

// Look shared by the Settings screens (ports `SettingsStyle` + `SettingsTableViewController`): inset grouped
// cards on the grouped background at the grid margin, header/footer text on the rows' text edge.

/** A Settings screen: title bar with Back (and the system back gesture), optional pull-to-refresh, grouped list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    refreshing: Boolean? = null,
    onRefresh: () -> Unit = {},
    message: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = LocalFiberIrcColors.current
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = c.groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.groupedBackground,
                    titleContentColor = c.heading,
                    navigationIconContentColor = c.link,
                    actionIconContentColor = c.link,
                ),
            )
        },
    ) { padding ->
        val list: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Grid.margin, end = Grid.margin, bottom = Grid.section),
                    content = content,
                )
                if (message != null) SettingsMessage(message)
            }
        }
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (refreshing != null) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { list() }
            } else {
                list()
            }
        }
    }
}

/** Centred status / empty-state text behind the list (`SettingsStyle.message`). */
@Composable
fun SettingsMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = Grid.margin * 2), contentAlignment = Alignment.Center) {
        Text(text, color = LocalFiberIrcColors.current.textSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One inset grouped card: optional header above and footer below, rows separated by hairlines. */
fun LazyListScope.settingsSection(
    key: String,
    header: String? = null,
    footer: String? = null,
    footerColor: Color? = null,
    rows: List<@Composable () -> Unit>,
) {
    item(key = key) {
        val c = LocalFiberIrcColors.current
        Column(Modifier.fillMaxWidth().padding(top = Grid.section)) {
            if (header != null) {
                Text(
                    header.uppercase(),
                    color = c.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = Grid.margin, end = Grid.margin, bottom = Grid.unit * 2),
                )
            }
            if (rows.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.groupedCell)) {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = Grid.margin), color = c.border, thickness = 0.5.dp)
                        row()
                    }
                }
            }
            if (footer != null) {
                Text(
                    footer,
                    color = footerColor ?: c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = Grid.margin, end = Grid.margin, top = Grid.unit * 2),
                )
            }
        }
    }
}

/**
 * A list row: title, optional secondary text (beside it as a value, or below as a subtitle), optional trailing
 * control. Tappable when [onClick] is set.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color? = null,
    valueColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    centered: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalFiberIrcColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Grid.tapTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Grid.margin, vertical = Grid.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        if (leading != null) {
            Box(Modifier.width(Grid.leadingColumn), contentAlignment = Alignment.CenterStart) { leading() }
            Box(Modifier.width(Grid.gutter))
        }
        Column(if (centered) Modifier else Modifier.weight(1f)) {
            Text(title, color = titleColor ?: c.chatText, style = MaterialTheme.typography.bodyLarge, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
            if (subtitle != null) {
                Text(subtitle, color = valueColor ?: c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (value != null) {
            Text(
                value,
                color = valueColor ?: c.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = Grid.gutter).weight(1f, fill = false),
            )
        }
        if (trailing != null) {
            Box(Modifier.padding(start = Grid.gutter)) { trailing() }
        }
    }
}

/**
 * A trailing swipe action (`trailingSwipeActionsConfigurationForRowAt`): swiping the row end-to-start shows
 * [label] on [color] and calls [onSwipe]; the row springs back (the action decides, e.g. after a confirmation).
 */
@Composable
fun SwipeActionRow(label: String, color: Color, enabled: Boolean = true, onSwipe: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) return content()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onSwipe()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = Grid.margin), contentAlignment = Alignment.CenterEnd) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Box(Modifier.background(LocalFiberIrcColors.current.groupedCell)) { content() }
    }
}

/** A row with a switch; flipping it calls [onChange]. The switch always shows the model's value. */
@Composable
fun SettingsSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalFiberIrcColors.current
    SettingsRow(title = title, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
        )
    }
}

/** An error alert's content; shown by [ErrorDialog]. */
data class SettingsAlert(val title: String, val message: String?)

/** Holder for the screen's one alert (`showError(_:title:)`). */
@Stable
class AlertState {
    var current by mutableStateOf<SettingsAlert?>(null)
        private set

    fun show(title: String, error: Throwable) {
        current = SettingsAlert(title, error.message ?: error.javaClass.simpleName)
    }

    fun show(title: String, message: String?) {
        current = SettingsAlert(title, message)
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberAlertState(): AlertState = remember { AlertState() }

@Composable
fun ErrorDialog(state: AlertState) {
    val alert = state.current ?: return
    AlertDialog(
        onDismissRequest = state::dismiss,
        title = { Text(alert.title) },
        text = alert.message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = state::dismiss) { Text("OK") } },
    )
}

/** A Cancel / destructive-button confirmation (`confirm(title:message:button:…)`). */
data class Confirmation(val title: String, val message: String?, val button: String, val perform: () -> Unit)

@Composable
fun ConfirmDialog(confirmation: Confirmation?, onDismiss: () -> Unit) {
    confirmation ?: return
    val c = LocalFiberIrcColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirmation.title) },
        text = confirmation.message?.let { { Text(it) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); confirmation.perform() }) { Text(confirmation.button, color = c.error) }
        },
    )
}

/** "5 min. ago" for a server timestamp (ms), corrected by [skew] = local clock − server clock (`SettingsStyle.relative`). */
fun relativeTime(ms: Long, skew: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(ms + skew, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * Copies a credential: flagged sensitive (kept out of clipboard previews/sync, API 33+) and cleared after two
 * minutes if still on the clipboard — a credential must not linger (iOS: `.localOnly` + `.expirationDate`).
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        val current = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        if (current == text) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, 120_000)
}
