package com.najdev.snapvault

import android.content.Intent
import android.net.Uri

actual val isAndroidBuild: Boolean = true
actual val platformSupportPageUrl: String? = null
actual fun binaryInstallHint(): String = ""

actual fun openUrl(url: String, onResult: (Boolean) -> Unit) {
    val context = ContextHolder.context
    if (context == null) {
        onResult(false)
        return
    }
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    onResult(opened)
}

actual suspend fun <T> runInterruptibleCompat(block: () -> T): T =
    kotlinx.coroutines.runInterruptible(block = block)

// No user-facing file manager to reveal into on this platform; the UI hides the action
// rather than wiring it to something that does nothing.
actual val supportsFileManager: Boolean = false

actual fun revealInFileManager(path: String) = Unit
