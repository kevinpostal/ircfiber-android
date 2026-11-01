package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Reorder Connections… / Reorder Pins… (ports `ReorderViewController`): [items] (id to title) with drag handles —
 * drag a row's handle to move it (real drag-and-drop in the `LazyColumn`, not up/down buttons). Save hands the ids in
 * their new order to [onSave] and runs [onClose]; a failed save says why and stays open. Cancel keeps the old order.
 *
 * Callers: Reorder Connections → `NetworkLayoutStore.setNetworkOrder`; Reorder Pins → `PinListStore.setOrder` (both
 * throw on a failed save, after reverting).
 */
@Composable
fun ReorderScreen(
    title: String,
    items: List<Pair<String, String>>,
    onSave: suspend (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val list = remember(items) { items.toMutableStateList() }
    val listState = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun onDrag(dy: Float) {
        dragOffset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == draggingKey } ?: return
        val center = current.offset + dragOffset + current.size / 2f
        val target = visible.firstOrNull {
            it.key != draggingKey && center >= it.offset && center < it.offset + it.size
        } ?: return
        list.add(target.index, list.removeAt(current.index))
        // Keep the row under the finger: its new slot is where the target was.
        dragOffset += current.offset - target.offset
    }

    ManagementScaffold(
        title = title,
        navigation = { BarButton("Cancel", onClick = onClose) },
        actions = {
            BarButton("Save", enabled = !saving) {
                saving = true
                val ids = list.map { it.first }
                scope.launch {
                    try {
                        onSave(ids)
                        onClose()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    } finally {
                        saving = false
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            itemsIndexed(list, key = { _, it -> it.first }) { _, item ->
                val dragging = item.first == draggingKey
                Surface(
                    tonalElevation = if (dragging) 4.dp else 0.dp,
                    shadowElevation = if (dragging) 4.dp else 0.dp,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
                ) {
                    ListItem(
                        headlineContent = { Text(item.second, style = MaterialTheme.typography.bodyLarge) },
                        trailingContent = {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "Reorder ${item.second}",
                                modifier = Modifier.pointerInput(item.first) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingKey = item.first
                                            dragOffset = 0f
                                        },
                                        onDragEnd = { draggingKey = null; dragOffset = 0f },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            onDrag(amount.y)
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't save the order") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}
