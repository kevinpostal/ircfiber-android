package com.kevinpostal.fiberirc.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Account-settings calls of `SiteAPI.swift` (Settings → Account / Login Sessions / Bouncer / This Device).
// Every non-2xx answer is `SiteApiError.failure` (a wrong current password stays a `Message`, not `Unauthorized`).

/** GET /api/me/sessions row: one signed-in browser or app; times are server-clock epoch ms. */
@Serializable
data class LoginSession(
    val ref: String,
    val createdAt: Long,
    val lastAccess: Long,
    val expiresAt: Long,
    val clientIp: String = "",
    val userAgent: String = "",
    val current: Boolean = false,
    val clientCount: Int = 0,
)

/** GET /api/me/sessions answer; [now] is the server clock (ms). */
@Serializable
data class LoginSessions(val sessions: List<LoginSession>, val now: Long)

/** GET /api/me/bouncer/clients row: a client attached through the bouncer right now. */
@Serializable
data class BouncerClient(
    val sid: String,
    val networkId: String = "",
    val networkName: String = "",
    val clientId: String = "",
    val nick: String = "",
    val peer: String = "",
    val tls: Boolean = false,
    val attachedAt: Long = 0,
    val lastRecvMs: Long = 0,
    val lastSendMs: Long = 0,
    /** "app" (an IRC Fiber app's own connection) or "external"; null on an older row — reads as external. */
    val kind: String? = null,
)

@Serializable
data class BouncerClients(val clients: List<BouncerClient>, val now: Long)

/** POST /api/me/bouncer/settings answer. */
@Serializable
data class BouncerAccessSettings(val requireTls: Boolean, val maxClients: Int, val maxClientsCeiling: Int)

/** GET /api/me/devices/mine `device`: this install's own row. Times are server-clock epoch ms. */
@Serializable
data class MeDevice(
    val ref: String = "",
    val model: String = "",
    val osVersion: String = "",
    val appVersion: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastSeenAt: Long = 0,
    val push: PushStatus = PushStatus(),
    val bouncer: BouncerStatus? = null,
) {
    /** Push registration; `environment`/`bundleId`/`lastSeenAt` present when [on]. */
    @Serializable
    data class PushStatus(
        val on: Boolean = false,
        val environment: String? = null,
        val bundleId: String? = null,
        val lastSeenAt: Long? = null,
    )

    @Serializable
    data class BouncerStatus(val online: Boolean = false, val networks: List<NetworkStatus> = emptyList())

    @Serializable
    data class NetworkStatus(val networkId: String, val networkName: String = "")
}

@Serializable
data class MyDevice(val device: MeDevice? = null, val now: Long)

@Serializable
private class PasswordChanged(val sessionsEnded: Int = 0)

private val ME = listOf("api", "me")

/** GET /api/me/sessions. */
suspend fun SiteApi.loginSessions(): Result<LoginSessions> =
    getJson(ME + "sessions").decodeAs(LoginSessions.serializer())

/** DELETE /api/me/sessions/<ref> — signs that login out (the server refuses the current one). */
suspend fun SiteApi.revokeSession(ref: String): Result<Unit> = deletePath(ME + listOf("sessions", ref))

/** POST /api/me/password `{oldPassword,newPassword}` — the number of other sessions signed out. */
suspend fun SiteApi.changePassword(current: String, new: String): Result<Int> =
    postJson(ME + "password", buildJsonObject { put("oldPassword", current); put("newPassword", new) })
        .decodeAs(PasswordChanged.serializer()).map { it.sessionsEnded }

/** GET /api/me/bouncer, typed. */
suspend fun SiteApi.bouncer(): Result<Bouncer> = bouncerSettings().decodeAs(Bouncer.serializer())

/** GET /api/me/bouncer/clients — attached clients, newest first. */
suspend fun SiteApi.bouncerClients(): Result<BouncerClients> =
    getJson(ME + listOf("bouncer", "clients")).decodeAs(BouncerClients.serializer())

/** POST /api/me/bouncer/clients/<sid>/disconnect — drops one attached client; it may reconnect. */
suspend fun SiteApi.disconnectBouncerClient(sid: String): Result<Unit> =
    request("POST", ME + listOf("bouncer", "clients", sid, "disconnect")).map { }

/** DELETE /api/me/bouncer/devices/<clientId> — forgets the device's replay cursors. */
suspend fun SiteApi.resetBouncerDevice(clientId: String): Result<Unit> =
    deletePath(ME + listOf("bouncer", "devices", clientId))

/** POST /api/me/bouncer — (re)generates the account-wide bouncer password; other clients using it disconnect. */
suspend fun SiteApi.createBouncerToken(): Result<Bouncer> =
    request("POST", ME + "bouncer").decodeAs(Bouncer.serializer())

/** DELETE /api/me/bouncer (204) — revokes the account-wide password (the app's own token is unaffected). */
suspend fun SiteApi.revokeBouncerPassword(): Result<Unit> = deletePath(ME + "bouncer")

/** POST /api/me/bouncer/settings — partial patch of `{requireTls, maxClients}`. */
suspend fun SiteApi.updateBouncerAccess(requireTls: Boolean? = null, maxClients: Int? = null): Result<BouncerAccessSettings> =
    postJson(
        ME + listOf("bouncer", "settings"),
        buildJsonObject {
            requireTls?.let { put("requireTls", it) }
            maxClients?.let { put("maxClients", it) }
        },
    ).decodeAs(BouncerAccessSettings.serializer())

/** GET /api/me/devices/mine — this install's own row (null until the server has seen it). */
suspend fun SiteApi.myDevice(): Result<MyDevice> =
    getJson(ME + listOf("devices", "mine")).decodeAs(MyDevice.serializer())
