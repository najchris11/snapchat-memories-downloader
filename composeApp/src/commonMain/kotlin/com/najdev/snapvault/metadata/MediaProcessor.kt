package com.najdev.snapvault.metadata

interface MediaProcessor {
    fun checkExifTool(): Boolean
    fun checkFFmpeg(): Boolean
    fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean //META writes GPS + optional date; used in legacy pipeline only
    fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean //META writes date to a single file; used by default batch fallback
    fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean

    // Returns the name of the hardware encoder that will be used for video encoding,
    // or null if software (libx264) will be used. Triggers detection on first call.
    fun activeVideoEncoder(): String? = null

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
