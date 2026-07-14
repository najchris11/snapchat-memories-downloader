package com.najdev.snapvault

actual fun loadThemeModePreference(): ThemeMode = ThemeMode.SYSTEM
actual fun saveThemeModePreference(mode: ThemeMode) {}

actual fun computeWorkerCount(): Int = 4
