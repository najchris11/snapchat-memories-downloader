package com.najdev.snapvault

import android.content.Context

object ContextHolder {
    var context: Context? = null
}

private val prefs get() = ContextHolder.context?.getSharedPreferences("snapvault", Context.MODE_PRIVATE)

actual fun loadThemePreference(): Boolean = prefs?.getBoolean("darkMode", true) ?: true

actual fun saveThemePreference(dark: Boolean) {
    prefs?.edit()?.putBoolean("darkMode", dark)?.apply()
}

actual fun loadWorkersPreference(): Int = prefs?.getInt("workers", 4) ?: 4

actual fun saveWorkersPreference(workers: Int) {
    prefs?.edit()?.putInt("workers", workers)?.apply()
}

actual fun loadLayoutOverride(): LayoutOverride {
    val name = prefs?.getString("layoutOverride", LayoutOverride.Auto.name) ?: LayoutOverride.Auto.name
    return try {
        LayoutOverride.valueOf(name)
    } catch (e: Exception) {
        LayoutOverride.Auto
    }
}

actual fun saveLayoutOverride(override: LayoutOverride) {
    prefs?.edit()?.putString("layoutOverride", override.name)?.apply()
}
