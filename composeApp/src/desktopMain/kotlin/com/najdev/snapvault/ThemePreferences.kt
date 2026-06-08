package com.najdev.snapvault

import java.util.prefs.Preferences

actual fun loadThemePreference(): Boolean =
    Preferences.userRoot().node("snapvault").getBoolean("darkMode", true)

actual fun saveThemePreference(dark: Boolean) {
    Preferences.userRoot().node("snapvault").putBoolean("darkMode", dark)
}
