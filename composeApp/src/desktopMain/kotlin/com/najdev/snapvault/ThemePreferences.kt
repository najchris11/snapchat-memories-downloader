package com.najdev.snapvault

import java.util.prefs.Preferences

private val prefs get() = Preferences.userRoot().node("snapvault")

actual fun loadThemeModePreference(): ThemeMode {
    val modeStr = prefs.get("themeMode", null)
    if (modeStr != null) {
        return try {
            ThemeMode.valueOf(modeStr)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }
    // Backward compatibility: check legacy "darkMode" boolean
    val hasOldDark = prefs.get("darkMode", null) != null
    return if (hasOldDark) {
        if (prefs.getBoolean("darkMode", true)) ThemeMode.DARK else ThemeMode.LIGHT
    } else {
        ThemeMode.SYSTEM
    }
}

actual fun saveThemeModePreference(mode: ThemeMode) {
    prefs.put("themeMode", mode.name)
}

actual fun loadWorkersPreference(): Int {
    val default = (Runtime.getRuntime().availableProcessors() * 0.6).toInt().coerceAtLeast(1)
    return prefs.getInt("workers", default)
}

actual fun saveWorkersPreference(workers: Int) {
    prefs.putInt("workers", workers)
}

actual fun loadLayoutOverride(): LayoutOverride {
    val name = prefs.get("layoutOverride", LayoutOverride.Auto.name)
    return try {
        LayoutOverride.valueOf(name)
    } catch (e: Exception) {
        LayoutOverride.Auto
    }
}

actual fun saveLayoutOverride(override: LayoutOverride) {
    prefs.put("layoutOverride", override.name)
}
