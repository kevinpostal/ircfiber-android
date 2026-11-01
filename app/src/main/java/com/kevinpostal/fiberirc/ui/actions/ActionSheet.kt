package com.kevinpostal.fiberirc.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** One row of an [ActionSheet] (a `UIAlertAction` / `UIAction`). */
internal data class SheetAction(
    val title: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * The Android form of a UIKit action sheet / context menu: a modal bottom sheet with an optional title and
 * subtitle, the [actions] in order, and Cancel last. Tapping a row runs its action, then [onDismiss] (so an action
 * that moves its host on to a follow-up step can tell the two apart).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(
    title: String?,
    subtitle: String?,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.panel,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Grid.gutter),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Grid.margin),
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Grid.margin, vertical = 2.dp),
                )
            }
            if (title != null || subtitle != null) HorizontalDivider(Modifier.padding(top = Grid.gutter), color = c.border)
            for (a in actions) {
                SheetRow(a) {
                    a.onClick()
                    onDismiss()
                }
            }
            HorizontalDivider(color = c.border)
            SheetRow(SheetAction("Cancel", onClick = {}), onDismiss)
        }
    }
}

@Composable
private fun SheetRow(action: SheetAction, onClick: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val color = when {
        !action.enabled -> c.textTertiary
        action.destructive -> c.error
        else -> c.textPrimary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Grid.tapTarget + Grid.unit)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Grid.section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Grid.margin + Grid.unit),
    ) {
        action.icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) }
        Text(action.title, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}
