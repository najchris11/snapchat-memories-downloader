package com.najdev.snapvault.metadata

import cnames.structs.CGImage
import cnames.structs.__CFDictionary
import cnames.structs.__CFString
import cnames.structs.__CFURL
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGInterpolationHigh
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSDate
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationAddImageFromSource
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
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

// ImageIO's CGImageDestination is created with a single-image count and only frame 0 is
// copied across, so routing GIFs through writeImageMetadata would silently flatten every
// animated GIF to a still. Treat them like video: no destructive rewrite.
private val singleFrameUnsafeExtensions = setOf("gif")
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

// CGImageDestinationCreateWithURL's type param is a raw CFStringRef; a Kotlin String bridges
// to CFString, but the retain has to be balanced by the caller.
@OptIn(ExperimentalForeignApi::class)
private fun String.toCFString() = CFBridgingRetain(this)!!.reinterpret<__CFString>()

// Matches Android's JPEG_QUALITY. High enough that a re-encode of an already-lossy Snapchat
// JPEG is not visibly worse; below 1.0, which costs a lot of bytes for no visible gain.
private const val JPEG_COMPRESSION_QUALITY = 0.95

/**
 * The encoder to write [outputPath] with, or null if there is none.
 *
 * Keyed on the *output* extension rather than the source's CGImageSourceGetType, because
 * those differ by design: findOverlayPairNames renames formats ImageIO can read but not
 * write (HEIC, HEIF, WebP) to .jpg, so reusing the source's type would put HEIC bytes in a
 * file named .jpg. OverlayPairingTest pins the set of extensions that can reach here.
 */
private fun imageDestinationUti(outputPath: String): String? =
    when (outputPath.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "public.jpeg"
        "png" -> "public.png"
        "tif", "tiff" -> "public.tiff"
        else -> null
    }

@OptIn(ExperimentalForeignApi::class)
class IosMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = false
    override fun checkFFmpeg(): Boolean = false

    // GPS is only embeddable in image formats via ImageIO; video GPS tagging would need a
    // full AVAssetExportSession re-encode and isn't implemented yet. GIFs are skipped too—
    // ImageIO would flatten the animation just to add a GPS tag.
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        if (isVideo(filePath) || isSingleFrameUnsafe(filePath)) return false
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
        if (isVideo(filePath) || isSingleFrameUnsafe(filePath)) {
            return writeFileModificationDate(filePath, dateTimeUtc)
        }
        return writeImageMetadata(filePath) { properties -> applyExifDate(properties, dateTimeUtc) }
    }

    // Video overlay compositing needs an AVAssetExportSession re-encode with a
    // AVVideoCompositionCoreAnimationTool overlay layer; not implemented yet, and
    // IosZipPipelineRunner reports it to the user as skipped rather than failed.
    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean = false

    /**
     * Composites [overlayPath] onto [mainPath] with CoreGraphics, writing [outputPath].
     *
     * The iOS counterpart to AndroidMediaProcessor's android.graphics version, and it makes
     * the same two choices for the same reasons: the overlay is scaled to the main image's
     * bounds (it was authored at the capture device's screen size, not the photo's), and the
     * output is verified non-empty before it is allowed to stand, because an encoder can
     * report success having written nothing.
     *
     * Unlike desktop, metadata is not a second pass: the source's property dictionary is
     * handed to the destination in the same encode, so GPS and dates ride along without a
     * separate exiftool step that could fail independently.
     */
    override fun combineImageWithOverlay(
        mainPath: String,
        overlayPath: String,
        outputPath: String,
        onWarning: ((String) -> Unit)?,
    ): Boolean {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(mainPath) || !fileManager.fileExistsAtPath(overlayPath)) {
            return false
        }

        val uti = imageDestinationUti(outputPath)
        if (uti == null) {
            onWarning?.invoke("no image encoder for ${outputPath.substringAfterLast('.', "")}")
            return false
        }

        // Built beside the destination and moved into place only once it is verified, so a
        // crash mid-encode cannot leave a truncated file where MediaScanner indexes it as a
        // finished memory. Same reasoning as the desktop combiner's staging directory.
        val tmpPath = "$outputPath.tmp"
        fileManager.removeItemAtPath(tmpPath, error = null)

        var mainImage: CPointer<CGImage>? = null
        var overlayImage: CPointer<CGImage>? = null
        var composite: CPointer<CGImage>? = null

        return try {
            val main = loadImage(mainPath) ?: return false
            mainImage = main.image
            overlayImage = loadImage(overlayPath)?.image ?: return false

            composite = drawOverlay(mainImage, overlayImage) ?: return false
            if (!encode(composite, main.properties, uti, tmpPath)) return false

            // An encoder can finalize "successfully" having produced nothing, and a zero-byte
            // file that a later run treats as a finished combine is worse than no combine.
            val bytes = (fileManager.attributesOfItemAtPath(tmpPath, error = null)
                ?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            if (bytes <= 0L) {
                onWarning?.invoke("combined output was empty: ${outputPath.substringAfterLast('/')}")
                return false
            }

            fileManager.removeItemAtPath(outputPath, error = null)
            fileManager.moveItemAtPath(tmpPath, toPath = outputPath, error = null)
        } catch (e: Exception) {
            onWarning?.invoke("overlay combine failed: ${e.message}")
            false
        } finally {
            // CoreGraphics objects are not managed by Kotlin/Native's GC; three
            // full-resolution images are live at once here, so leaking them exhausts the
            // app's memory on exactly the large exports this is for.
            CGImageRelease(mainImage)
            CGImageRelease(overlayImage)
            CGImageRelease(composite)
            if (fileManager.fileExistsAtPath(tmpPath)) {
                fileManager.removeItemAtPath(tmpPath, error = null)
            }
        }
    }

    private class LoadedImage(val image: CPointer<CGImage>, val properties: NSDictionary?)

    private fun loadImage(path: String): LoadedImage? {
        val cfUrl = NSURL.fileURLWithPath(path).toCFURL()
        try {
            val source = CGImageSourceCreateWithURL(cfUrl, null) ?: return null
            try {
                val image = CGImageSourceCreateImageAtIndex(source, 0uL, null) ?: return null
                val properties = CGImageSourceCopyPropertiesAtIndex(source, 0uL, null)
                    ?.let { CFBridgingRelease(it) as? NSDictionary }
                return LoadedImage(image, properties)
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(cfUrl)
        }
    }

    private fun drawOverlay(
        mainImage: CPointer<CGImage>,
        overlayImage: CPointer<CGImage>,
    ): CPointer<CGImage>? {
        val width = CGImageGetWidth(mainImage)
        val height = CGImageGetHeight(mainImage)
        if (width == 0uL || height == 0uL) return null

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        try {
            val context = CGBitmapContextCreate(
                data = null,
                width = width,
                height = height,
                bitsPerComponent = 8uL,
                // 0 lets CoreGraphics pick the row stride, which it aligns for the hardware.
                bytesPerRow = 0uL,
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: return null
            try {
                val bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
                CGContextSetInterpolationQuality(context, kCGInterpolationHigh)
                CGContextDrawImage(context, bounds, mainImage)
                // Same rect: the overlay is scaled to the main image rather than drawn 1:1,
                // because it was authored at the capture device's screen size.
                CGContextDrawImage(context, bounds, overlayImage)
                return CGBitmapContextCreateImage(context)
            } finally {
                CGContextRelease(context)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    private fun encode(
        image: CPointer<CGImage>,
        sourceProperties: NSDictionary?,
        uti: String,
        destinationPath: String,
    ): Boolean {
        val cfUrl = NSURL.fileURLWithPath(destinationPath).toCFURL()
        val cfType = uti.toCFString()
        try {
            val destination = CGImageDestinationCreateWithURL(cfUrl, cfType, 1uL, null) ?: return false
            try {
                // The source's own dictionary, so GPS and dates survive the re-encode; the
                // compression quality is layered on top rather than replacing it.
                val properties = (sourceProperties?.mutableCopy() as? NSMutableDictionary)
                    ?: NSMutableDictionary()
                properties.setObject(
                    JPEG_COMPRESSION_QUALITY,
                    forKey = kCGImageDestinationLossyCompressionQuality.asKey(),
                )
                CGImageDestinationAddImage(destination, image, properties.toCFDictionary())
                return CGImageDestinationFinalize(destination)
            } finally {
                CFRelease(destination)
            }
        } finally {
            CFRelease(cfType)
            CFRelease(cfUrl)
        }
    }

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
                // replaceItemAtURL swaps the two files as a single atomic operation, so a failure
                // (or the app getting killed mid-write) leaves the original untouched — unlike a
                // separate remove-then-move, which can delete the original before the move lands.
                val replaced = fileManager.replaceItemAtURL(
                    originalItemURL = NSURL.fileURLWithPath(filePath),
                    withItemAtURL = NSURL.fileURLWithPath(tmpPath),
                    backupItemName = null,
                    options = 0uL,
                    resultingItemURL = null,
                    error = null
                )
                if (!replaced) {
                    fileManager.removeItemAtPath(tmpPath, error = null)
                }
                replaced
            } else {
                fileManager.removeItemAtPath(tmpPath, error = null)
                false
            }
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

    private fun isSingleFrameUnsafe(filePath: String): Boolean =
        filePath.substringAfterLast('.', "").lowercase() in singleFrameUnsafeExtensions
}
