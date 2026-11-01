package com.kevinpostal.fiberirc.ui.settings

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
import com.kevinpostal.fiberirc.data.api.LoginSession
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.loginSessions
import com.kevinpostal.fiberirc.data.api.revokeSession
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
                            SettingsRow(
                                title = UserAgentSummary.label(s.userAgent),
                                subtitle = sessionDetail(s, relativeTime(s.lastAccess, skew)),
                                trailing = {
                                    if (s.current) {
                                        Text("This Device", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        TextButton(onClick = { confirmSignOut(s) }) { Text("End Session", color = c.error) }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    }
    ConfirmDialog(confirmation) { confirmation = null }
    ErrorDialog(alerts)
}
