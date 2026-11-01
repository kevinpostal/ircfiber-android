package com.kevinpostal.fiberirc.ui.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings

/**
 * First screen: sign in with the ircfiber.com account (ports `LoginViewController`'s layout with Material3
 * defaults; the theme pass applies `ui/theme` colors). Only the status row shows while a stored session is probed.
 * [onSession] receives the connections to start once signed in.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    baseUrl: String,
    onSession: (List<ConnectionSettings>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var moreOpen by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        session?.let {
            viewModel.sessionShown()
            onSession(it)
        }
    }

    fun open(url: String?) {
        url ?: return
        focus.clearFocus()
        if (!openCustomTab(context, url)) viewModel.showError("No browser available to sign in")
    }

    val enabled = !state.busy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 24.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("IRC Fiber", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Sign in with your ircfiber.com account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                if (state.formVisible) {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::setUsername,
                        label = { Text("Username") },
                        placeholder = { Text("nick") },
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            focus.clearFocus()
                            viewModel.signIn()
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            focus.clearFocus()
                            viewModel.signIn()
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 12.dp),
                    ) { Text("Sign in") }
                    if ("google" in state.enabledProviders) {
                        OutlinedButton(
                            onClick = { open(viewModel.providerSignInUrl("google")) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) { Text("Continue with Google") }
                    }
                    if ("github" in state.enabledProviders) {
                        OutlinedButton(
                            onClick = { open(viewModel.providerSignInUrl("github")) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) { Text("Continue with GitHub") }
                    }
                    if (state.otherProviders.isNotEmpty()) {
                        TextButton(
                            onClick = { if (!state.busy) moreOpen = true },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("More sign-in options") }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    if (state.status.isNotEmpty()) {
                        Text(
                            state.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.statusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                if (state.formVisible) {
                    TextButton(onClick = { open(viewModel.registerUrl()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Create an account at ircfiber.com", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    if (moreOpen) {
        AlertDialog(
            onDismissRequest = { moreOpen = false },
            title = { Text("Sign in with") },
            text = {
                Column {
                    for (p in state.otherProviders) {
                        TextButton(onClick = {
                            moreOpen = false
                            open(viewModel.providerSignInUrl(p.name))
                        }, modifier = Modifier.fillMaxWidth()) { Text(p.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moreOpen = false }) { Text("Cancel") } },
        )
    }

    if (state.guidelinesVisible) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Community Guidelines") },
            text = {
                Text(
                    "IRC Fiber has zero tolerance for objectionable content and abusive users. By continuing you " +
                        "agree to the Terms of Use. Long-press any message, or tap a member, to report or block.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.answerGuidelines(true) }) { Text("Agree") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { openCustomTab(context, "${baseUrl.trimEnd('/')}/terms") }) { Text("Read Terms") }
                    TextButton(onClick = { viewModel.answerGuidelines(false) }) { Text("Cancel") }
                }
            },
        )
    }
}

/** Opens [url] in a Chrome Custom Tab; false when no browser can. */
fun openCustomTab(context: Context, url: String): Boolean = try {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    true
} catch (_: ActivityNotFoundException) {
    false
}
