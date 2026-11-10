package com.kevinpostal.fiberirc.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production resource/generation gate; no Android microphone or SDK network is opened. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceLifecycleTest {
    private class Track(val id: Int) { var releases = 0 }
    @Test fun mutedJoinNeedsNoMicrophoneQualification() {
        val fgs = VoiceForegroundQualification()
        fgs.promote(false, visible = true, permissionGranted = false)
        assertFalse(fgs.microphone)
        assertTrue(runCatching { fgs.promote(true, false, true) }.isFailure)
        assertFalse(fgs.microphone)
        assertTrue(runCatching { fgs.promote(true, true, false) }.isFailure)
        assertFalse(fgs.microphone)
        fgs.promote(true, true, true)
        assertTrue(fgs.microphone)
        fgs.promote(false, false, false)
        assertTrue("Never downgrade microphone FGS from a background mute", fgs.microphone)
        assertFalse("A new service needs visible qualification again", VoiceForegroundQualification().microphone)
    }
    @Test fun deniedPermissionOrServerGrantCreatesNoTrack() = runTest {
        var creates = 0
        val capture = VoiceCaptureLifecycle<Track> { it.releases++ }
        assertTrue(runCatching {
            capture.start(authorize = { error("permission denied") }, create = { Track(++creates) }, publish = {}, current = { true })
        }.isFailure)
        assertEquals(0, creates)
        assertFalse(capture.active)
    }
    @Test fun latePermissionAfterLeaveCannotCreateCapture() = runTest {
        val grant = CompletableDeferred<Unit>()
        var creates = 0
        val capture = VoiceCaptureLifecycle<Track> { it.releases++ }
        val start = async { capture.start({ grant.await() }, { Track(++creates) }, {}, { true }) }
        runCurrent(); capture.stop(); grant.complete(Unit); runCurrent()
        assertTrue(start.isCancelled)
        assertEquals(0, creates)
        assertFalse(capture.active)
    }
    @Test fun muteDisposesAnUnpublishedTrackAndLatePublicationCannotResurrectIt() = runTest {
        val publication = CompletableDeferred<Unit>()
        val track = Track(1)
        val capture = VoiceCaptureLifecycle<Track> { it.releases++ }
        val start = async { capture.start({}, { track }, { publication.await() }, { true }) }
        runCurrent()
        assertTrue(capture.active)
        capture.stop()
        assertEquals(1, track.releases)
        assertFalse(capture.active)
        publication.complete(Unit); runCurrent()
        assertTrue(start.isCancelled)
        assertEquals("Late publish is explicitly unpublished/disposed again", 2, track.releases)
    }
    @Test fun accountReplacementDuringPublicationDisposesCapture() = runTest {
        val publication = CompletableDeferred<Unit>()
        var sameAccount = true
        val track = Track(1)
        val capture = VoiceCaptureLifecycle<Track> { it.releases++ }
        val start = async { capture.start({}, { track }, { publication.await() }, { sameAccount }) }
        runCurrent(); sameAccount = false; publication.complete(Unit); runCurrent()
        assertTrue(start.isCancelled)
        assertEquals(1, track.releases)
        assertFalse(capture.active)
    }
    @Test fun repeatedExplicitUnmuteAlwaysOwnsAFreshTrack() = runTest {
        val released = mutableListOf<Int>()
        val capture = VoiceCaptureLifecycle<Track> { released += it.id }
        var next = 0
        repeat(3) {
            capture.start({}, { Track(++next) }, {}, { true })
            assertTrue(capture.active)
            capture.stop()
            assertFalse(capture.active)
        }
        assertEquals(listOf(1, 2, 3), released)
    }
    @Test fun publishFailureDisposesTheUnpublishedResource() = runTest {
        val track = Track(1)
        val capture = VoiceCaptureLifecycle<Track> { it.releases++ }
        assertTrue(runCatching { capture.start({}, { track }, { error("publish failed") }, { true }) }.isFailure)
        assertEquals(1, track.releases)
        assertFalse(capture.active)
    }
    @Test fun deafenServerMuteAndReconnectNeverAuthorizeTransmission() {
        assertTrue(voiceMayUnmute(true, false, false, true))
        assertFalse(voiceMayUnmute(false, false, false, true))
        assertFalse(voiceMayUnmute(true, true, false, true))
        assertFalse(voiceMayUnmute(true, false, true, true))
        assertFalse(voiceMayUnmute(true, false, false, false))
    }
    @Test fun staleNotificationsAndProcessDeathCannotControlANewRoom() {
        assertTrue(voiceActionIsCurrent("instance", "instance", 3, 3, true))
        assertFalse(voiceActionIsCurrent("instance", "instance", 2, 3, true))
        assertFalse(voiceActionIsCurrent("old-instance", "new-instance", 3, 3, true))
        assertFalse(voiceActionIsCurrent("instance", null, 3, 3, false))
        assertFalse(voiceActionIsCurrent(null, null, 0, 0, true))
    }
    @Test fun logsAndCrashUrlsLoseCredentials() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZXNzaW9uLWlkIn0.abcdefghijklmnopqrstuvwxy1234567890"
        val result = VoicePrivacy.redact("wss://voice.example/rtc?access_token=$jwt Authorization=Bearer-$jwt")
        assertFalse(result.contains(jwt))
        assertFalse(result.contains("access_token="))
        assertTrue(result.contains("wss://voice.example/rtc"))
        assertTrue(VoicePrivacy.sensitiveKey("Authorization"))
    }
}
