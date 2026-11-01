package com.kevinpostal.fiberirc.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** GET /api/networks — one row per network: its `NetworkConfig` (flat) plus the upstream state (`SiteAPI.NetworkInfo`). */
@Serializable
data class NetworkInfo(
    val id: String,
    val name: String,
    val host: String? = null,
    val port: Int? = null,
    /** "disabled", "enabled" or "required". */
    val tls: String? = null,
    val nick: String? = null,
    val ident: String? = null,
    val realName: String? = null,
    val serverPass: String? = null,
    /** "none", "plain" or "external". */
    val sasl: String? = null,
    val saslUsername: String? = null,
    val saslPassword: String? = null,
    val nspass: String? = null,
    val autoJoinChannels: List<String>? = null,
    /** Commands sent on connect, one per line. */
    val commands: String? = null,
    /** Provisioned by IRC Fiber: not editable or removable. */
    val systemManaged: Boolean? = null,
    val connected: Boolean? = null,
    val status: String? = null,
    val currentNick: String? = null,
    /** Capabilities the upstream server acked (absent when none). */
    val caps: List<String>? = null,
)

/** GET /api/networks. */
suspend fun SiteApi.networks(): Result<List<NetworkInfo>> =
    getJson(listOf("api", "networks")).decodeAs(ListSerializer(NetworkInfo.serializer()))

/** POST /api/networks — [fields] are `NetworkConfig` JSON keys; answers the stored config. */
suspend fun SiteApi.createNetwork(fields: JsonObject): Result<NetworkInfo> =
    postJson(listOf("api", "networks"), fields).decodeAs(NetworkInfo.serializer())

/** PATCH /api/networks/<id> — changes only the keys in [fields]; answers the stored config. */
suspend fun SiteApi.updateNetwork(id: String, fields: JsonObject): Result<NetworkInfo> =
    request("PATCH", listOf("api", "networks", id), body = fields).decodeAs(NetworkInfo.serializer())

/** DELETE /api/networks/<id> (refused with 403 for `systemManaged` networks). */
suspend fun SiteApi.deleteNetwork(id: String): Result<Unit> = deletePath(listOf("api", "networks", id))

/** POST /api/networks/<id>/reconnect. */
suspend fun SiteApi.reconnectNetwork(id: String): Result<Unit> =
    request("POST", listOf("api", "networks", id, "reconnect")).map { }

/** POST /api/networks/<id>/disconnect. */
suspend fun SiteApi.disconnectNetwork(id: String): Result<Unit> =
    request("POST", listOf("api", "networks", id, "disconnect")).map { }
