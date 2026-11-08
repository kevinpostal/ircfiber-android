package com.kevinpostal.fiberirc.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.kevinpostal.fiberirc.data.update.AppUpdates
import com.kevinpostal.fiberirc.data.update.PlayUpdateFlow
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import com.kevinpostal.fiberirc.ui.update.UpdateNoticeHost
import kotlinx.coroutines.launch

/**
 * DEBUG-only host for the app-update instrumentation (androidTest `AppUpdatesE2eTest`); the debug source set and
 * manifest keep it out of release builds. It wires an injected [AppUpdates]/[PlayUpdateFlow] the way `MainActivity`
 * wires `Deps`' instances: [PlayUpdateFlow.attach] in onCreate (before STARTED), [AppUpdates.onForeground] on every
 * onResume, and one [UpdateNoticeHost] over the screen — here around test-supplied [Fixture.content].
 *
 * [fixture] is read in every onCreate, so `ActivityScenario.recreate()` remounts it (a compose rule's one-shot
 * setContent can't survive recreation); a test may swap it before recreating to model a restarted process.
 */
class UpdateFixtureActivity : ComponentActivity() {
    class Fixture(
        val appUpdates: AppUpdates,
        val playUpdateFlow: PlayUpdateFlow,
        /** The Context [UpdateNoticeHost] opens update links with (its LocalContext); the activity by default. */
        val updateLinkContext: (ComponentActivity) -> Context = { it },
        val content: @Composable () -> Unit,
    )

    private lateinit var mounted: Fixture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val current = checkNotNull(fixture) { "Set UpdateFixtureActivity.fixture before launching it" }
        mounted = current
        current.playUpdateFlow.attach(this)
        val linkContext = current.updateLinkContext(this)
        setContent {
            FiberIrcTheme {
                Box(Modifier.fillMaxSize().background(LocalFiberIrcColors.current.background)) {
                    current.content()
                    CompositionLocalProvider(LocalContext provides linkContext) {
                        UpdateNoticeHost(current.appUpdates, current.playUpdateFlow)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { mounted.appUpdates.onForeground() }
    }

    companion object {
        @Volatile
        var fixture: Fixture? = null
    }
}
