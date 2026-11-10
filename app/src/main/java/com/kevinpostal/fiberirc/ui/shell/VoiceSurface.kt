package com.kevinpostal.fiberirc.ui.shell

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kevinpostal.fiberirc.R
import com.kevinpostal.fiberirc.data.voice.*
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

val LocalVoiceOverlay = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** The only permission bridge. Join and room selection never invoke this launcher. */
@Composable
fun VoicePermissionHost(owner: VoiceRoomOwner) {
    var pending by remember(owner) { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        pending?.complete(allowed); pending = null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle) {
        owner.requestMicrophone = {
            val answer = CompletableDeferred<Boolean>(); pending = answer
            launcher.launch(Manifest.permission.RECORD_AUDIO)
            try {
                val allowed = answer.await()
                // The OS result can precede ON_RESUME. Never qualify a microphone while still backgrounded.
                if (allowed) withTimeoutOrNull(2_000) {
                    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                }
                allowed
            } finally { if (pending === answer) pending = null }
        }
        owner.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) owner.setVisible(true)
            if (event == Lifecycle.Event.ON_PAUSE) owner.setVisible(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner.setVisible(false); owner.requestMicrophone = null; pending?.cancel(); pending = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceDock(owner: VoiceRoomOwner, open: () -> Unit) {
    val state by owner.state.collectAsState()
    val session = state.session?.takeIf { it.ownedByThisClient }
    if (session == null && !state.pending) return
    val room = state.snapshot?.rooms?.firstOrNull { it.id == session?.roomId }
    val c = LocalFiberIrcColors.current
    Surface(color = c.groupedCell, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            TextButton(onClick = open, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(if (session == null) stringResource(R.string.voice_pending) else "${room?.name ?: stringResource(R.string.voice_audio)} · ${state.phase} · ${stringResource(if (state.muted) R.string.voice_muted else R.string.voice_speaking)}")
            }
            if (session?.serverMuted == true) Text(stringResource(R.string.voice_server_muted), color = c.textSecondary)
            state.error?.let { Text(it, color = c.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (session != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { owner.perform { owner.setMuted(!state.muted) } }, enabled = state.muted.not() || (session.canSpeak && !session.serverMuted && !state.deafened && state.phase == "connected")) {
                    Text(stringResource(if (state.muted) R.string.voice_unmute else R.string.voice_mute))
                }
                TextButton(onClick = { owner.perform { owner.setDeafened(!state.deafened) } }) { Text(stringResource(if (state.deafened) R.string.voice_undeafen else R.string.voice_deafen)) }
                TextButton(onClick = { owner.perform { owner.leave() } }) { Text(stringResource(R.string.voice_leave), color = c.error) }
            }
            if (state.pending && session != null) Text(stringResource(R.string.voice_pending), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceAudioScreen(owner: VoiceRoomOwner, baseUrl: String, initialRoom: String? = null, onBack: () -> Unit) {
    val state by owner.state.collectAsState()
    var selected by remember(initialRoom) { mutableStateOf(initialRoom) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var manage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val c = LocalFiberIrcColors.current
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) owner.refreshAudioRoutes() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(owner) { owner.refresh() }
    if (manage) {
        VoiceAdministration(owner, selected, onBack = { manage = false })
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow {
            TextButton(onClick = onBack) { Text(stringResource(R.string.voice_back)) }
            TextButton(onClick = owner::refresh) { Text(stringResource(R.string.voice_refresh)) }
            if (state.snapshot?.canManage == true) TextButton(onClick = { manage = true }) { Text(stringResource(R.string.voice_manage)) }
        }
        Text(stringResource(R.string.voice_audio), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.voice_privacy), color = c.textSecondary)
        Text(stringResource(R.string.voice_join_privacy), color = c.textSecondary)
        if (state.snapshot?.available == false) Text(stringResource(R.string.voice_unavailable), color = c.error)
        if (state.snapshot?.enabled == false) Text(stringResource(R.string.voice_disabled))
        state.error?.let { error ->
            Text(error, color = c.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            if (error.contains("permission", true)) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.voice_allow_microphone)) }
        }
        if (state.pending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.voice_pending)) }
        for (room in state.snapshot?.rooms.orEmpty()) {
            Card(Modifier.fillMaxWidth().clickable { selected = room.id }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(room.name + (room.participantCount?.let { " · $it/${room.capacity}" } ?: " · ${stringResource(R.string.voice_locked)}"), style = MaterialTheme.typography.titleMedium)
                    Text(room.description)
                    if (room.closed) Text(stringResource(R.string.voice_closed))
                    if (selected == room.id) {
                        room.participants?.forEach { p ->
                            val speaking = p.sessionId in state.speakers && state.session?.roomId == room.id
                            Text("${p.username} · ${if (p.serverMuted) stringResource(R.string.voice_server_muted) else if (speaking) stringResource(R.string.voice_speaking) else if (p.deafened) stringResource(R.string.voice_deafen) else if (p.muted) stringResource(R.string.voice_muted) else ""}")
                            if (room.canModerate) VoiceModeration(owner, room.id, p.accountId)
                        }
                        if (state.session?.roomId != room.id || state.session?.ownedByThisClient != true || state.phase == "disconnected") {
                            Button(enabled = room.canJoin && !room.closed && state.snapshot?.available == true && !state.pending, onClick = {
                                val old = state.session
                                if (old != null) confirm = room.id to old.id else owner.perform { owner.join(room.id) }
                            }) { Text(stringResource(if (state.session != null) R.string.voice_move else R.string.voice_join)) }
                        }
                        if (room.canManage) TextButton(onClick = { manage = true }) { Text(stringResource(R.string.voice_edit_room)) }
                    }
                }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.voice_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.voice_volume))
        Slider(value = state.volume, onValueChange = owner::setVolume)
        Text(stringResource(R.string.voice_route, state.route ?: "Android default"))
        FlowRow { state.devices.forEach { name -> TextButton(onClick = { owner.selectOutput(name) }) { Text(name) } } }
        if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text(stringResource(R.string.voice_bluetooth)) }
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.voice_notifications)) }
        Text(stringResource(R.string.voice_notification_privacy), color = c.textSecondary)
        TextButton(onClick = { LinkOpener.open(context, "${baseUrl.trimEnd('/')}/support", true) }) { Text(stringResource(R.string.voice_report)) }
    }
    confirm?.let { (room, previous) -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(stringResource(R.string.voice_replace)) }, text = { Text(stringResource(R.string.voice_replace_warning)) },
        confirmButton = { TextButton(onClick = { confirm = null; owner.perform { owner.join(room, previous) } }) { Text(stringResource(R.string.voice_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.voice_cancel)) } }) }
}
