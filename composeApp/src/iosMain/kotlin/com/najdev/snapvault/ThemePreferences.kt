package com.najdev.snapvault

import platform.Foundation.NSUserDefaults

private val defaults get() = NSUserDefaults.standardUserDefaults

actual fun loadThemeModePreference(): ThemeMode {
    val name = defaults.stringForKey("themeMode") ?: return ThemeMode.SYSTEM
    return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
}

actual fun saveThemeModePreference(mode: ThemeMode) {
    defaults.setObject(mode.name, "themeMode")
}

actual fun computeWorkerCount(): Int = 4

actual fun loadLayoutOverride(): LayoutOverride {
    val name = defaults.stringForKey("layoutOverride") ?: return LayoutOverride.Auto
    return runCatching { LayoutOverride.valueOf(name) }.getOrDefault(LayoutOverride.Auto)
}

actual fun saveLayoutOverride(override: LayoutOverride) {
    defaults.setObject(override.name, "layoutOverride")
}
