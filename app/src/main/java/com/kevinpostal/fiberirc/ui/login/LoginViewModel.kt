package com.kevinpostal.fiberirc.ui.login

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.LocaleList
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
import com.kevinpostal.fiberirc.data.api.SignupResult
import com.kevinpostal.fiberirc.data.api.SignupWatchState
import com.kevinpostal.fiberirc.data.api.SiteApi
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.prefs.AccountStores
import com.kevinpostal.fiberirc.data.prefs.DeviceSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Which of the login screen's three surfaces shows. */
enum class LoginMode { SIGN_IN, SIGN_UP, CHECK_EMAIL }

/**
 * What the check-email screen is doing: following the signup watch (`GET /register/status`), recovering the
 * session the confirmation started (`GET /api/me` only), waiting for a manual sign-in (no check can work), or
 * paused on an unexpected answer until the user deliberately checks again.
 */
enum class VerificationPhase { FOLLOW_WATCH, RECOVER_SESSION, MANUAL_SIGN_IN, PAUSED_ERROR }

/** What the sign-in screen shows. */
data class LoginUiState(
    /** Hidden while the stored session is probed, so a valid session never flashes the form. */
    val formVisible: Boolean = false,
    /** A sign-in, account-creation POST or authenticated hand-off is running; never set by verification checks. */
    val busy: Boolean = false,
    val status: String = "",
    val statusIsError: Boolean = false,
    val mode: LoginMode = LoginMode.SIGN_IN,
    val username: String = "",
    /** Shared by sign-in and account creation; cleared whenever signup accepts, signs in or is left. */
    val password: String = "",
    val email: String = "",
    /** The address the server says it sent the confirmation to (check-email screen). */
    val pendingEmail: String = "",
    val verificationPhase: VerificationPhase = VerificationPhase.FOLLOW_WATCH,
    /** A verification/session-recovery request is in flight (the check button coalesces with it). */
    val verificationChecking: Boolean = false,
    /** Providers with a direct button the server enabled (assumed on until `/api/auth/providers` answers). */
    val enabledProviders: Set<String> = setOf("google", "github"),
    /** Server providers without a direct button (e.g. Codeberg), under "More sign-in options". */
    val otherProviders: List<AuthProvider> = emptyList(),
    /** The Community Guidelines dialog is waiting for an answer. */
    val guidelinesVisible: Boolean = false,
)

/**
 * The sign-in flow (ports `LoginViewController.swift`): resume a stored session, password sign-in, native account
 * creation with an in-app email-verification wait, social sign-in through a Custom Tab handing off on
 * `fiberirc://auth?code=`, the once-per-install Community Guidelines, then the bootstrap — the account's bouncer
 * (one connection per network) or a direct ircd connection with SASL. Sign in with Apple is not offered (no Android
 * equivalent).
 *
 * Account creation: POST /register is single-flight under [LoginUiState.busy]. An accepted 202 with a watch cookie
 * is remembered in [PendingSignupStore] and followed by one verification poller that runs only while the
 * check-email screen is foreground ([setSignupForeground]): immediately, then no sooner than [POLL_INTERVAL_MS]
 * after the previous answer. Every signup callback is fenced by a generation, so leaving, a new attempt or another
 * auth flow drops late answers, and every authenticated path reaches [connect] — whose entrance ends signup work
 * and forgets the marker and watch — exactly once.
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

    private val pendingSignup = PendingSignupStore(appPrefs)
    /** Bumped whenever signup work is invalidated; async signup callbacks act only while theirs is current. */
    private var signupGeneration = 0L
    private var signupJob: Job? = null
    private var pollJob: Job? = null
    /** "Check verification" taps; conflated, so taps made while a check is in flight coalesce with it. */
    private val checkNow = Channel<Unit>(Channel.CONFLATED)
    /** The login screen is resumed (reported by the UI in every mode). */
    private var signupForeground = false
    /** An authenticated signup that finished while backgrounded, with the generation it belongs to. */
    private var deferredHandoff: Pair<Long, Me>? = null
    /** The phase a [VerificationPhase.PAUSED_ERROR] resumes on a deliberate check. */
    private var pausedFrom = VerificationPhase.FOLLOW_WATCH
    /** Session recovery started from `gone` rather than `verified` (only changes the manual-sign-in wording). */
    private var recoveringFromGone = false

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

    fun setEmail(value: String) = _state.update { it.copy(email = value) }

    fun signIn() {
        val s = _state.value
        if (s.busy || s.mode != LoginMode.SIGN_IN) return
        val u = s.username.trim()
        val p = s.password
        if (u.isEmpty() || p.isEmpty()) {
            show("Username and password are required", error = true)
            return
        }
        val stale = invalidateSignupWork(cancelPost = true)
        setBusy(true)
        show("Signing in…")
        viewModelScope.launch {
            try {
                stale.forEach { it.cancelAndJoin() }
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
    fun providerSignInUrl(provider: String): String? {
        if (_state.value.busy) return null
        leaveSignup(cancelPost = true)
        _state.update { it.copy(mode = LoginMode.SIGN_IN) }
        return appSignInUrl(api, listOf("auth", provider))
    }

    /** The `fiberirc://auth?code=` hand-off (MainActivity `onCreate`/`onNewIntent`); false when [uri] isn't one. */
    fun handleAuthRedirect(uri: Uri?): Boolean {
        val code = authCode(uri) ?: return false
        if (code.isEmpty()) {
            show("Sign-in did not complete", error = true)
            return true
        }
        // Another auth flow takes over: drop signup polling, an unanswered POST and any deferred hand-off first.
        val stale = leaveSignup(cancelPost = true)
        _state.update { it.copy(mode = LoginMode.SIGN_IN) }
        setBusy(true)
        show("Signing in…")
        viewModelScope.launch {
            try {
                stale.forEach { it.cancelAndJoin() }
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

    // MARK: Account creation

    /** Sign-in form → create-account form (username kept, password emptied). */
    fun showSignUp() {
        val s = _state.value
        if (s.busy || !s.formVisible || s.mode != LoginMode.SIGN_IN) return
        _state.update { it.copy(mode = LoginMode.SIGN_UP, password = "", status = "", statusIsError = false) }
    }

    /**
     * Back to sign-in from either signup surface (also system Back and "Already confirmed? Sign in"): username
     * prefilled, password emptied, the local marker and watch forgotten. The emailed link stays valid.
     */
    fun showSignIn() {
        val s = _state.value
        if (s.busy || s.mode == LoginMode.SIGN_IN) return
        leaveSignup(cancelPost = false)
        _state.update { it.copy(mode = LoginMode.SIGN_IN) }
    }

    /** Check-email → the form with username/email kept and password empty; the next submit is a fresh attempt. */
    fun editSignupDetails() {
        val s = _state.value
        if (s.busy || s.mode != LoginMode.CHECK_EMAIL) return
        leaveSignup(cancelPost = false)
        _state.update { it.copy(mode = LoginMode.SIGN_UP) }
    }

    /**
     * POST /register once. Never retried automatically: a lost answer tells the user to confirm the email and sign
     * in instead. A POST finishing while backgrounded still saves its accepted state, but an immediate sign-in waits
     * for [setSignupForeground] before entering [connect].
     */
    fun signUp() {
        val s = _state.value
        if (s.busy || s.mode != LoginMode.SIGN_UP || signupJob?.isActive == true) return
        val u = s.username.trim()
        val e = s.email.trim()
        val p = s.password
        if (u.isEmpty() || e.isEmpty() || p.isEmpty()) {
            show("Username, email and password are required", error = true)
            return
        }
        val stale = invalidateSignupWork(cancelPost = false)
        val gen = signupGeneration
        setBusy(true)
        show("Creating account…")
        signupJob = viewModelScope.launch {
            stale.forEach { it.cancelAndJoin() }
            // A fresh attempt: only the previous marker and watch go; the session cookie stays.
            forgetPendingSignup()
            val result = try {
                api.register(u, e, p, LocaleList.getDefault().toLanguageTags())
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                if (gen != signupGeneration) return@launch
                releaseSignupJob(coroutineContext[Job])
                setBusy(false)
                // The answer is lost, not refused: the server may have sent a confirmation, so never re-POST.
                runCatching { Log.e(TAG, "sign-up: request failed (${x.javaClass.simpleName})") }
                _state.update {
                    it.copy(
                        status = "Couldn't reach the server. If a confirmation email arrives, confirm it and then sign in.",
                        statusIsError = true,
                    )
                }
                return@launch
            }
            if (gen != signupGeneration) return@launch
            releaseSignupJob(coroutineContext[Job])
            onSignupResult(gen, u, e, result)
        }
    }

    /** "Check verification": an immediate check (coalesced with one in flight), or a retry from a paused error. */
    fun pollSignupNow() {
        val s = _state.value
        if (s.mode != LoginMode.CHECK_EMAIL) return
        when (s.verificationPhase) {
            VerificationPhase.MANUAL_SIGN_IN -> return
            VerificationPhase.PAUSED_ERROR -> {
                _state.update { it.copy(verificationPhase = pausedFrom, status = "", statusIsError = false) }
                syncPolling()
            }
            VerificationPhase.FOLLOW_WATCH, VerificationPhase.RECOVER_SESSION -> {
                if (s.verificationChecking) return
                if (pollJob?.isActive == true) checkNow.trySend(Unit) else syncPolling()
            }
        }
    }

    /**
     * The login screen is resumed ([active]) or paused/gone. Verification checks run only while it is; pausing
     * cancels the request in flight and resuming checks at once. A deferred signup sign-in proceeds on resume.
     */
    fun setSignupForeground(active: Boolean) {
        signupForeground = active
        if (active) {
            deferredHandoff?.let { (gen, me) ->
                deferredHandoff = null
                if (gen == signupGeneration) launchHandoff(me)
            }
        }
        syncPolling()
    }

    private fun onSignupResult(gen: Long, username: String, email: String, result: SignupResult) {
        when (result) {
            is SignupResult.VerificationSent -> {
                val watched = api.hasSignupWatch()
                if (watched) {
                    pendingSignup.save(PendingSignupStore.Entry(baseKey(), username, result.email, System.currentTimeMillis()))
                }
                _state.update {
                    it.copy(
                        busy = false,
                        password = "",
                        mode = LoginMode.CHECK_EMAIL,
                        pendingEmail = result.email,
                        verificationPhase = if (watched) VerificationPhase.FOLLOW_WATCH else VerificationPhase.MANUAL_SIGN_IN,
                        verificationChecking = false,
                        status = if (watched) "" else "Confirm the email we sent, then sign in.",
                        statusIsError = false,
                    )
                }
                syncPolling()
            }
            is SignupResult.SignedIn -> {
                _state.update { it.copy(password = "") }
                beginSignupHandoff(gen, result.me)
            }
            is SignupResult.Failure -> when (val error = result.error) {
                // The server accepted something it then failed to describe (an invalid 202, or /api/me after an
                // immediate creation): pause on the check-email screen; a deliberate check follows the watch when
                // there is one, else probes the session. Never re-POSTs.
                SiteApiError.Malformed -> {
                    pausedFrom = if (api.hasSignupWatch()) VerificationPhase.FOLLOW_WATCH else VerificationPhase.RECOVER_SESSION
                    recoveringFromGone = pausedFrom == VerificationPhase.RECOVER_SESSION
                    _state.update {
                        it.copy(
                            busy = false,
                            password = "",
                            mode = LoginMode.CHECK_EMAIL,
                            pendingEmail = email,
                            verificationPhase = VerificationPhase.PAUSED_ERROR,
                            verificationChecking = false,
                        )
                    }
                    show("The server's answer was unexpected. Check verification to try again, or confirm your email and sign in.", error = true)
                }
                // Created (2xx/3xx) but the session didn't stick: the account may exist — sign in by hand.
                SiteApiError.Unauthorized -> {
                    _state.update { it.copy(busy = false, password = "", mode = LoginMode.SIGN_IN) }
                    show("Your account may have been created — sign in to continue.")
                }
                else -> {
                    setBusy(false)
                    val code = (error as? SiteApiError.Rejected)?.code ?: error.javaClass.simpleName
                    runCatching { Log.e(TAG, "sign-up: rejected ($code)") }
                    _state.update { it.copy(status = describe(error), statusIsError = true) }
                }
            }
        }
    }

    // MARK: Verification polling

    private enum class Next { NOW, LATER, STOP }

    /** Starts the single poller when the check-email screen is foreground and following/recovering; else stops it. */
    private fun syncPolling() {
        val s = _state.value
        val wanted = signupForeground && s.mode == LoginMode.CHECK_EMAIL &&
            (s.verificationPhase == VerificationPhase.FOLLOW_WATCH || s.verificationPhase == VerificationPhase.RECOVER_SESSION)
        if (!wanted) {
            pollJob?.cancel()
            if (s.verificationChecking) _state.update { it.copy(verificationChecking = false) }
            return
        }
        if (pollJob?.isActive == true) return
        val gen = signupGeneration
        val previous = pollJob
        checkNow.tryReceive() // the poller checks at once anyway
        pollJob = viewModelScope.launch {
            previous?.join()
            pollLoop(gen)
        }
    }

    private suspend fun pollLoop(gen: Long) {
        try {
            while (gen == signupGeneration) {
                when (checkOnce(gen)) {
                    Next.STOP -> return
                    Next.NOW -> continue
                    Next.LATER -> {
                        checkNow.tryReceive() // a tap during the request was answered by it
                        withTimeoutOrNull(POLL_INTERVAL_MS) { checkNow.receive() }
                    }
                }
            }
        } finally {
            if (gen == signupGeneration && _state.value.verificationChecking) {
                _state.update { it.copy(verificationChecking = false) }
            }
        }
    }

    private suspend fun checkOnce(gen: Long): Next {
        _state.update { it.copy(verificationChecking = true) }
        return if (_state.value.verificationPhase == VerificationPhase.RECOVER_SESSION) recoverSession(gen) else followWatch(gen)
    }

    /** One `GET /register/status`. */
    private suspend fun followWatch(gen: Long): Next {
        val result = api.signupStatus()
        if (gen != signupGeneration) return Next.STOP
        _state.update { it.copy(verificationChecking = false) }
        return result.fold(
            onSuccess = { watch ->
                when (watch) {
                    SignupWatchState.Pending -> {
                        show("")
                        Next.LATER
                    }
                    SignupWatchState.Verified -> {
                        recoveringFromGone = false
                        _state.update { it.copy(verificationPhase = VerificationPhase.RECOVER_SESSION) }
                        show("Email confirmed — signing in…")
                        Next.NOW
                    }
                    // The watch ended without a verified answer reaching us: a lost `verified` may still have
                    // started the session, so probe it before offering sign-in.
                    SignupWatchState.Gone -> {
                        recoveringFromGone = true
                        _state.update { it.copy(verificationPhase = VerificationPhase.RECOVER_SESSION) }
                        show("Checking your sign-in…")
                        Next.NOW
                    }
                }
            },
            onFailure = { e ->
                if (retryable(e)) {
                    show("Can't reach the server — retrying…", error = true)
                    Next.LATER
                } else {
                    pause(VerificationPhase.FOLLOW_WATCH, "Couldn't check verification (${describe(e)}).")
                    Next.STOP
                }
            },
        )
    }

    /** One `GET /api/me` after the watch ended; the consumed watch is never polled again. */
    private suspend fun recoverSession(gen: Long): Next {
        val result = api.me()
        if (gen != signupGeneration) return Next.STOP
        _state.update { it.copy(verificationChecking = false) }
        return result.fold(
            onSuccess = { me ->
                beginSignupHandoff(gen, me)
                Next.STOP
            },
            onFailure = { e ->
                when {
                    e is SiteApiError.Unauthorized -> {
                        forgetPendingSignup()
                        _state.update { it.copy(verificationPhase = VerificationPhase.MANUAL_SIGN_IN) }
                        show(
                            if (recoveringFromGone) {
                                "This app is no longer following that confirmation. If you confirmed your email, sign in; otherwise edit your details to try again."
                            } else {
                                "Your email is confirmed. Sign in to continue."
                            },
                        )
                        Next.STOP
                    }
                    retryable(e) -> {
                        show("Can't reach the server — retrying…", error = true)
                        Next.LATER
                    }
                    else -> {
                        pause(VerificationPhase.RECOVER_SESSION, "Couldn't finish signing in (${describe(e)}).")
                        Next.STOP
                    }
                }
            },
        )
    }

    private fun pause(from: VerificationPhase, text: String) {
        pausedFrom = from
        _state.update { it.copy(verificationPhase = VerificationPhase.PAUSED_ERROR, verificationChecking = false) }
        show("$text Check verification to try again.", error = true)
    }

    private fun retryable(e: Throwable): Boolean = e is IOException || e is SiteApiError.RateLimited ||
        (e is SiteApiError.Server && (e.code >= 500 || e.code == 408 || e.code == 429))

    // MARK: Signup lifecycle

    /**
     * The one authenticated exit from signup: fences every other signup callback, then enters [connect] once —
     * now, or when the screen next resumes if it is backgrounded.
     */
    private fun beginSignupHandoff(gen: Long, me: Me) {
        if (gen != signupGeneration) return
        signupGeneration++
        if (signupForeground) {
            launchHandoff(me)
        } else {
            deferredHandoff = signupGeneration to me
            setBusy(true)
            show("Account ready — finishing sign-in…")
        }
    }

    /** Runs [connect] outside the poll/POST jobs, so its entrance can cancel them without cancelling itself. */
    private fun launchHandoff(me: Me) {
        setBusy(true)
        viewModelScope.launch {
            try {
                connect(me)
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Invalidates signup work: bumps the generation, cancels the poller (and the POST when [cancelPost]) and drops
     * a deferred hand-off. Returns the cancelled jobs for callers that join them before their own network work.
     */
    private fun invalidateSignupWork(cancelPost: Boolean): List<Job> {
        signupGeneration++
        deferredHandoff = null
        recoveringFromGone = false
        val stale = mutableListOf<Job>()
        pollJob?.let { it.cancel(); stale += it }
        if (cancelPost) signupJob?.let { it.cancel(); stale += it; signupJob = null }
        if (_state.value.verificationChecking) _state.update { it.copy(verificationChecking = false) }
        return stale
    }

    /**
     * The shared local exit (back to sign-in, edit details, another auth flow): stops signup work and forgets the
     * marker, watch and password. The session cookie, account and encryption storage are untouched; callers set
     * the next mode.
     */
    private fun leaveSignup(cancelPost: Boolean): List<Job> {
        val stale = invalidateSignupWork(cancelPost)
        forgetPendingSignup()
        _state.update {
            it.copy(
                password = "",
                pendingEmail = "",
                verificationPhase = VerificationPhase.FOLLOW_WATCH,
                status = "",
                statusIsError = false,
            )
        }
        return stale
    }

    /** Only the signup marker and watch cookie — never the session. */
    private fun forgetPendingSignup() {
        pendingSignup.clear()
        api.forgetSignupWatch()
    }

    /** Forgets [job] as the POST in flight (it has answered); a newer POST is never touched. */
    private fun releaseSignupJob(job: Job?) {
        if (signupJob === job) signupJob = null
    }

    /** The API's canonical base URL, the key [PendingSignupStore] matches exactly. */
    private fun baseKey(): String = api.url(emptyList()).toString()

    /**
     * After `/api/me` definitively answered unauthenticated: resumes a valid pending signup whose watch cookie is
     * still in the jar. A stray watch without valid metadata is forgotten, never claimed; valid metadata without
     * a watch keeps the username for a manual sign-in.
     */
    private fun restorePendingSignup() {
        val entry = pendingSignup.load(baseKey())
        if (entry == null) {
            api.forgetSignupWatch()
            return
        }
        val watched = api.hasSignupWatch()
        if (!watched) pendingSignup.clear()
        _state.update {
            it.copy(
                mode = LoginMode.CHECK_EMAIL,
                username = entry.username,
                email = entry.email,
                password = "",
                pendingEmail = entry.email,
                verificationPhase = if (watched) VerificationPhase.FOLLOW_WATCH else VerificationPhase.MANUAL_SIGN_IN,
                verificationChecking = false,
                status = if (watched) "" else "If you confirmed your email, sign in to continue — confirmation still works.",
                statusIsError = false,
            )
        }
        syncPolling()
    }

    // MARK: Flow

    private suspend fun resumeSession() {
        setBusy(true)
        show("Checking session…")
        try {
            api.me().fold(
                onSuccess = { connect(it) },
                onFailure = { e ->
                    if (e is SiteApiError.Unauthorized) {
                        show(notice ?: "")
                        restorePendingSignup()
                    } else {
                        // Not a definitive answer: keep any pending signup for a later launch, start nothing.
                        show(describe(e), error = true)
                    }
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
     *
     * The shared entrance of every authenticated path (password, provider, resume, signup): it ends signup work —
     * joining a poller or POST other than the caller — and forgets only the signup marker and watch.
     */
    private suspend fun connect(me: Me) {
        val self = currentCoroutineContext()[Job]
        val stale = invalidateSignupWork(cancelPost = true).filter { it !== self }
        forgetPendingSignup()
        _state.update {
            it.copy(
                mode = LoginMode.SIGN_IN,
                password = "",
                pendingEmail = "",
                verificationPhase = VerificationPhase.FOLLOW_WATCH,
                verificationChecking = false,
            )
        }
        stale.forEach { it.cancelAndJoin() }
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
        /** Minimum gap between a verification answer and the next automatic check. */
        const val POLL_INTERVAL_MS = 2_000L
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
         * `fiberirc://auth?code=` (social sign-in and the encryption settings' organization sign-in).
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
