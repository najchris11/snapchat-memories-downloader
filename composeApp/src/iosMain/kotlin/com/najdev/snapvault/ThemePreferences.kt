package com.najdev.snapvault

import platform.Foundation.NSUserDefaults

private val defaults get() = NSUserDefaults.standardUserDefaults

actual fun loadThemePreference(): Boolean {
    if (defaults.objectForKey("darkMode") == null) {
        return true
    }
    return defaults.boolForKey("darkMode")
}

actual fun saveThemePreference(dark: Boolean) {
    defaults.setBool(dark, "darkMode")
}

actual fun loadWorkersPreference(): Int {
    val saved = defaults.integerForKey("workers")
    return if (saved == 0L) 4 else saved.toInt()
}

actual fun saveWorkersPreference(workers: Int) {
    defaults.setInteger(workers.toLong(), "workers")
}

actual fun loadLayoutOverride(): LayoutOverride {
    val name = defaults.stringForKey("layoutOverride") ?: LayoutOverride.Auto.name
    return try {
        LayoutOverride.valueOf(name)
    } catch (e: Exception) {
        LayoutOverride.Auto
    }
}

actual fun saveLayoutOverride(override: LayoutOverride) {
    defaults.setObject(override.name, "layoutOverride")
}
