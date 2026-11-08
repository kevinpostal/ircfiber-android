package com.kevinpostal.fiberirc.ui.settings

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.support.FakeSiteServer
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Long session details and controls remain readable on a 320dp phone at 200% font scale. */
@RunWith(AndroidJUnit4::class)
class LoginSessionsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var server: FakeSiteServer

    @Before
    fun setUp() {
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        server = FakeSiteServer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun show(current: Boolean) {
        val now = System.currentTimeMillis()
        server.on("GET", "/api/me/sessions", body = """
            {"now":$now,"sessions":[{"ref":"layout-session","createdAt":$now,"lastAccess":$now,
            "expiresAt":${now + 86400000},"clientIp":"2603:8001:98f0:1530:abcd:1234:5678:9012",
            "userAgent":"FiberIRC Android","current":$current,"clientCount":3}]}
        """.trimIndent())
        val api = server.siteApi()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                FiberIrcTheme {
                    Box(Modifier.width(320.dp)) { LoginSessionsScreen(api, onBack = {}) }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("IRC Fiber for Android"))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @Test
    fun currentDeviceUsesFullWidthAtLargeText() {
        show(current = true)
        val title = compose.onNodeWithText("IRC Fiber for Android", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val marker = compose.onNodeWithText("This Device", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("The current-device marker must not steal the detail column's width", title.left, marker.left, 1f)
        assertTrue("The marker belongs below the client title", marker.top >= title.bottom)
        compose.onNodeWithText("End Session").assertDoesNotExist()
    }

    @Test
    fun endSessionDoesNotSqueezeLongDetails() {
        show(current = false)
        val details = compose.onNodeWithText("2603:8001:98f0:1530:abcd:1234:5678:9012", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText("End Session", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The action must stay below the session details", action.top >= details.bottom)
        compose.onNodeWithText("End Session").performClick()
        compose.onNodeWithText("Sign Out This Session?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(server.requests().none { it.method == "DELETE" })
    }
}
