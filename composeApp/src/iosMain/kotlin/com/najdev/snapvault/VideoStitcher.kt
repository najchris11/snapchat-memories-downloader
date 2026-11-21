package com.najdev.snapvault

actual val supportsVideoStitching: Boolean = false
actual suspend fun stitchVideos(paths: List<String>, outputDir: String): VideoStitchResult = VideoStitchResult.Unsupported
