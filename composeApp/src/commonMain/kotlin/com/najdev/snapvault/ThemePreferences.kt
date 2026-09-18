package com.najdev.snapvault

enum class ThemeMode { SYSTEM, DARK, LIGHT }

expect fun loadThemeModePreference(): ThemeMode
expect fun saveThemeModePreference(mode: ThemeMode)

expect fun computeWorkerCount(): Int

expect fun loadLayoutOverride(): LayoutOverride
expect fun saveLayoutOverride(override: LayoutOverride)

// The library folder from the last session. Reopening the app used to leave it unset, so every
// launch started by finding the same folder again in a native picker. Null when nothing has
// been chosen yet, or when the user has not chosen one since this was added.
expect fun loadLastOutputFolderPreference(): String?
expect fun saveLastOutputFolderPreference(path: String?)
