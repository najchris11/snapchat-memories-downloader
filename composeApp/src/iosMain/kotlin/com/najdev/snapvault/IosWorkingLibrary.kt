package com.najdev.snapvault

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

private const val LIBRARY_FOLDER = "SnapVault Library"

/**
 * The folder iOS imports go into, created on first use. It sits in Documents, which
 * Info.plist publishes to the Files app (UIFileSharingEnabled, LSSupportsOpeningDocumentsInPlace),
 * so users can browse and copy their media without SnapVault; deleting the app deletes it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosWorkingLibrary(): String {
    val documents = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
    val path = "${documents.path!!}/$LIBRARY_FOLDER"
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    return path
}
