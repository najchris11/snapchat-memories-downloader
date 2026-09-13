package com.najdev.snapvault

import kotlinx.coroutines.CoroutineDispatcher

expect val isAndroidBuild: Boolean

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

// Opens a URL in the user's browser. Silently does nothing if the platform can't service
// the request — a failed Help click should not take the window down.
expect fun openUrl(url: String)

// Shows [path] in the platform's file manager, selecting the file where that is supported
// and opening its parent directory otherwise. Like openUrl, silently does nothing rather
// than taking the window down.
expect fun revealInFileManager(path: String)

// Whether [revealInFileManager] does anything. False on the mobile targets, which have no
// user-facing file manager to reveal into — the UI hides the action rather than offering a
// control that does nothing.
expect val supportsFileManager: Boolean

// Runs [block] so that coroutine cancellation interrupts the executing thread.
// On the JVM this lets blocking calls (Process.waitFor, stream copies) abort promptly
// when the user stops the pipeline; on platforms without thread interruption it just
// invokes the block.
expect suspend fun <T> runInterruptibleCompat(block: () -> T): T
