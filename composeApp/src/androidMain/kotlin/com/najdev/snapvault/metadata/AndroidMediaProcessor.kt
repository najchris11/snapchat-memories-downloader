package com.najdev.snapvault.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
            dateStr?.let { applyExifDate(exif, it) }

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

            val exif = ExifInterface(filePath)
            applyExifDate(exif, dateTimeUtc)
            exif.saveAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun applyExifDate(exif: ExifInterface, dateStr: String) {
        val formattedDate = formatToExifDate(dateStr)
        if (formattedDate != null) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
        }
    }

    private fun formatToExifDate(dateStr: String): String? {
        // ExifInterface expects "yyyy:MM:dd HH:mm:ss"
        val cleaned = dateStr.trim().removeSuffix(" UTC")
        val regex = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")
        val match = regex.find(cleaned)
        return if (match != null) {
            "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]} ${match.groupValues[4]}:${match.groupValues[5]}:${match.groupValues[6]}"
        } else null
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        // Not yet implemented for Android (Phase 4)
        return false
    }

    /**
     * Composites [overlayPath] onto [mainPath] with android.graphics.
     *
     * Ported from feature/mobile-responsive-ui, where it was the one part of the Android
     * pipeline with no desktop equivalent: there is no ImageIO on Android and no bundled
     * FFmpeg, so the platform's own Canvas is the only way to burn an overlay on.
     *
     * The caller serialises these — see AndroidZipPipelineRunner. Three full-resolution
     * bitmaps are live at once here, so concurrent calls are what push the process over its
     * heap limit.
     */
    override fun combineImageWithOverlay(
        mainPath: String,
        overlayPath: String,
        outputPath: String,
        onWarning: ((String) -> Unit)?,
    ): Boolean {
        var mainBitmap: Bitmap? = null
        var overlayBitmap: Bitmap? = null
        var compositeBitmap: Bitmap? = null

        return try {
            if (!File(mainPath).exists() || !File(overlayPath).exists()) return false

            mainBitmap = BitmapFactory.decodeFile(mainPath) ?: return false
            overlayBitmap = BitmapFactory.decodeFile(overlayPath) ?: return false

            compositeBitmap = mainBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return false
            val canvas = Canvas(compositeBitmap)
            // The overlay is authored at the screen size it was captured on, so it is scaled
            // to the main image's bounds rather than drawn 1:1.
            canvas.drawBitmap(
                overlayBitmap,
                Rect(0, 0, overlayBitmap.width, overlayBitmap.height),
                Rect(0, 0, mainBitmap.width, mainBitmap.height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )

            val outputFile = File(outputPath)
            val compressed = outputFile.outputStream().use { out ->
                compositeBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }

            // compress() can report success having written nothing, and a zero-byte file that
            // replaces a real photo is worse than no combine at all.
            if (!compressed || !outputFile.exists() || outputFile.length() == 0L) {
                if (outputFile.exists()) outputFile.delete()
                return false
            }

            copyExifTags(mainPath, outputPath, onWarning)
            true
        } catch (e: Exception) {
            onWarning?.invoke("Could not combine overlay for ${File(mainPath).name}: ${e.message}")
            false
        } finally {
            // Explicit: these hold native heap that the GC is slow to reclaim, and the next
            // pair decodes its own full-resolution copies immediately after.
            mainBitmap?.recycle()
            overlayBitmap?.recycle()
            compositeBitmap?.recycle()
        }
    }

    /**
     * Carries the capture time and GPS from the source onto the combined file.
     *
     * A failure here is a warning, not an error: the pixels are correct and the file is
     * usable, it just sorts by its own mtime until a later metadata pass fixes it.
     */
    private fun copyExifTags(fromPath: String, toPath: String, onWarning: ((String) -> Unit)?) {
        try {
            val src = ExifInterface(fromPath)
            val dst = ExifInterface(toPath)
            for (tag in CARRIED_EXIF_TAGS) {
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
            dst.saveAttributes()
        } catch (e: Exception) {
            onWarning?.invoke("Combined ${File(toPath).name} but could not carry its metadata: ${e.message}")
        }
    }

    private companion object {
        const val JPEG_QUALITY = 95

        val CARRIED_EXIF_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        )
    }
}
