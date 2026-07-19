package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSTimeZone
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeIntervalSince1970

private data class ScannedItem(
    val item: LibraryItem,
    val modDateMillis: Long
)

@OptIn(ExperimentalForeignApi::class)
actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val fileManager = NSFileManager.defaultManager
    val contents = fileManager.contentsOfDirectoryAtPath(folderPath, null)?.filterIsInstance<String>() ?: return emptyList()

    val index: Map<String, FileMeta> = runCatching {
        val indexPath = "$folderPath/vault_index.json"
        val data = fileManager.contentsAtPath(indexPath) ?: return@runCatching emptyMap()
        if (data.length == 0UL) return@runCatching emptyMap()
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        Json.decodeFromString<Map<String, FileMeta>>(bytes.decodeToString())
    }.getOrDefault(emptyMap())

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
            val modDateMillis = (modDate?.timeIntervalSince1970() ?: 0.0).toLong() * 1000L
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val meta = index[fileName]

            ScannedItem(
                item = LibraryItem(
                    id = fullPath,
                    date = dateStr,
                    title = fileName.substringBeforeLast('.'),
                    type = if (ext in videoExtensions) "video" else "photo",
                    duration = null,
                    hasGps = meta?.hasGps ?: false,
                    hasOverlay = meta?.hasOverlay ?: false,
                    fileSizeBytes = (attrs[NSFileSize] as? Number)?.toLong() ?: 0L
                ),
                modDateMillis = modDateMillis
            )
        }
        .sortedByDescending { it.modDateMillis }
        .map { it.item }
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
        val fullImage = Image.makeFromEncoded(bytes)
        val maxDim = maxOf(fullImage.width, fullImage.height)
        if (maxDim <= 320) {
            fullImage.toComposeImageBitmap()
        } else {
            val scale = 320.0f / maxDim
            val newWidth = (fullImage.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (fullImage.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(newWidth, newHeight)
            val canvas = surface.canvas
            val srcRect = Rect.makeWH(fullImage.width.toFloat(), fullImage.height.toFloat())
            val dstRect = Rect.makeWH(newWidth.toFloat(), newHeight.toFloat())
            canvas.drawImageRect(fullImage, srcRect, dstRect)
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }.getOrNull()
}

@OptIn(ExperimentalForeignApi::class)
actual fun loadFullImage(path: String): ImageBitmap? {
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
