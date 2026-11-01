package com.kevinpostal.fiberirc.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.kevinpostal.fiberirc.data.media.UploadCategory
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

private val Filters = listOf(
    "All" to null,
    "Images" to UploadCategory.Image,
    "Videos" to UploadCategory.Video,
    "Files" to UploadCategory.File,
)

/** File Uploads' All / Images / Videos / Files row, styled like the composer's pill chips (`CompletionStrip`). */
@Composable
internal fun UploadFilterChips(filter: UploadCategory?, onSelect: (UploadCategory?) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFiberIrcColors.current
    LazyRow(
        modifier = modifier.fillMaxWidth().height(Grid.control),
        contentPadding = PaddingValues(horizontal = Grid.margin),
        horizontalArrangement = Arrangement.spacedBy(Grid.unit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(Filters, key = { it.first }) { (label, value) ->
            val selected = value == filter
            Box(
                Modifier
                    .height(Grid.control - Grid.gutter)
                    .clip(RoundedCornerShape(MediaPreviewRadius))
                    .background(if (selected) c.accent else c.inputPill)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = Grid.margin),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.White else c.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}
