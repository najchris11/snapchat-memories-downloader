package com.najdev.snapvault

actual val isAndroidBuild: Boolean = false

actual fun nowIsoString(): String = java.time.Instant.now().toString().take(19) + "Z"
