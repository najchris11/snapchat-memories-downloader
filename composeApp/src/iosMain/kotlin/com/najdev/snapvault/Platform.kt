package com.najdev.snapvault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual val isAndroidBuild: Boolean = false
actual fun binaryInstallHint(): String = ""

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl)
}

// Kotlin/Native has no thread interruption; cancellation is handled at suspension points.
actual suspend fun <T> runInterruptibleCompat(block: () -> T): T = block()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual class SyncLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

// No user-facing file manager to reveal into on this platform; the UI hides the action
// rather than wiring it to something that does nothing.
actual val supportsFileManager: Boolean = false

actual fun revealInFileManager(path: String) = Unit
