package com.najdev.snapvault.metadata

import com.najdev.snapvault.util.DateUtil
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.dateWithTimeIntervalSince1970

class IosMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = false
    override fun checkFFmpeg(): Boolean = false

    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        return false
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean {
        return try {
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.fileExistsAtPath(filePath)) return false

            val epochMillis = DateUtil.toEpochMillis(dateTimeUtc) ?: return false
            val nsDate = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

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
