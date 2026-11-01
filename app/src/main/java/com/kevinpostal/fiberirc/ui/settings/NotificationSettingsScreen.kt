package com.kevinpostal.fiberirc.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.data.prefs.NotificationPrefsStore
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings → Notifications (ports `NotificationSettingsViewController`): whether Android lets IRC Fiber notify
 * (tap "Off — Open Settings" for the app's system notification settings), and this install's Mute All and Sound
 * ([NotificationPrefsStore], POST /api/me/notification-prefs). A failed save puts the switch back.
 */
@Composable
fun NotificationSettingsScreen(notificationPrefs: NotificationPrefsStore, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    var allowed by remember { mutableStateOf<Boolean?>(null) }
    val muteAll by notificationPrefs.muteAll.collectAsStateWithLifecycle()
    val sound by notificationPrefs.notificationSound.collectAsStateWithLifecycle()

    // Re-read on appear and on return from system settings, where the user may have turned them on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun set(key: NotificationPrefsStore.Key, on: Boolean) {
        scope.launch {
            try {
                notificationPrefs.set(key, on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alerts.show("Couldn't save setting", e)
            }
        }
    }

    SettingsScaffold(title = "Notifications", onBack = onBack) {
        settingsSection(
            key = "status",
            rows = listOf {
                SettingsRow(
                    "Notifications",
                    value = when (allowed) {
                        true -> "Allowed"
                        false -> "Off — Open Settings"
                        null -> "…"
                    },
                    valueColor = if (allowed == false) c.link else null,
                    onClick = if (allowed == false) ({
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }) else null,
                )
            },
        )
        settingsSection(
            key = "prefs",
            footer = "Per-channel notifications are in each channel's Display Options.",
            rows = listOf(
                { SettingsSwitchRow("Mute All Notifications", muteAll) { set(NotificationPrefsStore.Key.MUTE_ALL, it) } },
                { SettingsSwitchRow("Sound", sound) { set(NotificationPrefsStore.Key.NOTIFICATION_SOUND, it) } },
            ),
        )
    }
    ErrorDialog(alerts)
}
