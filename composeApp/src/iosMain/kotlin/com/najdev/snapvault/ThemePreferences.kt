package com.najdev.snapvault

actual fun loadThemeModePreference(): ThemeMode = ThemeMode.SYSTEM
actual fun saveThemeModePreference(mode: ThemeMode) {}

actual fun loadWorkersPreference(): Int = 4
actual fun saveWorkersPreference(workers: Int) {}
