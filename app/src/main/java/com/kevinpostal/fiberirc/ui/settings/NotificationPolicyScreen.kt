package com.kevinpostal.fiberirc.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.data.api.NOTIFY_CLASSES
import com.kevinpostal.fiberirc.data.api.NotificationPolicyView
import com.kevinpostal.fiberirc.data.api.PolicyQuietWindow
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.api.snoozeActive
import com.kevinpostal.fiberirc.data.irc.LinkOpener
import com.kevinpostal.fiberirc.data.prefs.NotificationPolicyStore
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings titles of the notification classes (contract order). */
internal fun notifyClassTitle(cls: String): String = when (cls) {
    "dm" -> "Direct Messages"
    "mention" -> "Mentions"
    "all" -> "Other Channel Messages"
    "invite" -> "Invites"
    "notice" -> "Notices"
    "wallops" -> "Wallops"
    else -> cls
}

/** The presets the app offers, in display order, with their titles and descriptions. */
private val PRESETS = listOf(
    Triple("everything", "Everything", "DMs, mentions, invites and notices"),
    Triple("focused", "Mentions & DMs", "Recommended"),
    Triple("quiet", "Quiet", "DMs only, without sound"),
)

/** Quiet-hours templates the server defines, with their titles. */
private val TEMPLATES = listOf(
    "nights" to "Nights",
    "nightsWeekends" to "Nights + weekends",
    "offHours" to "Outside work hours",
)

private const val CUSTOM = "custom"

/** One snooze menu entry: its label and the POST body ([body] null = the current state, not an action). */
internal data class SnoozeChoice(val label: String, val body: JsonObject?)

/** "Until 3:45 PM" (today) / "Until 11/2/26, 8:00 AM" / "Until you resume" for a snooze end. */
internal fun snoozeEndLabel(until: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (until == -1L) return "Until you resume"
    val at = Instant.ofEpochMilli(until).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val format = if (at.toLocalDate() == today) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return "Until ${at.format(format)}"
}

/**
 * The snooze menu: "This device" entries (`client:"self"`, only when the server knows this install) then
 * "Everywhere" ones (the account); Resume entries only while that scope is snoozed.
 */
internal fun snoozeChoices(view: NotificationPolicyView, now: Long, deviceTz: String): List<SnoozeChoice> {
    val until8 = LocalTime.of(8, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    fun scope(prefix: String, client: String?, snoozed: Boolean): List<SnoozeChoice> {
        fun body(vararg pairs: Pair<String, JsonElement>) =
            JsonObject(mapOf(*pairs) + (client?.let { mapOf("client" to JsonPrimitive(it)) } ?: emptyMap()))
        return listOfNotNull(
            SnoozeChoice("$prefix: 1 hour", body("seconds" to JsonPrimitive(3600))),
            SnoozeChoice("$prefix: until $until8", body("nextLocal" to JsonPrimitive("08:00"), "tz" to JsonPrimitive(deviceTz))),
            SnoozeChoice("$prefix: until I resume", body("indefinite" to JsonPrimitive(true))),
            if (snoozed) SnoozeChoice("$prefix: resume", body("off" to JsonPrimitive(true))) else null,
        )
    }
    val policy = view.policy
    val self = view.selfRef
    val device = if (self != null) scope("This device", NotificationPolicyStore.SELF, snoozeActive(policy.snoozeOf(self), now)) else emptyList()
    return device + scope("Everywhere", null, snoozeActive(policy.snoozeUntil, now))
}

/** The template whose windows [windows] are, else [CUSTOM]. */
internal fun quietTemplateOf(windows: List<PolicyQuietWindow>, templates: Map<String, List<PolicyQuietWindow>>): String =
    templates.entries.firstOrNull { it.value == windows }?.key ?: CUSTOM

/**
 * Settings → Notifications on a Notification Policy v2 server (ports `NotificationPolicyViewController`): system
 * permission and snooze, the account presets, this install's push and sound per class (overrides of the account,
 * `PATCH …/clients/self`), quiet hours, highlight words, and a link to the full settings on the web. Every edit
 * goes through [NotificationPolicyStore] (optimistic, one retry on a 409); a failed save puts the value back.
 */
@Composable
fun NotificationPolicyScreen(
    store: NotificationPolicyStore,
    highlightWordCount: Int,
    baseUrl: String,
    openLinksInApp: Boolean,
    onOpenHighlightWords: () -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalFiberIrcColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    val current by store.view.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf<Boolean?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val deviceTz = remember { ZoneId.systemDefault().id }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        now = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun save(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alerts.show("Couldn't save setting", e)
            }
        }
    }

    fun quietPatch(vararg pairs: Pair<String, JsonElement>) =
        save { store.patchPolicy(JsonObject(mapOf("quiet" to JsonObject(mapOf(*pairs))))) }

    val view = current
    SettingsScaffold(title = "Notifications", onBack = onBack) {
        if (view == null) return@SettingsScaffold
        val policy = view.policy
        val self = view.selfRef
        val snoozeUntil = if (self != null) policy.snoozeOf(self) else policy.snoozeUntil
        val snoozed = snoozeActive(snoozeUntil, now)

        settingsSection(
            key = "status",
            footer = "“This device” pauses notifications on this phone only; “Everywhere” pauses them for your whole " +
                "account.",
            rows = listOf(
                {
                    SettingsRow(
                        "Notifications",
                        value = when (allowed) {
                            true -> "Allowed"
                            false -> "Off — Open Settings"
                            null -> "…"
                        },
                        valueColor = if (allowed == false) c.link else null,
                        onClick = if (allowed == false) ({
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }) else null,
                    )
                },
                {
                    val state = SnoozeChoice(if (snoozed) snoozeEndLabel(snoozeUntil, now) else "Off", null)
                    PopUpRow(
                        "Snooze",
                        snoozeChoices(view, now, deviceTz),
                        state,
                        label = { it.label },
                        modifier = Modifier.testTag("notifications.snooze"),
                    ) { choice -> choice.body?.let { body -> save { store.snooze(body) } } }
                },
            ),
        )

        settingsSection(
            key = "presets",
            header = "Notify Me About",
            footer = "Applies to your whole account. Changes you make for this phone below stay.",
            rows = buildList<@Composable () -> Unit> {
                for ((name, title, detail) in PRESETS) {
                    add {
                        SettingsRow(
                            title,
                            subtitle = detail,
                            modifier = Modifier.testTag("notifications.preset.$name"),
                            onClick = { if (view.preset != name) save { store.applyPreset(name) } },
                        ) {
                            if (view.preset == name) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.accent)
                        }
                    }
                }
                if (view.preset == CUSTOM) {
                    add {
                        SettingsRow("Custom", subtitle = "Edited on the web", modifier = Modifier.testTag("notifications.preset.custom")) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.accent)
                        }
                    }
                }
            },
        )

        if (self != null) {
            val overridden = policy.clients[self] != null
            settingsSection(
                key = "device",
                header = "On This Phone",
                footer = "Push and sound for this phone. A change here overrides your account default for this phone only.",
                rows = buildList<@Composable () -> Unit> {
                    for (cls in NOTIFY_CLASSES) {
                        add {
                            val changed = policy.overrides(self, cls, "mobile") || policy.overrides(self, cls, "sound")
                            ClassRow(
                                cls = cls,
                                subtitle = if (changed) "Changed on this device" else "Account default",
                                push = policy.resolveCell(self, cls, "mobile"),
                                sound = policy.resolveCell(self, cls, "sound"),
                                onPush = { on -> save { store.setDeviceCell(cls, "mobile", on) } },
                                onSound = { on -> save { store.setDeviceCell(cls, "sound", on) } },
                            )
                        }
                    }
                    add {
                        SettingsRow(
                            "Reset to Account",
                            titleColor = if (overridden) c.link else c.textTertiary,
                            modifier = Modifier.testTag("notifications.device.reset"),
                            onClick = if (overridden) ({ save { store.resetDevice() } }) else null,
                        )
                    }
                },
            )
        }

        val quiet = policy.quiet
        val template = quietTemplateOf(quiet.windows, view.quietTemplates)
        val templates = TEMPLATES.filter { it.first in view.quietTemplates }.map { it.first } +
            if (template == CUSTOM) listOf(CUSTOM) else emptyList()
        val quietNow = view.effective?.quietNow ?: view.status.quietNow
        val quietEndsAt = view.effective?.quietEndsAt ?: view.status.quietEndsAt
        settingsSection(
            key = "quiet",
            header = "Quiet Hours",
            footer = when {
                !quiet.enabled -> "Notifications wait until quiet hours end. DMs from VIPs, repeated DMs and urgent " +
                    "highlight words still come through; edit those on the web."
                quietNow && quietEndsAt > 0 -> "Quiet now — ${snoozeEndLabel(quietEndsAt, now).replaceFirstChar { it.lowercase() }}."
                view.status.nextQuietStartsAt > 0 ->
                    "Next quiet hours start ${snoozeEndLabel(view.status.nextQuietStartsAt, now).removePrefix("Until ")}."
                else -> null
            },
            rows = listOf(
                {
                    SettingsSwitchRow("Quiet Hours", quiet.enabled) { on ->
                        if (on && quiet.tz.isEmpty()) quietPatch("enabled" to JsonPrimitive(true), "tz" to JsonPrimitive(deviceTz))
                        else quietPatch("enabled" to JsonPrimitive(on))
                    }
                },
                {
                    PopUpRow(
                        "Schedule",
                        templates,
                        template,
                        label = { t -> TEMPLATES.firstOrNull { it.first == t }?.second ?: "Custom (edit on the web)" },
                        modifier = Modifier.testTag("notifications.quiet.template"),
                    ) { t ->
                        val windows = view.quietTemplates[t] ?: return@PopUpRow
                        quietPatch("windows" to SiteJson.encodeToJsonElement(ListSerializer(PolicyQuietWindow.serializer()), windows))
                    }
                },
                {
                    PopUpRow(
                        "Time Zone",
                        listOf("account", "device"),
                        if (quiet.tzMode == "device") "device" else "account",
                        label = { mode ->
                            if (mode == "device") "This phone ($deviceTz)" else "Account (${quiet.tz.ifEmpty { "not set" }})"
                        },
                        modifier = Modifier.testTag("notifications.quiet.tzMode"),
                    ) { mode -> quietPatch("tzMode" to JsonPrimitive(mode)) }
                },
                {
                    SettingsSwitchRow("Summary When Quiet Hours End", quiet.summary) { on ->
                        quietPatch("summary" to JsonPrimitive(on))
                    }
                },
            ),
        )

        settingsSection(
            key = "highlights",
            rows = listOf {
                SettingsRow(
                    "Highlight Words",
                    value = highlightWordCount.toString(),
                    onClick = onOpenHighlightWords,
                    trailing = { Chevron() },
                )
            },
        )

        settingsSection(
            key = "web",
            footer = "Channel and network overrides, email, delivery health and more.",
            rows = listOf {
                SettingsRow(
                    "More on ircfiber.com",
                    titleColor = c.link,
                    modifier = Modifier.testTag("notifications.web"),
                    onClick = {
                        LinkOpener.open(context, "${baseUrl.trimEnd('/')}/?/settings=notifications", openLinksInApp)
                    },
                )
            },
        )
    }
    ErrorDialog(alerts)
}

/** One class on this phone: title, inherited/changed subtitle, Push and Sound switches (Sound only with Push). */
@Composable
private fun ClassRow(
    cls: String,
    subtitle: String,
    push: Boolean,
    sound: Boolean,
    onPush: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
) {
    val c = LocalFiberIrcColors.current
    SettingsRow(notifyClassTitle(cls), subtitle = subtitle, modifier = Modifier.testTag("notifications.class.$cls")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Grid.unit)) {
            Text("Push", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = push,
                onCheckedChange = onPush,
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                modifier = Modifier.testTag("notifications.class.$cls.mobile"),
            )
            Text("Sound", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = sound && push,
                onCheckedChange = onSound,
                enabled = push,
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                modifier = Modifier.testTag("notifications.class.$cls.sound"),
            )
        }
    }
}
