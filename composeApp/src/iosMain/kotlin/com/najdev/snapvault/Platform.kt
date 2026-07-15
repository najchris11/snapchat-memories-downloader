package com.najdev.snapvault

actual val isAndroidBuild: Boolean = false
actual fun binaryInstallHint(): String = ""

// Kotlin/Native has no thread interruption; cancellation is handled at suspension points.
actual suspend fun <T> runInterruptibleCompat(block: () -> T): T = block()
