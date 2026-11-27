package com.najdev.snapvault

sealed interface VideoStitchResult {
    data class Success(val outputPath: String) : VideoStitchResult
    data object InvalidSelection : VideoStitchResult
    data object ToolsMissing : VideoStitchResult
    data object Incompatible : VideoStitchResult
    data object Failed : VideoStitchResult
    data object Unsupported : VideoStitchResult
}

expect val supportsVideoStitching: Boolean

/** Explicitly ordered clips only. Creates a new file and always retains the source clips. */
expect suspend fun stitchVideos(paths: List<String>, outputDir: String): VideoStitchResult
