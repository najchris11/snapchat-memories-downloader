package com.najdev.snapvault

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: iOS asked for an output folder through the Files picker but never saved a
 * security-scoped bookmark, so the chosen folder was unreadable after a relaunch and the
 * Library said "No output folder set yet". iOS now uses an app-owned library in Documents,
 * which Info.plist exposes in the Files app (UIFileSharingEnabled).
 */
@OptIn(ExperimentalForeignApi::class)
class IosWorkingLibraryTest {
    @Test
    fun iosUsesTheAppOwnedLibrary() {
        assertTrue(usesAppOwnedLibrary)
    }

    @Test
    fun theWorkingLibraryIsAnExistingFolderInDocuments() {
        val documents = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first()
            .let { (it as platform.Foundation.NSURL).path!! }
        val library = iosWorkingLibrary()
        assertEquals("$documents/SnapVault Library", library)
        memScoped {
            val isDirectory = alloc<BooleanVar>()
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(library, isDirectory.ptr))
            assertTrue(isDirectory.value)
        }
    }

    @Test
    fun choosingAnOutputFolderSelectsTheLibraryWithoutAPicker() {
        var picked: String? = null
        IosPickers().pickOutputFolder { picked = it }
        assertEquals(iosWorkingLibrary(), picked, "answered synchronously, with no document picker")
    }
}
