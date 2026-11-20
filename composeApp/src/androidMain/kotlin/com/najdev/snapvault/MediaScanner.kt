package com.najdev.snapvault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.najdev.snapvault.metadata.SupportedMediaExtensions
import com.najdev.snapvault.ui.LibraryItem
import java.io.File

private const val THUMBNAIL_MAX_EDGE_PX = 320

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> = scanJvmMediaFiles(folderPath)

actual fun loadThumbnail(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val ext = file.extension.lowercase()
    return if (ext in SupportedMediaExtensions.VIDEO) {
        decodeVideoFrameThumbnail(file.absolutePath)
    } else {
        decodeDownsampledBitmap(file.absolutePath, THUMBNAIL_MAX_EDGE_PX)?.asImageBitmap()
    }
}

actual fun loadFullImage(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val ext = file.extension.lowercase()
    if (ext in SupportedMediaExtensions.VIDEO || ext == "gif") return null
    return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
}

private fun decodeDownsampledBitmap(path: String, maxEdgePx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxEdgePx * 2 || bounds.outHeight / sampleSize > maxEdgePx * 2) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    BitmapFactory.decodeFile(path, decodeOpts)
}.getOrNull()

private fun decodeVideoFrameThumbnail(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
            ?: return null
        val maxDim = maxOf(raw.width, raw.height)
        if (maxDim <= THUMBNAIL_MAX_EDGE_PX) {
            raw.asImageBitmap()
        } else {
            val scale = THUMBNAIL_MAX_EDGE_PX.toFloat() / maxDim.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== raw) raw.recycle()
            scaled.asImageBitmap()
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
