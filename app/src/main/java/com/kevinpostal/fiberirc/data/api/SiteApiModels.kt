package com.kevinpostal.fiberirc.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The one JSON configuration for ircfiber.com answers: fields the app doesn't use are ignored, not errors. */
val SiteJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * GET /api/me (ports `SiteAPI.Me`). Every prefs field is absent on auth-exchange answers, so all are nullable.
 * Server fields the app never reads (`membersCollapsed`, `inactiveCollapsed`, `bncPlaybackLines`,
 * `desktopNotifications`, `autoDismissNotifs`, `prefVersion`) are skipped by [SiteJson].
 */
@Serializable
data class Me(
    val id: String,
    val username: String,
    val email: String = "",
    val ignores: List<String>? = null,
    /** `<networkId>:<buffer>` keys in the web's drag order. */
    val pinnedChannels: List<String>? = null,
    val archivedChannels: List<String>? = null,
    /** Network id → collapsed (only `true` entries are meaningful). */
    val collapsed: Map<String, Boolean>? = null,
    val networkOrder: List<String>? = null,
    /** Lenient like `BufferPrefsPayload`: non-bool values, non-object entries and empty maps drop out. */
    @Serializable(with = LenientBufferPrefsSerializer::class)
    val bufferPrefs: Map<String, Map<String, Boolean>>? = null,
    val showMemberPrefixes: Boolean? = null,
    val notificationSound: Boolean? = null,
    val muteAll: Boolean? = null,
    /** This install's display prefs (its `X-IRCFiber-Device` profile); decoded by `DisplayPrefs.fromJson`. */
    val displayPrefs: JsonObject? = null,
    val highlightWords: List<String>? = null,
)

/** `prefs.bufferPrefs`: a value that isn't a boolean, or an entry that isn't an object, decodes as absent. */
object LenientBufferPrefsSerializer : KSerializer<Map<String, Map<String, Boolean>>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Map<String, Map<String, Boolean>> {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return emptyMap()
        return lenientBufferPrefs(element)
    }

    override fun serialize(encoder: Encoder, value: Map<String, Map<String, Boolean>>) {
        val json = JsonObject(value.mapValues { (_, prefs) -> JsonObject(prefs.mapValues { JsonPrimitive(it.value) }) })
        (encoder as JsonEncoder).encodeJsonElement(json)
    }
}

fun lenientBufferPrefs(element: JsonElement): Map<String, Map<String, Boolean>> {
    val root = element as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, Map<String, Boolean>>()
    for ((key, entry) in root) {
        val obj = entry as? JsonObject ?: continue
        val prefs = LinkedHashMap<String, Boolean>()
        for ((name, v) in obj) {
            val p = v as? JsonPrimitive ?: continue
            if (p.isString) continue
            p.booleanOrNull?.let { prefs[name] = it }
        }
        if (prefs.isNotEmpty()) out[key] = prefs
    }
    return out
}

/** One social sign-in provider configured on the server (`GET /api/auth/providers`). */
@Serializable
data class AuthProvider(val name: String, val label: String)

/** POST /api/app/session answer: the account the one-time code signed in. */
@Serializable
data class AppSessionResult(val id: String, val username: String, val email: String = "") {
    /** As an auth-response `Me`: no prefs, so stores keep their cached values. */
    fun toMe(): Me = Me(id = id, username = username, email = email)
}

/** GET /api/me/bouncer — the account's bouncer settings (ports `SiteAPI.Bouncer`). */
@Serializable
data class Bouncer(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String,
    /** 48-hex account-wide token; null until minted. */
    val password: String? = null,
    val requireTls: Boolean = false,
    val maxClients: Int = 0,
    val maxClientsCeiling: Int = 0,
    val networks: List<BouncerNetwork> = emptyList(),
    val devices: List<BouncerDevice>? = null,
)

@Serializable
data class BouncerNetwork(
    val id: String,
    val slug: String,
    val name: String,
    val host: String = "",
    val nick: String = "",
    val managed: Boolean = false,
    val connected: Boolean = false,
)

@Serializable
data class BouncerDevice(
    val id: String,
    val clientId: String,
    val anonymous: Boolean = false,
    val online: Boolean = false,
    val networks: List<BouncerCursor> = emptyList(),
    /** "app" or "external"; null on a pre-migration row (reads as external). */
    val kind: String? = null,
)

@Serializable
data class BouncerCursor(val networkId: String, val networkName: String, val cursor: Long)

/** POST /api/me/bouncer-token — this install's own bouncer password. */
@Serializable
data class BouncerToken(val clientId: String, val token: String, val createdAt: Long = 0)

/** GET /api/me/irc-account — SASL PLAIN credentials provisioned for the account. */
@Serializable
data class IrcAccount(
    val status: IrcAccountStatus,
    val reason: String? = null,
    val account: String? = null,
    val password: String? = null,
    val host: String? = null,
    val port: Int? = null,
    /** Name of the account's IRC Fiber network ("IRC Fiber" when it has none). */
    val network: String? = null,
)

@Serializable
enum class IrcAccountStatus {
    @SerialName("ready") READY,
    @SerialName("pending") PENDING,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("none") NONE;

    val wire: String get() = name.lowercase()
}
