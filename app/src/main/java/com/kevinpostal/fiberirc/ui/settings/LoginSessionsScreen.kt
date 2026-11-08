package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kevinpostal.fiberirc.data.api.LoginSession
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.loginSessions
import com.kevinpostal.fiberirc.data.api.revokeSession
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch

/** "1.2.3.4 · active 5 min. ago · 2 tabs" (the row's secondary text). */
internal fun sessionDetail(s: LoginSession, active: String): String {
    val address = s.clientIp.ifEmpty { "unknown address" }
    var detail = "$address · active $active"
    if (s.clientCount > 0) detail += " · ${s.clientCount} tabs"
    return detail
}

/**
 * Settings → Login Sessions (ports `LoginSessionsViewController`): every browser or app signed in to the account
 * (GET /api/me/sessions); swipe a row, or tap its End Session button, to sign it out
 * (DELETE /api/me/sessions/<ref>) — never the current one.
 * Details use the full row width; current-device labels and actions stack instead
 * of squeezing long addresses or large accessibility text into a narrow column.
 */
@Composable
fun LoginSessionsScreen(api: SiteApi, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    var sessions by remember { mutableStateOf<List<LoginSession>>(emptyList()) }
    /** Local clock − server clock (ms), so relative times don't inherit the phone's skew. */
    var skew by remember { mutableStateOf(0L) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>("Loading…") }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }

    suspend fun load() {
        api.loginSessions().fold(
            onSuccess = {
                sessions = it.sessions
                skew = System.currentTimeMillis() - it.now
                message = null
            },
            onFailure = { message = it.message },
        )
        loaded = true
        refreshing = false
    }
    LaunchedEffect(Unit) { load() }

    fun confirmSignOut(s: LoginSession) {
        confirmation = Confirmation("Sign Out This Session?", UserAgentSummary.label(s.userAgent), "Sign Out") {
            scope.launch {
                api.revokeSession(s.ref).fold(onSuccess = { load() }, onFailure = { alerts.show("Session not signed out", it) })
            }
        }
    }

    SettingsScaffold(
        title = "Login Sessions",
        onBack = onBack,
        refreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        message = message,
    ) {
        if (loaded && sessions.isNotEmpty()) {
            settingsSection(
                key = "sessions",
                footer = "One row per browser or app signed in to your account. Sessions expire on their own; signing one " +
                    "out ends it immediately.",
                rows = sessions.map { s ->
                    {
                        SwipeActionRow("Sign Out", c.error, enabled = !s.current, onSwipe = { confirmSignOut(s) }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Grid.margin, vertical = Grid.gutter),
                                verticalArrangement = Arrangement.spacedBy(Grid.unit),
                            ) {
                                Text(UserAgentSummary.label(s.userAgent), color = c.chatText, style = MaterialTheme.typography.bodyLarge)
                                if (s.current) {
                                    Text("This Device", color = c.accent, style = MaterialTheme.typography.labelMedium)
                                }
                                Text(
                                    sessionDetail(s, relativeTime(s.lastAccess, skew)),
                                    color = c.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!s.current) {
                                    TextButton(onClick = { confirmSignOut(s) }, modifier = Modifier.align(Alignment.End)) {
                                        Text("End Session", color = c.error)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
    ConfirmDialog(confirmation) { confirmation = null }
    ErrorDialog(alerts)
}
