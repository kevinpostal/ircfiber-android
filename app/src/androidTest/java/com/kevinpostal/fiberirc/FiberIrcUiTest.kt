package com.kevinpostal.fiberirc

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.BufferLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** TestLab's smoke tier (tools/testlab/suites.json): the app launches, and a launch deep link reaches the router. */
@RunWith(AndroidJUnit4::class)
class FiberIrcUiTest {
    @Test
    fun coldLaunchResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun bufferDeepLinkWaitsForTheSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = BufferLink.Buffer("net1", "#chan")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppRouter.bufferUri(link.networkId, link.name)))
            .setClass(context, MainActivity::class.java)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertEquals(link, AppRouter.pending.value)
            }
        } finally {
            AppRouter.consume(link)
        }
    }
}
