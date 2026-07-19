package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSTimeZone
import platform.Foundation.defaultTimeZone

@OptIn(ExperimentalForeignApi::class)
actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val fileManager = NSFileManager.defaultManager
    val contents = fileManager.contentsOfDirectoryAtPath(folderPath, null)?.filterIsInstance<String>() ?: return emptyList()

    val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "gif")
    val videoExtensions = setOf("mp4", "mov")
    val formatter = NSDateFormatter().apply {
        dateFormat = "MMM dd, yyyy"
        timeZone = NSTimeZone.defaultTimeZone()
    }

    return contents
        .filter { fileName ->
            val ext = fileName.substringAfterLast('.', "").lowercase()
            ext in mediaExtensions
        }
        .mapNotNull { fileName ->
            val fullPath = "$folderPath/$fileName"
            val attrs = fileManager.attributesOfItemAtPath(fullPath, error = null) ?: return@mapNotNull null
            val modDate = attrs[NSFileModificationDate] as? NSDate
            val dateStr = modDate?.let { formatter.stringFromDate(it).uppercase() } ?: "UNKNOWN"
            val ext = fileName.substringAfterLast('.', "").lowercase()

            LibraryItem(
                id = fullPath,
                date = dateStr,
                title = fileName.substringBeforeLast('.'),
                type = if (ext in videoExtensions) "video" else "photo",
                duration = null,
                hasGps = false,
                hasOverlay = false,
                fileSizeBytes = (attrs[NSFileSize] as? Number)?.toLong() ?: 0L
            )
        }
}

@OptIn(ExperimentalForeignApi::class)
actual fun loadThumbnail(path: String): ImageBitmap? {
    return runCatching {
        val fileManager = NSFileManager.defaultManager
        val data = fileManager.contentsAtPath(path) ?: return null
        if (data.length == 0UL) return null
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual fun loadFullImage(path: String): ImageBitmap? {
    return loadThumbnail(path)
}
