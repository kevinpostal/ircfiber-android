package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Failures of ircfiber.com calls (ports `SiteAPI.Error`); [message] is the text the user sees.
 * Transport failures stay `IOException`s.
 */
sealed class SiteApiError(message: String) : Exception(message) {
    data object BadCredentials : SiteApiError("Incorrect username or password")
    data object Unauthorized : SiteApiError("Session expired — sign in again")
    data class Server(val code: Int) : SiteApiError("Server error ($code)")
    data object Malformed : SiteApiError("Unexpected response from ircfiber.com")
    data object RateLimited : SiteApiError("Too many reports in the last hour — try again later")
    /** Server-provided explanation (`{"error": "…"}`). */
    data class Message(val text: String) : SiteApiError(text)
    /**
     * 409 with a JSON [body] — the notification policy's `rev_conflict` `{"error","code","current"}`: someone
     * else changed it first; [body]'s `current` is the stored state to retry on.
     */
    data class Conflict(val body: JsonElement) : SiteApiError(
        ((body as? JsonObject)?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: "Changed elsewhere — try again",
    )

    /**
     * A POST /register rejection, `{"error","code","params"}` (`apiErrorBody`): [text] is the server's explanation,
     * [code] its stable reason (`username_taken`, `nick_registered`, `verification_cooldown`, …) and [nick] its
     * `params.nick`. Never a [Conflict]: a registration 409 is a taken name, not a preference revision.
     */
    data class Rejected(val status: Int, val code: String?, val text: String, val nick: String?) : SiteApiError(text)

    companion object {
        /** `{"error": "…"}` bodies become [Message] so the user sees the server's explanation (`serverError`). */
        fun serverError(code: Int, body: String): SiteApiError {
            val text = runCatching {
                (SiteJson.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.contentOrNull
            }.getOrNull()
            return if (!text.isNullOrEmpty()) Message(text) else Server(code)
        }

        /** Non-2xx/3xx answers of POST /register: [Rejected] when the body explains itself, else [Server]. */
        fun registration(status: Int, body: String): SiteApiError {
            val obj = runCatching { SiteJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return Server(status)
            val text = obj.string("error")
            if (text.isNullOrEmpty()) return Server(status)
            val params = obj["params"] as? JsonObject
            return Rejected(status, obj.string("code")?.takeIf { it.isNotEmpty() }, text, params?.string("nick")?.takeIf { it.isNotEmpty() })
        }

        /**
         * Non-2xx answers of the account-settings calls (`failure`). A 401 is a lost session, unless the server
         * explains it (a wrong current password); requireAuth's own body is the generic "Unauthorized". A 409 with
         * a JSON object body is a [Conflict].
         */
        fun failure(code: Int, body: String): SiteApiError {
            if (code == 409) {
                (runCatching { SiteJson.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.let { return Conflict(it) }
            }
            val error = serverError(code, body)
            if (code != 401) return error
            if (error is Message && error.text != "Unauthorized") return error
            return Unauthorized
        }
    }
}

/** POST /login outcome: the signed-in account, or [SiteApiError]/`IOException`. */
sealed interface LoginResult {
    data class Success(val me: Me) : LoginResult
    data class Failure(val error: Throwable) : LoginResult
}

/**
 * POST /register outcome: the confirmation email went out to [VerificationSent.email] (the
 * `ircfiber_signup_watch` cookie, when the server could start one, is in the jar — see [SiteApi.hasSignupWatch]);
 * the server created the account and signed in at once (verification off, 302 + session cookie); or the server's
 * [SiteApiError]. Transport failures are thrown as `IOException`, not wrapped.
 */
sealed interface SignupResult {
    data class VerificationSent(val email: String) : SignupResult
    data class SignedIn(val me: Me) : SignupResult
    data class Failure(val error: SiteApiError) : SignupResult
}

/** GET /register/status `state`. Unknown states are [SiteApiError.Malformed], never a default. */
enum class SignupWatchState(val wire: String) {
    Pending("pending"),
    Verified("verified"),
    Gone("gone"),
    ;

    companion object {
        fun fromWire(state: String): SignupWatchState? = entries.firstOrNull { it.wire == state }
    }
}

/**
 * ircfiber.com account API (ports `SiteAPI.swift`). Auth is the session cookie POST /login (or
 * POST /api/app/session) sets, kept by the client's [CookieJarStore]. [client] must not follow redirects:
 * POST /login answers 302 on success and that status is the signal. Headers come from
 * [fiberIrcHeaderInterceptor]. Logs method, path and status only, never bodies.
 */
class SiteApi(private val client: OkHttpClient, val baseUrl: String) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** `baseUrl` plus path segments, each percent-encoded whole (a channel's `#` or `/` stays in its segment). */
    fun url(segments: List<String>, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = base.newBuilder()
        for (s in segments) b.addPathSegment(s)
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    fun url(vararg segments: String): HttpUrl = url(segments.toList())

    // MARK: Session

    /** GET /api/me — [SiteApiError.Unauthorized] when there is no valid session. */
    suspend fun me(): Result<Me> = attempt {
        val a = send(get(url("api", "me")))
        if (a.code == 401) throw SiteApiError.Unauthorized
        if (a.code != 200) throw SiteApiError.Server(a.code)
        decode(Me.serializer(), a.body)
    }

    /** POST /login (form). Any 2xx/3xx (302 in practice) = success with the cookie stored; 400/401 = bad credentials. */
    suspend fun login(username: String, password: String): LoginResult {
        val result = attempt {
            val form = FormBody.Builder().add("username", username).add("password", password).build()
            val a = send(Request.Builder().url(url("login")).post(form).build())
            when (a.code) {
                in 200 until 400 -> Unit
                400, 401 -> throw SiteApiError.BadCredentials
                else -> throw SiteApiError.Server(a.code)
            }
            me().getOrThrow()
        }
        return result.fold({ LoginResult.Success(it) }, { LoginResult.Failure(it) })
    }

    /** GET /logout (answer ignored), then forgets every ircfiber.com cookie. */
    /** Local media must stop before any credential invalidation, including direct IRC auth-failure paths. */
    var beforeCredentialLoss: suspend () -> Unit = {}
    suspend fun logout() {
        beforeCredentialLoss()
        attempt { send(get(url("logout"))) }
        (client.cookieJar as? CookieJarStore)?.clear(base.host)
    }

    /** DELETE /api/me — permanently deletes the account, then signs out like [logout]. */
    suspend fun deleteAccount(): Result<Unit> = attempt {
        beforeCredentialLoss()
        val a = send(Request.Builder().url(url("api", "me")).delete().build())
        when (a.code) {
            200 -> Unit
            401 -> throw SiteApiError.Unauthorized
            else -> throw SiteApiError.serverError(a.code, a.body)
        }
    }.onSuccess { logout() }

    /** GET /api/auth/providers — social sign-in providers configured on the server. */
    suspend fun authProviders(): Result<List<AuthProvider>> = attempt {
        val a = send(get(url("api", "auth", "providers")))
        if (a.code != 200) throw SiteApiError.Server(a.code)
        decode(ProvidersEnvelope.serializer(), a.body).providers
    }

    /** POST /api/app/session — redeems the one-time code from the `fiberirc://auth?code=` hand-off. */
    suspend fun appSession(code: String): Result<AppSessionResult> = attempt {
        val a = send(post(url("api", "app", "session"), buildJsonObject { put("code", code) }))
        when (a.code) {
            200 -> decode(AppSessionResult.serializer(), a.body)
            400, 401 -> throw SiteApiError.Unauthorized
            else -> throw SiteApiError.Server(a.code)
        }
    }

    // MARK: Signup

    /**
     * POST /register (form, `Accept: application/json` from the header interceptor). First forgets any earlier
     * signup watch, so after [SignupResult.VerificationSent] [hasSignupWatch] reflects this answer alone. 202 must
     * be `{status:"verification_sent", email}` with a nonempty email, else [SiteApiError.Malformed]. Any other
     * 2xx/3xx (302 when verification is off) is resolved through [me]; its `Location` is never followed. Errors are
     * [SiteApiError.registration]. Only [SiteApiError]s become [SignupResult.Failure]; transport `IOException`s and
     * coroutine cancellation propagate. The password is sent unchanged and never logged or kept.
     */
    suspend fun register(username: String, email: String, password: String, langs: String): SignupResult = try {
        forgetSignupWatch()
        val form = FormBody.Builder()
            .add("username", username)
            .add("email", email)
            .add("password", password)
            .add("langs", langs)
            .build()
        val a = send(Request.Builder().url(url("register")).post(form).build())
        when (a.code) {
            202 -> {
                val obj = decodeObject(a.body)
                val sentTo = obj.string("email")
                if (obj.string("status") != "verification_sent" || sentTo.isNullOrBlank()) throw SiteApiError.Malformed
                SignupResult.VerificationSent(sentTo)
            }
            in 200 until 400 -> SignupResult.SignedIn(me().getOrThrow())
            else -> throw SiteApiError.registration(a.code, a.body)
        }
    } catch (e: SiteApiError) {
        SignupResult.Failure(e)
    }

    /**
     * GET /register/status with the watch cookie. `verified` consumes the watch and starts the session (the
     * browser-only `next` is ignored). A non-200 is [SiteApiError.Server] with its status (5xx = retryable);
     * a missing or unknown `state` is [SiteApiError.Malformed].
     */
    suspend fun signupStatus(): Result<SignupWatchState> = attempt {
        val a = send(get(url("register", "status")))
        if (a.code != 200) throw SiteApiError.Server(a.code)
        val state = decodeObject(a.body).string("state")
        state?.let(SignupWatchState::fromWire) ?: throw SiteApiError.Malformed
    }

    /** Whether the jar holds an unexpired `ircfiber_signup_watch` cookie for this site's base URL (root path). */
    fun hasSignupWatch(): Boolean = client.cookieJar.loadForRequest(base).any { it.name == SIGNUP_WATCH_COOKIE }

    /**
     * Forgets only the signup watch: saves an already-expired copy (same name, domain and path) of each one sent to
     * the base URL, which the jar treats as a deletion. The session and every other cookie stay.
     */
    fun forgetSignupWatch() {
        val expired = client.cookieJar.loadForRequest(base).filter { it.name == SIGNUP_WATCH_COOKIE }.map { c ->
            Cookie.Builder().name(c.name).value("").expiresAt(0L).path(c.path)
                .apply { if (c.hostOnly) hostOnlyDomain(c.domain) else domain(c.domain) }
                .apply { if (c.secure) secure() }
                .apply { if (c.httpOnly) httpOnly() }
                .build()
        }
        if (expired.isNotEmpty()) client.cookieJar.saveFromResponse(base, expired)
    }

    // MARK: Bootstrap

    /** GET /api/me/bouncer — the account's bouncer settings (decode with [Bouncer.serializer]). */
    suspend fun bouncerSettings(): Result<JsonObject> = attempt {
        val a = send(get(url("api", "me", "bouncer")))
        if (a.code == 401) throw SiteApiError.Unauthorized
        if (a.code != 200) throw SiteApiError.Server(a.code)
        decodeObject(a.body)
    }

    /** POST /api/me/bouncer-token `{}` — this install's own bouncer password ([BouncerToken]). Android never sends `legacyClientId`. */
    suspend fun bouncerToken(): Result<JsonObject> = attempt {
        val a = send(post(url("api", "me", "bouncer-token"), JsonObject(emptyMap())))
        if (a.code == 401) throw SiteApiError.Unauthorized
        if (a.code != 200) throw SiteApiError.failure(a.code, a.body)
        decodeObject(a.body)
    }

    /** GET /api/me/irc-account — SASL credentials ([IrcAccount]). */
    suspend fun ircAccount(): Result<JsonObject> = attempt {
        val a = send(get(url("api", "me", "irc-account")))
        if (a.code == 401) throw SiteApiError.Unauthorized
        if (a.code != 200) throw SiteApiError.Server(a.code)
        decodeObject(a.body)
    }

    /** POST /api/me/irc-account/retry — re-provision NickServ credentials; the answer is ignored. */
    suspend fun retryIrcAccount(): Result<Unit> = attempt {
        send(post(url("api", "me", "irc-account", "retry"), null))
        Unit
    }

    // MARK: Generic JSON calls

    /** GET `path` (`/`-separated, each part one segment). 2xx → body (JsonNull when empty); else `SiteApiError.failure`. */
    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): Result<JsonElement> =
        request("GET", segments(path), query, null)

    suspend fun getJson(segments: List<String>, query: Map<String, String> = emptyMap()): Result<JsonElement> =
        request("GET", segments, query, null)

    suspend fun postJson(path: String, body: JsonElement): Result<JsonElement> =
        request("POST", segments(path), emptyMap(), body)

    suspend fun postJson(segments: List<String>, body: JsonElement): Result<JsonElement> =
        request("POST", segments, emptyMap(), body)

    suspend fun deletePath(path: String): Result<Unit> = deletePath(segments(path))

    /** DELETE with each segment encoded whole — use for user-named parts (`api/me/pins/<network>/<channel>`). */
    suspend fun deletePath(segments: List<String>): Result<Unit> =
        request("DELETE", segments, emptyMap(), null).map { }

    /**
     * Any method (`PATCH` included) on `segments`, with extra request [headers] (`If-Match`): 2xx → the JSON body
     * (JsonNull when empty), else [SiteApiError.failure]; an unparsable 2xx body is [SiteApiError.Malformed].
     */
    suspend fun request(
        method: String,
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): Result<JsonElement> = attempt {
        val rb = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "GET" || method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val builder = Request.Builder().url(url(segments, query)).method(method, rb)
        for ((name, value) in headers) builder.header(name, value)
        val a = send(builder.build())
        if (a.code !in 200 until 300) throw SiteApiError.failure(a.code, a.body)
        if (a.body.isBlank()) JsonNull else runCatching { SiteJson.parseToJsonElement(a.body) }
            .getOrElse { throw SiteApiError.Malformed }
    }

    /** An HTTP answer passed through as is (status and text body), for callers that parse errors themselves. */
    class RawAnswer(val code: Int, val body: String, val headers: okhttp3.Headers = okhttp3.Headers.Builder().build())

    /**
     * One call of the encryption API (`/api` + [encodedPath], already allowlisted and percent-encoded by the
     * caller) whose status and body go back untouched: the shared encryption coordinator parses HTTP errors once
     * itself. Redirects are never followed (this client's policy); a 3xx comes back as its status.
     */
    suspend fun rawApiRequest(
        method: String,
        encodedPath: String,
        encodedQuery: String?,
        body: JsonElement?,
        headers: Map<String, String>,
    ): Result<RawAnswer> = attempt {
        require(encodedPath.startsWith("/")) { "path must be absolute under /api" }
        val url = base.newBuilder().addEncodedPathSegments("api" + encodedPath).encodedQuery(encodedQuery).build()
        val rb = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "GET" || method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val builder = Request.Builder().url(url).method(method, rb)
        for ((name, value) in headers) builder.header(name, value)
        val a = send(builder.build())
        RawAnswer(a.code, a.body, a.headers)
    }

    // MARK: Plumbing

    private class Answer(val code: Int, val body: String, val headers: okhttp3.Headers)

    @kotlinx.serialization.Serializable
    private class ProvidersEnvelope(val providers: List<AuthProvider>)

    private fun get(url: HttpUrl) = Request.Builder().url(url).get().build()

    private fun post(url: HttpUrl, json: JsonElement?): Request =
        Request.Builder().url(url).post(json?.toString()?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody(null)).build()

    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    /** Runs on [Dispatchers.IO]: callers may suspend from the main dispatcher (`viewModelScope`), but reading the
     * response body is blocking socket I/O that must never land on the main thread. */
    private suspend fun send(request: Request): Answer = withContext(Dispatchers.IO) {
        val method = request.method
        val path = request.url.encodedPath
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            log(Log.ERROR, "$method $path failed: ${e.javaClass.simpleName}")
            throw e
        }
        val body = response.use { it.body?.string().orEmpty() }
        when {
            response.code >= 500 -> log(Log.ERROR, "$method $path → ${response.code}")
            response.code >= 400 -> log(Log.WARN, "$method $path → ${response.code}")
        }
        Answer(response.code, body, response.headers)
    }

    private fun <T> decode(strategy: DeserializationStrategy<T>, body: String): T = try {
        SiteJson.decodeFromString(strategy, body)
    } catch (_: SerializationException) {
        throw SiteApiError.Malformed
    } catch (_: IllegalArgumentException) {
        throw SiteApiError.Malformed
    }

    private fun decodeObject(body: String): JsonObject =
        runCatching { SiteJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw SiteApiError.Malformed

    private fun log(priority: Int, text: String) {
        runCatching { Log.println(priority, TAG, text) }
    }

    companion object {
        const val TAG = "FiberIRC.api"
        /** The cookie POST /register's 202 sets; GET /register/status reads (and on `verified`/`gone` clears) it. */
        const val SIGNUP_WATCH_COOKIE = "ircfiber_signup_watch"
        private val JSON = "application/json".toMediaType()
    }
}

/** Decodes a JSON answer into a typed model; [SiteApiError.Malformed] when it doesn't fit. */
fun <T> Result<JsonElement>.decodeAs(strategy: DeserializationStrategy<T>): Result<T> = mapCatching {
    try {
        SiteJson.decodeFromJsonElement(strategy, it)
    } catch (_: SerializationException) {
        throw SiteApiError.Malformed
    } catch (_: IllegalArgumentException) {
        throw SiteApiError.Malformed
    }
}

/** A string member of a JSON object; null when absent or not a string. */
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** `runCatching` that lets coroutine cancellation through. */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** Suspends until the call answers; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
