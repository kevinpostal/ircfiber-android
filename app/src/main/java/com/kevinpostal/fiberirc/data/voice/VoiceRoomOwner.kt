package com.kevinpostal.fiberirc.data.voice

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.*
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.*
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.UUID

data class VoiceState(
    val snapshot: VoiceSnapshot? = null, val session: VoiceSession? = null,
    val phase: String = "disconnected", val pending: Boolean = false,
    val muted: Boolean = true, val deafened: Boolean = false,
    val error: String? = null, val errorCode: String? = null,
    val speakers: Set<String> = emptySet(), val devices: List<String> = emptyList(),
    val route: String? = null, val volume: Float = 1f, val openAudio: Boolean = false,
)

/** All SDK/native objects stay here, outside Compose and persistence. Main-thread confined. */
class VoiceRoomOwner(
    private val app: Application, private val site: SiteApi, private val http: OkHttpClient,
    private val baseUrl: String, private val deviceId: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transitions = Mutex()
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private var account: String? = null
    private var generation = 0L
    private var captureGeneration = 0L
    private var api: VoiceApi? = null
    private var events: VoiceEvents? = null
    private var room: Room? = null
    private data class OwnedTrack(val room: Room, val track: LocalAudioTrack)
    private val capture = VoiceCaptureLifecycle<OwnedTrack> { disposeTrack(it.room, it.track) }
    private var roomEvents: Job? = null
    private var reconcile: Job? = null
    private var refreshJob: Job? = null
    private var service: VoiceConnectionService? = null
    private var serviceReady: CompletableDeferred<VoiceConnectionService>? = null
    private var micQualified = false
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var deviceListener: AudioDeviceChangeListener? = null
    private var routeDevice: AudioDevice? = null
    private var visible = false
    /** Installed only by the visible Activity; never used from a service or a background first unmute. */
    var requestMicrophone: (suspend () -> Boolean)? = null
    val actionGeneration: Long get() = generation
    val instanceId: String? get() = api?.instanceId
    val controlApi: VoiceApi get() = checkNotNull(api) { "Sign in to use Audio" }

    fun setVisible(value: Boolean) { visible = value; if (value) refresh() }
    internal val isVisible: Boolean get() = visible
    fun openAudio() { _state.update { it.copy(openAudio = true) } }
    fun audioOpened() { _state.update { it.copy(openAudio = false) } }
    fun bind(accountId: String?) {
        if (account == accountId) return
        unbind()
        if (accountId == null) return
        account = accountId
        val instance = UUID.randomUUID().toString()
        api = VoiceApi(site, instance) { account == accountId && api?.instanceId == instance }
        val epoch = generation
        val prefs = app.getSharedPreferences("voice_device_preferences", 0)
        _state.value = VoiceState(volume = prefs.getFloat("$accountId.volume", 1f).coerceIn(0f, 1f))
        events = VoiceEvents(http, baseUrl, deviceId, { if (epoch == generation) refresh() }, {
            if (epoch == generation) { unbind(); _state.update { it.copy(error = "Session expired — sign in again") } }
        }).also { it.start() }
        reconcile = scope.launch { while (account == accountId) { refreshNow(); delay(5_000) } }
    }
    /** Immediate local hardware/output stop precedes every account/cookie teardown await. */
    fun unbind() {
        val oldApi = api; val id = _state.value.session?.takeIf { it.ownedByThisClient }?.id
        ++generation; ++captureGeneration
        closeRoom()
        events?.close(); events = null
        reconcile?.cancel(); reconcile = null; refreshJob?.cancel(); refreshJob = null
        serviceReady?.cancel(); serviceReady = null
        service?.finish(); service = null; micQualified = false
        account = null; api = null; _state.value = VoiceState()
        if (id != null && oldApi != null) scope.launch { runCatching { oldApi.leave(id) } }
    }
    fun refresh() {
        if (refreshJob?.isActive == true || api == null) return
        refreshJob = scope.launch { refreshNow() }
    }
    suspend fun refreshNow() {
        val a = api ?: return; val epoch = generation
        try {
            val snapshot = a.snapshot()
            if (a !== api || epoch != generation) return
            val current = _state.value.session
            val authoritative = newestVoiceSession(current, snapshot.session)
            if (current != null && current.ownedByThisClient && !state.value.pending &&
                snapshot.available && (authoritative == null || authoritative.id != current.id || !authoritative.ownedByThisClient || authoritative.status == VoiceSessionStatus.REVOKING)) {
                ++generation; ++captureGeneration; closeRoom(); service?.finish(); service = null; micQualified = false
                _state.update { it.copy(snapshot = snapshot, session = authoritative, phase = "disconnected", muted = true,
                    error = authoritative?.reason ?: "Voice session ended or was replaced", speakers = emptySet()) }
                restartEvents()
                return
            }
            if (authoritative?.serverMuted == true || authoritative?.canSpeak == false ||
                (authoritative?.muted == true && capture.active)) {
                ++captureGeneration; stopCapture()
            }
            _state.update { it.copy(snapshot = snapshot,
                session = if (current?.ownedByThisClient == true && authoritative?.id == current.id) authoritative else if (room == null) authoritative else current,
                muted = it.muted || authoritative?.muted == true || authoritative?.serverMuted == true,
                deafened = it.deafened || authoritative?.deafened == true) }
            room?.setSpeakerMute(state.value.deafened)
            subscribe()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (epoch == generation && a === api) report(e) }
    }
    suspend fun join(roomId: String, replacing: String? = null) {
        val a = controlApi; var epoch = generation
        check(visible) { "Open Audio in the app to join" }
        transitions.withLock {
            checkCurrent(epoch, a)
            _state.update { it.copy(pending = true, error = null, errorCode = null) }
            var admitted: VoiceJoin? = null
            try {
                // A denied/full target does not disturb the old membership or microphone.
                val join = a.join(roomId, replacing, current = { checkCurrent(epoch, a) }); admitted = join
                checkCurrent(epoch, a)
                check(visible) { "Return to Audio in the app to finish joining" }
                ++generation; epoch = generation; ++captureGeneration
                restartEvents()
                stopCapture(); closeRoom()
                _state.update { it.copy(session = join.session, muted = true, deafened = false, phase = "connecting") }
                val ready = CompletableDeferred<VoiceConnectionService>(); serviceReady = ready
                if (service == null) VoiceConnectionService.start(app, a.instanceId, epoch)
                else { checkNotNull(service).retarget(a.instanceId, epoch); ready.complete(checkNotNull(service)) }
                val foreground = withTimeout(5_000) { ready.await() }
                checkCurrent(epoch, a)
                serviceReady = null
                foreground.promote(false)
                LiveKit.loggingLevel = LoggingLevel.OFF
                LiveKit.enableWebRTCLogging = false
                val r = LiveKit.create(app, overrides = LiveKitOverrides(audioOptions = AudioOptions(
                    disableCommunicationModeWorkaround = true, disableAudioPrewarming = true)))
                room = r
                r.setMicrophoneMute(true)
                r.audioSwitchHandler?.loggingEnabled = false
                listenRouting(r, epoch)
                roomEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    r.events.collect { if (room === r && generation == epoch) onRoomEvent(it, r, epoch) }
                }
                r.connect(join.liveKitUrl, join.token, ConnectOptions(autoSubscribe = false, audio = false, video = false))
                if (epoch != generation || api !== a || room !== r) { r.disconnect(); r.release(); throw CancellationException("Voice session changed") }
                _state.update { it.copy(phase = "connected", muted = true) }
                subscribe(); refresh()
            } catch (e: Exception) {
                if (epoch == generation && api === a) {
                    if (admitted != null) { closeRoom(); service?.finish(); service = null; micQualified = false
                        _state.update { it.copy(session = null, phase = "disconnected", muted = true) }
                    }
                    if (e !is CancellationException) report(e)
                    refresh()
                }
                admitted?.let { scope.launch { runCatching { a.leave(it.session.id) } } }
                throw e
            } finally { if (epoch == generation && api === a) _state.update { it.copy(pending = false) } }
        }
    }
    suspend fun setMuted(value: Boolean) {
        // Do not queue a hardware stop behind an in-flight HTTP permission request.
        if (value) { ++captureGeneration; stopCapture(); _state.update { it.copy(muted = true) } }
        val intent = captureGeneration; val epoch = generation; val a = controlApi
        transitions.withLock {
            checkCurrent(epoch, a)
            val s = state.value.session?.takeIf { it.ownedByThisClient } ?: error("No owned voice session")
            if (value) {
                val result = a.control(s, true, state.value.deafened)
                checkCurrent(epoch, a)
                _state.update { it.copy(session = result, muted = true) }
            } else {
                check(voiceMayUnmute(state.value.phase == "connected", state.value.deafened, s.serverMuted, s.canSpeak)) { "Speaking is not permitted right now" }
                val r = checkNotNull(room)
                var allowed: VoiceSession? = null
                try {
                    capture.start(
                        authorize = {
                            val grantedAlready = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (!micQualified || !grantedAlready) {
                                check(visible) { "Open the app to allow microphone access" }
                                val granted = if (grantedAlready) true else requestMicrophone?.invoke() == true
                                checkCurrent(epoch, a); checkCapture(intent)
                                check(granted) { "Microphone permission denied. You can keep listening; allow it in Android app settings to speak." }
                                check(visible) { "Return to the app to enable the microphone" }
                                checkNotNull(service).promote(true)
                                micQualified = true
                            }
                            val result = a.control(s, false, false)
                            checkCurrent(epoch, a); checkCapture(intent)
                            check(!result.muted && !result.deafened && !result.serverMuted && result.canSpeak) { "Speaking was not authorized" }
                            allowed = result
                            _state.update { it.copy(session = result) }
                        },
                        create = {
                            r.setMicrophoneMute(false)
                            OwnedTrack(r, r.localParticipant.createAudioTrack("microphone"))
                        },
                        publish = { owned ->
                            owned.track.start()
                            check(r.localParticipant.publishAudioTrack(owned.track)) { "Microphone publication failed" }
                        },
                        current = { epoch == generation && a === api && room === r && intent == captureGeneration },
                    )
                    _state.update { it.copy(session = checkNotNull(allowed), muted = false, error = null, errorCode = null) }
                } catch (e: Exception) {
                    if (epoch == generation && a === api) {
                        stopCapture()
                        allowed?.let { granted -> _state.update { it.copy(session = granted, muted = true) } }
                        perform { setMuted(true) }
                    }
                    throw e
                }
            }
            service?.update()
        }
    }
    suspend fun setDeafened(value: Boolean) {
        ++captureGeneration; stopCapture(); room?.setSpeakerMute(true)
        _state.update { it.copy(muted = true, deafened = value || it.deafened) }
        val epoch = generation; val a = controlApi
        transitions.withLock {
            checkCurrent(epoch, a)
            val s = checkNotNull(state.value.session).also { check(it.ownedByThisClient) }
            val result = a.control(s, true, value)
            checkCurrent(epoch, a)
            _state.update { it.copy(session = result, muted = true, deafened = value) }
            room?.setSpeakerMute(value); subscribe(); service?.update()
        }
    }
    /** Stop locally now; logout must not wait indefinitely for a remote removal operation. */
    fun stopBeforeCredentialLoss() { scope.launch(start = CoroutineStart.UNDISPATCHED) { leave() } }
    suspend fun leave() {
        val a = api; val s = state.value.session?.takeIf { it.ownedByThisClient }
        ++captureGeneration; ++generation; val epoch = generation
        closeRoom(); serviceReady?.cancel(); serviceReady = null
        service?.finish(); service = null; micQualified = false
        _state.update { it.copy(session = null, phase = "disconnected", pending = s != null, muted = true, deafened = false, speakers = emptySet()) }
        // Rebuild account event callbacks after a connection generation changes; the instance stays in memory.
        restartEvents()
        try { if (s != null && a != null) a.leave(s.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (epoch == generation) report(e) }
        finally { if (epoch == generation) { _state.update { it.copy(pending = false) }; refresh() } }
    }
    private fun restartEvents() {
        events?.close()
        if (api != null) events = VoiceEvents(http, baseUrl, deviceId, { refresh() }, { unbind() }).also { it.start() }
    }
    internal fun serviceAttached(value: VoiceConnectionService, instance: String, epoch: Long): Boolean {
        if (api?.instanceId != instance || epoch != generation || state.value.session?.ownedByThisClient != true) return false
        service = value; serviceReady?.complete(value); return true
    }
    internal fun serviceLost(value: VoiceConnectionService) {
        if (service !== value) return
        service = null; micQualified = false
        stopCapture(); closeRoom(); _state.update { it.copy(muted = true, phase = "disconnected", error = "Android stopped voice. Join again from the app.") }
        scope.launch { leave() }
    }
    internal fun action(instance: String?, epoch: Long, action: String) {
        if (!voiceActionIsCurrent(instance, api?.instanceId, epoch, generation, state.value.session?.ownedByThisClient == true)) return
        if (action == "mute" && state.value.muted && (!micQualified ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            _state.update { it.copy(error = "Open Audio in the app to enable the microphone.") }
            openAudio(); runCatching { service?.openApp() }; return
        }
        perform { when (action) {
            "mute" -> setMuted(!state.value.muted)
            "deafen" -> setDeafened(!state.value.deafened)
            "leave" -> leave()
        } }
    }
    fun perform(action: suspend () -> Unit) { scope.launch {
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    } }
    private fun report(e: Exception) {
        // Never log SDK errors/URLs: a transport exception may contain a signaling JWT.
        val text = if (e is VoiceApiError) e.message else if (e is IllegalStateException) e.message else "Voice connection failed. Try again."
        _state.update { it.copy(error = text?.let(VoicePrivacy::redact), errorCode = (e as? VoiceApiError)?.code) }
    }
    fun clearError() { _state.update { it.copy(error = null, errorCode = null) } }
    private fun checkCurrent(epoch: Long, a: VoiceApi) { if (epoch != generation || api !== a) throw CancellationException("Voice account/session changed") }
    private fun checkCapture(intent: Long) { if (intent != captureGeneration) throw CancellationException("Microphone request cancelled") }
    private fun disposeTrack(r: Room?, track: LocalAudioTrack) {
        runCatching { track.stopPrewarm() }
        runCatching { r?.localParticipant?.unpublishTrack(track, stopOnUnpublish = true) }
        runCatching { track.stop() }
        runCatching { track.dispose() }
    }
    private fun stopCapture() { runCatching { room?.setMicrophoneMute(true) }; capture.stop() }
    private fun closeRoom() {
        stopCapture(); roomEvents?.cancel(); roomEvents = null
        val r = room; room = null
        if (r != null) {
            runCatching { r.setSpeakerMute(true) }
            runCatching { focusListener?.let { r.audioSwitchHandler?.unregisterOnAudioFocusChangeListener(it) } }
            runCatching { deviceListener?.let { r.audioSwitchHandler?.unregisterAudioDeviceChangeListener(it) } }
            runCatching { r.disconnect() }
            runCatching { r.release() }
        }
        focusListener = null; deviceListener = null; routeDevice = null
        _state.update { it.copy(devices = emptyList(), route = null) }
    }
    private fun subscribe() {
        val r = room ?: return
        val allowed = state.value.snapshot?.rooms?.firstOrNull { it.id == state.value.session?.roomId }?.participants
            ?.map { it.sessionId }?.toSet() ?: emptySet()
        for (participant in r.remoteParticipants.values) for (publication in participant.trackPublications.values) {
            val remote = publication as? RemoteTrackPublication ?: continue
            remote.setSubscribed(!state.value.deafened && participant.identity?.value in allowed && remote.kind == Track.Kind.AUDIO)
            (remote.track as? RemoteAudioTrack)?.setVolume(if (state.value.deafened) 0.0 else state.value.volume.toDouble())
        }
    }
    private fun onRoomEvent(event: RoomEvent, r: Room, epoch: Long) {
        when (event) {
            is RoomEvent.Reconnecting -> { interrupt("reconnecting"); refresh() }
            is RoomEvent.Reconnected -> { interrupt("connected"); refresh(); subscribe() }
            is RoomEvent.Disconnected -> {
                ++captureGeneration; stopCapture()
                _state.update { it.copy(muted = true, phase = "disconnected", speakers = emptySet()) }
                // Predecessor removal is expected while an explicit move/takeover admission drains.
                if (!state.value.pending) scope.launch { leave() }
            }
            is RoomEvent.TrackMuted -> if (event.participant === r.localParticipant) interrupt(state.value.phase)
            is RoomEvent.ParticipantPermissionsChanged -> if (event.participant === r.localParticipant) refresh()
            is RoomEvent.ActiveSpeakersChanged -> _state.update { it.copy(speakers = event.speakers.mapNotNull { p -> p.identity?.value }.toSet()) }
            is RoomEvent.TrackPublished, is RoomEvent.TrackSubscribed, is RoomEvent.ParticipantConnected -> { subscribe(); refresh() }
            else -> Unit
        }
        if (generation == epoch) service?.update()
    }
    private fun interrupt(phase: String) {
        ++captureGeneration; stopCapture(); _state.update { it.copy(muted = true, phase = phase) }
        perform { setMuted(true) }
    }
    private fun listenRouting(r: Room, epoch: Long) {
        val handler = r.audioSwitchHandler ?: return
        val focus = AudioManager.OnAudioFocusChangeListener { change -> scope.launch {
            if (generation == epoch && room === r && change != AudioManager.AUDIOFOCUS_GAIN) interrupt(state.value.phase)
        } }
        val devices = object : AudioDeviceChangeListener {
            override fun invoke(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice?) { scope.launch {
                if (epoch != generation || room !== r) return@launch
                val old = routeDevice
                if (old != null && old !in audioDevices) interrupt(state.value.phase)
                routeDevice = selectedAudioDevice
                if (old == null) {
                    val preferred = account?.let { app.getSharedPreferences("voice_device_preferences", 0).getString("$it.route", null) }
                    audioDevices.firstOrNull { it.name == preferred && it != selectedAudioDevice }?.let { handler.selectDevice(it) }
                }
                _state.update { it.copy(devices = audioDevices.map { d -> d.name }, route = selectedAudioDevice?.name) }
            } }
        }
        focusListener = focus; deviceListener = devices
        handler.registerOnAudioFocusChangeListener(focus); handler.registerAudioDeviceChangeListener(devices)
    }
    fun refreshAudioRoutes() {
        val handler = room?.audioSwitchHandler ?: return
        interrupt(state.value.phase)
        handler.stop(); handler.start()
    }
    fun selectOutput(name: String) {
        val handler = room?.audioSwitchHandler ?: return
        account?.let { app.getSharedPreferences("voice_device_preferences", 0).edit().putString("$it.route", name).apply() }
        handler.availableAudioDevices.firstOrNull { it.name == name }?.let { handler.selectDevice(it) }
    }
    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f); _state.update { it.copy(volume = v) }; subscribe()
        account?.let { app.getSharedPreferences("voice_device_preferences", 0).edit().putFloat("$it.volume", v).apply() }
    }
}
