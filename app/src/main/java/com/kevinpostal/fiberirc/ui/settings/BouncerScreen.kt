package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevinpostal.fiberirc.data.api.Bouncer
import com.kevinpostal.fiberirc.data.api.BouncerClient
import com.kevinpostal.fiberirc.data.api.BouncerDevice
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.bouncer
import com.kevinpostal.fiberirc.data.api.bouncerClients
import com.kevinpostal.fiberirc.data.api.createBouncerToken
import com.kevinpostal.fiberirc.data.api.disconnectBouncerClient
import com.kevinpostal.fiberirc.data.api.resetBouncerDevice
import com.kevinpostal.fiberirc.data.api.revokeBouncerPassword
import com.kevinpostal.fiberirc.data.api.updateBouncerAccess
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Rows the screen lists: everything but the IRC Fiber apps' own connections (`kind == "app"`; null reads as external). */
internal fun isExternal(kind: String?): Boolean = (kind ?: "external") != "app"

/** "WeeChat-laptop" / nick / "anonymous": the Connected row's title. */
internal fun clientName(c: BouncerClient): String = c.clientId.ifEmpty { c.nick.ifEmpty { "anonymous" } }

/** "libera · as nick · 1.2.3.4 · TLS · connected 5 min. ago". */
internal fun clientDetail(c: BouncerClient, connected: String): String {
    val network = c.networkName.ifEmpty { "bouncer" }
    var detail = "$network · as ${c.nick} · ${c.peer}"
    if (c.tls) detail += " · TLS"
    return "$detail · connected $connected"
}

/** The Devices row's title: "<clientId>[ (no client id)][ · online]". */
internal fun deviceName(d: BouncerDevice): String {
    var name = if (d.anonymous) "${d.clientId} (no client id)" else d.clientId
    if (d.online) name += " · online"
    return name
}

/**
 * Settings → Bouncer (ports `BouncerViewController`): the account-wide bouncer password and access policy,
 * clients attached through the bouncer right now (disconnect one) and the devices the bouncer keeps a replay
 * position for (reset one). External clients only — the app's own connection uses its own per-device token and
 * shows under This Device.
 *
 * Calls: GET /api/me/bouncer + GET /api/me/bouncer/clients (load), POST /api/me/bouncer (generate/regenerate),
 * DELETE /api/me/bouncer (revoke, 204), POST /api/me/bouncer/clients/<sid>/disconnect,
 * DELETE /api/me/bouncer/devices/<clientId> (reset), POST /api/me/bouncer/settings `{requireTls?, maxClients?}`.
 */
@Composable
fun BouncerScreen(api: SiteApi, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    var bouncer by remember { mutableStateOf<Bouncer?>(null) }
    var clients by remember { mutableStateOf<List<BouncerClient>>(emptyList()) }
    var devices by remember { mutableStateOf<List<BouncerDevice>>(emptyList()) }
    var skew by remember { mutableStateOf(0L) }
    var enabled by remember { mutableStateOf(false) }
    var showsPassword by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>("Loading…") }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var passwordMenu by remember { mutableStateOf(false) }

    suspend fun load() {
        val result = runCatching {
            coroutineScope {
                val b = async { api.bouncer().getOrThrow() }
                val a = async { api.bouncerClients().getOrThrow() }
                b.await() to a.await()
            }
        }
        result.fold(
            onSuccess = { (b, a) ->
                bouncer = b
                enabled = b.enabled
                devices = (b.devices ?: emptyList()).filter { isExternal(it.kind) }
                clients = a.clients.filter { isExternal(it.kind) }
                skew = System.currentTimeMillis() - a.now
                message = if (enabled) null else "The bouncer is off for this account."
            },
            onFailure = {
                if (it is kotlinx.coroutines.CancellationException) throw it
                enabled = false
                message = it.message
            },
        )
        refreshing = false
    }
    LaunchedEffect(Unit) { load() }

    fun confirm(title: String, message: String, button: String, failure: String, perform: suspend () -> Result<*>) {
        confirmation = Confirmation(title, message, button) {
            scope.launch {
                perform().fold(onSuccess = { load() }, onFailure = { alerts.show(failure, it) })
            }
        }
    }

    fun updateAccess(requireTls: Boolean? = null, maxClients: Int? = null) {
        scope.launch {
            api.updateBouncerAccess(requireTls, maxClients).onFailure { alerts.show("Couldn't save setting", it) }
            load() // success shows the saved value; failure reverts the control to the server's
        }
    }

    fun disconnect(cl: BouncerClient) = confirm(
        "Disconnect This Client?", cl.clientId.ifEmpty { cl.nick }, "Disconnect", "Client not disconnected",
    ) { api.disconnectBouncerClient(cl.sid) }

    fun reset(d: BouncerDevice) = confirm("Reset Backlog?", d.clientId, "Reset", "Device not reset") {
        api.resetBouncerDevice(d.clientId)
    }

    SettingsScaffold(
        title = "Bouncer",
        onBack = onBack,
        refreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        message = message,
    ) {
        if (!enabled) return@SettingsScaffold
        val b = bouncer
        val password = b?.password?.takeIf { it.isNotEmpty() }
        settingsSection(
            key = "password",
            header = "Password",
            footer = "This password grants access to every network on your account from any other IRC client. " +
                "Regenerating or revoking it disconnects any client using it — the IRC Fiber app keeps its own " +
                "separate connection and is unaffected.",
            rows = listOf {
                Box {
                    SettingsRow(
                        "Password",
                        value = if (password != null) (if (showsPassword) password else "•".repeat(46)) else null,
                        subtitle = if (password == null) "Generate a bouncer password to connect another client." else null,
                        onClick = { passwordMenu = true },
                    )
                    DropdownMenu(expanded = passwordMenu, onDismissRequest = { passwordMenu = false }) {
                        if (password != null) {
                            DropdownMenuItem(
                                text = { Text(if (showsPassword) "Hide Password" else "Show Password") },
                                onClick = { passwordMenu = false; showsPassword = !showsPassword },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Password") },
                                onClick = { passwordMenu = false; copySensitive(context, "Bouncer Password", password) },
                            )
                            DropdownMenuItem(
                                text = { Text("Regenerate") },
                                onClick = {
                                    passwordMenu = false
                                    confirm(
                                        "Regenerate Password?",
                                        "Any other client using the current password is disconnected. The IRC Fiber app is unaffected.",
                                        "Regenerate", "Password not regenerated",
                                    ) { api.createBouncerToken() }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Revoke", color = c.error) },
                                onClick = {
                                    passwordMenu = false
                                    confirm(
                                        "Revoke Password?",
                                        "Every other client using it is disconnected. The IRC Fiber app is unaffected.",
                                        "Revoke", "Password not revoked",
                                    ) { api.revokeBouncerPassword() }
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Generate Password") },
                                onClick = {
                                    passwordMenu = false
                                    confirm(
                                        "Generate a Bouncer Password?",
                                        "Use it to connect another IRC client (WeeChat, Textual, irssi…) to your account.",
                                        "Generate", "Password not generated",
                                    ) { api.createBouncerToken() }
                                },
                            )
                        }
                    }
                }
            },
        )
        settingsSection(
            key = "connected",
            header = "Connected",
            // An empty Connected section still shows one row saying so.
            rows = if (clients.isEmpty()) {
                listOf { SettingsRow("No clients connected through the bouncer right now.", titleColor = c.textSecondary) }
            } else {
                clients.map { cl ->
                    {
                        SwipeActionRow("Disconnect", c.error, onSwipe = { disconnect(cl) }) {
                            SettingsRow(
                                title = clientName(cl),
                                subtitle = clientDetail(cl, relativeTime(cl.attachedAt, skew)),
                                trailing = { TextButton(onClick = { disconnect(cl) }) { Text("Disconnect", color = c.error) } },
                            )
                        }
                    }
                }
            },
        )
        if (devices.isNotEmpty()) {
            settingsSection(
                key = "devices",
                header = "Devices",
                footer = "A device with a client id replays only what it missed. Resetting makes its next connection " +
                    "replay the history buffer instead.",
                rows = devices.map { d ->
                    {
                        SwipeActionRow("Reset", c.accent, onSwipe = { reset(d) }) {
                            SettingsRow(
                                title = deviceName(d),
                                subtitle = d.networks.joinToString(" · ") { "${it.networkName}: ${it.cursor}" }.ifEmpty { null },
                                trailing = { TextButton(onClick = { reset(d) }) { Text("Reset", color = c.link) } },
                            )
                        }
                    }
                },
            )
        }
        settingsSection(
            key = "access",
            header = "Access",
            footer = "Applies to other clients only — the IRC Fiber app always connects.",
            rows = listOf(
                {
                    SettingsRow("Require TLS", onClick = { updateAccess(requireTls = !(b?.requireTls ?: false)) }) {
                        Switch(
                            checked = b?.requireTls ?: false,
                            onCheckedChange = { updateAccess(requireTls = it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                        )
                    }
                },
                {
                    val max = b?.maxClients ?: 0
                    val ceiling = b?.maxClientsCeiling ?: 50
                    SettingsRow("Max Connected Clients", value = b?.let { "${it.maxClients}" } ?: "…") {
                        Row {
                            IconButton(onClick = { updateAccess(maxClients = max - 1) }, enabled = b != null && max > 0) {
                                Icon(Icons.Filled.Remove, contentDescription = "Fewer")
                            }
                            IconButton(onClick = { updateAccess(maxClients = max + 1) }, enabled = b != null && max < ceiling) {
                                Icon(Icons.Filled.Add, contentDescription = "More")
                            }
                        }
                    }
                },
            ),
        )
    }
    ConfirmDialog(confirmation) { confirmation = null }
    ErrorDialog(alerts)
}
