package com.kevinpostal.fiberirc.data.e2ee

import android.app.Application
import android.content.MutableContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.kevinpostal.fiberirc.data.diagnostics.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom
import java.util.Base64

/**
 * The account encryption's native passkeys: Credential Manager registration/assertion with the WebAuthn `prf`
 * extension, returning the authenticator's actual PRF `first` output (32 bytes) for the runtime's backup/unlock HKDF.
 *
 * RP ID is the configured API host (production `ircfiber.com`, the web backup's RP), so the same synced passkey
 * works on the web and here; the app's signing certificate must be listed in that host's
 * `/.well-known/assetlinks.json` (`delegate_permission/common.get_login_creds`). The PRF input is the raw UTF-8
 * `ircfiber-e2ee-prf`, exactly the web's `eval.first`; the provider applies WebAuthn's PRF salt hashing, as the
 * browser does, so outputs match across platforms. Native passkeys need Android 9 (API 28); [supported] is false
 * below that and for non-HTTPS/IP API hosts, and the service then reports `passkey_unsupported` (recovery key and
 * device linking stay available). A provider that doesn't return PRF output is unsupported — nothing is ever
 * derived from the signature, credential id or the user-verification result.
 *
 * Process-wide ([com.kevinpostal.fiberirc.data.Deps]); the root activity [attach]es itself so the system sheets
 * have an activity to present over. In-flight calls hold a [MutableContextWrapper] that follows activity
 * recreation and drops to the application context on destroy, so a pending request never retains a dead activity.
 */
class E2eePasskeys(private val app: Application, apiBaseUrl: String) : E2eePasskeyProvider {
    private val rpId: String? = E2eePasskeyProtocol.rpIdFor(apiBaseUrl)
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(app) }
    private val random = SecureRandom()
    private val busy = Mutex()

    // Main thread.
    private var activity: ComponentActivity? = null
    private val wrappers = ArrayList<MutableContextWrapper>()

    /** Whether this device can attempt native PRF passkeys at all (the provider decides per request). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && rpId != null

    /** The activity passkey sheets present over (the root activity, from `onCreate`); released on its destroy. */
    @MainThread
    fun attach(activity: ComponentActivity) {
        this.activity = activity
        wrappers.forEach { it.setBaseContext(activity) }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                if (this@E2eePasskeys.activity === activity) {
                    this@E2eePasskeys.activity = null
                    wrappers.forEach { it.setBaseContext(app) }
                }
            }
        })
    }

    override suspend fun prf(mode: String, account: String, credentialId: String?): E2eePrfResult =
        withContext(Dispatchers.Main.immediate) {
            val rp = rpId
            if (rp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw E2eePasskeyProtocol.unsupported("passkeys need Android 9 and an HTTPS site")
            // One system sheet at a time; a second request while one is open is refused, not queued behind it.
            if (!busy.tryLock()) throw E2eeBridgeException(E2eePasskeyProtocol.BUSY, "A passkey request is already open")
            try {
                when (mode) {
                    "create" -> create(rp, account)
                    "get" -> get(rp, credentialId?.let(E2eePasskeyProtocol::decodeCredentialId))
                    else -> throw E2eeBridgeException("bad_request", "mode must be create or get")
                }
            } finally {
                busy.unlock()
            }
        }

    private suspend fun create(rp: String, account: String): E2eePrfResult {
        val challenge = randomBytes(32)
        val options = E2eePasskeyProtocol.creationOptions(rp, account, challenge, userId = randomBytes(16))
        val response = presenting { context -> credentialManager.createCredential(context, CreatePublicKeyCredentialRequest(options)) }
        val json = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw E2eePasskeyProtocol.failed("the provider returned no passkey")
        return when (val registration = E2eePasskeyProtocol.registration(json, challenge)) {
            is E2eePasskeyProtocol.Registration.Output -> registration.result
            // PRF enabled at creation but evaluated only on assertion (the WebAuthn-allowed split): assert now
            // against exactly this credential.
            is E2eePasskeyProtocol.Registration.NeedsAssertion -> get(rp, registration.credentialId)
        }
    }

    private suspend fun get(rp: String, credentialId: ByteArray?): E2eePrfResult {
        val challenge = randomBytes(32)
        val options = E2eePasskeyProtocol.requestOptions(rp, challenge, credentialId)
        val response = presenting { context ->
            credentialManager.getCredential(context, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options))))
        }
        val json = (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw E2eePasskeyProtocol.failed("the provider returned no passkey assertion")
        return E2eePasskeyProtocol.assertion(json, challenge, credentialId)
    }

    /** Runs one Credential Manager call over the attached activity, mapping its failures to bridge errors. */
    private suspend fun <T> presenting(call: suspend (MutableContextWrapper) -> T): T {
        val host = activity?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            ?: throw E2eeBridgeException(E2eePasskeyProtocol.NO_UI, "Open IRC Fiber to use your passkey")
        val wrapper = MutableContextWrapper(host)
        wrappers.add(wrapper)
        try {
            return call(wrapper)
        } catch (e: CreateCredentialException) {
            Log.w(TAG, "passkey registration failed: ${e.type}")
            throw E2eePasskeyProtocol.error(e)
        } catch (e: GetCredentialException) {
            Log.w(TAG, "passkey assertion failed: ${e.type}")
            throw E2eePasskeyProtocol.error(e)
        } finally {
            wrappers.remove(wrapper)
            wrapper.setBaseContext(app)
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private companion object {
        const val TAG = "E2eePasskeys"
    }
}

/**
 * The WebAuthn JSON both directions of [E2eePasskeys] (pure, JVM-testable): request options mirroring the web's
 * `navigator.credentials` calls, and strict parsing of the provider's responses. Binary fields are base64url in
 * WebAuthn JSON; credential ids cross the runtime boundary as standard padded base64, like the web's backups.
 */
internal object E2eePasskeyProtocol {
    /** The PRF evaluation input, raw UTF-8 — the web's `eval.first` (the provider applies the WebAuthn salt hash). */
    val PRF_INPUT: ByteArray = "ircfiber-e2ee-prf".toByteArray(Charsets.UTF_8)
    const val PRF_BYTES = 32

    const val CANCELLED = "passkey_cancelled"
    const val UNSUPPORTED = "passkey_unsupported"
    const val NO_CREDENTIAL = "passkey_no_credential"
    const val NOT_ASSOCIATED = "passkey_not_associated"
    const val INTERRUPTED = "passkey_interrupted"
    const val MISMATCH = "passkey_mismatch"
    const val BUSY = "passkey_busy"
    const val NO_UI = "passkey_no_ui"
    const val FAILED = "passkey_failed"

    private const val MAX_CREDENTIAL_ID_BYTES = 1023 // WebAuthn's credential id bound

    private val json = Json { ignoreUnknownKeys = true }
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    sealed interface Registration {
        /** The provider evaluated PRF at creation. */
        class Output(val result: E2eePrfResult) : Registration

        /** PRF is enabled on [credentialId] but was not evaluated at creation: assert to get the output. */
        class NeedsAssertion(val credentialId: ByteArray) : Registration
    }

    /** The RP ID for the API base URL: its host, only for HTTPS domain names (passkeys need a verifiable domain). */
    fun rpIdFor(apiBaseUrl: String): String? {
        val url = apiBaseUrl.toHttpUrlOrNull() ?: return null
        val host = url.host
        if (!url.isHttps || host == "localhost" || host.endsWith(".localhost") || '.' !in host) return null
        if (':' in host || host.all { it.isDigit() || it == '.' }) return null // IP literal
        return host
    }

    fun creationOptions(rpId: String, account: String, challenge: ByteArray, userId: ByteArray): String =
        buildJsonObject {
            put("challenge", b64url.encodeToString(challenge))
            putJsonObject("rp") {
                put("id", rpId)
                put("name", "ircfiber")
            }
            // A random user handle, as on the web: a fixed per-account handle would make the provider replace the
            // account's existing passkey, orphaning the backup sealed with it.
            putJsonObject("user") {
                put("id", b64url.encodeToString(userId))
                put("name", account)
                put("displayName", "$account e2ee backup")
            }
            putJsonArray("pubKeyCredParams") {
                for (alg in listOf(-7, -257)) addJsonObject {
                    put("type", "public-key")
                    put("alg", alg)
                }
            }
            put("attestation", "none")
            putJsonArray("excludeCredentials") {}
            putJsonObject("authenticatorSelection") {
                put("residentKey", "required")
                put("requireResidentKey", true)
                put("userVerification", "required")
            }
            prfExtension()
        }.toString()

    fun requestOptions(rpId: String, challenge: ByteArray, credentialId: ByteArray?): String =
        buildJsonObject {
            put("challenge", b64url.encodeToString(challenge))
            put("rpId", rpId)
            putJsonArray("allowCredentials") {
                if (credentialId != null) addJsonObject {
                    put("type", "public-key")
                    put("id", b64url.encodeToString(credentialId))
                }
            }
            put("userVerification", "required")
            prfExtension()
        }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.prfExtension() {
        putJsonObject("extensions") {
            putJsonObject("prf") {
                putJsonObject("eval") { put("first", b64url.encodeToString(PRF_INPUT)) }
            }
        }
    }

    /** A `registrationResponseJson`: this request's credential, with PRF output or PRF enabled for assertion. */
    fun registration(responseJson: String, challenge: ByteArray): Registration {
        val o = parse(responseJson)
        val id = credentialId(o)
        checkClientData(o, "webauthn.create", challenge)
        val prf = extensionResults(o)
        prfFirst(prf)?.let { return Registration.Output(E2eePrfResult(standard(id), it)) }
        if ((prf?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true) return Registration.NeedsAssertion(id)
        throw unsupported("this passkey provider created a passkey without the PRF extension")
    }

    /** An `authenticationResponseJson`: this request's assertion, by [expectedCredential] when one was named. */
    fun assertion(responseJson: String, challenge: ByteArray, expectedCredential: ByteArray?): E2eePrfResult {
        val o = parse(responseJson)
        val id = credentialId(o)
        if (expectedCredential != null && !id.contentEquals(expectedCredential)) {
            throw E2eeBridgeException(MISMATCH, "the provider answered with a different passkey")
        }
        checkClientData(o, "webauthn.get", challenge)
        val first = prfFirst(extensionResults(o)) ?: throw unsupported("this passkey returned no PRF output")
        return E2eePrfResult(standard(id), first)
    }

    /** A runtime credential id (standard base64; base64url tolerated) as bytes. */
    fun decodeCredentialId(id: String): ByteArray {
        val bytes = decodeAny(id) ?: throw E2eeBridgeException("bad_request", "credentialId must be base64")
        if (bytes.isEmpty() || bytes.size > MAX_CREDENTIAL_ID_BYTES) throw E2eeBridgeException("bad_request", "credentialId has a bad length")
        return bytes
    }

    /** Credential Manager failures as bridge errors; cancellation stays distinct so callers can stay quiet. */
    fun error(e: Exception): E2eeBridgeException = when (e) {
        is CreateCredentialCancellationException, is GetCredentialCancellationException ->
            E2eeBridgeException(CANCELLED, "The passkey request was cancelled")
        is CreatePublicKeyCredentialDomException -> dom(e.domError, e.errorMessage)
        is GetPublicKeyCredentialDomException -> dom(e.domError, e.errorMessage)
        is NoCredentialException -> E2eeBridgeException(NO_CREDENTIAL, "No IRC Fiber passkey for this account is available on this device")
        is CreateCredentialProviderConfigurationException, is GetCredentialProviderConfigurationException,
        is CreateCredentialUnsupportedException, is GetCredentialUnsupportedException,
        is CreateCredentialNoCreateOptionException,
        -> unsupported("no passkey provider is available")
        is CreateCredentialInterruptedException, is GetCredentialInterruptedException ->
            E2eeBridgeException(INTERRUPTED, "The passkey request was interrupted — try again")
        is CreateCredentialException -> failed("${e.type}: ${e.errorMessage ?: ""}".trimEnd(' ', ':'))
        is GetCredentialException -> failed("${e.type}: ${e.errorMessage ?: ""}".trimEnd(' ', ':'))
        else -> failed(e.message ?: e.javaClass.simpleName)
    }

    private fun dom(error: DomError, message: CharSequence?): E2eeBridgeException = when (error) {
        // WebAuthn reports user dismissal and timeouts alike as NotAllowedError.
        is NotAllowedError -> E2eeBridgeException(CANCELLED, "The passkey request was cancelled or timed out")
        // The RP ID isn't associated with this app's signing certificate (Digital Asset Links).
        is SecurityError -> E2eeBridgeException(NOT_ASSOCIATED, "This build of IRC Fiber isn't verified to use passkeys for this site")
        is NotSupportedError -> unsupported("the passkey provider doesn't support this request")
        else -> failed("${error.type}: ${message ?: ""}".trimEnd(' ', ':'))
    }

    fun unsupported(detail: String) = E2eeBridgeException(UNSUPPORTED, detail)

    fun failed(detail: String) = E2eeBridgeException(FAILED, "Passkey failed: $detail")

    private fun parse(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw failed("malformed response")

    private fun credentialId(o: JsonObject): ByteArray {
        val raw = o.string("rawId") ?: o.string("id") ?: throw failed("response has no credential id")
        val id = decodeAny(raw)?.takeIf { it.isNotEmpty() && it.size <= MAX_CREDENTIAL_ID_BYTES } ?: throw failed("bad credential id")
        o.string("id")?.let { other -> if (!decodeAny(other).contentEquals(id)) throw failed("id and rawId disagree") }
        if (o.string("type")?.let { it != "public-key" } == true) throw failed("not a public-key credential")
        return id
    }

    /** The signed client data must be for this ceremony and this request's challenge. */
    private fun checkClientData(o: JsonObject, type: String, challenge: ByteArray) {
        val encoded = (o["response"] as? JsonObject)?.string("clientDataJSON") ?: throw failed("response has no client data")
        val clientData = decodeAny(encoded)?.let { runCatching { json.parseToJsonElement(String(it, Charsets.UTF_8)) as? JsonObject }.getOrNull() }
            ?: throw failed("malformed client data")
        if (clientData.string("type") != type) throw failed("client data is for another ceremony")
        val echoed = clientData.string("challenge")?.let(::decodeAny)
        if (echoed == null || !echoed.contentEquals(challenge)) throw failed("the response doesn't answer this request")
    }

    private fun extensionResults(o: JsonObject): JsonObject? =
        (o["clientExtensionResults"] as? JsonObject)?.get("prf") as? JsonObject

    /** `prf.results.first` — exactly 32 bytes, or null when absent. Anything else is not usable PRF output. */
    private fun prfFirst(prf: JsonObject?): ByteArray? {
        val encoded = (prf?.get("results") as? JsonObject)?.string("first") ?: return null
        val first = decodeAny(encoded) ?: throw unsupported("malformed PRF output")
        if (first.size != PRF_BYTES) throw unsupported("PRF output is ${first.size} bytes, not $PRF_BYTES")
        return first
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun standard(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Base64url or standard base64, padded or not; null when neither. */
    private fun decodeAny(s: String): ByteArray? {
        val url = s.trimEnd('=').replace('+', '-').replace('/', '_')
        return runCatching { Base64.getUrlDecoder().decode(url) }.getOrNull()
    }
}
