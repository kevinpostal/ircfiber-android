package com.kevinpostal.fiberirc.ui.shell

import android.app.Application
import com.kevinpostal.fiberirc.data.irc.IrcMessage
import android.graphics.Bitmap
import android.util.Log
import android.view.inspector.WindowInspector
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import java.io.File
import com.kevinpostal.fiberirc.data.api.BufferPrefValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.TestSession
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [SessionHost] (buffer list + chat + composer, unmodified production code) with a
 * [TestSession]: a real [com.kevinpostal.fiberirc.data.irc.IrcClient] over a [com.kevinpostal.fiberirc.support.FakeTransport]
 * instead of a socket. Validates the signed-in half of the app the same way [com.kevinpostal.fiberirc.ui.login.LoginE2eTest]
 * validates sign-in.
 */
@RunWith(AndroidJUnit4::class)
class ChatE2eTest {
    private val effects = StandardTestDispatcher()
    @get:Rule
    val compose = createComposeRule(effectContext = effects)

    private lateinit var server: FakeSiteServer
    private var completionFixture: TestSession? = null
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        completionFixture?.let { fixture ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fixture.deps.bufferPrefs.load(emptyMap())
                fixture.manager.stop()
            }
        }
        server.shutdown()
    }

    private val settings = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = "#general",
    )

    /** Registration (001) → the client auto-joins `#general`; the server echoes it back → the buffer shows up. */
    private fun TestSession.joinGeneral() {
        transport.feed(":server 001 tester :Welcome")
        compose.waitUntil(timeoutMillis = 5_000) { transport.sentLines.any { it.startsWith("JOIN #general") } }
        transport.feed(":tester!u@h JOIN #general")
    }

    /** A line sent by another user appears as a chat row once a buffer exists for the channel. */
    @Test
    fun receivedMessageAppearsInChat() {
        val session = TestSession(app, server, listOf(settings))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.joinGeneral()

        session.transport.feed(":alice!u@h PRIVMSG #general :hello from alice")
        // Message rows collapse their semantics into one contentDescription (accessibility), not a plain Text node.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("alice: hello from alice", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Typing in the composer and tapping Send writes a real `PRIVMSG` line to the network client. */
    @Test
    fun sendingMessageWritesPrivmsg() {
        val session = TestSession(app, server, listOf(settings))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.joinGeneral()
        session.transport.clearSent()

        compose.onNodeWithContentDescription("Message").performTextInput("hi everyone")
        compose.onNodeWithTag("sendButton").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            session.transport.sentLines.any { it == "PRIVMSG #general :hi everyone" }
        }
        assertTrue(session.transport.sentLines.any { it == "PRIVMSG #general :hi everyone" })
    }
    /** A blank bouncer buffer must request history once attach finishes, without requiring a scroll. */
    @Test
    fun bouncerHistoryLoadsAfterAttachCompletes() {
        val bouncerSettings = settings.copy(
            viaBouncer = true,
            autoJoinChannels = "",
            serverPassword = "tester@test-device/net1:token",
        )
        val session = TestSession(app, server, listOf(bouncerSettings))
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.transport.feed(":s CAP * LS :server-time message-tags echo-message batch")
        session.transport.feed(":s CAP tester ACK :server-time message-tags echo-message batch")
        session.transport.feed(":s 001 tester :hi")
        session.transport.feed(":tester!u@h JOIN #general")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(session.transport.sentLines.none { it == "CHATHISTORY LATEST #general * 100" })
        session.transport.feed(":s PONG s :fiberirc-sync")
        compose.waitUntil(timeoutMillis = 5_000) {
            session.transport.sentLines.any { it == "CHATHISTORY LATEST #general * 100" }
        }
        session.transport.feed(":s BATCH +h chathistory #general")
        session.transport.feed("@batch=h;time=2026-11-04T10:00:00.000Z;msgid=m1 :alice!u@h PRIVMSG #general :replayed after attach")
        session.transport.feed(":s BATCH -h")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("alice: replayed after attach", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun completionSession(): TestSession {
        val session = TestSession(app, server, listOf(settings))
        completionFixture = session
        session.deps.bufferPrefs.load(emptyMap())
        compose.setContent { SessionHost(session.deps, session.manager, session.sessionActions) }
        session.joinGeneral()
        session.transport.feed(":tester!t@h JOIN #other")
        session.transport.feed(":fixture 353 tester = #general :tester bob alice albert")
        session.transport.feed(":fixture 366 tester #general :End")
        compose.waitUntil(5_000) { session.manager.buffer("net1", "#general")?.members?.size == 4 }
        compose.onNodeWithTag("buffersButton").performClick()
        compose.onNodeWithContentDescription("#general").performClick()
        compose.onNodeWithTag("messageInput").performClick()
        compose.waitForIdle()
        return session
    }

    private fun settleEdit() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun openAutomatic() {
        settleEdit()
        compose.mainClock.advanceTimeBy(300)
        settleEdit()
        compose.waitForIdle()
        compose.onNodeWithTag("completionStrip").assertExists()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val requested = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        val dir = requested?.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() }
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "completion-screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        Log.i("FiberIRC.completionE2e", file.absolutePath)
    }

    @Test
    fun automaticNicksWaitForTwoCharactersAndTapRetainsFocus() {
        val session = completionSession()
        session.transport.clearSent()
        compose.mainClock.autoAdvance = false
        val input = compose.onNodeWithTag("messageInput")
        input.performTextInput("a")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        input.performTextInput("l")
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        openAutomatic()
        input.assertTextEquals("al")
        compose.onNodeWithText("albert").assertExists()
        compose.onNodeWithText("alice").assertExists()
        screenshot("automatic-nick-strip")
        compose.onNodeWithText("alice").performClick()
        settleEdit()
        input.assertTextEquals("alice: ").assertIsFocused()
        assertEquals(TextRange(7), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        assertTrue(session.transport.sentLines.none { it.startsWith("PRIVMSG") })
        screenshot("automatic-nick-picked")
        input.performTextInput("hello")
        settleEdit()
        input.assertTextEquals("alice: hello").assertIsFocused()
        input.performTextReplacement("hello al")
        openAutomatic()
        compose.onNodeWithText("alice").performClick()
        settleEdit()
        input.assertTextEquals("hello alice ")
    }

    @Test
    fun automaticNicksRefilterAndReplaceSuffixWithoutLosingLaterWords() {
        completionSession()
        compose.mainClock.autoAdvance = false
        val input = compose.onNodeWithTag("messageInput")
        input.performTextInput("al")
        openAutomatic()
        input.performTextInput("i")
        settleEdit()
        compose.onNodeWithText("albert").assertDoesNotExist()
        compose.onNodeWithText("alice").assertExists()
        input.performTextReplacement("a")
        settleEdit()
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        input.performTextReplacement("zz")
        settleEdit()
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        input.performTextReplacement("hello alxx  later")
        settleEdit()
        input.performTextInputSelection(TextRange(8))
        openAutomatic()
        compose.onNodeWithText("alice").performClick()
        settleEdit()
        input.assertTextEquals("hello alice  later").assertIsFocused()
        assertEquals(TextRange(12), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test
    fun imeSendSendsLiteralPrefixBothVisibleAndPending() {
        val session = completionSession()
        session.transport.clearSent()
        fun sentBodies() = session.transport.sentLines.mapNotNull { IrcMessage.parse(it) }
            .filter { it.command == "PRIVMSG" && it.params.firstOrNull() == "#general" }
            .map { it.params.getOrNull(1) }
        compose.mainClock.autoAdvance = false
        val input = compose.onNodeWithTag("messageInput")
        input.performTextInput("al")
        openAutomatic()
        input.performImeAction()
        settleEdit()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sentBodies() == listOf("al") }
        compose.mainClock.autoAdvance = false
        input.performTextInput("al")
        settleEdit()
        input.performImeAction()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { sentBodies() == listOf("al", "al") }
        assertEquals(listOf("al", "al"), sentBodies())
    }

    @Test
    fun automaticOpeningCancelsOnSelectionClearAndBufferSwitch() {
        completionSession()
        compose.mainClock.autoAdvance = false
        val input = compose.onNodeWithTag("messageInput")
        input.performTextInput("al")
        settleEdit()
        input.performTextClearance()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        input.performTextInput("al")
        settleEdit()
        input.performTextInputSelection(TextRange(0, 2))
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        input.performTextInputSelection(TextRange(2))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("buffersButton").performClick()
        compose.onNodeWithContentDescription("#other").performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("messageInput").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
    }

    @Test
    fun disabledSuggestionsStillAllowImmediateOneCharacterTabAndSnapshotCycling() {
        val session = completionSession()
        compose.runOnIdle {
            session.deps.bufferPrefs.load(mapOf("net1:#general" to mapOf("nickSuggestions" to BufferPrefValue.Bool(false))))
        }
        val input = compose.onNodeWithTag("messageInput")
        input.performTextInput("a")
        settleEdit()
        input.performKeyInput { pressKey(Key.Tab) }
        settleEdit()
        input.assertTextEquals("albert: ")
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        session.transport.feed(":aaron!a@h JOIN #general")
        compose.waitUntil(5_000) { session.manager.buffer("net1", "#general")?.members?.size == 5 }
        input.performKeyInput { pressKey(Key.Tab) }
        settleEdit()
        input.assertTextEquals("alice: ")
        input.performTextReplacement("al")
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
    }

    @Test
    fun markedInputWaitsForCommit() {
        completionSession()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().firstNotNullOf { it.findFocus() }
            val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
            connection.setComposingText("al", 1)
        }
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().firstNotNullOf { it.findFocus() }
            requireNotNull(view.onCreateInputConnection(EditorInfo())).finishComposingText()
        }
        openAutomatic()
        compose.onNodeWithTag("messageInput").assertTextEquals("al")
    }
    @Test
    fun selfOnlyRosterStaysHiddenAndUnicodeThresholdCountsCodePoints() {
        val session = completionSession()
        for (nick in listOf("bob", "alice", "albert")) session.transport.feed(":$nick!u@h PART #general")
        compose.waitUntil(5_000) { session.manager.buffer("net1", "#general")?.members?.size == 1 }
        compose.mainClock.autoAdvance = false
        val input = compose.onNodeWithTag("messageInput")
        input.performTextInput("te")
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        session.transport.feed(":𝔞lice!u@h JOIN #general")
        compose.waitUntil(5_000) { session.manager.buffer("net1", "#general")?.members?.size == 2 }
        input.performTextReplacement("𝔞")
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("completionStrip").assertDoesNotExist()
        input.performTextInput("l")
        openAutomatic()
        input.assertTextEquals("𝔞l")
        compose.onNodeWithText("𝔞lice").assertExists()
    }
}
