package com.kevinpostal.fiberirc.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

@Serializable
data class VoiceAccount(val accountId: String, val username: String, val avatarUrl: String?)
@Serializable
enum class VoiceRole { @SerialName("member") MEMBER, @SerialName("moderator") MODERATOR }
@Serializable
data class VoiceParticipant(
    val accountId: String, val sessionId: String, val username: String, val avatarUrl: String?,
    val role: VoiceRole, val muted: Boolean, val deafened: Boolean, val serverMuted: Boolean,
)
@Serializable
enum class VoiceSessionStatus {
    @SerialName("joining") JOINING, @SerialName("connected") CONNECTED,
    @SerialName("reconnecting") RECONNECTING, @SerialName("revoking") REVOKING,
}
@Serializable
data class VoiceSession(
    val id: String, val roomId: String, val status: VoiceSessionStatus,
    val muted: Boolean, val deafened: Boolean, val serverMuted: Boolean, val canSpeak: Boolean,
    val controlRevision: Long, val ownedByThisClient: Boolean, val reason: String? = null,
)
@Serializable
enum class VoiceVisibility { @SerialName("public") PUBLIC, @SerialName("private") PRIVATE }
@Serializable
enum class VoiceJoinPolicy { @SerialName("members") MEMBERS, @SerialName("restricted") RESTRICTED }
@Serializable
enum class VoiceSpeakPolicy { @SerialName("members") MEMBERS, @SerialName("speakers") SPEAKERS }
@Serializable
data class VoiceRoom(
    val id: String, val slug: String, val name: String, val description: String,
    val visibility: VoiceVisibility, val joinPolicy: VoiceJoinPolicy, val speakPolicy: VoiceSpeakPolicy,
    val capacity: Int, val revision: String, val closed: Boolean, val isDefault: Boolean,
    val canJoin: Boolean, val canSpeak: Boolean, val canModerate: Boolean, val canManage: Boolean,
    val participantCount: Int?, val participants: List<VoiceParticipant>?,
)
@Serializable
data class VoiceLimits(val maxRoomCapacity: Int, val maxParticipants: Int)
@Serializable
data class VoiceSnapshot(
    val version: Int, val enabled: Boolean, val available: Boolean, val canManage: Boolean,
    val revision: String, val limits: VoiceLimits, val defaultRoomId: String,
    val rooms: List<VoiceRoom>, val session: VoiceSession?,
)
@Serializable
data class VoiceJoin(val session: VoiceSession, val liveKitUrl: String, val token: String, val expiresAtMs: Long)
@Serializable
enum class VoiceOperationStatus { @SerialName("pending") PENDING, @SerialName("complete") COMPLETE }
@Serializable
data class VoiceOperation(val status: VoiceOperationStatus, val join: VoiceJoin? = null)
@Serializable
enum class VoiceEventKind {
    @SerialName("voice_directory_changed") DIRECTORY, @SerialName("voice_session") SESSION,
}
@Serializable
data class VoiceEvent(
    val type: VoiceEventKind, val revision: String? = null, val sessionId: String? = null,
    val controlRevision: Long? = null, val reason: String? = null,
)
class VoiceApiError(
    val status: Int, val code: String, override val message: String, val params: JsonElement,
    val retryAfter: String? = null,
) : Exception(message)

/** Strict voice wire decoding; unlike preference JSON, missing/null or mistyped fields never get coerced. */
object VoiceContract {
    private val json = Json { ignoreUnknownKeys = true }
    private val decimalRevision = Regex("0|[1-9][0-9]*")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val secretFields = setOf("clientInstanceId", "token", "liveKitUrl", "loginId", "password", "passwordHash", "email")
    private fun objectValue(value: JsonElement): JsonObject = value as? JsonObject ?: error("Invalid voice object")
    private fun strings(raw: JsonObject, vararg keys: String) {
        for (key in keys) require((raw[key] as? JsonPrimitive)?.isString == true) { "Invalid voice string: $key" }
    }
    private fun revision(raw: JsonObject) {
        strings(raw, "revision")
        require(decimalRevision.matches((raw.getValue("revision") as JsonPrimitive).content))
    }
    private fun integer(raw: JsonObject, key: String) {
        val value = raw[key] as? JsonPrimitive ?: error("Invalid voice integer: $key")
        require(!value.isString && value.longOrNull?.let { it in 0..MAX_SAFE_INTEGER } == true)
    }
    private fun noSecrets(raw: JsonObject) { require(raw.keys.none { it in secretFields }) }
    private fun validateSession(session: VoiceSession, raw: JsonObject) {
        strings(raw, "id", "roomId", "status")
        integer(raw, "controlRevision")
        noSecrets(raw)
        require(!session.deafened || session.muted)
    }
    private fun validateJoin(join: VoiceJoin, raw: JsonObject) {
        strings(raw, "liveKitUrl", "token")
        integer(raw, "expiresAtMs")
        validateSession(join.session, objectValue(raw.getValue("session")))
        require(join.session.ownedByThisClient)
    }
    fun decodeAccount(value: JsonElement): VoiceAccount {
        val raw = objectValue(value)
        strings(raw, "accountId", "username")
        if (raw["avatarUrl"] != JsonNull) strings(raw, "avatarUrl")
        return json.decodeFromJsonElement<VoiceAccount>(raw)
    }
    fun decodeSession(value: JsonElement): VoiceSession {
        val raw = objectValue(value)
        return json.decodeFromJsonElement<VoiceSession>(raw).also { validateSession(it, raw) }
    }
    fun decodeSnapshot(value: JsonElement): VoiceSnapshot {
        val raw = objectValue(value)
        revision(raw)
        strings(raw, "defaultRoomId")
        val result = json.decodeFromJsonElement<VoiceSnapshot>(raw)
        require(result.version == 1 && result.limits.maxRoomCapacity > 0 && result.limits.maxParticipants > 0)
        require(result.enabled || result.canManage || (result.rooms.isEmpty() && result.session == null))
        val rawRooms = raw.getValue("rooms") as? kotlinx.serialization.json.JsonArray ?: error("Invalid rooms")
        for ((room, element) in result.rooms.zip(rawRooms)) {
            val roomRaw = objectValue(element)
            strings(roomRaw, "id", "slug", "name", "description", "visibility", "joinPolicy", "speakPolicy")
            revision(roomRaw)
            require(room.capacity > 0 && (room.participants == null) == (room.participantCount == null))
            if (room.participants != null) {
                require(room.participants.size == room.participantCount)
                require(room.joinPolicy != VoiceJoinPolicy.RESTRICTED || room.closed || room.canJoin || room.canManage || room.canModerate)
                val people = roomRaw.getValue("participants") as kotlinx.serialization.json.JsonArray
                for (person in people) {
                    val personRaw = objectValue(person)
                    strings(personRaw, "accountId", "sessionId", "username", "role")
                    if (personRaw["avatarUrl"] != JsonNull) strings(personRaw, "avatarUrl")
                }
            }
        }
        result.session?.let { validateSession(it, objectValue(raw.getValue("session"))) }
        return result
    }
    fun decodeJoin(value: JsonElement): VoiceJoin {
        val raw = objectValue(value)
        return json.decodeFromJsonElement<VoiceJoin>(raw).also { validateJoin(it, raw) }
    }
    fun decodeOperation(value: JsonElement): VoiceOperation {
        val raw = objectValue(value)
        strings(raw, "status")
        val result = json.decodeFromJsonElement<VoiceOperation>(raw)
        if (result.status == VoiceOperationStatus.PENDING) require("join" !in raw)
        else if (result.join != null) validateJoin(result.join, objectValue(raw.getValue("join")))
        else require("join" !in raw)
        return result
    }
    fun decodeEvent(value: JsonElement): VoiceEvent {
        val raw = objectValue(value)
        noSecrets(raw)
        require("ownedByThisClient" !in raw && "session" !in raw)
        strings(raw, "type")
        val result = json.decodeFromJsonElement<VoiceEvent>(raw)
        when (result.type) {
            VoiceEventKind.DIRECTORY -> revision(raw)
            VoiceEventKind.SESSION -> { strings(raw, "sessionId"); integer(raw, "controlRevision") }
        }
        return result
    }
    @Serializable
    private data class ErrorBody(val error: String, val code: String, val params: JsonElement)
    fun decodeError(status: Int, value: JsonElement): VoiceApiError {
        require(status in 400..599)
        val raw = objectValue(value)
        strings(raw, "error", "code")
        val body = json.decodeFromJsonElement<ErrorBody>(raw)
        return VoiceApiError(status, body.code, body.error, body.params)
    }
}
