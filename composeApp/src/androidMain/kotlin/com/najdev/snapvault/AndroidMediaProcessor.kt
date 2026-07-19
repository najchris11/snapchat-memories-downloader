package com.najdev.snapvault.metadata

import androidx.exifinterface.media.ExifInterface
import com.najdev.snapvault.util.DateUtil
import java.io.File

class AndroidMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = true
    override fun checkFFmpeg(): Boolean = false

    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false

            val exif = ExifInterface(filePath)
            exif.setLatLong(latitude, longitude)

            DateUtil.toExifDateString(dateStr)?.let { formattedDate ->
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
            }

            exif.saveAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false

            val ext = file.extension.lowercase()
            val isVideo = ext in setOf("mp4", "mov", "avi", "mkv", "3gp", "webm")

            if (isVideo) {
                // For videos: update filesystem mtime
                val epochMillis = DateUtil.toEpochMillis(dateTimeUtc) ?: return false
                file.setLastModified(epochMillis)
            } else {
                // For images: write EXIF date and require EXIF save success
                val formattedDate = DateUtil.toExifDateString(dateTimeUtc) ?: return false
                val exif = ExifInterface(filePath)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
                exif.saveAttributes()

                // Also update filesystem mtime as a bonus
                DateUtil.toEpochMillis(dateTimeUtc)?.let { file.setLastModified(it) }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        return false
    }

    override fun combineImageWithOverlay(
        mainPath: String,
        overlayPath: String,
        outputPath: String,
        onWarning: ((String) -> Unit)?
    ): Boolean {
        var mainBitmap: android.graphics.Bitmap? = null
        var overlayBitmap: android.graphics.Bitmap? = null
        var compositeBitmap: android.graphics.Bitmap? = null

        return try {
            val mainFile = File(mainPath)
            val overlayFile = File(overlayPath)
            if (!mainFile.exists() || !overlayFile.exists()) return false

            mainBitmap = android.graphics.BitmapFactory.decodeFile(mainPath) ?: return false
            overlayBitmap = android.graphics.BitmapFactory.decodeFile(overlayPath) ?: return false

            compositeBitmap = mainBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(compositeBitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

            val srcRect = android.graphics.Rect(0, 0, overlayBitmap.width, overlayBitmap.height)
            val dstRect = android.graphics.Rect(0, 0, mainBitmap.width, mainBitmap.height)
            canvas.drawBitmap(overlayBitmap, srcRect, dstRect, paint)

            val outputFile = File(outputPath)
            val compressed = outputFile.outputStream().use { out ->
                compositeBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Verify file output exists and is non-zero byte size
            if (!compressed || !outputFile.exists() || outputFile.length() == 0L) {
                if (outputFile.exists()) outputFile.delete()
                return false
            }

            // Copy EXIF metadata from main file to merged file
            try {
                val srcExif = ExifInterface(mainPath)
                val dstExif = ExifInterface(outputPath)
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF
                )
                for (tag in tags) {
                    srcExif.getAttribute(tag)?.let { value ->
                        dstExif.setAttribute(tag, value)
                    }
                }
                dstExif.saveAttributes()
            } catch (e: Exception) {
                val warnMsg = "Failed to copy EXIF metadata for ${File(outputPath).name}: ${e.message}"
                android.util.Log.w("AndroidMediaProcessor", warnMsg)
                onWarning?.invoke(warnMsg)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            mainBitmap?.recycle()
            overlayBitmap?.recycle()
            compositeBitmap?.recycle()
        }
    }
}
