package com.kevinpostal.fiberirc.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.kevinpostal.fiberirc.ui.theme.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-local Appearance (ports `DisplaySettings.swift`): light / dark / follow the system, never synced.
 * Stored as `"system"|"light"|"dark"` (the iOS raw values) under `appearance`; missing or unknown → SYSTEM.
 */
class DisplaySettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val _appearance = MutableStateFlow(decode(prefs.getString(KEY, null)))
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun setAppearance(value: Appearance) {
        if (value == _appearance.value) return
        _appearance.value = value
        prefs.edit().putString(KEY, value.name.lowercase()).apply()
    }

    companion object {
        const val FILE = "display_settings"
        const val KEY = "appearance"

        fun decode(raw: String?): Appearance = Appearance.entries.firstOrNull { it.name.lowercase() == raw } ?: Appearance.SYSTEM
    }
}
