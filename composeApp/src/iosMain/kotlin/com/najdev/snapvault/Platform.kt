package com.najdev.snapvault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual val isAndroidBuild: Boolean = false
actual val platformSupportPageUrl: String? = null
actual fun binaryInstallHint(): String = ""

// openURL: alone -- the one-argument form -- is deprecated ios(2.0, 10.0) and no longer
// opens anything on a current iOS. It is still in the UIKit bindings and still compiles, so
// every link in the app (the onboarding walkthrough's "request your data" and video buttons,
// and the Help button in the top bar) was a button that reported nothing and did nothing when
// tapped. openURL:options:completionHandler: is the replacement the header names; an empty
// options map is documented as giving the old behaviour, asynchronously.
actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(
        nsUrl,
        options = emptyMap<Any?, Any>(),
        completionHandler = null,
    )
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

// iOS runs one instance of an app against a sandboxed, app-private directory, so there is no
// second writer for a lock to arbitrate between. Unenforced here states that rather than
// implying a guard that does not exist.
actual val platformOutputDirectoryLocker: OutputDirectoryLocker = UnenforcedOutputDirectoryLocker
