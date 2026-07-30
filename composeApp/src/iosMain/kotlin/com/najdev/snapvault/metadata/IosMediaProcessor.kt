package com.najdev.snapvault.metadata

import cnames.structs.__CFDictionary
import cnames.structs.__CFURL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFStringRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSDate
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImageFromSource
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.kCGImagePropertyExifDateTimeDigitized
import platform.ImageIO.kCGImagePropertyExifDateTimeOriginal
import platform.ImageIO.kCGImagePropertyExifDictionary
import platform.ImageIO.kCGImagePropertyGPSDictionary
import platform.ImageIO.kCGImagePropertyGPSLatitude
import platform.ImageIO.kCGImagePropertyGPSLatitudeRef
import platform.ImageIO.kCGImagePropertyGPSLongitude
import platform.ImageIO.kCGImagePropertyGPSLongitudeRef
import platform.ImageIO.kCGImagePropertyTIFFDateTime
import platform.ImageIO.kCGImagePropertyTIFFDictionary
import kotlin.math.abs

private val videoExtensions = setOf("mp4", "mov")
private val dateRegex = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")

// Seconds between the Unix epoch (1970-01-01) and NSDate's reference date (2001-01-01),
// needed because this Kotlin/Native Foundation binding only exposes the reference-date NSDate
// constructor, not a timeIntervalSince1970 one.
private const val REFERENCE_DATE_OFFSET_SECONDS = 978307200.0

// ImageIO's kCGImageProperty* constants are plain CFStringRef C globals, not bridged NSString
// at the Kotlin type level, so dictionary keys need an explicit CF -> NS bridge.
@OptIn(ExperimentalForeignApi::class)
private fun CFStringRef?.asKey(): NSString = CFBridgingRelease(this) as NSString

// CGImageSource/DestinationCreateWithURL take a raw CFURLRef, not the bridged NSURL Kotlin sees.
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.toCFURL() = CFBridgingRetain(this)!!.reinterpret<__CFURL>()

// CGImageDestinationAddImageFromSource's properties param is a raw CFDictionaryRef too.
@OptIn(ExperimentalForeignApi::class)
private fun NSDictionary.toCFDictionary() = CFBridgingRetain(this)!!.reinterpret<__CFDictionary>()

@OptIn(ExperimentalForeignApi::class)
class IosMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = false
    override fun checkFFmpeg(): Boolean = false

    // GPS is only embeddable in image formats via ImageIO; video GPS tagging would need a
    // full AVAssetExportSession re-encode and isn't implemented yet.
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        if (isVideo(filePath)) return false
        return writeImageMetadata(filePath) { properties ->
            val gps = NSMutableDictionary()
            gps.setObject(if (latitude >= 0) "N" else "S", forKey = kCGImagePropertyGPSLatitudeRef.asKey())
            gps.setObject(abs(latitude), forKey = kCGImagePropertyGPSLatitude.asKey())
            gps.setObject(if (longitude >= 0) "E" else "W", forKey = kCGImagePropertyGPSLongitudeRef.asKey())
            gps.setObject(abs(longitude), forKey = kCGImagePropertyGPSLongitude.asKey())
            properties.setObject(gps, forKey = kCGImagePropertyGPSDictionary.asKey())
            dateStr?.let { applyExifDate(properties, it) }
        }
    }

    override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean {
        if (isVideo(filePath)) return writeFileModificationDate(filePath, dateTimeUtc)
        return writeImageMetadata(filePath) { properties -> applyExifDate(properties, dateTimeUtc) }
    }

    // Image and video overlay compositing (CoreGraphics / AVFoundation) is a larger follow-up;
    // IosZipPipelineRunner.combineAll already reports this as not-yet-available to the user.
    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean = false

    private fun applyExifDate(properties: NSMutableDictionary, dateTimeUtc: String) {
        val exifDate = toExifDateString(dateTimeUtc) ?: return

        val exif = NSMutableDictionary()
        exif.setObject(exifDate, forKey = kCGImagePropertyExifDateTimeOriginal.asKey())
        exif.setObject(exifDate, forKey = kCGImagePropertyExifDateTimeDigitized.asKey())
        properties.setObject(exif, forKey = kCGImagePropertyExifDictionary.asKey())

        val tiff = NSMutableDictionary()
        tiff.setObject(exifDate, forKey = kCGImagePropertyTIFFDateTime.asKey())
        properties.setObject(tiff, forKey = kCGImagePropertyTIFFDictionary.asKey())
    }

    private fun writeImageMetadata(filePath: String, mutate: (NSMutableDictionary) -> Unit): Boolean {
        val fileManager = NSFileManager.defaultManager
        val tmpPath = "$filePath.tmp"
        return try {
            val source = CGImageSourceCreateWithURL(NSURL.fileURLWithPath(filePath).toCFURL(), null) ?: return false
            val type = CGImageSourceGetType(source) ?: return false
            val originalProps = CGImageSourceCopyPropertiesAtIndex(source, 0uL, null)
                ?.let { CFBridgingRelease(it) as? NSDictionary }
            val properties = (originalProps?.mutableCopy() as? NSMutableDictionary) ?: NSMutableDictionary()
            mutate(properties)

            val destination = CGImageDestinationCreateWithURL(NSURL.fileURLWithPath(tmpPath).toCFURL(), type, 1uL, null)
                ?: return false
            CGImageDestinationAddImageFromSource(destination, source, 0uL, properties.toCFDictionary())
            val ok = CGImageDestinationFinalize(destination)
            if (ok) {
                fileManager.removeItemAtPath(filePath, error = null)
                fileManager.moveItemAtPath(tmpPath, toPath = filePath, error = null)
            } else {
                fileManager.removeItemAtPath(tmpPath, error = null)
            }
            ok
        } catch (e: Exception) {
            fileManager.removeItemAtPath(tmpPath, error = null)
            false
        }
    }

    private fun writeFileModificationDate(filePath: String, dateTimeUtc: String): Boolean {
        val epochSeconds = parseUtcToEpochSeconds(dateTimeUtc) ?: return false
        val date = NSDate(timeIntervalSinceReferenceDate = epochSeconds - REFERENCE_DATE_OFFSET_SECONDS)
        return try {
            NSFileManager.defaultManager.setAttributes(
                mapOf(NSFileModificationDate to date),
                ofItemAtPath = filePath,
                error = null
            )
        } catch (e: Exception) {
            false
        }
    }

    private fun toExifDateString(dateTimeUtc: String): String? {
        val cleaned = dateTimeUtc.removeSuffix(" UTC").trim()
        val g = dateRegex.find(cleaned)?.groupValues ?: return null
        return "${g[1]}:${g[2]}:${g[3]} ${g[4]}:${g[5]}:${g[6]}"
    }

    private fun parseUtcToEpochSeconds(dateTimeUtc: String): Double? {
        val cleaned = dateTimeUtc.removeSuffix(" UTC").trim()
        val g = dateRegex.find(cleaned)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        val days = daysFromCivil(g[0], g[1], g[2])
        return (days * 86400L + g[3] * 3600L + g[4] * 60L + g[5]).toDouble()
    }

    // Howard Hinnant's days_from_civil: proleptic-Gregorian days since 1970-01-01 (UTC).
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = (if (m <= 2) y - 1 else y).toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153L * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    private fun isVideo(filePath: String): Boolean =
        filePath.substringAfterLast('.', "").lowercase() in videoExtensions
}
