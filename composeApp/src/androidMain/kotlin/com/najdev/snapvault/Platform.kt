package com.najdev.snapvault

actual val isAndroidBuild: Boolean = true
actual fun binaryInstallHint(): String = ""

actual suspend fun <T> runInterruptibleCompat(block: () -> T): T =
    kotlinx.coroutines.runInterruptible(block = block)
