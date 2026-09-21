package com.najdev.snapvault

import java.io.File
import java.util.prefs.Preferences

private val prefs get() = Preferences.userRoot().node("snapvault")

actual fun loadOnboardingCompleted(): Boolean = prefs.getBoolean("onboardingCompleted", false)

actual fun saveOnboardingCompleted(completed: Boolean) {
    prefs.putBoolean("onboardingCompleted", completed)
}

// Beside the browser's own download folder: the zips Snapchat emails land there already, so
// the move into it is a drag of inches rather than across the disk. Named with a space to
// read as a folder a person made, not a dotfile.
internal const val ZIP_DROP_FOLDER_NAME = "SnapVault Exports"

actual fun defaultZipDropFolder(): String? {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return null
    return File(File(home, "Downloads"), ZIP_DROP_FOLDER_NAME).path
}
