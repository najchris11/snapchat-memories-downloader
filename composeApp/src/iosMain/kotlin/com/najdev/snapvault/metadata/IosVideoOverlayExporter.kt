package com.najdev.snapvault.metadata

import objcnames.classes.CIImage as AVFoundationCIImage
import com.najdev.snapvault.downloader.VideoOverlayFiles
import com.najdev.snapvault.downloader.exportVideoOverlay
import com.najdev.snapvault.downloader.supportsNativeVideoOverlayContainer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.*
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreImage.CIImage
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.coroutines.resume

/** Re-encodes the original asset, keeping its audio tracks and orientation composition. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun exportIosVideoOverlay(main: String, overlay: String, output: String): Boolean {
    if (!supportsNativeVideoOverlayContainer(output)) return false
    val manager = NSFileManager.defaultManager
    val files = object : VideoOverlayFiles {
        override fun exists(path: String) = manager.fileExistsAtPath(path)
        override fun stage(output: String) = "$output.${NSUUID().UUIDString}.stage"
        override fun publish(stage: String, output: String) = manager.moveItemAtPath(stage, output, null)
        override fun discard(stage: String) { manager.removeItemAtPath(stage, null) }
    }
    return exportVideoOverlay(main, overlay, output, files) { source, sticker, stage ->
        val asset = AVURLAsset(NSURL.fileURLWithPath(source), null)
        if (asset.tracksWithMediaType(AVMediaTypeVideo).isEmpty()) return@exportVideoOverlay false
        val caption = CIImage.imageWithContentsOfURL(NSURL.fileURLWithPath(sticker)) ?: return@exportVideoOverlay false
        val captionWidth = caption.extent.useContents { size.width }
        val captionHeight = caption.extent.useContents { size.height }
        if (captionWidth <= 0.0 || captionHeight <= 0.0) return@exportVideoOverlay false
        // Core Animation's postprocessor trapped in IOSurface/XPC on the simulator.
        // Filter the oriented source frames directly, retaining the asset's audio tracks.
        val composition = AVVideoComposition.videoCompositionWithAsset(asset, applyingCIFiltersWithHandler = { request ->
            if (request != null) {
                val frame = request.sourceImage as CIImage
                val extent = frame.extent
                val scaled = caption.imageByApplyingTransform(CGAffineTransformMakeScale(
                    extent.useContents { size.width } / captionWidth,
                    extent.useContents { size.height } / captionHeight,
                )).imageByApplyingTransform(extent.useContents { CGAffineTransformMakeTranslation(origin.x, origin.y) })
                request.finishWithImage(scaled.imageByCompositingOverImage(frame).imageByCroppingToRect(extent) as AVFoundationCIImage, null)
            }
        })
        // AVAssetExportPresetHighestQuality re-encodes HLG/PQ as 8-bit H.264 tagged Rec.709, silently
        // discarding HDR. The HEVC preset keeps the 10-bit transfer function. There is deliberately no
        // fallback to the SDR preset: a session that cannot be created fails the export instead.
        val hdr = asset.tracksWithMediaType(AVMediaTypeVideo).any {
            (it as AVAssetTrack).hasMediaCharacteristic(AVMediaCharacteristicContainsHDRVideo)
        }
        val preset = if (hdr) AVAssetExportPresetHEVCHighestQuality else AVAssetExportPresetHighestQuality
        val exporter = AVAssetExportSession.exportSessionWithAsset(asset, preset) ?: return@exportVideoOverlay false
        val type = if (output.endsWith(".mov", ignoreCase = true)) AVFileTypeQuickTimeMovie else AVFileTypeMPEG4
        if (type !in exporter.supportedFileTypes) return@exportVideoOverlay false
        exporter.outputURL = NSURL.fileURLWithPath(stage)
        exporter.outputFileType = type
        exporter.videoComposition = composition
        exporter.metadata = asset.metadata
        val completed = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { exporter.cancelExport() }
                if (continuation.isActive) {
                    try {
                        exporter.exportAsynchronouslyWithCompletionHandler {
                            completed.complete(Unit)
                            if (continuation.isActive) continuation.resume(exporter.status == AVAssetExportSessionStatusCompleted)
                        }
                    } catch (e: Exception) {
                        completed.complete(Unit)
                        throw e
                    }
                } else completed.complete(Unit)
            }
        } finally {
            // Wait for the encoder to close its output before the outer staging cleanup.
            withContext(NonCancellable) {
                exporter.cancelExport()
                completed.await()
            }
        }
    }
}
