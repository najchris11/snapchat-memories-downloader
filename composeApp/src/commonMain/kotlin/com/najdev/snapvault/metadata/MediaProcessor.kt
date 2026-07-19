package com.najdev.snapvault.metadata

// How many videos in the current run were encoded with the hardware encoder vs the
// libx264 software fallback. Used for the end-of-combine UI summary.
data class VideoEncodeStats(val hardware: Int, val software: Int)

interface MediaProcessor {
    fun checkExifTool(): Boolean
    fun checkFFmpeg(): Boolean
    fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean //META writes GPS + optional date; used in legacy pipeline only
    fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean //META writes date to a single file; used by default batch fallback
    fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean
    fun combineImageWithOverlay(mainPath: String, overlayPath: String, outputPath: String): Boolean = false

    // Name of the hardware encoder that passed the runtime probe (e.g. "h264_vaapi"),
    // or null when videos will be software-encoded. First call triggers detection.
    fun activeVideoEncoder(): String? = null

    // Per-run encode counters; null on platforms that don't combine videos.
    fun videoEncodeStats(): VideoEncodeStats? = null
    fun resetVideoEncodeStats() {}

    // Batch variant: write the same YYYY-MM-DD date to every path in one shot.
    // Default implementation falls back to per-file calls (works on Android/iOS without exiftool).
    // Desktop overrides this with a single exiftool invocation per media type group.
    // onError receives a human-readable message for each file that could not be tagged.
    fun writeDateMetadataBatch( //META primary date write path for ZIP pipeline; date-only (no time, no GPS)
        filePaths: List<String>,
        dateOnly: String,
        onError: ((String) -> Unit)? = null,
    ): Int = filePaths.count { path ->
        val ok = writeDateMetadata(path, "$dateOnly 00:00:00 UTC")
        if (!ok) onError?.invoke("writeDateMetadata failed: ${path.substringAfterLast('/')}")
        ok
    }
}
