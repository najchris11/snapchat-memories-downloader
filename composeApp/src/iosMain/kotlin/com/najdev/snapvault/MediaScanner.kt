package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale

private val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "gif")
private val videoExtensions = setOf("mp4", "mov")
private val fs = FileSystem.SYSTEM
private const val THUMBNAIL_TARGET_SIZE = 320

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = folderPath.toPath()
    if (!fs.exists(folder)) return emptyList()

    val index: Map<String, FileMeta> = runCatching {
        Json.decodeFromString<Map<String, FileMeta>>(fs.read(folder / "vault_index.json") { readUtf8() })
    }.getOrDefault(emptyMap())

    return fs.list(folder)
        .filter { path ->
            val metadata = fs.metadata(path)
            metadata.isRegularFile && path.name.substringAfterLast('.', "").lowercase() in mediaExtensions
        }
        .sortedByDescending { fs.metadata(it).lastModifiedAtMillis ?: 0L }
        .map { path ->
            val meta = index[path.name]
            val metadata = fs.metadata(path)
            val ext = path.name.substringAfterLast('.', "").lowercase()
            LibraryItem(
                id = path.toString(),
                date = formatFileDate(metadata.lastModifiedAtMillis),
                title = path.name.substringBeforeLast('.'),
                type = if (ext in videoExtensions) "video" else "photo",
                duration = null,
                hasGps = meta?.hasGps ?: false,
                hasOverlay = meta?.hasOverlay ?: false,
                fileSizeBytes = metadata.size ?: 0L
            )
        }
}

// Video/GIF thumbnails aren't implemented on iOS yet (would need AVAssetImageGenerator);
// ImageIO-backed decoding below only covers still images.
actual fun loadThumbnail(path: String): ImageBitmap? {
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext in videoExtensions || ext == "gif") return null
    val bytes = readBytesOrNull(path) ?: return null
    return runCatching {
        downsample(SkiaImage.makeFromEncoded(bytes), THUMBNAIL_TARGET_SIZE).toComposeImageBitmap()
    }.getOrNull()
}

actual fun loadFullImage(path: String): ImageBitmap? {
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext in videoExtensions || ext == "gif") return null
    val bytes = readBytesOrNull(path) ?: return null
    return runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

private fun readBytesOrNull(path: String): ByteArray? {
    val p = path.toPath()
    if (!fs.exists(p)) return null
    return runCatching { fs.read(p) { readByteArray() } }.getOrNull()
}

private fun downsample(image: SkiaImage, targetSize: Int): SkiaImage {
    val width = image.width
    val height = image.height
    if (width <= targetSize && height <= targetSize) return image

    val (newWidth, newHeight) = if (width > height) {
        targetSize to (height * targetSize / width).coerceAtLeast(1)
    } else {
        (width * targetSize / height).coerceAtLeast(1) to targetSize
    }

    val bitmap = Bitmap().apply { allocN32Pixels(newWidth, newHeight) }
    Canvas(bitmap).drawImageRect(
        image,
        Rect.makeWH(width.toFloat(), height.toFloat()),
        Rect.makeWH(newWidth.toFloat(), newHeight.toFloat())
    )
    return SkiaImage.makeFromBitmap(bitmap)
}

// NSDate only exposes a reference-date constructor here, so shift from Unix epoch (1970-01-01)
// to NSDate's reference date (2001-01-01); the formatter defaults to the local time zone.
private const val REFERENCE_DATE_OFFSET_SECONDS = 978307200.0

private fun formatFileDate(millis: Long?): String {
    if (millis == null) return ""
    val date = NSDate(timeIntervalSinceReferenceDate = millis / 1000.0 - REFERENCE_DATE_OFFSET_SECONDS)
    val formatter = NSDateFormatter().apply {
        dateFormat = "MMM dd, yyyy"
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }
    return formatter.stringFromDate(date).uppercase()
}
