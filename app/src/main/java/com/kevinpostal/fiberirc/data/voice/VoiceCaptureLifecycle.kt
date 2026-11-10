package com.kevinpostal.fiberirc.data.voice

import kotlinx.coroutines.CancellationException

/** Owns even a not-yet-published native track. stop() never waits for authorization/publication. */
internal class VoiceCaptureLifecycle<T>(private val release: (T) -> Unit) {
    private var revision = 0L
    private var owned: T? = null
    val active: Boolean get() = owned != null
    fun stop() {
        ++revision
        val old = owned; owned = null
        if (old != null) release(old)
    }
    suspend fun start(authorize: suspend () -> Unit, create: () -> T, publish: suspend (T) -> Unit, current: () -> Boolean) {
        stop()
        val ticket = revision
        fun checkCurrent() { if (ticket != revision || !current()) throw CancellationException("Microphone request cancelled") }
        authorize()
        checkCurrent()
        val track = create()
        owned = track
        try {
            publish(track)
            checkCurrent()
            if (owned !== track) throw CancellationException("Microphone no longer owned")
        } catch (e: Throwable) {
            // Publication may have finished after stop(): unpublish again, including late/unpublished tracks.
            release(track)
            if (owned === track) owned = null
            throw e
        }
    }
}

internal fun voiceMayUnmute(connected: Boolean, deafened: Boolean, serverMuted: Boolean, canSpeak: Boolean) =
    connected && !deafened && !serverMuted && canSpeak

internal fun voiceActionIsCurrent(instance: String?, currentInstance: String?, epoch: Long, currentEpoch: Long, owned: Boolean) =
    instance != null && instance == currentInstance && epoch == currentEpoch && owned

internal fun newestVoiceSession(current: VoiceSession?, incoming: VoiceSession?): VoiceSession? =
    if (current != null && incoming != null && current.id == incoming.id &&
        incoming.controlRevision < current.controlRevision) current else incoming
