package com.najdev.snapvault.metadata

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTimeZone
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeZoneWithName

class IosMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = false
    override fun checkFFmpeg(): Boolean = false

    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        // Low-level ImageIO GPS dictionary write planned for Phase 3
        return false
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean {
        return try {
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.fileExistsAtPath(filePath)) return false

            val cleaned = dateTimeUtc.replace("UTC", "").trim()
            val formatter = NSDateFormatter().apply {
                dateFormat = "yyyy-MM-dd HH:mm:ss"
                timeZone = NSTimeZone.timeZoneWithName("UTC") ?: NSTimeZone.defaultTimeZone()
            }
            val nsDate = formatter.dateFromString(cleaned) ?: return false

            @Suppress("UNCHECKED_CAST")
            val attributes = mapOf(NSFileModificationDate to nsDate) as Map<Any?, *>
            fileManager.setAttributes(attributes, ofItemAtPath = filePath, error = null)
        } catch (e: Exception) {
            false
        }
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        return false
    }
}
