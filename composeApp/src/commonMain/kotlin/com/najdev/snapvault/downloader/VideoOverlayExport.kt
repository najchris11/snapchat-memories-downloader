package com.najdev.snapvault.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Filesystem boundary; publish must atomically refuse an existing destination. */
interface VideoOverlayFiles {
    fun exists(path: String): Boolean
    fun stage(output: String): String
    fun publish(stage: String, output: String): Boolean
    fun discard(stage: String)
}

suspend fun exportVideoOverlay(
    main: String,
    overlay: String,
    output: String,
    files: VideoOverlayFiles,
    encode: suspend (String, String, String) -> Boolean,
): Boolean {
    if (!files.exists(main) || !files.exists(overlay) || files.exists(output)) return false
    val stage = files.stage(output)
    return try {
        if (!encode(main, overlay, stage)) return false
        currentCoroutineContext().ensureActive()
        files.publish(stage, output)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    } finally {
        files.discard(stage)
    }
}

/** Containers the AVFoundation exporter can publish without disguising MPEG-4 bytes. */
fun supportsNativeVideoOverlayContainer(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v")

/** Containers an MP4-only muxer (Media3 on Android) can publish without disguising its bytes. */
fun supportsMp4VideoOverlayContainer(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v")

/**
 * Attached to every combined video. It contains [METADATA_NOT_CARRIED_MARKER], so the result
 * reports metadataCarried = false and the Library does not claim the source's GPS or date.
 */
const val VIDEO_METADATA_NOT_VERIFIED_WARNING =
    "Video export could not carry its metadata with verified fidelity; originals retained"
