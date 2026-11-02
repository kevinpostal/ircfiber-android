package com.kevinpostal.fiberirc.data.api

import kotlinx.serialization.json.JsonObject

// Notification Policy v2 calls (ports the iOS `SiteAPI` policy methods). Writes answer the new
// [NotificationPolicyView]; the policy and client PATCHes need `If-Match: "np-<rev>"`, and a stale rev answers 409,
// surfaced as [SiteApiError.Conflict] whose [currentPolicy] is the stored view to retry on.

private val POLICY = listOf("api", "me", "notification-policy")

/** `If-Match` value for policy revision [rev]. */
fun policyIfMatch(rev: Long): String = "\"np-$rev\""

/** GET /api/me/notification-policy. */
suspend fun SiteApi.notificationPolicy(): Result<NotificationPolicyView> =
    getJson(POLICY).decodeAs(NotificationPolicyView.serializer())

/** PATCH /api/me/notification-policy — a merge-patch of the stored policy, or `{"applyPreset": name}`. */
suspend fun SiteApi.patchNotificationPolicy(patch: JsonObject, rev: Long): Result<NotificationPolicyView> =
    request("PATCH", POLICY, body = patch, headers = mapOf("If-Match" to policyIfMatch(rev)))
        .decodeAs(NotificationPolicyView.serializer())

/**
 * PATCH /api/me/notification-policy/clients/<ref> — one client's `{"cells": {"<cls>.<transport>": bool|null},
 * "snoozeUntil": ms|-1|0|null}` (`null` deletes the override). [ref] is `"self"` for this install.
 */
suspend fun SiteApi.patchNotificationClient(ref: String, patch: JsonObject, rev: Long): Result<NotificationPolicyView> =
    request("PATCH", POLICY + listOf("clients", ref), body = patch, headers = mapOf("If-Match" to policyIfMatch(rev)))
        .decodeAs(NotificationPolicyView.serializer())

/**
 * POST /api/me/notifications/snooze — exactly one of `{"seconds"}`, `{"nextLocal","tz"}`, `{"indefinite":true}`,
 * `{"off":true}`, plus `"client":"self"` for this install only (omitted: the whole account). Last write wins.
 */
suspend fun SiteApi.snoozeNotifications(body: JsonObject): Result<NotificationPolicyView> =
    postJson(listOf("api", "me", "notifications", "snooze"), body).decodeAs(NotificationPolicyView.serializer())

/** The 409 body's `current` view; null when it doesn't decode. */
fun SiteApiError.Conflict.currentPolicy(): NotificationPolicyView? {
    val current = (body as? JsonObject)?.get("current") as? JsonObject ?: return null
    return runCatching { SiteJson.decodeFromJsonElement(NotificationPolicyView.serializer(), current) }.getOrNull()
}
