package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.R
import com.kevinpostal.fiberirc.data.voice.*
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceModeration(owner: VoiceRoomOwner, room: String, account: String) {
    var action by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FlowRow {
        for ((key, label) in listOf("server_mute" to "Server mute", "clear_server_mute" to "Clear server mute", "remove" to "Remove", "ban" to "Ban", "unban" to "Unban")) {
            TextButton(enabled = !saving, onClick = { action = key }) { Text(label) }
        }
    }
    if (saving) Text(stringResource(R.string.voice_pending))
    error?.let { Text(it, color = LocalFiberIrcColors.current.error) }
    action?.let { command -> VoiceChangeConfirmation(onCancel = { action = null }, onConfirm = {
        action = null; scope.launch {
            saving = true
            try { owner.controlApi.moderate(room, account, command); owner.refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { saving = false }
        }
    }) }
}

@Composable
private fun VoiceChangeConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.voice_confirm)) },
        text = { Text(stringResource(R.string.voice_manage_confirmation)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.voice_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.voice_cancel)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceAdministration(owner: VoiceRoomOwner, initialRoom: String?, onBack: () -> Unit) {
    val state by owner.state.collectAsState()
    val api = owner.controlApi
    val scope = rememberCoroutineScope()
    var roomId by remember { mutableStateOf(initialRoom ?: state.snapshot?.defaultRoomId) }
    val room = state.snapshot?.rooms?.firstOrNull { it.id == roomId }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<VoiceSettings?>(null) }
    var mode by remember { mutableStateOf("off") }
    var pilots by remember { mutableStateOf("") }
    var name by remember(room?.id, room?.revision, creating) { mutableStateOf(if (creating) "" else room?.name.orEmpty()) }
    var slug by remember(room?.id, room?.revision, creating) { mutableStateOf(if (creating) "" else room?.slug.orEmpty()) }
    var description by remember(room?.id, room?.revision, creating) { mutableStateOf(if (creating) "" else room?.description.orEmpty()) }
    var capacity by remember(room?.id, room?.revision, creating) { mutableStateOf(if (creating) "12" else room?.capacity?.toString().orEmpty()) }
    var privateRoom by remember(room?.id, room?.revision, creating) { mutableStateOf(!creating && room?.visibility == VoiceVisibility.PRIVATE) }
    var restricted by remember(room?.id, room?.revision, creating) { mutableStateOf(!creating && room?.joinPolicy == VoiceJoinPolicy.RESTRICTED) }
    var speakers by remember(room?.id, room?.revision, creating) { mutableStateOf(!creating && room?.speakPolicy == VoiceSpeakPolicy.SPEAKERS) }
    var closed by remember(room?.id, room?.revision, creating) { mutableStateOf(!creating && room?.closed == true) }
    var members by remember(roomId) { mutableStateOf<List<VoiceMember>>(emptyList()) }
    var nextOffset by remember(roomId) { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<VoiceAccount>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            saving = true; error = null
            try { action(); owner.refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { saving = false }
        }
    }
    suspend fun loadMembers(offset: Int = 0) {
        val id = roomId ?: return
        val page = api.members(id, offset)
        if (id != roomId) return
        members = if (offset == 0) page.members else members + page.members
        nextOffset = page.nextOffset
    }
    LaunchedEffect(api) { run { settings = api.settings(); settings?.let { mode = it.mode; pilots = it.pilotAccountIds.joinToString("\n") } } }
    LaunchedEffect(roomId) { if (roomId != null) run { loadMembers() } }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.voice_back)) }
        Text(stringResource(R.string.voice_manage), style = MaterialTheme.typography.headlineSmall)
        state.snapshot?.limits?.let { Text(stringResource(R.string.voice_limits, it.maxRoomCapacity, it.maxParticipants)) }
        if (saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.voice_pending)) }
        error?.let { Text(it, color = LocalFiberIrcColors.current.error) }
        Text(stringResource(R.string.voice_mode))
        FlowRow { for (value in listOf("off", "pilot", "on")) FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(value) }, enabled = !saving) }
        OutlinedTextField(pilots, { pilots = it }, label = { Text(stringResource(R.string.voice_pilot_accounts)) }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
        Button(enabled = !saving && settings != null, onClick = { confirmation = {
            val s = checkNotNull(settings)
            api.awaitResult(api.request("PUT", "settings", body = buildJsonObject {
                put("revision", s.revision); put("mode", mode)
                put("pilotAccountIds", JsonArray(pilots.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive)))
            }))
            settings = api.settings()
        } }) { Text(stringResource(R.string.voice_save)) }
        HorizontalDivider()
        FlowRow {
            state.snapshot?.rooms.orEmpty().forEach { r -> TextButton(onClick = { creating = false; roomId = r.id }) { Text(r.name) } }
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.voice_create_room)) }
        }
        if (room != null || creating) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.voice_name)) }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            if (creating) OutlinedTextField(slug, { slug = it }, label = { Text(stringResource(R.string.voice_slug)) }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.voice_description)) }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            OutlinedTextField(capacity, { capacity = it }, label = { Text(stringResource(R.string.voice_capacity)) }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            VoiceToggle(stringResource(R.string.voice_private), privateRoom, !saving) { privateRoom = it }
            VoiceToggle(stringResource(R.string.voice_restricted), restricted, !saving) { restricted = it }
            VoiceToggle(stringResource(R.string.voice_speakers), speakers, !saving) { speakers = it }
            VoiceToggle(stringResource(R.string.voice_close_room), closed, !saving) { closed = it }
            Button(enabled = !saving && name.isNotBlank() && name.length <= 64 && description.length <= 500 && capacity.toIntOrNull()?.let { it in 1..(state.snapshot?.limits?.maxRoomCapacity ?: 12) } == true,
                onClick = { confirmation = {
                    val body = buildJsonObject {
                        if (creating) put("slug", slug)
                        put("name", name); put("description", description); put("capacity", capacity.toInt())
                        put("visibility", if (privateRoom) "private" else "public")
                        put("joinPolicy", if (restricted) "restricted" else "members")
                        put("speakPolicy", if (speakers) "speakers" else "members"); put("closed", closed)
                    }
                    if (creating) api.awaitResult(api.request("POST", "rooms", body = body))
                    else api.awaitResult(api.request("PATCH", "rooms", checkNotNull(room).id, body = body, headers = mapOf("If-Match" to "\"vr-${room.revision}\"")))
                    creating = false
                } }) { Text(stringResource(R.string.voice_save)) }
            if (!creating && room?.closed == true && !room.isDefault) TextButton(enabled = !saving, onClick = { confirmation = {
                api.awaitResult(api.request("DELETE", "rooms", room.id)); roomId = state.snapshot?.defaultRoomId
            } }) { Text(stringResource(R.string.voice_delete_room)) }
            if (!creating && room != null) {
                HorizontalDivider(); Text(stringResource(R.string.voice_members), style = MaterialTheme.typography.titleMedium)
                members.forEach { member ->
                    key(member.accountId, member.revision) { VoiceMemberEditor(member, enabled = !saving, save = { role, join, speak, banned, muted -> confirmation = {
                        api.awaitResult(api.request("PUT", "rooms", room.id, "members", member.accountId, body = buildJsonObject {
                            put("revision", member.revision); put("role", role)
                            put("mayJoin", join?.let(::JsonPrimitive) ?: JsonNull); put("maySpeak", speak?.let(::JsonPrimitive) ?: JsonNull)
                            put("banned", banned); put("serverMuted", muted)
                        })); loadMembers()
                    } }) }
                }
                nextOffset?.let { offset -> TextButton(enabled = !saving, onClick = { run { loadMembers(offset) } }) { Text(stringResource(R.string.voice_more)) } }
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.voice_search_accounts)) }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !saving, onClick = { run { accounts = api.accounts(query) } }) { Text(stringResource(R.string.voice_search)) }
                accounts.filter { a -> members.none { it.accountId == a.accountId } }.forEach { a ->
                    TextButton(onClick = { members = members + VoiceMember(a.accountId, a.username, a.avatarUrl, VoiceRole.MEMBER, null, null, false, false, "0") }) { Text("${a.username} · ${a.accountId}") }
                }
            }
        }
    }
    confirmation?.let { action -> VoiceChangeConfirmation(onCancel = { confirmation = null }, onConfirm = { confirmation = null; run(action) }) }
}

@Composable
private fun VoiceToggle(label: String, value: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(value, changed, enabled = enabled) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceMemberEditor(member: VoiceMember, enabled: Boolean, save: (String, Boolean?, Boolean?, Boolean, Boolean) -> Unit) {
    var moderator by remember { mutableStateOf(member.role == VoiceRole.MODERATOR) }
    var join by remember { mutableStateOf(member.mayJoin) }
    var speak by remember { mutableStateOf(member.maySpeak) }
    var banned by remember { mutableStateOf(member.banned) }
    var muted by remember { mutableStateOf(member.serverMuted) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("${member.username} · ${member.accountId}")
        VoiceToggle("Moderator", moderator, enabled) { moderator = it }
        FlowRow {
            TextButton(enabled = enabled, onClick = { join = when (join) { null -> true; true -> false; false -> null } }) { Text("Join: ${join?.toString() ?: "inherit"}") }
            TextButton(enabled = enabled, onClick = { speak = when (speak) { null -> true; true -> false; false -> null } }) { Text("Speak: ${speak?.toString() ?: "inherit"}") }
        }
        VoiceToggle("Banned", banned, enabled) { banned = it }
        VoiceToggle("Server muted", muted, enabled) { muted = it }
        TextButton(enabled = enabled, onClick = { save(if (moderator) "moderator" else "member", join, speak, banned, muted) }) { Text(stringResource(R.string.voice_save)) }
    } }
}
