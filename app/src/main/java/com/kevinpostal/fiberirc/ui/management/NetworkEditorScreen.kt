package com.kevinpostal.fiberirc.ui.management

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.api.createNetwork
import com.kevinpostal.fiberirc.data.api.deleteNetwork
import com.kevinpostal.fiberirc.data.api.updateNetwork
import com.kevinpostal.fiberirc.ui.shell.NetworkPrefill
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch

/**
 * Add a Network ([network] null, or [prefill] from an `irc(s)://` link) and Edit Connection (ports
 * `NetworkEditorViewController`): the network's server, identity, authentication, auto-join channels and connect
 * commands. Save POSTs a new network or PATCHes only the changed keys, then runs [onSaved] (the session's
 * `syncNetworks`) and [onClose]; a server error shows its message under the Server section. Editing adds a confirmed
 * Delete row; a network IRC Fiber manages (`systemManaged`) is read-only, with no Save or Delete.
 */
@Composable
fun NetworkEditorScreen(
    api: SiteApi,
    network: NetworkInfo?,
    onSaved: () -> Unit,
    onClose: () -> Unit,
    prefill: NetworkPrefill? = null,
) {
    val original = remember(network, prefill) {
        when {
            network != null -> NetworkForm.from(network)
            prefill != null -> NetworkForm.fromLink(prefill)
            else -> NetworkForm()
        }
    }
    var form by remember(original) { mutableStateOf(original) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val readOnly = network?.systemManaged == true
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colors = LocalFiberIrcColors.current

    fun show(error: String?) {
        errorText = error
        if (error != null) scope.launch { listState.animateScrollToItem(0) }
    }

    fun finish() {
        onSaved()
        onClose()
    }

    fun save() {
        if (saving) return
        form.validationError?.let { return show(it) }
        val snapshot = form
        saving = true
        scope.launch {
            val result = if (network != null) {
                val changes = snapshot.changes(original)
                if (changes.isEmpty()) Result.success(Unit) else api.updateNetwork(network.id, changes).map { }
            } else {
                api.createNetwork(snapshot.createBody).map { }
            }
            saving = false
            result.fold({ finish() }, { show(it.message ?: it.toString()) })
        }
    }

    fun delete() {
        val n = network ?: return
        saving = true
        scope.launch {
            val result = api.deleteNetwork(n.id)
            saving = false
            result.fold({ finish() }, { e ->
                show(
                    if (e is SiteApiError.Server && e.code == 403) "IRC Fiber manages this network; it can't be deleted."
                    else e.message ?: e.toString(),
                )
            })
        }
    }

    ManagementScaffold(
        title = if (network == null) "Add Network" else "Edit Connection",
        navigation = { BarButton("Cancel", onClick = onClose) },
        actions = { if (!readOnly) BarButton("Save", enabled = !saving) { save() } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            section("Server") {
                item { Field("Name", form.name, readOnly, words = true) { form = form.copy(name = it) } }
                item {
                    Field("Hostname", form.host, readOnly, keyboard = KeyboardType.Uri) { form = form.copy(host = it) }
                }
                item { Field("Port", form.port, readOnly, keyboard = KeyboardType.Number) { form = form.copy(port = it) } }
                item { Toggle("Use TLS", form.tls, readOnly) { form = form.copy(tls = it) } }
                item {
                    Field("Server Password", form.serverPass, readOnly, secure = true) { form = form.copy(serverPass = it) }
                }
                val footer = if (readOnly) "Managed by IRC Fiber." else errorText
                if (footer != null) {
                    item { SectionFooter(footer, if (readOnly) MaterialTheme.colorScheme.onSurfaceVariant else colors.error) }
                }
            }
            section("Identity") {
                item {
                    Field("Nickname", form.nick, readOnly, keyboard = KeyboardType.Ascii) { form = form.copy(nick = it) }
                }
                item { Field("Real Name", form.realName, readOnly, words = true) { form = form.copy(realName = it) } }
                item {
                    Field("Username", form.ident, readOnly, keyboard = KeyboardType.Ascii) { form = form.copy(ident = it) }
                }
            }
            section("Authentication") {
                item { Toggle("SASL Authentication", form.sasl, readOnly) { form = form.copy(sasl = it) } }
                if (form.sasl) {
                    item {
                        Field("SASL Username", form.saslUsername, readOnly, keyboard = KeyboardType.Ascii) {
                            form = form.copy(saslUsername = it)
                        }
                    }
                    item {
                        Field("SASL Password", form.saslPassword, readOnly, secure = true) {
                            form = form.copy(saslPassword = it)
                        }
                    }
                }
                item {
                    Field("NickServ Password", form.nspass, readOnly, secure = true) { form = form.copy(nspass = it) }
                }
            }
            section("Auto-join Channels") {
                item {
                    Field(
                        "Auto-join Channels", form.autoJoin, readOnly, keyboard = KeyboardType.Ascii,
                        placeholder = "#channel, #another",
                    ) { form = form.copy(autoJoin = it) }
                }
            }
            section("Commands on Connect") {
                item {
                    OutlinedTextField(
                        value = form.commands,
                        onValueChange = { form = form.copy(commands = it) },
                        readOnly = readOnly,
                        label = { Text("Commands on Connect") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (network != null && !readOnly) {
                item {
                    ListItem(
                        headlineContent = {
                            Text("Delete Network", color = colors.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        },
                        modifier = Modifier.padding(top = 16.dp).clickable(enabled = !saving) { confirmingDelete = true },
                    )
                }
            }
        }
    }

    if (confirmingDelete && network != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${network.name}?") },
            text = { Text("The network and its channels are removed from your account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    delete()
                }) { Text("Delete", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.section(title: String, content: LazyListScope.() -> Unit) {
    item { SectionHeader(title) }
    content()
}

@Composable
private fun Field(
    label: String,
    value: String,
    readOnly: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    secure: Boolean = false,
    words: Boolean = false,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        readOnly = readOnly,
        enabled = !readOnly,
        singleLine = true,
        label = if (placeholder == null) ({ Text(label) }) else null,
        placeholder = placeholder?.let { p -> { Text(p) } },
        visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secure) KeyboardType.Password else keyboard,
            capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, readOnly: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = !readOnly) },
    )
}
