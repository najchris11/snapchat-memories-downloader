package com.najdev.snapvault.metadata

interface MediaProcessor {
    fun checkExifTool(): Boolean
    fun checkFFmpeg(): Boolean
    fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean
    fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean
    fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean
}
