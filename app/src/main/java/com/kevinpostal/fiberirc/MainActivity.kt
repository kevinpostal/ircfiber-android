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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.ui.login.LoginScreen
import com.kevinpostal.fiberirc.ui.login.LoginViewModel
import com.kevinpostal.fiberirc.ui.login.LoginViewModelFactory
import com.kevinpostal.fiberirc.ui.shell.AppRouter
import com.kevinpostal.fiberirc.ui.shell.SessionActions
import com.kevinpostal.fiberirc.ui.shell.SessionHost
import com.kevinpostal.fiberirc.ui.theme.FiberIrcTheme
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/**
 * Root activity: the sign-in flow until a session exists, then the signed-in shell ([SessionHost]); sign-out and
 * rejected credentials return to sign-in. Also the target of the OAuth return (`fiberirc://auth`), buffer links,
 * conversation shortcuts and `irc(s)://` / App Links ([AppRouter]).
 */
class MainActivity : ComponentActivity() {
    private val deps: Deps get() = (application as FiberIrcApp).deps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

/**
 * Which screen the app shows; survives activity recreation, so a theme or locale change keeps the session's
 * connections. The session's [IrcSessionManager] is stopped when it ends and when the activity finishes.
 */
class AppViewModel(private val deps: Deps) : ViewModel() {
    sealed interface Screen {
        /** Sign-in, with [notice] in its status row; [generation] gives each visit a fresh login flow. */
        data class Login(val notice: String?, val generation: Int) : Screen

        class Session(val manager: IrcSessionManager) : Screen
    }

    var screen by mutableStateOf<Screen>(Screen.Login(null, 0))
        private set
    private var generation = 0

    /** Signed in: connect [settings] and show the shell. */
    fun startSession(settings: List<ConnectionSettings>) {
        (screen as? Screen.Session)?.manager?.stop()
        screen = Screen.Session(deps.sessionManager(settings, ::showLogin))
    }

    /** Sign-out, account deletion or rejected credentials: drop the connections, back to sign-in. */
    fun showLogin(notice: String) {
        (screen as? Screen.Session)?.manager?.stop()
        screen = Screen.Login(notice.ifEmpty { null }, ++generation)
    }

    override fun onCleared() {
        (screen as? Screen.Session)?.manager?.stop()
    }

    class Factory(private val deps: Deps) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(deps) as T
    }
}

@Composable
private fun FiberIrcRoot(deps: Deps) {
    val appearance by deps.displaySettings.appearance.collectAsStateWithLifecycle()
    val app: AppViewModel = viewModel(factory = AppViewModel.Factory(deps))
    FiberIrcTheme(appearance = appearance) {
        Box(Modifier.fillMaxSize().background(LocalFiberIrcColors.current.background)) {
            when (val screen = app.screen) {
                is AppViewModel.Screen.Login -> Login(deps, screen, onSession = app::startSession)
                is AppViewModel.Screen.Session -> {
                    val context = LocalContext.current
                    val actions = remember(screen) {
                        SessionActions(context, deps.api, deps.accountStores, deps.push::unregister, app::showLogin)
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
private fun Login(deps: Deps, screen: AppViewModel.Screen.Login, onSession: (List<ConnectionSettings>) -> Unit) {
    val context = LocalContext.current
    val login: LoginViewModel = viewModel(
        key = "login-${screen.generation}",
        factory = LoginViewModelFactory(context, deps.api, deps.accountStores, { deps.deviceIdentity.id }, screen.notice),
    )
    val authRedirect by AppRouter.pendingAuthRedirect.collectAsStateWithLifecycle()
    LaunchedEffect(login, authRedirect) {
        val uri = authRedirect ?: return@LaunchedEffect
        login.handleAuthRedirect(Uri.parse(uri))
        AppRouter.consumeAuthRedirect(uri)
    }
    LoginScreen(login, BuildConfig.API_BASE_URL, onSession = onSession)
}
