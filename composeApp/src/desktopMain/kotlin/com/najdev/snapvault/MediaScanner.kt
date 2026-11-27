package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.ui.LibraryItem
import org.jetbrains.skia.Image as SkiaImage
import java.io.File

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> = scanJvmMediaFiles(folderPath)

actual fun loadThumbnail(path: String): ImageBitmap? = DesktopThumbnails().load(path)

actual fun loadFullImage(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.extension.lowercase() in setOf("mp4", "mov", "gif")) return null
    return runCatching {
        SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    }.getOrNull()
}
