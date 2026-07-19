@file:JvmName("MediaScannerAndroid")
package com.najdev.snapvault

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual fun loadThumbnail(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null

    val ext = file.extension.lowercase()
    if (ext in setOf("mp4", "mov")) {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            bitmap?.asImageBitmap()
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    if (ext !in setOf("jpg", "jpeg", "png", "webp", "gif")) return null

    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        val targetSize = 320
        var sampleSize = 1
        while (options.outWidth / sampleSize > targetSize || options.outHeight / sampleSize > targetSize) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeFile(path, decodeOptions)
        bitmap?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

actual fun loadFullImage(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.extension.lowercase() in setOf("mp4", "mov")) return null
    return try {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
