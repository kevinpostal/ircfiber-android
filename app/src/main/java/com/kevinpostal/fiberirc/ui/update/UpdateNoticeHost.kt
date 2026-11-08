package com.kevinpostal.fiberirc.ui.update

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.BuildConfig
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.update.AppUpdates
import com.kevinpostal.fiberirc.data.update.AvailableRelease
import com.kevinpostal.fiberirc.data.update.PlayUpdateFlow
import com.kevinpostal.fiberirc.data.update.UpdateChannel
import com.kevinpostal.fiberirc.data.update.UpdateDestination
import com.kevinpostal.fiberirc.data.update.UpdateNotice
import com.kevinpostal.fiberirc.data.update.UpdateProblem
import com.kevinpostal.fiberirc.data.update.UpdateState
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Exact user-facing update texts, shared with the Settings row and tests. */
object UpdateTexts {
    const val AVAILABLE_TITLE = "Update available"
    const val UPDATE = "Update"
    const val LATER = "Later"
    const val OK = "OK"
    const val READY_TITLE = "Update ready"
    const val RESTART = "Restart to update"
    const val UP_TO_DATE = "You're up to date"
    const val CHECK_TITLE = "Check for updates"
    const val CHECK_FAILED = "Couldn't check for updates. Try again later."
    const val REQUIRES_OS = "This update requires a newer operating system."
    const val APK_GUIDANCE = "Download the APK, then open it to update. Keep the existing app installed."
    const val PLAY_FLEXIBLE_GUIDANCE =
        "It downloads in the background while you keep chatting. You choose when to restart."
    const val PLAY_LISTING_GUIDANCE = "Google Play opens so you can install it."
    const val OPEN_FAILED_TITLE = "Couldn't open the update"
    const val INSTALL_FAILED_TITLE = "Update failed"
    const val DOWNLOADING = "Downloading update"

    fun releaseLabel(release: AvailableRelease): String =
        release.versionName?.let { "IRC Fiber $it" } ?: "IRC Fiber build ${release.versionCode}"
}

/** Test tags for the root update UI. */
object UpdateTags {
    const val DIALOG = "update.dialog"
    const val PRIMARY = "update.action.primary"
    const val LATER = "update.action.later"
    const val PROGRESS = "update.progress"
}

/**
 * The app's one presenter of update notices, rendered once over login or the session (in `FiberIrcRoot`). Owns
 * the Update available / Update ready / result dialogs and a non-modal download-progress capsule that leaves the
 * chat underneath usable.
 *
 * A notice is considered only when it is new (its sequence) or when the activity comes to the foreground.
 * A foreground activation may settle window focus briefly; a check completing over another dialog never waits
 * for that dialog to close. Automatic notices need a resumed activity, a focused window (another
 * dialog or system sheet holds focus) and a keyboard known to be hidden; manual ones (Settings' check, OPEN/INSTALL
 * problems) may show over the keyboard but never over another dialog. A notice that isn't safe, or that arrives
 * while an update dialog is on screen, stays pending in [AppUpdates.notice] for the next foreground; it is never
 * re-tried just because a dialog closed. A dialog on screen is a snapshot that newer notices never replace. Nothing
 * here touches focus, drafts or navigation.
 */
@Composable
fun UpdateNoticeHost(
    appUpdates: AppUpdates,
    playUpdateFlow: PlayUpdateFlow,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notice by appUpdates.notice.collectAsStateWithLifecycle()
    val state by appUpdates.state.collectAsStateWithLifecycle()

    var resumeEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeEpoch++ }
    var lastEvaluatedResume by remember { mutableIntStateOf(-1) }

    /** The dialog on screen, frozen at presentation. */
    var shown by remember { mutableStateOf<ShownNotice?>(null) }

    /** The newest sequence the user already answered here (its notice may linger until the core clears it). */
    var answered by remember { mutableLongStateOf(0L) }

    val pending = notice?.takeIf { it.sequence > answered }
    LaunchedEffect(pending?.sequence, resumeEpoch) {
        val newForeground = resumeEpoch != lastEvaluatedResume
        lastEvaluatedResume = resumeEpoch
        if (pending == null || shown != null) return@LaunchedEffect
        withFrameNanos { }
        if (pending.manual || newForeground) awaitWindowFocus(windowInfo)
        val current = appUpdates.notice.value
        if (current == null || current.sequence != pending.sequence || shown != null) return@LaunchedEffect
        if (!safeToPresent(current, lifecycle, view)) return@LaunchedEffect
        shown = ShownNotice(current, appUpdates.state.value)
    }


    Box(modifier.fillMaxSize()) {
        (state as? UpdateState.Downloading)?.let { DownloadCapsule(it) }
    }

    val presented = shown ?: return
    val sequence = presented.notice.sequence
    fun close() {
        if (sequence > answered) answered = sequence
        shown = null
    }
    val acknowledge = {
        close()
        appUpdates.dismiss(sequence)
    }
    val content = remember(presented) { presented.content() }
    AlertDialog(
        onDismissRequest = acknowledge,
        modifier = Modifier.testTag(UpdateTags.DIALOG),
        title = { Text(content.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                content.lines.forEach { Text(it) }
            }
        },
        confirmButton = {
            when (val action = content.action) {
                is NoticeAction.Install -> TextButton(
                    onClick = {
                        close()
                        when (val destination = action.release.destination) {
                            is UpdateDestination.Apk -> {
                                appUpdates.onExternalUpdateStarted()
                                if (!LinkOpener.openExternally(context, destination.url)) appUpdates.onExternalOpenFailed()
                            }
                            is UpdateDestination.Play ->
                                if (destination.flexibleAllowed) playUpdateFlow.start() else playUpdateFlow.openListing(context)
                        }
                    },
                    modifier = Modifier.testTag(UpdateTags.PRIMARY),
                ) { Text(UpdateTexts.UPDATE) }
                NoticeAction.Restart -> TextButton(
                    onClick = {
                        close()
                        playUpdateFlow.complete()
                    },
                    modifier = Modifier.testTag(UpdateTags.PRIMARY),
                ) { Text(UpdateTexts.RESTART) }
                NoticeAction.Acknowledge -> TextButton(
                    onClick = acknowledge,
                    modifier = Modifier.testTag(UpdateTags.PRIMARY),
                ) { Text(UpdateTexts.OK) }
            }
        },
        dismissButton = if (content.action == NoticeAction.Acknowledge) null else ({
            TextButton(onClick = acknowledge, modifier = Modifier.testTag(UpdateTags.LATER)) { Text(UpdateTexts.LATER) }
        }),
    )
}

/** A notice as presented, with the update state at that moment (problems describe the release it retained). */
private data class ShownNotice(val notice: UpdateNotice, val state: UpdateState)

private sealed interface NoticeAction {
    data class Install(val release: AvailableRelease) : NoticeAction
    data object Restart : NoticeAction
    data object Acknowledge : NoticeAction
}

private class NoticeContent(val title: String, val lines: List<String>, val action: NoticeAction)

private fun ShownNotice.content(): NoticeContent {
    when (notice.problem) {
        UpdateProblem.OPEN -> return NoticeContent(
            UpdateTexts.OPEN_FAILED_TITLE,
            listOf(
                when (state.releaseOrNull()?.channel) {
                    UpdateChannel.APK -> "No app on this device could open the download link. Install or enable a web " +
                        "browser, then try again from Settings → Check for updates."
                    UpdateChannel.PLAY -> "Google Play couldn't be opened. Make sure the Play Store app is installed " +
                        "and enabled, then try again from Settings → Check for updates."
                    null -> "No app on this device could open the update. Try again from Settings → Check for updates."
                },
            ),
            NoticeAction.Acknowledge,
        )
        UpdateProblem.INSTALL -> return NoticeContent(
            UpdateTexts.INSTALL_FAILED_TITLE,
            listOf(
                if (state is UpdateState.Downloaded) {
                    "Google Play couldn't finish installing the update. Your current version keeps working; " +
                        "choose Restart to update again from Settings → Check for updates."
                } else {
                    "Google Play couldn't download the update. Your current version keeps working; " +
                        "try again from Settings → Check for updates."
                },
            ),
            NoticeAction.Acknowledge,
        )
        null -> Unit
    }
    return when (val outcome = notice.outcome) {
        is UpdateState.Available -> {
            val release = outcome.release
            NoticeContent(
                UpdateTexts.AVAILABLE_TITLE,
                listOf(
                    "${UpdateTexts.releaseLabel(release)} is available.",
                    when (val destination = release.destination) {
                        is UpdateDestination.Apk -> UpdateTexts.APK_GUIDANCE
                        is UpdateDestination.Play ->
                            if (destination.flexibleAllowed) UpdateTexts.PLAY_FLEXIBLE_GUIDANCE else UpdateTexts.PLAY_LISTING_GUIDANCE
                    },
                ),
                NoticeAction.Install(release),
            )
        }
        is UpdateState.Downloaded -> NoticeContent(
            UpdateTexts.READY_TITLE,
            listOf(
                "${UpdateTexts.releaseLabel(outcome.release)} has downloaded. Restarting closes and reopens IRC Fiber; " +
                    "your chats reconnect.",
            ),
            NoticeAction.Restart,
        )
        is UpdateState.Downloading -> NoticeContent(
            "Update downloading",
            listOf(
                "${UpdateTexts.releaseLabel(outcome.release)} is downloading" +
                    (outcome.progress?.let { " (${percent(it)})" } ?: "") +
                    ". Keep chatting; you'll be asked to restart when it's ready.",
            ),
            NoticeAction.Acknowledge,
        )
        is UpdateState.Installing -> NoticeContent(
            "Update installing",
            listOf("Google Play is installing ${UpdateTexts.releaseLabel(outcome.release)}. IRC Fiber restarts when it finishes."),
            NoticeAction.Acknowledge,
        )
        UpdateState.UpToDate -> NoticeContent(
            UpdateTexts.UP_TO_DATE,
            listOf("IRC Fiber ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) is the newest version available for this device."),
            NoticeAction.Acknowledge,
        )
        is UpdateState.RequiresOs -> NoticeContent(
            UpdateTexts.CHECK_TITLE,
            listOf(
                UpdateTexts.REQUIRES_OS,
                "IRC Fiber ${outcome.versionName} needs Android API level ${outcome.minSdk} or newer; this device has " +
                    "API level ${Build.VERSION.SDK_INT}.",
            ),
            NoticeAction.Acknowledge,
        )
        // Idle/Checking only reach a notice when a check ended without an answer: that's a failed check.
        UpdateState.Unavailable, UpdateState.Idle, UpdateState.Checking -> NoticeContent(
            UpdateTexts.CHECK_TITLE,
            listOf(UpdateTexts.CHECK_FAILED),
            NoticeAction.Acknowledge,
        )
    }
}

/** Bounded settlement for a foreground activation or an explicit result after the app's own dialog closes. */
private const val FOCUS_SETTLE_MILLIS = 500L

private suspend fun awaitWindowFocus(windowInfo: WindowInfo) {
    withTimeoutOrNull(FOCUS_SETTLE_MILLIS) { snapshotFlow { windowInfo.isWindowFocused }.first { it } }
}

/**
 * Sampled once per evaluation, never observed: resumed, window focused (no other dialog or system sheet over the
 * app) and, for automatic notices, a keyboard known to be hidden (unknown insets count as typing).
 */
private fun safeToPresent(notice: UpdateNotice, lifecycle: Lifecycle, view: View): Boolean {
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
    if (!view.hasWindowFocus()) return false
    if (notice.manual) return true
    return ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false
}

/** Non-modal capsule under the status bar; takes no touches, so the chat below stays usable. */
@Composable
private fun DownloadCapsule(downloading: UpdateState.Downloading) {
    val c = LocalFiberIrcColors.current
    val progress = downloading.progress
    val shape = RoundedCornerShape(Grid.margin)
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = Grid.gutter),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .testTag(UpdateTags.PROGRESS)
                .background(c.inputArea, shape)
                .border(1.dp, c.border, shape)
                .padding(horizontal = Grid.margin, vertical = Grid.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.gutter)) {
                Text(
                    UpdateTexts.DOWNLOADING,
                    color = c.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (progress != null) Text(percent(progress), color = c.textSecondary, fontSize = 12.sp)
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = c.accent,
                    trackColor = c.border,
                    modifier = Modifier.width(160.dp),
                )
            } else {
                LinearProgressIndicator(color = c.accent, trackColor = c.border, modifier = Modifier.width(160.dp))
            }
        }
    }
}

private fun percent(progress: Float): String = "${(progress * 100).roundToInt()}%"

private fun UpdateState.releaseOrNull(): AvailableRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.Downloaded -> release
    is UpdateState.Installing -> release
    else -> null
}
