package com.kevinpostal.fiberirc.ui.login

import com.kevinpostal.fiberirc.data.SessionStart

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * First screen (ports `LoginViewController`'s layout with Material3 defaults; the theme pass applies `ui/theme`
 * colors): sign in with the ircfiber.com account, create one natively ([LoginMode.SIGN_UP]), then wait for its
 * email confirmation ([LoginMode.CHECK_EMAIL]). Only the status row shows while a stored session is probed.
 * [onSession] receives the connections to start once signed in.
 *
 * Signup verification polling follows this screen's lifecycle: [LoginViewModel.setSignupForeground] is true only
 * while the screen is RESUMED and false once it pauses or leaves composition.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    baseUrl: String,
    onSession: (SessionStart) -> Unit,
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

    LifecycleResumeEffect(viewModel) {
        viewModel.setSignupForeground(true)
        onPauseOrDispose { viewModel.setSignupForeground(false) }
    }

    // Back from creating an account or waiting for its email returns to sign-in (the same local exit as the
    // buttons); while the registration POST or the sign-in hand-off runs, Back is swallowed instead.
    BackHandler(enabled = state.formVisible && state.mode != LoginMode.SIGN_IN) {
        if (!state.busy) {
            focus.clearFocus()
            viewModel.showSignIn()
        }
    }

    fun open(url: String?) {
        url ?: return
        focus.clearFocus()
        if (!openCustomTab(context, url)) viewModel.showError("No browser available to sign in")
    }

    fun openPage(path: String) {
        focus.clearFocus()
        if (!openCustomTab(context, "${baseUrl.trimEnd('/')}/$path")) viewModel.showError("No browser available")
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
                        when (state.mode) {
                            LoginMode.SIGN_IN -> "Sign in with your ircfiber.com account"
                            LoginMode.SIGN_UP -> "Create your ircfiber.com account"
                            LoginMode.CHECK_EMAIL -> "Confirm your email"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                if (state.formVisible) {
                    when (state.mode) {
                        LoginMode.SIGN_IN -> {
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
                        LoginMode.SIGN_UP -> SignUpForm(
                            state = state,
                            viewModel = viewModel,
                            onSubmit = {
                                focus.clearFocus()
                                viewModel.signUp()
                            },
                            onOpenPage = ::openPage,
                        )
                        LoginMode.CHECK_EMAIL -> CheckEmail(state = state, viewModel = viewModel)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.busy || state.verificationChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    if (state.status.isNotEmpty()) {
                        Text(
                            state.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.statusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                if (state.formVisible && state.mode == LoginMode.SIGN_IN) {
                    TextButton(onClick = viewModel::showSignUp, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("Create an account", textAlign = TextAlign.Center)
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

/**
 * Username, email and password for a new account. Only emptiness is left to [LoginViewModel.signUp]; grammar,
 * email, length and availability are the server's to judge, and its message lands in the status row. Every
 * control locks while the registration request runs.
 */
@Composable
private fun ColumnScope.SignUpForm(
    state: LoginUiState,
    viewModel: LoginViewModel,
    onSubmit: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    val enabled = !state.busy
    var passwordShown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::setUsername,
        label = { Text("Username") },
        placeholder = { Text("nick") },
        supportingText = { Text("Your IRC nickname") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag("signupUsername").semantics { contentType = ContentType.NewUsername },
    )
    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::setEmail,
        label = { Text("Email") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag("signupEmail").semantics { contentType = ContentType.EmailAddress },
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::setPassword,
        label = { Text("Password") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (passwordShown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordShown = !passwordShown }) {
                Icon(
                    if (passwordShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (passwordShown) "Hide password" else "Show password",
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().testTag("signupPassword").semantics { contentType = ContentType.NewPassword },
    )
    Text(
        "By creating an account you agree to the Terms of Use and Privacy Policy. We'll email you a link to " +
            "confirm your address.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        TextButton(onClick = { onOpenPage("terms") }, enabled = enabled) { Text("Terms of Use") }
        TextButton(onClick = { onOpenPage("privacy") }, enabled = enabled) { Text("Privacy Policy") }
    }
    Button(
        onClick = onSubmit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("signupSubmit"),
    ) { Text("Create account") }
    TextButton(onClick = viewModel::showSignIn, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("Already have an account? Sign in", textAlign = TextAlign.Center)
    }
}

/**
 * The confirmation wait: the accepted address, a reminder that the link works from any device, and the three
 * ways on. A background status check never locks the exits; only the sign-in hand-off ([LoginUiState.busy]) does.
 */
@Composable
private fun ColumnScope.CheckEmail(state: LoginUiState, viewModel: LoginViewModel) {
    val enabled = !state.busy
    Column(
        modifier = Modifier.fillMaxWidth().testTag("signupWaiting"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("We sent a confirmation link to", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(state.pendingEmail, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            if (state.verificationPhase == VerificationPhase.MANUAL_SIGN_IN) {
                "Open the link on this or any other device. Once your email is confirmed, sign in with your new " +
                    "username and password."
            } else {
                "Open the link on this or any other device. When you come back, IRC Fiber continues automatically."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Button(
        onClick = viewModel::pollSignupNow,
        enabled = state.verificationPhase != VerificationPhase.MANUAL_SIGN_IN && !state.verificationChecking,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 12.dp).testTag("signupCheck"),
    ) { Text("Check verification") }
    OutlinedButton(
        onClick = viewModel::editSignupDetails,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("signupEdit"),
    ) { Text("Edit details") }
    TextButton(
        onClick = viewModel::showSignIn,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("signupSignIn"),
    ) { Text("Already confirmed? Sign in", textAlign = TextAlign.Center) }
}

/** Opens [url] in a Chrome Custom Tab; false when no browser can. */
fun openCustomTab(context: Context, url: String): Boolean = try {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    true
} catch (_: ActivityNotFoundException) {
    false
}
