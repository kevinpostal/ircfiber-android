package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.kevinpostal.fiberirc.data.api.MeDevice
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.myDevice
import kotlinx.coroutines.launch

/** The Push Notifications row's value. */
internal fun pushStatus(push: MeDevice.PushStatus): String = when {
    !push.on -> "Not Registered"
    push.environment == "production" -> "Registered"
    else -> "Registered (Sandbox)"
}

/**
 * Settings → This Device (ports `ThisDeviceViewController`, GET /api/me/devices/mine): this install's own
 * status — model, OS and app version, first/last seen, push registration, and (when attached) its live bouncer
 * connection per network. Read-only: reconnect/disconnect lives in the chat's gear menu. The app's own bouncer
 * connection never appears under Settings → Bouncer; this is where it shows.
 */
@Composable
fun ThisDeviceScreen(api: SiteApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<MeDevice?>(null) }
    var skew by remember { mutableStateOf(0L) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>("Loading…") }

    suspend fun load() {
        api.myDevice().fold(
            onSuccess = {
                device = it.device
                skew = System.currentTimeMillis() - it.now
                loaded = true
                message = if (it.device == null) "This device hasn't been seen by the server yet." else null
            },
            onFailure = {
                loaded = false
                message = it.message
            },
        )
        refreshing = false
    }
    LaunchedEffect(Unit) { load() }

    SettingsScaffold(
        title = "This Device",
        onBack = onBack,
        refreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        message = message,
    ) {
        val d = device
        if (!loaded || d == null) return@SettingsScaffold
        settingsSection(
            key = "device",
            header = "This Device",
            rows = listOfNotNull<@Composable () -> Unit>(
                { SettingsRow("Model", value = d.model.ifEmpty { "Unknown" }) },
                if (d.osVersion.isNotEmpty()) ({ SettingsRow("OS Version", value = d.osVersion) }) else null,
                if (d.appVersion.isNotEmpty()) ({ SettingsRow("App Version", value = d.appVersion) }) else null,
                { SettingsRow("First Seen", value = relativeTime(d.createdAt, skew)) },
                { SettingsRow("Last Seen", value = relativeTime(d.lastSeenAt, skew)) },
                { SettingsRow("Push Notifications", value = pushStatus(d.push)) },
            ),
        )
        val networks = d.bouncer?.networks.orEmpty()
        if (networks.isNotEmpty()) {
            val online = d.bouncer?.online ?: false
            settingsSection(
                key = "bouncer",
                header = "Bouncer",
                footer = "Your other devices, and any other IRC client connected with your bouncer password, are listed " +
                    "separately under Bouncer.",
                rows = networks.map { n -> { SettingsRow(n.networkName, value = if (online) "Online" else "Offline") } },
            )
        }
    }
}
