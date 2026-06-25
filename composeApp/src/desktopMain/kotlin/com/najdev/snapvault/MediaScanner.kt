package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.awt.Image
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()

    val index: Map<String, FileMeta> = runCatching {
        Json.decodeFromString<Map<String, FileMeta>>(
            File(folder, "vault_index.json").readText()
        )
    }.getOrDefault(emptyMap())

    val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "gif")
    val videoExtensions = setOf("mp4", "mov")
    return (folder.listFiles() ?: return emptyList())
        .filter { it.isFile && it.extension.lowercase() in mediaExtensions }
        .sortedByDescending { it.lastModified() }
        .map { file ->
            val meta = index[file.name]
            LibraryItem(
                id = file.absolutePath,
                date = formatFileDate(file.lastModified()),
                title = file.nameWithoutExtension,
                type = if (file.extension.lowercase() in videoExtensions) "video" else "photo",
                duration = null,
                hasGps = meta?.hasGps ?: false,
                hasOverlay = meta?.hasOverlay ?: false,
                fileSizeBytes = file.length()
            )
        }
}

actual fun loadThumbnail(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null
    val ext = file.extension.lowercase()
    val parent = file.parentFile ?: return null
    
    val cacheDir = File(parent, ".thumbnails")
    val thumbFile = File(cacheDir, "${file.name}.jpg")
    
    if (thumbFile.exists()) {
        return runCatching {
            SkiaImage.makeFromEncoded(thumbFile.readBytes()).toComposeImageBitmap()
        }.getOrNull()
    }
    
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    
    if (ext in setOf("mp4", "mov", "gif")) {
        val ffmpegPath = BinaryExtractor.checkCommand("ffmpeg") ?: return null
        try {
            val args = listOf(
                ffmpegPath,
                "-y",
                "-ss", "00:00:00.000",
                "-i", file.absolutePath,
                "-vframes", "1",
                "-vf", "scale=320:-1",
                thumbFile.absolutePath
            )
            val process = ProcessBuilder(args).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    } else if (ext in setOf("jpg", "jpeg", "png")) {
        try {
            val original = ImageIO.read(file)
            if (original != null) {
                val targetSize = 320
                val width = original.width
                val height = original.height
                val (newWidth, newHeight) = if (width > height) {
                    targetSize to (height * targetSize / width)
                } else {
                    (width * targetSize / height) to targetSize
                }
                val scaled = original.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
                val buffered = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
                val g2d = buffered.createGraphics()
                g2d.drawImage(scaled, 0, 0, null)
                g2d.dispose()
                ImageIO.write(buffered, "jpg", thumbFile)
            } else {
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return runCatching {
                SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
            }.getOrNull()
        }
    } else {
        return null
    }
    
    if (thumbFile.exists()) {
        return runCatching {
            SkiaImage.makeFromEncoded(thumbFile.readBytes()).toComposeImageBitmap()
        }.getOrNull()
    }
    return null
}

actual fun loadFullImage(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.extension.lowercase() in setOf("mp4", "mov", "gif")) return null
    return runCatching {
        SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    }.getOrNull()
}

private fun formatFileDate(millis: Long): String =
    SimpleDateFormat("MMM dd, yyyy", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date(millis))
        .uppercase()
