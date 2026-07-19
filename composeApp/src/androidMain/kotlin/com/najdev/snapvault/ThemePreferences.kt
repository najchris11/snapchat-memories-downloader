package com.najdev.snapvault

import android.content.Context

// Set once from MainActivity.onCreate — SharedPreferences needs a Context, and the
// expect/actual preference functions have no way to receive one per call.
object ContextHolder {
    var context: Context? = null
}

private val prefs get() = ContextHolder.context?.getSharedPreferences("snapvault", Context.MODE_PRIVATE)

actual fun loadThemeModePreference(): ThemeMode {
    val name = prefs?.getString("themeMode", null) ?: return ThemeMode.SYSTEM
    return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
}

actual fun saveThemeModePreference(mode: ThemeMode) {
    prefs?.edit()?.putString("themeMode", mode.name)?.apply()
}

actual fun computeWorkerCount(): Int = 4

actual fun loadLayoutOverride(): LayoutOverride {
    val name = prefs?.getString("layoutOverride", null) ?: return LayoutOverride.Auto
    return runCatching { LayoutOverride.valueOf(name) }.getOrDefault(LayoutOverride.Auto)
}

actual fun saveLayoutOverride(override: LayoutOverride) {
    prefs?.edit()?.putString("layoutOverride", override.name)?.apply()
}
