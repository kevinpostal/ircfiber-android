package com.kevinpostal.fiberirc.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
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
    /** Lenient like `BufferPrefsPayload`: values that are neither boolean nor string, non-object entries and empty
     *  maps drop out. */
    @Serializable(with = LenientBufferPrefsSerializer::class)
    val bufferPrefs: Map<String, Map<String, BufferPrefValue>>? = null,
    val showMemberPrefixes: Boolean? = null,
    val notificationSound: Boolean? = null,
    val muteAll: Boolean? = null,
    /** This install's display prefs (its `X-IRCFiber-Device` profile); decoded by `DisplayPrefs.fromJson`. */
    val displayPrefs: JsonObject? = null,
    val highlightWords: List<String>? = null,
    /** Notification Policy v2; null from a server without it (old servers keep the v1 keys above). Lenient: a
     * malformed policy decodes as null rather than failing `/api/me`. */
    @Serializable(with = LenientPolicyViewSerializer::class)
    val notificationPolicy: NotificationPolicyView? = null,
)

/**
 * One `prefs.bufferPrefs` value: the web stores booleans (`mute`, `typing`, `e2ee`, …) and strings (`e2eeMode`).
 * Both survive a round trip, so a key this app doesn't know is never dropped from the account.
 */
sealed interface BufferPrefValue {
    data class Bool(val value: Boolean) : BufferPrefValue

    data class Text(val value: String) : BufferPrefValue

    /** The boolean, or null for a string value. */
    val bool: Boolean? get() = (this as? Bool)?.value

    /** The string, or null for a boolean value. */
    val text: String? get() = (this as? Text)?.value

    fun toJson(): JsonPrimitive = when (this) {
        is Bool -> JsonPrimitive(value)
        is Text -> JsonPrimitive(value)
    }

    companion object {
        /** A JSON boolean or string; null for every other JSON type (the existing lossy handling). */
        fun of(element: JsonElement): BufferPrefValue? {
            val p = element as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            if (p.isString) return Text(p.content)
            return p.booleanOrNull?.let(::Bool)
        }
    }
}

/** `prefs.bufferPrefs`: a value that is neither a boolean nor a string, or an entry that isn't an object, decodes as
 *  absent. The on-disk cache uses the same shape (an older boolean-only cache decodes unchanged). */
object LenientBufferPrefsSerializer : KSerializer<Map<String, Map<String, BufferPrefValue>>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Map<String, Map<String, BufferPrefValue>> {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return emptyMap()
        return lenientBufferPrefs(element)
    }

    override fun serialize(encoder: Encoder, value: Map<String, Map<String, BufferPrefValue>>) {
        val json = JsonObject(value.mapValues { (_, prefs) -> JsonObject(prefs.mapValues { it.value.toJson() }) })
        (encoder as JsonEncoder).encodeJsonElement(json)
    }
}

fun lenientBufferPrefs(element: JsonElement): Map<String, Map<String, BufferPrefValue>> {
    val root = element as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, Map<String, BufferPrefValue>>()
    for ((key, entry) in root) {
        val obj = entry as? JsonObject ?: continue
        val prefs = LinkedHashMap<String, BufferPrefValue>()
        for ((name, v) in obj) BufferPrefValue.of(v)?.let { prefs[name] = it }
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

// MARK: Notification Policy v2 (`NotificationPolicyView`: GET /api/me/notification-policy, `/api/me`
// `notificationPolicy`, every policy write's answer). Every field defaults so [SiteJson] decodes whatever the server
// adds or drops later.

/** Notification classes in contract order (wire names). */
val NOTIFY_CLASSES = listOf("dm", "mention", "all", "invite", "notice", "wallops")

/** The client key of every browser (`clients["web"]`). */
const val POLICY_CLIENT_WEB = "web"

/** One class's delivery cells (`matrix.<cls>`); presets carry no `email`. */
@Serializable
data class PolicyCells(
    val desktop: Boolean = false,
    val sound: Boolean = false,
    val mobile: Boolean = false,
    val email: Boolean = false,
) {
    fun get(transport: String): Boolean? = when (transport) {
        "desktop" -> desktop
        "sound" -> sound
        "mobile" -> mobile
        "email" -> email
        else -> null
    }
}

/** A quiet-hours window: days 0 = Monday … 6 = Sunday, `HH:MM` start and end (end may be `24:00`). */
@Serializable
data class PolicyQuietWindow(val days: List<Int> = emptyList(), val start: String = "", val end: String = "")

@Serializable
data class PolicyBreakthrough(
    val vip: List<String> = emptyList(),
    val repeatSec: Int = 180,
    val urgentWords: Boolean = true,
)

@Serializable
data class PolicyQuiet(
    val enabled: Boolean = false,
    val tz: String = "",
    /** `account` or `device` (an app install's own timezone, sent as `X-IRCFiber-Device-TZ`). */
    val tzMode: String = "account",
    val windows: List<PolicyQuietWindow> = emptyList(),
    val breakthrough: PolicyBreakthrough = PolicyBreakthrough(),
    val summary: Boolean = true,
)

@Serializable
data class PolicyHighlightWord(val w: String = "", val urgent: Boolean = false)

@Serializable
data class PolicyHighlight(
    val words: List<PolicyHighlightWord> = emptyList(),
    val wholeWord: Boolean = true,
    val caseSensitive: Boolean = false,
)

@Serializable
data class PolicyPresentation(val preview: String = "full", val coalesceSec: Int = 30, val autoDismiss: Boolean = true)

@Serializable
data class PolicyEmail(val mode: String = "delay15", val dailyHour: Int = 8)

/** A client's sparse overrides: `"<cls>.<transport>"` cells and an optional own snooze (`0` overrides the account's). */
@Serializable
data class PolicyClientOverride(val cells: Map<String, Boolean> = emptyMap(), val snoozeUntil: Long? = null)

/** The stored policy; `clients` keys are `"web"` or an install's `sessionRef`. */
@Serializable
data class NotificationPolicy(
    val v: Int = 2,
    val rev: Long = 1,
    val matrix: Map<String, PolicyCells> = emptyMap(),
    val snoozeUntil: Long = 0,
    val quiet: PolicyQuiet = PolicyQuiet(),
    val highlight: PolicyHighlight = PolicyHighlight(),
    val presentation: PolicyPresentation = PolicyPresentation(),
    val email: PolicyEmail = PolicyEmail(),
    /** Network id → `all` | `muted`. */
    val networks: Map<String, String> = emptyMap(),
    val clients: Map<String, PolicyClientOverride> = emptyMap(),
    val migratedAt: Long = 0,
    val bannerDismissed: Boolean = false,
) {
    /** The account cell (`matrix[cls][transport]`), false when absent. */
    fun accountCell(cls: String, transport: String): Boolean = matrix[cls]?.get(transport) ?: false

    /** Contract `resolveCell`: the client's override when it has one, else the account cell. */
    fun resolveCell(target: String, cls: String, transport: String): Boolean =
        clients[target]?.cells?.get("$cls.$transport") ?: accountCell(cls, transport)

    /** Whether [target] overrides `cls.transport`. */
    fun overrides(target: String, cls: String, transport: String): Boolean =
        clients[target]?.cells?.containsKey("$cls.$transport") == true

    /** Contract `snoozeOf`: the client's own snooze when set (0 included), else the account's. */
    fun snoozeOf(target: String): Long = clients[target]?.snoozeUntil ?: snoozeUntil
}

/** `-1` (until resumed) or a future instant snoozes; `0` and past instants don't. */
fun snoozeActive(snoozeUntil: Long, nowMs: Long): Boolean = snoozeUntil == -1L || snoozeUntil > nowMs

/** `clientsMeta[]`: `web` (every browser), then each app install. */
@Serializable
data class PolicyClientMeta(
    val ref: String = "",
    /** `web`, `ios` or `android`. */
    val kind: String = "",
    val label: String = "",
    val appVersion: String = "",
    val lastSeenAt: Long = 0,
    val push: Boolean = false,
    val tz: String = "",
)

/** The `web` target's state now (ms; 0 = none). */
@Serializable
data class PolicyStatus(
    val snoozedUntil: Long = 0,
    val quietNow: Boolean = false,
    val quietEndsAt: Long = 0,
    val nextQuietStartsAt: Long = 0,
)

@Serializable
data class PolicyWebPushCapability(val publicKey: String = "")

@Serializable
data class PolicyEmailCapability(val available: Boolean = false, val address: String = "")

@Serializable
data class PolicyCapabilities(
    val webPush: PolicyWebPushCapability? = null,
    val email: PolicyEmailCapability = PolicyEmailCapability(),
)

/** This install's resolved column (`mobile`) and sound per class. */
@Serializable
data class PolicyEffectiveCells(val mobile: Boolean = false, val sound: Boolean = false)

/** Only with `X-IRCFiber-Device` for a known install: what the server resolves for it. */
@Serializable
data class PolicyEffective(
    val matrix: Map<String, PolicyEffectiveCells> = emptyMap(),
    val snoozeUntil: Long = 0,
    val quietNow: Boolean = false,
    val quietEndsAt: Long = 0,
)

@Serializable
data class NotificationPolicyView(
    val policy: NotificationPolicy = NotificationPolicy(),
    /** The If-Match token (`"np-<rev>"`). */
    val rev: Long = 1,
    val prefVersion: Long = 0,
    /** `everything`, `focused`, `quiet` or `custom` (derived from the account matrix). */
    val preset: String = "custom",
    /** Preset → class → desktop/sound/mobile. */
    val presets: Map<String, Map<String, PolicyCells>> = emptyMap(),
    /** Template → windows. */
    val quietTemplates: Map<String, List<PolicyQuietWindow>> = emptyMap(),
    val clientsMeta: List<PolicyClientMeta> = emptyList(),
    val status: PolicyStatus = PolicyStatus(),
    val capabilities: PolicyCapabilities = PolicyCapabilities(),
    /** This install's client key; null when the server doesn't know its profile yet. */
    val selfRef: String? = null,
    val effective: PolicyEffective? = null,
)

/** `notificationPolicy` that decodes as null instead of failing its parent when it doesn't fit the model. */
object LenientPolicyViewSerializer : KSerializer<NotificationPolicyView?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): NotificationPolicyView? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: return null
        return runCatching { SiteJson.decodeFromJsonElement(NotificationPolicyView.serializer(), element) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: NotificationPolicyView?) {
        if (value == null) encoder.encodeNull()
        else (encoder as JsonEncoder).encodeJsonElement(SiteJson.encodeToJsonElement(NotificationPolicyView.serializer(), value))
    }
}
