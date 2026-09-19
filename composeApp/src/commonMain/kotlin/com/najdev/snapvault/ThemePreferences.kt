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

/**
 * Where the library folder is remembered between sessions.
 *
 * An interface rather than direct calls to the two functions above, because those reach the
 * machine's real preference store: a test that took the default would read whatever this
 * machine happens to hold, and — worse — write to it. [None] is what tests that are not about
 * this pass instead.
 */
interface OutputFolderMemory {
    fun load(): String?
    fun save(path: String?)

    object Platform : OutputFolderMemory {
        override fun load(): String? = loadLastOutputFolderPreference()
        override fun save(path: String?) = saveLastOutputFolderPreference(path)
    }

    object None : OutputFolderMemory {
        override fun load(): String? = null
        override fun save(path: String?) = Unit
    }
}
