package com.kevinpostal.fiberirc.ui.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.support.TestSession
import com.kevinpostal.fiberirc.ui.shell.SessionHost
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The media half of the signed-in app through the real [SessionHost]: inline previews and the full-screen
 * [ImageViewerScreen], the composer's upload sheet ([UploadConfirmScreen] → a real multipart `POST /api/upload`),
 * File Uploads ([UploadsScreen], [UploadPreviewScreen]) and text snippets ([SnippetEditorScreen], [SnippetsScreen]).
 * Every byte comes from [FakeSiteServer]; the system document picker is answered by a test
 * [ActivityResultRegistry] (the production `rememberLauncherForActivityResult` code path, minus the picker UI).
 */
@RunWith(AndroidJUnit4::class)
class MediaE2eTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: FakeSiteServer
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val settings = ConnectionSettings(
        nick = "tester",
        realName = "tester",
        networkId = "net1",
        networkName = "Test Net",
        autoJoinChannels = "#general",
    )

    private val base: String get() = server.baseUrl.trimEnd('/')

    /** What the system pickers "return": the next launched contract gets [result], and its input is recorded. */
    private class FakePickers : ActivityResultRegistryOwner {
        var result: Any? = null
        val launched = ArrayList<Any?>()
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launched += input
                val answer = result
                // Delivered on a later main-loop turn, as an activity result would be.
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, answer) }
            }
        }
    }

    private fun startSession(pickers: FakePickers = FakePickers()): TestSession {
        val session = TestSession(app, server, listOf(settings))
        // Device-wide prefs persist across tests: pin the ones these flows depend on to their defaults.
        session.deps.deviceSettings.update {
            it.copy(embedExternalMedia = true, embedOnCellular = true, imagesInBrowser = false, askToPostSnippet = true)
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickers) {
                SessionHost(session.deps, session.manager, session.sessionActions)
            }
        }
        session.transport.feed(":server 001 tester :Welcome")
        compose.waitUntil(timeoutMillis = 5_000) { session.sent().any { it.startsWith("JOIN #general") } }
        session.transport.feed(":tester!u@h JOIN #general")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        session.transport.clearSent()
        return session
    }

    private fun TestSession.sent(): List<String> = runCatching { transport.sentLines.toList() }.getOrDefault(emptyList())

    private fun waitForText(text: String, timeout: Long = 5_000) = compose.waitUntil(timeoutMillis = timeout) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForDescription(text: String, substring: Boolean = false, timeout: Long = 5_000) =
        compose.waitUntil(timeoutMillis = timeout) {
            compose.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitGone(text: String, timeout: Long = 5_000) = compose.waitUntil(timeoutMillis = timeout) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun requests(method: String, path: String): List<RecordedRequest> =
        server.requests().filter { it.method == method && it.path?.substringBefore('?') == path }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun pngResponse(bytes: ByteArray) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))

    private fun openUploadSheet() {
        compose.onNodeWithTag("uploadButton").performClick()
        waitForText("Choose Document")
    }

    /**
     * An external image link in a channel message shows an inline preview loaded through the site's image proxy
     * (the session's site, not production); tapping it opens the full-screen viewer, which loads the bytes and
     * closes on Done.
     */
    @Test
    fun inlineImageOpensViewer() {
        val png = pngBytes()
        server.onEach("GET", "/api/image-proxy") { pngResponse(png) }
        val session = startSession()

        session.transport.feed(":alice!u@h PRIVMSG #general :look https://example.com/cat.png")
        waitForDescription("Image: cat.png", timeout = 10_000)
        // The preview's bytes came through the fake site's image proxy for the external URL.
        compose.waitUntil(timeoutMillis = 10_000) { requests("GET", "/api/image-proxy").isNotEmpty() }
        assertEquals(
            "https://example.com/cat.png",
            requests("GET", "/api/image-proxy").first().requestUrl?.queryParameter("url"),
        )

        compose.onNodeWithContentDescription("Image: cat.png").performClick()
        waitForText("Done", timeout = 10_000)
        // Save is enabled once the viewer has the image's bytes (Loaded.Bytes), i.e. it really loaded.
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithContentDescription("Save to Photos").assertIsEnabled() }.isSuccess
        }
        assertTrue(compose.onAllNodesWithText("Couldn't load the image.", substring = true).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Done").performClick()
        waitGone("Done")
    }

    /**
     * Upload button → Choose Document → (picker returns a file) → the confirm screen with the file's name; renaming
     * it and adding a message then Send posts a real multipart `POST /api/upload` (buffer, filename, networkId,
     * then the file) and sends `"<message> <url>"` to the channel.
     */
    @Test
    fun documentUploadPostsMultipartAndSendsLink() {
        val picked = File(app.cacheDir, "report.txt").apply { writeText("hello upload") }
        val pickers = FakePickers().apply { result = Uri.fromFile(picked) }
        val uploadedUrl = "$base/uploads/notes.txt"
        server.on(
            "POST",
            "/api/upload",
            body = """{"id":"up1","url":"$uploadedUrl","pageUrl":"$uploadedUrl","name":"notes.txt","size":12}""",
        )
        val session = startSession(pickers)

        openUploadSheet()
        compose.onNodeWithText("Choose Document").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { pickers.launched.isNotEmpty() }
        waitForText("Upload")
        compose.onNode(hasSetTextAction() and hasText("report.txt")).performTextReplacement("notes.txt")
        compose.onNode(hasSetTextAction() and hasText("Add a message")).performTextInput("here it is")
        compose.onNodeWithText("Send").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { session.sent().any { it == "PRIVMSG #general :here it is $uploadedUrl" } }
        waitGone("Upload")

        val request = requests("POST", "/api/upload").single()
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue(contentType, contentType.startsWith("multipart/form-data"))
        val parts = multipartParts(contentType.substringAfter("boundary="), request.body.clone().readUtf8())
        assertEquals(listOf("buffer", "filename", "networkId", "file"), parts.map { it.name })
        assertEquals("#general", parts[0].body)
        assertEquals("notes.txt", parts[1].body)
        assertEquals("net1", parts[2].body)
        val file = parts[3]
        assertTrue(file.headers, file.headers.contains("filename=\"notes.txt\""))
        assertTrue(file.headers, file.headers.contains("Content-Type: text/plain"))
        assertEquals("hello upload", file.body)
    }

    /**
     * File Uploads lists the account's uploads; a file opens its preview, whose Delete calls
     * `DELETE /api/uploads/<id>` and drops it from the grid; an image opens the full-screen viewer.
     */
    @Test
    fun fileUploadsPreviewDeleteAndView() {
        val png = pngBytes()
        server.on(
            "GET",
            "/api/uploads",
            body = """
                {"uploads":[
                  {"id":"u1","url":"$base/uploads/cat.png","name":"cat.png","mimeType":"image/png","size":1234,
                   "buffer":"#general","networkId":"net1","createdAt":1700000000000},
                  {"id":"u2","url":"$base/uploads/notes.txt","name":"notes.txt","mimeType":"text/plain","size":12,
                   "buffer":"#general","networkId":"net1","createdAt":1690000000000}
                ],"total":2}
            """.trimIndent(),
        )
        server.onEach("GET", "/uploads/cat.png") { pngResponse(png) }
        server.on("DELETE", "/api/uploads/u2")
        startSession()

        openUploadSheet()
        compose.onNodeWithText("File Uploads").performClick()
        waitForDescription("notes.txt")
        waitForDescription("cat.png")

        compose.onNodeWithContentDescription("notes.txt").performClick()
        waitForText("Share")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("notes.txt").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, requests("DELETE", "/api/uploads/u2").size)
        assertTrue(compose.onAllNodesWithText("Share").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription("cat.png").performClick()
        waitForText("Done")
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithContentDescription("Save to Photos").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Done").performClick()
        waitGone("Done")
        // Back on File Uploads, the image is still listed.
        waitForDescription("cat.png")
    }

    /**
     * `/paste <text>` in the composer opens the snippet editor with that text; picking a syntax and Post Snippet
     * saves it with `POST /api/pastebins` and sends `"<message> <snippet page>"` on the session's site.
     */
    @Test
    fun pasteCommandPostsSnippet() {
        server.on(
            "POST",
            "/api/pastebins",
            body = """{"id":"abcd1234ef","name":"hello.py","syntax":"python","lines":1,"body":"print(1)","createdAt":1700000000000}""",
        )
        val session = startSession()

        compose.onNodeWithContentDescription("Message").performTextInput("/paste print(1)")
        compose.onNodeWithTag("sendButton").performClick()
        waitForText("Text Snippet")
        compose.onNode(hasSetTextAction() and hasText("print(1)")).assertExists()
        assertTrue(session.sent().none { it.startsWith("PRIVMSG") })

        compose.onNode(hasSetTextAction() and hasText("snippet.txt")).performTextReplacement("hello.py")
        compose.onNodeWithContentDescription("Syntax").performClick()
        waitForText("python")
        compose.onNodeWithText("python").performClick()
        compose.onNode(hasSetTextAction() and hasText("Add a message")).performTextInput("see this")
        compose.onNodeWithText("Post Snippet").performClick()

        val link = "$base/?/pastebin=abcd1234ef"
        compose.waitUntil(timeoutMillis = 10_000) { session.sent().any { it == "PRIVMSG #general :see this $link" } }
        waitGone("Text Snippet")

        val json = Json.parseToJsonElement(requests("POST", "/api/pastebins").single().body.clone().readUtf8()).jsonObject
        assertEquals("hello.py", json["name"]?.jsonPrimitive?.content)
        assertEquals("print(1)", json["body"]?.jsonPrimitive?.content)
        assertEquals("python", json["syntax"]?.jsonPrimitive?.content)
        assertEquals("net1", json["networkId"]?.jsonPrimitive?.content)
        assertEquals("#general", json["buffer"]?.jsonPrimitive?.content)
    }

    /**
     * Upload sheet → Start a Text Snippet → Send as Messages sends each line as its own message; Text Snippets
     * lists the account's snippets from `GET /api/pastebins`.
     */
    @Test
    fun snippetSendAsMessagesAndList() {
        server.on(
            "GET",
            "/api/pastebins",
            body = """{"pastebins":[{"id":"abcd1234ef","name":"hello.py","syntax":"python","lines":2,"body":"a\nb","createdAt":1700000000000}],"total":1}""",
        )
        val session = startSession()

        openUploadSheet()
        compose.onNodeWithText("Start a Text Snippet").performClick()
        waitForText("Text Snippet")
        compose.onNode(hasSetTextAction() and hasText("Snippet")).performTextInput("line one\n/line two")
        compose.onNodeWithText("Send as Messages").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            session.sent().containsAll(listOf("PRIVMSG #general :line one", "PRIVMSG #general :/line two"))
        }
        waitGone("Send as Messages")

        openUploadSheet()
        compose.onNodeWithText("Text Snippets").performClick()
        waitForText("hello.py")
        compose.onNodeWithText("2 lines", substring = true).assertExists()
    }

    private class Part(val name: String, val headers: String, val body: String)

    /** Splits a `multipart/form-data` body into its parts, in order. */
    private fun multipartParts(boundary: String, body: String): List<Part> =
        body.split("--$boundary")
            .map { it.removePrefix("\r\n") }
            .filter { it.isNotBlank() && !it.startsWith("--") }
            .map { raw ->
                val headers = raw.substringBefore("\r\n\r\n")
                val content = raw.substringAfter("\r\n\r\n").removeSuffix("\r\n")
                val name = Regex("""name="([^"]*)"""").find(headers)?.groupValues?.get(1).orEmpty()
                Part(name, headers, content)
            }
}
