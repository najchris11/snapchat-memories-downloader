package com.najdev.snapvault

import android.content.Intent
import android.net.Uri

actual val isAndroidBuild: Boolean = true
actual fun binaryInstallHint(): String = ""

actual fun openUrl(url: String) {
    val context = ContextHolder.context ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

actual suspend fun <T> runInterruptibleCompat(block: () -> T): T =
    kotlinx.coroutines.runInterruptible(block = block)
