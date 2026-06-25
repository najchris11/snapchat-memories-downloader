package com.najdev.snapvault

enum class ThemeMode { SYSTEM, DARK, LIGHT }

expect fun loadThemeModePreference(): ThemeMode
expect fun saveThemeModePreference(mode: ThemeMode)

expect fun loadWorkersPreference(): Int
expect fun saveWorkersPreference(workers: Int)
