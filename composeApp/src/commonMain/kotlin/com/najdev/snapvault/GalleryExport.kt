package com.najdev.snapvault

enum class GalleryExportResult { Saved, Failed, Unsupported }

expect val supportsGalleryExport: Boolean

/** Copies a library item to the system gallery only after an explicit user action. */
expect suspend fun publishToGallery(path: String): GalleryExportResult
