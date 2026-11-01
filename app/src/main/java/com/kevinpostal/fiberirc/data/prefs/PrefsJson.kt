package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** SharedPreferences file of every account-wide cache (cleared key by key on sign-out). */
const val ACCOUNT_PREFS_FILE = "account_settings"

fun accountPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(ACCOUNT_PREFS_FILE, Context.MODE_PRIVATE)

private val stringList = ListSerializer(String.serializer())

/** A JSON string array stored under [key]; null when absent or unreadable. */
internal fun SharedPreferences.getStringList(key: String): List<String>? =
    getString(key, null)?.let { runCatching { SiteJson.decodeFromString(stringList, it) }.getOrNull() }

internal fun SharedPreferences.Editor.putStringList(key: String, value: List<String>): SharedPreferences.Editor =
    putString(key, SiteJson.encodeToString(stringList, value))

/** `"<networkId>:<buffer>"` matched case-insensitively (IRC names are), like `caseInsensitiveCompare`. */
internal fun sameKey(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
