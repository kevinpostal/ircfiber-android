package com.kevinpostal.fiberirc.data.voice

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionStateTest {
    private fun session(): VoiceSession {
        val root = javaClass.classLoader!!.getResourceAsStream("voice-v1.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject
        return VoiceContract.decodeSession(root.getValue("sessions").jsonArray[1].jsonObject.getValue("value"))
    }
    @Test fun staleSnapshotsCannotOverrideNewControlAcknowledgements() {
        val confirmed = session().copy(controlRevision = 9, muted = false)
        val stale = confirmed.copy(controlRevision = 8, muted = true)
        assertSame(confirmed, newestVoiceSession(confirmed, stale))
        val denied = confirmed.copy(controlRevision = 10, muted = true, serverMuted = true)
        assertSame(denied, newestVoiceSession(confirmed, denied))
    }
    @Test fun replacementAndTerminalAbsenceAreNeverHiddenByRevisionComparison() {
        val old = session().copy(controlRevision = 99)
        val replacement = old.copy(id = "new-session", controlRevision = 0, ownedByThisClient = false)
        assertSame(replacement, newestVoiceSession(old, replacement))
        assertNull(newestVoiceSession(old, null))
    }
}
