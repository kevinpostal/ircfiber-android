package com.kevinpostal.fiberirc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcSessionState
import com.kevinpostal.fiberirc.ui.login.LoginScreen
import com.kevinpostal.fiberirc.ui.login.LoginViewModel
import com.kevinpostal.fiberirc.ui.login.LoginViewModelFactory
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.SessionActions
import com.kevinpostal.fiberirc.ui.shell.SessionHost
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/**
 * Root activity: the sign-in flow until the process's session ([Deps.session]) exists, then the signed-in shell
 * ([SessionHost]); sign-out and rejected credentials return to sign-in. The session outlives this activity, so
 * recreating or reopening it shows the same connections. Also the target of the OAuth return (`fiberirc://auth`),
 * buffer links, conversation shortcuts and `irc(s)://` / App Links ([AppRouter]).
 */
class MainActivity : ComponentActivity() {
    private val deps: Deps get() = (application as FiberIrcApp).deps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Passkey sheets (encryption backup/unlock) present over this activity; released on its destroy.
        deps.passkeys.attach(this)
        // A recreated activity's launch intent was already handled.
        if (savedInstanceState == null) AppRouter.handle(intent)
        setContent { FiberIrcRoot(deps) }
    }

    /** `fiberirc://auth` → the login screen; buffer, `irc(s)://` and `/irc/…` App Links → the session. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppRouter.handle(intent)
    }
}

@Composable
private fun FiberIrcRoot(deps: Deps) {
    val appearance by deps.displaySettings.appearance.collectAsStateWithLifecycle()
    val state by deps.session.collectAsStateWithLifecycle()
    FiberIrcTheme(appearance = appearance) {
        Box(Modifier.fillMaxSize().background(LocalFiberIrcColors.current.background)) {
            when (val screen = state) {
                is IrcSessionState.Login -> Login(deps, screen, onSession = deps::startSession)
                is IrcSessionState.Session -> {
                    val context = LocalContext.current
                    val actions = remember(screen) {
                        SessionActions(
                            context, deps.api, deps.accountStores, deps.push::unregister, deps::showLogin,
                            e2ee = screen.manager.e2ee,
                        )
                    }
                    SessionHost(deps, screen.manager, actions)
                }
            }
        }
    }
}

/**
 * The sign-in flow: resumes a stored session first (the form stays hidden while `/api/me` answers), takes the
 * `fiberirc://auth` hand-off of social sign-in, then hands the session's connections to [onSession].
 */
@Composable
private fun Login(deps: Deps, screen: IrcSessionState.Login, onSession: (List<ConnectionSettings>) -> Unit) {
    val context = LocalContext.current
    val login: LoginViewModel = viewModel(
        key = "login-${screen.generation}",
        factory = LoginViewModelFactory(
            context, deps.api, deps.accountStores, deps.deviceSettings, { deps.deviceIdentity.id },
            screen.notice, screen.resumeStoredSession,
        ),
    )
    val authRedirect by AppRouter.pendingAuthRedirect.collectAsStateWithLifecycle()
    LaunchedEffect(login, authRedirect) {
        val uri = authRedirect ?: return@LaunchedEffect
        login.handleAuthRedirect(Uri.parse(uri))
        AppRouter.consumeAuthRedirect(uri)
    }
    LoginScreen(login, BuildConfig.API_BASE_URL, onSession = onSession)
}
