package com.najdev.snapvault

expect val isAndroidBuild: Boolean

// Returns platform-specific instructions for obtaining exiftool and ffmpeg.
// Empty on platforms where neither tool applies (Android, iOS).
expect fun binaryInstallHint(): String
