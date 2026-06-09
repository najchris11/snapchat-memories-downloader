package com.najdev.snapvault

actual fun loadThemePreference(): Boolean = true
actual fun saveThemePreference(dark: Boolean) {}

actual fun loadWorkersPreference(): Int = 4
actual fun saveWorkersPreference(workers: Int) {}
