package com.kevinpostal.fiberirc.ui.login

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevinpostal.fiberirc.data.api.AuthProvider
import com.kevinpostal.fiberirc.data.api.IrcAccount
import com.kevinpostal.fiberirc.data.api.IrcAccountStatus
import com.kevinpostal.fiberirc.data.api.LoginResult
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.SessionBootstrap
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the sign-in screen shows. */
data class LoginUiState(
    /** Hidden while the stored session is probed, so a valid session never flashes the form. */
    val formVisible: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val statusIsError: Boolean = false,
    val username: String = "",
    val password: String = "",
    /** Providers with a direct button the server enabled (assumed on until `/api/auth/providers` answers). */
    val enabledProviders: Set<String> = setOf("google", "github"),
    /** Server providers without a direct button (e.g. Codeberg), under "More sign-in options". */
    val otherProviders: List<AuthProvider> = emptyList(),
    /** The Community Guidelines dialog is waiting for an answer. */
    val guidelinesVisible: Boolean = false,
)

/**
 * The sign-in flow (ports `LoginViewController.swift`): resume a stored session, password sign-in, social sign-in
 * through a Custom Tab handing off on `fiberirc://auth?code=`, the once-per-install Community Guidelines, then the
 * bootstrap — the account's bouncer (one connection per network) or a direct ircd connection with SASL.
 * Sign in with Apple is not offered (no Android equivalent).
 *
 * The connections to start arrive on [session]; the host shows the shell and calls [sessionShown].
 */
class LoginViewModel(
    private val api: SiteApi,
    private val stores: AccountStores,
    private val deviceSettings: DeviceSettingsStore,
    private val deviceId: () -> String,
    private val appPrefs: SharedPreferences,
    private val notice: String?,
    resumeStoredSession: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _session = MutableStateFlow<List<ConnectionSettings>?>(null)
    /** Set once signed in: the connections the session starts with. */
    val session: StateFlow<List<ConnectionSettings>?> = _session.asStateFlow()

    private var guidelinesReply: CompletableDeferred<Boolean>? = null

    init {
        viewModelScope.launch { loadProviders() }
        if (resumeStoredSession) {
            viewModelScope.launch { resumeSession() }
        } else {
            show(notice.orEmpty())
            setFormVisible(true)
        }
    }

    fun sessionShown() {
        _session.value = null
    }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun signIn() {
        val s = _state.value
        if (s.busy) return
        val u = s.username.trim()
        val p = s.password
        if (u.isEmpty() || p.isEmpty()) {
            show("Username and password are required", error = true)
            return
        }
        setBusy(true)
        show("Signing in…")
        viewModelScope.launch {
            try {
                when (val r = api.login(u, p)) {
                    is LoginResult.Success -> {
                        _state.update { it.copy(password = "") }
                        connect(r.me)
                    }
                    is LoginResult.Failure -> show(describe(r.error), error = true)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    /** `<base>/auth/<provider>?app=fiberirc` for the Custom Tab, or null while busy. */
    fun providerSignInUrl(provider: String): String? = webSignInUrl(listOf("auth", provider))

    /** Signup on `/register`: its "Check your email" page hands the account to the app like social sign-in. */
    fun registerUrl(): String? = webSignInUrl(listOf("register"))

    private fun webSignInUrl(segments: List<String>): String? {
        if (_state.value.busy) return null
        return appSignInUrl(api, segments)
    }

    /** The `fiberirc://auth?code=` hand-off (MainActivity `onCreate`/`onNewIntent`); false when [uri] isn't one. */
    fun handleAuthRedirect(uri: Uri?): Boolean {
        val code = authCode(uri) ?: return false
        if (code.isEmpty()) {
            show("Sign-in did not complete", error = true)
            return true
        }
        setBusy(true)
        show("Signing in…")
        viewModelScope.launch {
            try {
                api.appSession(code).fold(
                    onSuccess = { connect(it.toMe()) },
                    onFailure = { show(describe(it), error = true) },
                )
            } finally {
                setBusy(false)
            }
        }
        return true
    }

    fun showError(text: String) = show(text, error = true)

    fun answerGuidelines(accepted: Boolean) {
        if (accepted) appPrefs.edit().putBoolean(KEY_ACCEPTED_TERMS, true).apply()
        _state.update { it.copy(guidelinesVisible = false) }
        guidelinesReply?.complete(accepted)
        guidelinesReply = null
    }

    // MARK: Flow

    private suspend fun resumeSession() {
        setBusy(true)
        show("Checking session…")
        try {
            api.me().fold(
                onSuccess = { connect(it) },
                onFailure = { e ->
                    if (e is SiteApiError.Unauthorized) show(notice ?: "") else show(describe(e), error = true)
                    setFormVisible(true)
                },
            )
        } finally {
            setBusy(false)
        }
    }

    /** Gates the provider buttons on `/api/auth/providers`; a failed fetch keeps GitHub + Google and no "More". */
    private suspend fun loadProviders() {
        val list = api.authProviders().getOrNull() ?: return
        _state.update { s ->
            s.copy(
                enabledProviders = list.map { it.name }.toSet().intersect(setOf("google", "github")),
                otherProviders = list.filter { it.name != "google" && it.name != "github" },
            )
        }
    }

    /**
     * Loads the account's settings, asks for the Community Guidelines, then attaches to the bouncer — or, when
     * it's off, connects directly to the ircd with the account's SASL credentials.
     */
    private suspend fun connect(me: Me) {
        stores.loadAtLogin(me)
        // The pre-sync local Font Size moves onto this device's display prefs, once; a failed save is dropped.
        deviceSettings.takeLegacyChatScalePct()?.let { pct ->
            runCatching { stores.accountSettings.updateDisplay { it.copy(chatScalePct = pct) } }
        }
        if (!acceptGuidelines()) {
            api.logout()
            show("")
            setFormVisible(true)
            return
        }
        show("Attaching to your session…")
        val clientId = withContext(Dispatchers.IO) { deviceId() }
        SessionBootstrap.bouncerSettings(me, api, clientId).fold(
            onSuccess = { settings ->
                if (settings.isNotEmpty()) {
                    show("")
                    _session.value = settings
                    return
                }
            },
            onFailure = { e ->
                if (e is SiteApiError.Unauthorized) {
                    show("Session expired — sign in again", error = true)
                    setFormVisible(true)
                    return
                }
                show("Bouncer unavailable (${describe(e)}); connecting directly")
            },
        )
        connectDirect(me)
    }

    private suspend fun connectDirect(me: Me) {
        show("Fetching IRC credentials…")
        var irc: IrcAccount? = null
        SessionBootstrap.ircAccountWaitingForReady(api).fold(
            onSuccess = { irc = it },
            onFailure = { e ->
                if (e is SiteApiError.Unauthorized) {
                    show("Session expired — sign in again", error = true)
                    setFormVisible(true)
                    return
                }
                show("Could not fetch IRC credentials (${describe(e)}); connecting without SASL")
            },
        )
        val settings = ConnectionSettings.forAccount(me.username, irc)
        val account = irc
        if (account != null && account.status != IrcAccountStatus.READY) {
            val status = account.status.wire + (account.reason?.let { " — $it" } ?: "")
            show("IRC account $status; connecting as ${me.username} without SASL")
        } else {
            show("")
        }
        _session.value = listOf(settings)
    }

    /** True once the user has agreed (asked once per install); false when they cancel. */
    private suspend fun acceptGuidelines(): Boolean {
        if (appPrefs.getBoolean(KEY_ACCEPTED_TERMS, false)) return true
        val reply = CompletableDeferred<Boolean>()
        guidelinesReply = reply
        _state.update { it.copy(guidelinesVisible = true) }
        return reply.await()
    }

    private fun setBusy(on: Boolean) = _state.update { it.copy(busy = on) }

    private fun setFormVisible(on: Boolean) = _state.update { it.copy(formVisible = on) }

    /** Status row under the form; error rows are also logged. */
    private fun show(text: String, error: Boolean = false) {
        _state.update { it.copy(status = text, statusIsError = error) }
        if (error) runCatching { Log.e(TAG, "sign-in: $text") }
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        private const val TAG = "FiberIRC.ui"
        /** Per-install Community Guidelines acceptance (iOS `acceptedTermsV1`). */
        const val KEY_ACCEPTED_TERMS = "accepted_terms_v1"
        /** App-wide per-install flags (never cleared on sign-out). */
        const val APP_PREFS_FILE = "fiberirc_app"

        /** The `code` of a `fiberirc://auth?code=` hand-off ("" when missing), null for any other link. */
        fun authCode(uri: Uri?): String? {
            if (uri == null || uri.scheme != "fiberirc" || uri.host != "auth") return null
            return uri.getQueryParameter("code").orEmpty()
        }

        /**
         * `<base>/<segments>?app=fiberirc`: a web sign-in in a Custom Tab that hands the account back on
         * `fiberirc://auth?code=` (social sign-in, signup, and the encryption settings' organization sign-in).
         */
        fun appSignInUrl(api: SiteApi, segments: List<String>): String = api.url(segments, mapOf("app" to "fiberirc")).toString()
    }
}

/** Hand-written factory, as vanlife's `MainActivity` does for its ViewModels. */
class LoginViewModelFactory(
    private val context: Context,
    private val api: SiteApi,
    private val stores: AccountStores,
    private val deviceSettings: DeviceSettingsStore,
    private val deviceId: () -> String,
    private val notice: String? = null,
    private val resumeStoredSession: Boolean = true,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val prefs = context.getSharedPreferences(LoginViewModel.APP_PREFS_FILE, Context.MODE_PRIVATE)
        return LoginViewModel(api, stores, deviceSettings, deviceId, prefs, notice, resumeStoredSession) as T
    }
}
