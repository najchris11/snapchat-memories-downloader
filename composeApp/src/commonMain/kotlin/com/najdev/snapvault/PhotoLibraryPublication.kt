package com.najdev.snapvault

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Add-only platform boundary; authorization and copying occur only during this explicit operation. */
object PhotoLibraryPublication {
    suspend fun publish(
        path: String,
        fileExists: Boolean,
        authorize: suspend () -> Boolean,
        copy: suspend (String) -> Boolean,
    ): GalleryExportResult {
        if (!fileExists) return GalleryExportResult.Failed
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "mp4", "mov", "m4v")) {
            return GalleryExportResult.Unsupported
        }
        if (!authorize()) return GalleryExportResult.Failed
        currentCoroutineContext().ensureActive()
        return if (copy(path)) GalleryExportResult.Saved else GalleryExportResult.Failed
    }
}
