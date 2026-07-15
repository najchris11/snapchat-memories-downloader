package com.najdev.snapvault

expect val isAndroidBuild: Boolean

// Returns platform-specific instructions for obtaining exiftool and ffmpeg.
// Empty on platforms where neither tool applies (Android, iOS).
expect fun binaryInstallHint(): String

// Runs [block] so that coroutine cancellation interrupts the executing thread.
// On the JVM this lets blocking calls (Process.waitFor, stream copies) abort promptly
// when the user stops the pipeline; on platforms without thread interruption it just
// invokes the block.
expect suspend fun <T> runInterruptibleCompat(block: () -> T): T
