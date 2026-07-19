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
}
