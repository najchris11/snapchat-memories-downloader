package com.najdev.snapvault

enum class ThemeMode { SYSTEM, DARK, LIGHT }

expect fun loadThemeModePreference(): ThemeMode
expect fun saveThemeModePreference(mode: ThemeMode)

expect fun computeWorkerCount(): Int

expect fun loadLayoutOverride(): LayoutOverride
expect fun saveLayoutOverride(override: LayoutOverride)
