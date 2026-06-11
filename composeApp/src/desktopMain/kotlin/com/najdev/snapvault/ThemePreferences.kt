package com.najdev.snapvault

import java.util.prefs.Preferences

private val prefs get() = Preferences.userRoot().node("snapvault")

actual fun loadThemePreference(): Boolean = prefs.getBoolean("darkMode", true)

actual fun saveThemePreference(dark: Boolean) {
    prefs.putBoolean("darkMode", dark)
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
