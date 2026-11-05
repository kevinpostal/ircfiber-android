package com.kevinpostal.fiberirc.data.e2ee

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The native passkey PRF wire contract: requests carry the web's exact PRF input and require user verification;
 * only `clientExtensionResults.prf.results.first` (32 bytes) of a response answering this request's challenge is
 * PRF output, never the signature or anything else; credential ids cross to the runtime as standard base64.
 */
class E2eePasskeyProtocolTest {
    private val url = Base64.getUrlEncoder().withoutPadding()
    private val challenge = ByteArray(32) { (it * 7).toByte() }
    // Bytes whose standard base64 contains '+' and '/', so the url/standard conversion is exercised.
    private val credential = byteArrayOf(-5, -17, -1, 0x10, 0x20, 0x3e, 0x3f, -2, -3)
    private val prfOutput = ByteArray(32) { (255 - it).toByte() }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun clientData(type: String, challenge: ByteArray = this.challenge) =
        url.encodeToString("""{"type":"$type","challenge":"${url.encodeToString(challenge)}","origin":"android:apk-key-hash:x"}""".toByteArray())

    private fun response(
        type: String,
        prf: String?,
        id: ByteArray = credential,
        challenge: ByteArray = this.challenge,
        extra: String = "",
    ) = """
        {"id":"${url.encodeToString(id)}","rawId":"${url.encodeToString(id)}","type":"public-key",
         "response":{"clientDataJSON":"${clientData(type, challenge)}"$extra},
         "clientExtensionResults":{${prf?.let { "\"prf\":$it" } ?: ""}}}
    """.trimIndent()

    private fun results(bytes: ByteArray) = """{"enabled":true,"results":{"first":"${url.encodeToString(bytes)}"}}"""

    private fun assertCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: E2eeBridgeException) {
            assertEquals(e.message, code, e.code)
        }
    }

    @Test
    fun requestsCarryTheWebPrfInputAndRequireUserVerification() {
        val create = obj(E2eePasskeyProtocol.creationOptions("ircfiber.com", "alice", challenge, ByteArray(16) { 1 }))
        assertEquals("ircfiber.com", create["rp"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("alice", create["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(url.encodeToString(challenge), create["challenge"]!!.jsonPrimitive.content)
        val selection = create["authenticatorSelection"]!!.jsonObject
        assertEquals("required", selection["residentKey"]!!.jsonPrimitive.content)
        assertEquals("required", selection["userVerification"]!!.jsonPrimitive.content)
        // Raw UTF-8 "ircfiber-e2ee-prf" (base64url), not pre-hashed: the provider applies WebAuthn's PRF salt hash.
        val prfFirst = { o: JsonObject -> o["extensions"]!!.jsonObject["prf"]!!.jsonObject["eval"]!!.jsonObject["first"]!!.jsonPrimitive.content }
        assertEquals("aXJjZmliZXItZTJlZS1wcmY", prfFirst(create))
        assertArrayEquals("ircfiber-e2ee-prf".toByteArray(), Base64.getUrlDecoder().decode(prfFirst(create)))

        val runtimeId = Base64.getEncoder().encodeToString(credential)
        assertTrue(runtimeId.contains('+') || runtimeId.contains('/'))
        val get = obj(E2eePasskeyProtocol.requestOptions("ircfiber.com", challenge, E2eePasskeyProtocol.decodeCredentialId(runtimeId)))
        assertEquals("ircfiber.com", get["rpId"]!!.jsonPrimitive.content)
        assertEquals("required", get["userVerification"]!!.jsonPrimitive.content)
        val allowed = get["allowCredentials"]!!.jsonArray.single().jsonObject
        assertEquals(url.encodeToString(credential), allowed["id"]!!.jsonPrimitive.content)
        assertEquals("aXJjZmliZXItZTJlZS1wcmY", prfFirst(get))
        // Without a stored credential the user picks a discoverable passkey.
        val any = obj(E2eePasskeyProtocol.requestOptions("ircfiber.com", challenge, null))
        assertEquals(JsonArray(emptyList()), any["allowCredentials"])
    }

    @Test
    fun registrationUsesOnlyActualPrfOutput() {
        val out = E2eePasskeyProtocol.registration(response("webauthn.create", results(prfOutput)), challenge)
        out as E2eePasskeyProtocol.Registration.Output
        assertArrayEquals(prfOutput, out.result.first)
        // Standard padded base64 at the runtime boundary (the web backup's credentialId format).
        assertEquals(Base64.getEncoder().encodeToString(credential), out.result.credentialId)

        // PRF enabled but not evaluated at creation: an assertion against exactly this credential follows.
        val later = E2eePasskeyProtocol.registration(response("webauthn.create", """{"enabled":true}"""), challenge)
        later as E2eePasskeyProtocol.Registration.NeedsAssertion
        assertArrayEquals(credential, later.credentialId)

        // No PRF (absent or disabled): unsupported, even though a credential and attestation exist.
        assertCode("passkey_unsupported") {
            E2eePasskeyProtocol.registration(response("webauthn.create", null, extra = ""","attestationObject":"o2NmbXRkbm9uZQ""""), challenge)
        }
        assertCode("passkey_unsupported") { E2eePasskeyProtocol.registration(response("webauthn.create", """{"enabled":false}"""), challenge) }
        assertCode("passkey_unsupported") { E2eePasskeyProtocol.registration(response("webauthn.create", results(ByteArray(16))), challenge) }
    }

    @Test
    fun responsesMustAnswerThisRequest() {
        val other = ByteArray(32) { 9 }
        assertCode("passkey_failed") {
            E2eePasskeyProtocol.registration(response("webauthn.create", results(prfOutput), challenge = other), challenge)
        }
        assertCode("passkey_failed") { E2eePasskeyProtocol.registration(response("webauthn.get", results(prfOutput)), challenge) }
        assertCode("passkey_failed") { E2eePasskeyProtocol.assertion(response("webauthn.create", results(prfOutput)), challenge, null) }
        assertCode("passkey_failed") { E2eePasskeyProtocol.assertion("not json", challenge, null) }
    }

    @Test
    fun assertionIsBoundToTheRequestedCredentialAndNeedsPrf() {
        val got = E2eePasskeyProtocol.assertion(response("webauthn.get", results(prfOutput)), challenge, credential)
        assertArrayEquals(prfOutput, got.first)
        assertEquals(Base64.getEncoder().encodeToString(credential), got.credentialId)

        // Another passkey than the backup's/enrolled one is refused before its output is used.
        assertCode("passkey_mismatch") {
            E2eePasskeyProtocol.assertion(response("webauthn.get", results(prfOutput), id = byteArrayOf(1, 2, 3)), challenge, credential)
        }
        // A signed assertion without PRF output is not a key, whatever else it carries.
        val signedOnly = response(
            "webauthn.get", null,
            extra = ""","authenticatorData":"SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAQ","signature":"${url.encodeToString(ByteArray(32) { 3 })}"""",
        )
        assertCode("passkey_unsupported") { E2eePasskeyProtocol.assertion(signedOnly, challenge, credential) }
        assertCode("passkey_unsupported") { E2eePasskeyProtocol.assertion(response("webauthn.get", results(ByteArray(64))), challenge, credential) }
    }

    @Test
    fun rpIdIsTheHttpsApiDomain() {
        assertEquals("ircfiber.com", E2eePasskeyProtocol.rpIdFor("https://ircfiber.com"))
        assertEquals("staging.ircfiber.com", E2eePasskeyProtocol.rpIdFor("https://staging.ircfiber.com/"))
        assertNull(E2eePasskeyProtocol.rpIdFor("http://ircfiber.com"))
        assertNull(E2eePasskeyProtocol.rpIdFor("https://10.0.2.2:8090"))
        assertNull(E2eePasskeyProtocol.rpIdFor("https://localhost:8090"))
        assertNull(E2eePasskeyProtocol.rpIdFor("https://[::1]:8090"))
        assertNull(E2eePasskeyProtocol.rpIdFor("not a url"))
    }

    @Test
    fun credentialManagerFailuresKeepCancellationDistinct() {
        fun code(e: Exception) = E2eePasskeyProtocol.error(e).code
        assertEquals("passkey_cancelled", code(CreateCredentialCancellationException()))
        assertEquals("passkey_cancelled", code(GetCredentialCancellationException()))
        assertEquals("passkey_cancelled", code(GetPublicKeyCredentialDomException(NotAllowedError(), "dismissed")))
        assertEquals("passkey_not_associated", code(CreatePublicKeyCredentialDomException(SecurityError(), "rp id")))
        assertEquals("passkey_no_credential", code(NoCredentialException()))
        assertEquals("passkey_unsupported", code(GetCredentialProviderConfigurationException()))
        assertEquals("passkey_unsupported", code(CreateCredentialNoCreateOptionException()))

        assertTrue(E2eeTexts.isPasskeyCancellation(E2eePasskeyProtocol.error(CreateCredentialCancellationException())))
        assertTrue(!E2eeTexts.isPasskeyCancellation(E2eePasskeyProtocol.error(NoCredentialException())))
        assertEquals(E2eeTexts.PRF_UNSUPPORTED, E2eeTexts.error(E2eePasskeyProtocol.error(GetCredentialProviderConfigurationException())))
    }

    @Test
    fun runtimeCredentialIdsAreValidated() {
        assertArrayEquals(credential, E2eePasskeyProtocol.decodeCredentialId(Base64.getEncoder().encodeToString(credential)))
        assertArrayEquals(credential, E2eePasskeyProtocol.decodeCredentialId(url.encodeToString(credential)))
        assertCode("bad_request") { E2eePasskeyProtocol.decodeCredentialId("") }
        assertCode("bad_request") { E2eePasskeyProtocol.decodeCredentialId("***") }
    }
}
