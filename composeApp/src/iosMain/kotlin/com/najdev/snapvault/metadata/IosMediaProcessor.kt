package com.najdev.snapvault.metadata

class IosMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = false
    override fun checkFFmpeg(): Boolean = false

    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        // TODO: Implement using PHAsset/PHPhotoLibrary or low-level AVFoundation
        return false
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        // TODO: Implement using AVFoundation
        return false
    }
}
