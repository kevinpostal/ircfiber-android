package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevinpostal.fiberirc.data.api.IrcAccount
import com.kevinpostal.fiberirc.data.api.IrcAccountStatus
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.SessionBootstrap
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.decodeAs
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class IrcRow { ACCOUNT, PASSWORD, SERVER, STATUS, RETRY }

/**
 * Settings → Account (ports `AccountSettingsViewController`): username and email, Change Password, and the
 * NickServ account this account owns on the IRC Fiber network (credentials for other IRC clients; retry when
 * registration failed). Calls GET /api/me, GET /api/me/irc-account, POST /api/me/irc-account/retry.
 */
@Composable
fun AccountScreen(api: SiteApi, onChangePassword: () -> Unit, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Me?>(null) }
    var irc by remember { mutableStateOf<IrcAccount?>(null) }
    var ircError by remember { mutableStateOf<Throwable?>(null) }
    var showsPassword by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var passwordMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coroutineScope {
            val account = async { api.me() }
            val nickserv = async { api.ircAccount().decodeAs(IrcAccount.serializer()) }
            account.await().onSuccess { me = it }
            nickserv.await().fold(onSuccess = { irc = it; ircError = null }, onFailure = { ircError = it })
        }
    }

    val ircRows = when (irc?.status) {
        IrcAccountStatus.READY -> listOf(IrcRow.ACCOUNT, IrcRow.PASSWORD, IrcRow.SERVER)
        IrcAccountStatus.PENDING, IrcAccountStatus.UNAVAILABLE -> listOf(IrcRow.STATUS, IrcRow.RETRY)
        IrcAccountStatus.NONE, null -> emptyList()
    }
    val ircFooter = when {
        ircError != null && irc == null -> ircError?.message
        irc?.status == IrcAccountStatus.READY -> "Registered with NickServ on ${irc?.network ?: "IRC Fiber"}."
        irc?.status == IrcAccountStatus.UNAVAILABLE -> irc?.reason
        irc?.status == IrcAccountStatus.NONE -> "This account has no IRC Fiber network."
        else -> null
    }

    fun retry() {
        if (retrying) return
        retrying = true
        scope.launch {
            api.retryIrcAccount()
            SessionBootstrap.ircAccountWaitingForReady(api, attempts = 12, intervalMs = 1_000)
                .fold(onSuccess = { irc = it; ircError = null }, onFailure = { ircError = it })
            retrying = false
        }
    }

    SettingsScaffold(title = "Account", onBack = onBack) {
        settingsSection(
            key = "profile",
            header = "Profile",
            rows = listOf(
                { SettingsRow("Username", value = me?.username ?: "…") },
                { SettingsRow("Email", value = me?.let { m -> m.email.ifEmpty { "None" } } ?: "…") },
            ),
        )
        settingsSection(
            key = "password",
            header = "Password",
            rows = listOf { SettingsRow("Change Password", onClick = onChangePassword, trailing = { Chevron() }) },
        )
        settingsSection(
            key = "irc",
            header = "IRC Account",
            footer = ircFooter,
            rows = ircRows.map { row ->
                {
                    val a = irc
                    when (row) {
                        IrcRow.ACCOUNT -> SettingsRow("Account", value = a?.account)
                        IrcRow.PASSWORD -> Box {
                            SettingsRow(
                                "NickServ Password",
                                value = if (showsPassword) a?.password else "••••••••",
                                onClick = { if (!a?.password.isNullOrEmpty()) passwordMenu = true },
                            )
                            DropdownMenu(expanded = passwordMenu, onDismissRequest = { passwordMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (showsPassword) "Hide Password" else "Show Password") },
                                    onClick = { passwordMenu = false; showsPassword = !showsPassword },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy Password") },
                                    onClick = {
                                        passwordMenu = false
                                        a?.password?.let { copySensitive(context, "NickServ Password", it) }
                                    },
                                )
                            }
                        }
                        IrcRow.SERVER -> SettingsRow("Server", value = "${a?.host ?: ""}:${a?.port?.toString() ?: ""}")
                        IrcRow.STATUS -> SettingsRow(
                            "Status",
                            value = if (a?.status == IrcAccountStatus.PENDING) "Registering…" else "Not registered",
                        )
                        IrcRow.RETRY -> SettingsRow(
                            if (retrying) "Retrying…" else "Retry Registration",
                            titleColor = if (retrying) c.textSecondary else c.link,
                            onClick = if (retrying) null else ::retry,
                        )
                    }
                }
            },
        )
    }
}
