package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcClient
import com.kevinpostal.fiberirc.data.irc.IrcClientListener
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcState
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.data.prefs.BlockListStore
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import com.kevinpostal.fiberirc.data.prefs.TimestampFormat
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.nickColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Page requests one "Search older messages" run may send. */
private const val OLDER_PAGE_BUDGET = 10

/**
 * Gear → Search (ports `BufferSearchViewController`): the messages in one conversation that contain the query, newest
 * first, with the matches highlighted. It searches the rows this device holds; through the bouncer, "Search older
 * messages" pages in earlier history (up to 10 pages, stopping at the first page that adds a match). Tapping a result
 * calls [onSelect] with its line: the chat comes back scrolled to that message and flashes it.
 */
@Composable
fun BufferSearchScreen(
    client: IrcClient,
    buffer: IrcBuffer,
    blockList: BlockListStore,
    accountSettings: AccountSettingsStore,
    deviceSettings: DeviceSettingsStore,
    onSelect: (IrcLine) -> Unit,
    onClose: () -> Unit,
) {
    val masks by blockList.masks.collectAsState()
    val display by accountSettings.display.collectAsState()
    val device by deviceSettings.settings.collectAsState()
    val colors = LocalFiberIrcColors.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<BufferSearch.Match>()) }
    /** Bumped by live rows (debounced re-search) and by events that change the footer. */
    var liveRevision by remember { mutableIntStateOf(0) }
    var chromeRevision by remember { mutableIntStateOf(0) }
    var pagingOlder by remember { mutableStateOf(false) }
    var olderPagesLeft by remember { mutableIntStateOf(0) }
    var resultsBeforeOlder by remember { mutableIntStateOf(0) }

    fun runSearch() {
        results = BufferSearch.matches(buffer.lines, query) { line -> line.sender?.let(blockList::isBlocked) ?: false }
        chromeRevision++
    }

    fun finishOlderSearch() {
        pagingOlder = false
        olderPagesLeft = 0
        chromeRevision++
    }

    /** Requests the next page, or ends the run when the budget is spent or nothing can be requested. */
    fun loadNextOlderPage() {
        if (olderPagesLeft > 0 && client.requestOlderHistory(buffer)) {
            olderPagesLeft--
            chromeRevision++
        } else {
            finishOlderSearch()
        }
    }

    val listener = remember(client, buffer) {
        val target = buffer
        object : IrcClientListener {
            override fun onHistoryLoaded(client: IrcClient, buffer: IrcBuffer, added: Int) {
                if (buffer !== target) return
                runSearch()
                if (!pagingOlder) return
                // Keep paging until a page adds a match, the budget runs out, or there is nothing older.
                if (results.size == resultsBeforeOlder && !target.historyExhausted) loadNextOlderPage() else finishOlderSearch()
            }

            /** Paging opens once the bouncer's attach replay is in. */
            override fun onAttachReplayFinished(client: IrcClient) {
                chromeRevision++
            }

            override fun onLineAppended(client: IrcClient, buffer: IrcBuffer, line: IrcLine) {
                if (buffer === target) liveRevision++
            }

            override fun onStateChanged(client: IrcClient, state: IrcState, error: Throwable?) {
                if (state != IrcState.CONNECTED) {
                    pagingOlder = false
                    olderPagesLeft = 0
                }
                chromeRevision++
            }
        }
    }
    DisposableEffect(client, listener) {
        client.addObserver(listener)
        onDispose { client.removeObserver(listener) }
    }
    // Paged-in history stays while it is searched.
    LaunchedEffect(buffer) { buffer.onScreen = true }
    var searchedOnce by remember { mutableStateOf(false) }
    // Typing and live rows re-run the search once things settle; the first run is immediate.
    LaunchedEffect(query, liveRevision, masks) {
        if (searchedOnce) delay(150)
        searchedOnce = true
        runSearch()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${buffer.name}") },
                leadingIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                trailingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.panel,
                    unfocusedContainerColor = colors.panel,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "bufferSearchField" },
            )
        },
        containerColor = colors.background,
    ) { padding ->
        val blank = query.isBlank()
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(results) { match ->
                ResultRow(match, display.timestampFormat, device.showSeconds) { onSelect(match.line) }
                HorizontalDivider(color = colors.borderDark)
            }
            if (results.isEmpty()) {
                item {
                    Text(
                        if (blank) "Search the messages in ${buffer.name}." else "No messages match “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            if (!blank) {
                item {
                    chromeRevision // the footer follows the link and paging state
                    val loading = pagingOlder || buffer.historyLoading
                    var note: String? = null
                    var canPage = false
                    when {
                        buffer.historyExhausted -> note = "Searched the whole conversation."
                        loading -> note = "Searching older messages…"
                        client.canRequestOlderHistory(buffer) -> canPage = true
                        client.settings.viaBouncer && client.state != IrcState.CONNECTED ->
                            note = "Connect to search older messages."
                        else -> note = "Only messages on this device are searched."
                    }
                    // With no results the empty state says so; the footer keeps the note and the older-history control.
                    val count = if (results.isEmpty()) null else "${results.size} results"
                    val status = listOfNotNull(count, note).joinToString(" · ")
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (status.isNotEmpty()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (canPage) {
                            FilledTonalButton(onClick = {
                                pagingOlder = true
                                olderPagesLeft = OLDER_PAGE_BUDGET
                                resultsBeforeOlder = results.size
                                loadNextOlderPage()
                            }) { Text("Search older messages") }
                        }
                        if (loading) CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/** One search result: sender and time, then the message around the match with every match marked. */
@Composable
private fun ResultRow(match: BufferSearch.Match, format: TimestampFormat, showSeconds: Boolean, onClick: () -> Unit) {
    val colors = LocalFiberIrcColors.current
    val sender = match.line.sender ?: ""
    val time = whenText(match.line.time, format, showSeconds)
    val typography = MaterialTheme.typography
    val header = remember(match, time, colors) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = nickColor(sender, colors.dark))) { append(sender) }
            withStyle(SpanStyle(color = colors.textTertiary)) { append((if (sender.isEmpty()) "" else "  ") + time) }
        }
    }
    val body = remember(match, colors) {
        BufferSearch.snippet(match).annotated(
            SpanStyle(fontWeight = FontWeight.Bold, background = colors.accent.copy(alpha = 0.3f)),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = listOf(sender, match.text).filter { it.isNotEmpty() }.joinToString(": ") },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(header, style = typography.labelMedium)
        Text(body, style = typography.bodyMedium, color = colors.chatText, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** The time for today's messages, else the date and time. */
private fun whenText(t: Instant, format: TimestampFormat, showSeconds: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = MessageTime.string(t, format, showSeconds, zone)
    val date = t.atZone(zone).toLocalDate()
    if (date == LocalDate.now(zone)) return time
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date) + " " + time
}
