package com.najdev.snapvault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSRecursiveLock

actual val isAndroidBuild: Boolean = false
actual fun binaryInstallHint(): String = ""

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
