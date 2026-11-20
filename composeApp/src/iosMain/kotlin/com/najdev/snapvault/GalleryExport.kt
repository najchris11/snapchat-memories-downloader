package com.najdev.snapvault

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

actual val supportsGalleryExport: Boolean = true

@OptIn(ExperimentalForeignApi::class)
actual suspend fun publishToGallery(path: String): GalleryExportResult = PhotoLibraryPublication.publish(
    path,
    NSFileManager.defaultManager.fileExistsAtPath(path),
    authorize = {
        suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                if (continuation.isActive) continuation.resume(status == PHAuthorizationStatusAuthorized)
            }
        }
    },
    copy = { source ->
        // File URL requests retain embedded metadata and never remove the library original.
        suspendCancellableCoroutine { continuation ->
            var requestCreated = false
            PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                if (continuation.isActive) {
                    val url = NSURL.fileURLWithPath(source)
                    val request = if (source.substringAfterLast('.').lowercase() in setOf("mp4", "mov", "m4v")) {
                        PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url)
                    } else {
                        PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(url)
                    }
                    requestCreated = request != null
                }
            }, completionHandler = { success, _ ->
                if (continuation.isActive) continuation.resume(success && requestCreated)
            })
        }
    },
)
