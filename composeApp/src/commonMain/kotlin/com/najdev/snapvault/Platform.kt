package com.najdev.snapvault

import kotlinx.coroutines.CoroutineDispatcher

expect val isAndroidBuild: Boolean
expect val isMobileBuild: Boolean

// Dispatchers.IO is JVM/Native-specific and not visible from common code; this is the
// platform's blocking-I/O dispatcher for file and process work.
expect val ioDispatcher: CoroutineDispatcher

// Mutual-exclusion lock usable from common code (kotlin.synchronized is JVM-only).
expect class SyncLock() {
    fun <T> withLock(block: () -> T): T
}

// Returns platform-specific instructions for obtaining exiftool and ffmpeg.
// Empty on platforms where neither tool applies (Android, iOS).
expect fun binaryInstallHint(): String

// Runs [block] so that coroutine cancellation interrupts the executing thread.
// On the JVM this lets blocking calls (Process.waitFor, stream copies) abort promptly
// when the user stops the pipeline; on platforms without thread interruption it just
// invokes the block.
expect suspend fun <T> runInterruptibleCompat(block: () -> T): T
