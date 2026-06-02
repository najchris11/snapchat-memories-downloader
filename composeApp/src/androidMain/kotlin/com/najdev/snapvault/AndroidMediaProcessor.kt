package com.najdev.snapvault.metadata

import androidx.exifinterface.media.ExifInterface
import java.io.File

class AndroidMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = true // We use native ExifInterface
    override fun checkFFmpeg(): Boolean = false // Not implemented yet for Android

    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false

            val exif = ExifInterface(filePath)
            
            // Set GPS coordinates
            exif.setGpsInfo(null) // Clear old GPS info
            exif.setLatLong(latitude, longitude)

            // Set Date/Time if available
            dateStr?.let {
                val formattedDate = formatToExifDate(it)
                if (formattedDate != null) {
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)
                    exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
                }
            }

            exif.saveAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun formatToExifDate(dateStr: String): String? {
        // Simple conversion for common formats. SnapVault already has date parsing logic elsewhere
        // but for now we'll do a quick check. 
        // ExifInterface expects "yyyy:MM:dd HH:mm:ss"
        val cleaned = dateStr.trim()
        val regex = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")
        val match = regex.find(cleaned)
        return if (match != null) {
            "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]} ${match.groupValues[4]}:${match.groupValues[5]}:${match.groupValues[6]}"
        } else null
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        // TODO: Implement using MediaMuxer or an FFmpeg library for Android
        return false
    }
}
