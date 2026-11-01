package com.kevinpostal.fiberirc.ui.media

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.media.FiberImageLoader
import com.kevinpostal.fiberirc.data.media.MediaApi
import com.kevinpostal.fiberirc.data.media.MediaPage
import com.kevinpostal.fiberirc.data.media.Upload
import com.kevinpostal.fiberirc.data.media.UploadCategory
import com.kevinpostal.fiberirc.data.media.category
import com.kevinpostal.fiberirc.ui.settings.ErrorDialog
import com.kevinpostal.fiberirc.ui.settings.SettingsMessage
import com.kevinpostal.fiberirc.ui.settings.SettingsRow
import com.kevinpostal.fiberirc.ui.settings.SwipeActionRow
import com.kevinpostal.fiberirc.ui.settings.rememberAlertState
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Rows per page of File Uploads / Text Snippets. */
private const val PAGE_SIZE = 25

/**
 * A newest-first list loaded [PAGE_SIZE] at a time as it scrolls (the shared paging of `UploadsViewController` /
 * `SnippetsViewController`): the next page unless one is loading, one failed, or all are in.
 */
@Stable
internal class PagedList<T>(
    private val scope: CoroutineScope,
    private val id: (T) -> String,
    private val fetch: suspend (offset: Int, limit: Int) -> Result<MediaPage<T>>,
) {
    val items = mutableStateListOf<T>()
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadedOnce by mutableStateOf(false)
        private set

    fun loadMore() {
        if (loading || error != null || (items.isNotEmpty() && items.size >= total)) return
        loading = true
        val offset = items.size
        scope.launch {
            fetch(offset, PAGE_SIZE)
                .onSuccess { page ->
                    if (offset == items.size) {
                        total = page.total
                        items.addAll(page.items)
                        if (page.items.isEmpty()) total = items.size
                    }
                }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loadedOnce = true
            loading = false
        }
    }

    fun remove(item: T) {
        val i = items.indexOfFirst { id(it) == id(item) }
        if (i >= 0) {
            items.removeAt(i)
            total = maxOf(0, total - 1)
        }
    }

    /** What shows behind the list: "Loading…", the empty text, the error, or nothing. */
    fun message(empty: String): String? = when {
        items.isNotEmpty() -> null
        error != null -> error
        !loadedOnce -> "Loading…"
        else -> empty
    }
}

/** "Mar 3, 2026, 2:05 PM" for a server timestamp (Unix ms). */
internal fun mediaDate(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(ms))


/**
 * Each row of a paged list, with a hairline between rows; reaching the last five loads the next page. [items] is
 * what's shown (a filtered view of [list]'s items, or all of them).
 */
internal fun <T> LazyListScope.pagedRows(
    list: PagedList<T>,
    key: (T) -> String,
    items: List<T> = list.items,
    row: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
        LaunchedEffect(index, items.size) { if (index >= items.size - 5) list.loadMore() }
        if (index > 0) HorizontalDivider(color = LocalFiberIrcColors.current.border, thickness = 0.5.dp)
        row(item)
    }
}

/** [pagedRows] for a grid: each cell of [items] (shown from [list]); reaching the last five loads the next page. */
internal fun <T> LazyGridScope.pagedGridItems(
    list: PagedList<T>,
    key: (T) -> String,
    items: List<T> = list.items,
    cell: @Composable (T, Int) -> Unit,
) {
    itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
        LaunchedEffect(index, items.size) { if (index >= items.size - 5) list.loadMore() }
        cell(item, index)
    }
}

private enum class UploadsMode { Grid, List }

/**
 * File Uploads (the upload sheet; ports `UploadsViewController`): the account's uploads, newest first, 25 at a time
 * as you scroll, as a 3-column thumbnail grid (default) or rows with size and date. Chips filter by type (loading
 * more pages until a page's worth match or all are in). A tap previews the upload (Share and Delete there); a long
 * press starts selecting, then taps toggle and Delete (N) deletes them one by one. In the list, swipe to delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UploadsScreen(media: MediaApi, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val alert = rememberAlertState()
    val list = remember { PagedList<Upload>(scope, { it.id }) { o, l -> media.uploads(o, l) } }
    var mode by remember { mutableStateOf(UploadsMode.Grid) }
    var filter by remember { mutableStateOf<UploadCategory?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf(false) }
    var previewUpload by remember { mutableStateOf<Upload?>(null) }
    val filtered by remember(filter) { derivedStateOf { list.items.filter { filter == null || it.category() == filter } } }
    LaunchedEffect(Unit) { list.loadMore() }
    // A narrow filter over the pages in so far may match few: keep loading until a page's worth match or all are in.
    LaunchedEffect(filter, list.items.size, list.total, list.loading, list.error) {
        if (filter != null && filtered.size < PAGE_SIZE && !list.loading && list.error == null && list.items.size < list.total) {
            list.loadMore()
        }
    }

    val selecting = selected.isNotEmpty()
    fun toggle(upload: Upload) {
        selected = if (upload.id in selected) selected - upload.id else selected + upload.id
    }
    fun tap(upload: Upload) {
        if (selecting) toggle(upload) else previewUpload = upload
    }
    fun deleteOne(upload: Upload, onDone: () -> Unit) {
        scope.launch {
            media.deleteUpload(upload.id)
                .onSuccess {
                    list.remove(upload)
                    selected = selected - upload.id
                    onDone()
                }
                .onFailure { alert.show("Couldn't delete ${upload.name}", it) }
        }
    }
    fun deleteSelected() {
        val targets = list.items.filter { it.id in selected }
        deleting = true
        scope.launch {
            var failures = 0
            var lastError: String? = null
            for (upload in targets) {
                media.deleteUpload(upload.id)
                    .onSuccess { list.remove(upload) }
                    .onFailure {
                        failures++
                        lastError = it.message ?: it.javaClass.simpleName
                    }
            }
            selected = emptySet()
            deleting = false
            if (failures > 0) alert.show("Couldn't delete $failures of ${targets.size} file(s)", lastError)
        }
    }

    BackHandler { if (selecting) selected = emptySet() else onBack() }
    Scaffold(
        containerColor = c.groupedBackground,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "File Uploads") },
                navigationIcon = {
                    if (selecting) {
                        TextButton(onClick = { selected = emptySet() }) { Text("Cancel", color = c.link) }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = ::deleteSelected, enabled = !deleting) {
                            Text("Delete (${selected.size})", color = if (deleting) c.textTertiary else c.offlineError)
                        }
                    } else {
                        SingleChoiceSegmentedButtonRow(Modifier.padding(end = Grid.margin)) {
                            UploadsMode.entries.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = mode == m,
                                    onClick = { mode = m },
                                    shape = SegmentedButtonDefaults.itemShape(i, UploadsMode.entries.size),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = c.accent,
                                        activeContentColor = Color.White,
                                        activeBorderColor = c.border,
                                        inactiveContainerColor = Color.Transparent,
                                        inactiveContentColor = c.link,
                                        inactiveBorderColor = c.border,
                                    ),
                                    icon = {},
                                ) {
                                    Icon(
                                        if (m == UploadsMode.Grid) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                                        contentDescription = m.name,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.groupedBackground,
                    titleContentColor = c.heading,
                    navigationIconContentColor = c.link,
                    actionIconContentColor = c.link,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UploadFilterChips(filter, onSelect = { filter = it })
            Box(Modifier.weight(1f).fillMaxSize()) {
                if (filtered.isNotEmpty()) {
                    when (mode) {
                        UploadsMode.Grid -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(Grid.margin),
                            horizontalArrangement = Arrangement.spacedBy(Grid.gutter),
                            verticalArrangement = Arrangement.spacedBy(Grid.gutter),
                        ) {
                            pagedGridItems(list, { it.id }, filtered) { upload, _ ->
                                UploadTile(
                                    upload = upload,
                                    selected = upload.id in selected,
                                    selecting = selecting,
                                    density = density,
                                    onClick = { tap(upload) },
                                    onLongClick = { toggle(upload) },
                                )
                            }
                        }
                        UploadsMode.List -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Grid.margin, end = Grid.margin, bottom = Grid.section),
                        ) {
                            pagedRows(list, { it.id }, filtered) { upload ->
                                SwipeActionRow(
                                    label = "Delete",
                                    color = c.offlineError,
                                    enabled = !selecting,
                                    onSwipe = { deleteOne(upload) {} },
                                ) {
                                    SettingsRow(
                                        title = upload.name,
                                        modifier = Modifier
                                            .background(c.groupedCell)
                                            .combinedClickable(onClick = { tap(upload) }, onLongClick = { toggle(upload) }),
                                        subtitle = "${Formatter.formatFileSize(context, upload.size)} · ${mediaDate(upload.createdAt)}",
                                        leading = { UploadThumbnail(upload, density) },
                                        trailing = if (selecting) {
                                            {
                                                val on = upload.id in selected
                                                Icon(
                                                    if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                                    contentDescription = if (on) "Selected" else "Not selected",
                                                    tint = if (on) c.accent else c.textTertiary,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                emptyMessage(list, filter, filtered.isEmpty())?.let { SettingsMessage(it) }
            }
        }
    }

    previewUpload?.let { upload ->
        val close = { previewUpload = null }
        if (upload.category() == UploadCategory.Image) {
            ImageViewerScreen(upload.url, upload.url, media, onClose = close, onDelete = { deleteOne(upload, close) })
        } else {
            UploadPreviewScreen(upload, onClose = close, onDelete = { deleteOne(upload, close) })
        }
    }
    ErrorDialog(alert)
}

/**
 * What shows behind the uploads when [filter] matches none of them: the unfiltered list's message, "Loading…" while
 * more pages may still match, the load error, or the filter's empty text.
 */
private fun emptyMessage(list: PagedList<Upload>, filter: UploadCategory?, empty: Boolean): String? = when {
    !empty -> null
    filter == null || list.items.isEmpty() -> list.message("No uploads yet.")
    list.error != null -> list.error
    list.loading || list.items.size < list.total -> "Loading…"
    else -> "No ${filter.name.lowercase()}s yet."
}

/** A list row's leading thumbnail: [UploadPreview] in a [Grid.leadingColumn] box. */
@Composable
private fun UploadThumbnail(upload: Upload, density: Float) {
    val side = Grid.leadingColumn
    Box(Modifier.size(side).clip(RoundedCornerShape(MediaPreviewRadius))) {
        UploadPreview(upload, FiberImageLoader.maxPixel(side.value, density), side)
    }
}

/** The share sheet for a link. */
internal fun shareLink(url: String): Intent =
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null)
