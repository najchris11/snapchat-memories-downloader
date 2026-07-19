package com.najdev.snapvault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual class SyncLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
