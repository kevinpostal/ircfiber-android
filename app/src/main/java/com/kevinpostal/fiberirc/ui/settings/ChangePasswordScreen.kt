package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.changePassword
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.launch

/** The client-side checks and wording of the web client (SettingsAccount.svelte); null when the input may be sent. */
internal fun changePasswordProblem(old: String, new: String, again: String): String? = when {
    old.isEmpty() || new.isEmpty() -> "Please fill in all fields"
    new != again -> "New passwords do not match"
    new.length < 8 -> "Password must be at least 8 characters"
    else -> null
}

/**
 * Settings → Account → Change Password (ports `ChangePasswordViewController`): POST /api/me/password; the server
 * signs every other session out. Accounts made with an OAuth provider have no password yet and set one through
 * Forgot Password (`<baseUrl>/forgot`).
 */
@Composable
fun ChangePasswordScreen(api: SiteApi, baseUrl: String, openLinksInApp: Boolean, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf<Int?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun save() {
        if (saving) return
        changePasswordProblem(current, new, confirm)?.let { errorText = it; return }
        saving = true
        scope.launch {
            api.changePassword(current, new).fold(
                onSuccess = {
                    errorText = null
                    focus.clearFocus()
                    ended = it
                },
                onFailure = { errorText = it.message },
            )
            saving = false
        }
    }

    @Composable
    fun field(value: String, placeholder: String, last: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
        TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = c.chatText,
                unfocusedTextColor = c.chatText,
            ),
            modifier = modifier.fillMaxWidth().padding(horizontal = Grid.unit),
        )
    }

    SettingsScaffold(
        title = "Change Password",
        onBack = onBack,
        actions = { TextButton(onClick = ::save, enabled = !saving) { Text("Save") } },
    ) {
        settingsSection(
            key = "fields",
            footer = errorText,
            footerColor = c.error,
            rows = listOf(
                { field(current, "Current Password", last = false, Modifier.focusRequester(first)) { current = it } },
                { field(new, "New Password", last = false) { new = it } },
                { field(confirm, "Confirm New Password", last = true) { confirm = it } },
            ),
        )
        settingsSection(
            key = "forgot",
            footer = "Signed up with Google or another provider? Your account has no password yet — set one with Forgot Password.",
            rows = listOf {
                SettingsRow(
                    "Forgot Password…",
                    titleColor = c.link,
                    onClick = { LinkOpener.open(context, "${baseUrl.trimEnd('/')}/forgot", openLinksInApp) },
                )
            },
        )
    }

    ended?.let { n ->
        AlertDialog(
            onDismissRequest = { ended = null; onBack() },
            title = { Text("Password Changed") },
            text = if (n > 0) ({ Text("Signed out of $n other sessions.") }) else null,
            confirmButton = { TextButton(onClick = { ended = null; onBack() }) { Text("OK") } },
        )
    }
}
