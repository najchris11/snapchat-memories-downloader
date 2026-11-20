package com.najdev.snapvault

actual val supportsGalleryExport: Boolean = false
actual suspend fun publishToGallery(path: String): GalleryExportResult = GalleryExportResult.Unsupported
