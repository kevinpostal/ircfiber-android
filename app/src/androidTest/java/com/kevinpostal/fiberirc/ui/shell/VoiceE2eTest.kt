package com.kevinpostal.fiberirc.ui.shell

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.support.FakeSiteServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** UI/control-plane behavior only. A real-device + real-SFU matrix remains mandatory for media. */
@RunWith(AndroidJUnit4::class)
class VoiceE2eTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: FakeSiteServer
    private lateinit var deps: Deps
    private var microphoneRequests = 0
    private fun snapshot(session: String = "null") = """{
      "version":1,"enabled":true,"available":true,"canManage":false,"revision":"1",
      "limits":{"maxRoomCapacity":12,"maxParticipants":24},"defaultRoomId":"lobby",
      "rooms":[{"id":"lobby","slug":"lobby","name":"Lobby","description":"Community room",
        "visibility":"public","joinPolicy":"members","speakPolicy":"members","capacity":12,"revision":"1",
        "closed":false,"isDefault":true,"canJoin":true,"canSpeak":true,"canModerate":false,"canManage":false,
        "participantCount":0,"participants":[]},
       {"id":"restricted","slug":"restricted","name":"Restricted","description":"Access required",
        "visibility":"public","joinPolicy":"restricted","speakPolicy":"speakers","capacity":4,"revision":"1",
        "closed":false,"isDefault":false,"canJoin":false,"canSpeak":false,"canModerate":false,"canManage":false,
        "participantCount":null,"participants":null}],"session":$session}
    """.trimIndent()
    private fun host(session: String = "null") {
        server.on("GET", "/api/voice", body = snapshot(session))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            deps.voice.bind("real-website-account-id")
            deps.voice.setVisible(true)
            deps.voice.requestMicrophone = { microphoneRequests++; false }
        }
        compose.setContent { VoiceAudioScreen(deps.voice, server.baseUrl, initialRoom = "lobby", onBack = {}) }
        compose.waitUntil(5_000) { deps.voice.state.value.snapshot != null }
    }
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
        deps = Deps(app, server.baseUrl)
    }
    @After fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { deps.voice.unbind() }
        server.shutdown()
    }
    @Test fun directoryAndPreviewNeverJoinOrRequestMicrophone() {
        host()
        compose.onNodeWithText("Lobby · 0/12").assertIsDisplayed().performClick()
        compose.onNodeWithText("Join muted").assertExists()
        assertEquals(0, microphoneRequests)
        assertEquals(0, server.count("POST", "/api/voice/sessions"))
        assertNull(deps.voice.state.value.session)
    }
    @Test fun lockedDirectoryDoesNotInventPresenceAndCannotJoin() {
        host()
        compose.onNodeWithText("Restricted · Restricted room").performScrollTo().performClick()
        compose.onNodeWithText("Join muted").assertIsNotEnabled()
        assertNull(deps.voice.state.value.snapshot!!.rooms.last().participants)
        assertNull(deps.voice.state.value.snapshot!!.rooms.last().participantCount)
        assertEquals(0, microphoneRequests)
    }
    @Test fun cancelledReplacementLeavesTheExistingSessionUntouched() {
        val active = """{"id":"old-session","roomId":"other-room","status":"connected","muted":true,
          "deafened":false,"serverMuted":false,"canSpeak":true,"controlRevision":4,"ownedByThisClient":false}"""
        host(active)
        compose.onNodeWithText("Move to this room").performScrollTo().performClick()
        compose.onNodeWithText("Replace active session").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("old-session", deps.voice.state.value.session!!.id)
        assertFalse(deps.voice.state.value.session!!.ownedByThisClient)
        assertEquals(0, server.count("POST", "/api/voice/sessions"))
        assertEquals(0, server.count("DELETE", "/api/voice/sessions/old-session"))
        assertEquals(0, microphoneRequests)
    }
}
