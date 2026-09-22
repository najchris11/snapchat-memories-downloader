package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.SupportedMediaExtensions

/**
 * One main/overlay pair, named rather than resolved to files.
 *
 * Names only, so the matching can live in common code and be tested without a filesystem:
 * each platform holds its own file type (java.io.File, okio.Path, NSURL) and resolves these
 * against its own directory.
 */
data class OverlayPairNames(
    val stem: String,
    val mainName: String,
    val overlayName: String,
    /** What the combined file should be called, extension included. */
    val outputName: String,
    val isVideo: Boolean,
)

// Formats the image encoders on both platforms can read but not write back. The overlay
// combine falls back to JPEG for these, so the output name has to say so upfront or the
// file lands with an extension that lies about its contents.
private val READ_ONLY_IMAGE_FORMATS = setOf("heic", "heif", "webp")

private const val MAIN_MARKER = "-main."
private const val OVERLAY_MARKER = "-overlay."

private fun String.extensionOrEmpty(): String =
    substringAfterLast('.', "").takeIf { it != this } ?: ""

/**
 * Finds the main/overlay pairs among [fileNames].
 *
 * Stem-based: "2017-07-13_UUID" is the stem shared by the main and overlay file for one
 * memory. No UUID parsing — this is physical name matching, which handles duplicate UUIDs
 * for free (different date prefixes give different stems) and survives filename format
 * changes. A main with no overlay, or an overlay with no main, is not a pair.
 *
 * Extracted from the desktop OverlayCombiner once the Android runner grew a second copy that
 * had already drifted: its hand-maintained video list omitted "m4v", so an .m4v pair went
 * down the image-compositing path. Video classification now goes through
 * [SupportedMediaExtensions] like every other caller (BUG-18).
 */
fun findOverlayPairNames(fileNames: List<String>): List<OverlayPairNames> {
    val overlayByStem = fileNames
        .filter { OVERLAY_MARKER in it }
        .mapNotNull { name ->
            name.substringBefore(OVERLAY_MARKER)
                .takeIf { it != name && it.isNotEmpty() }
                ?.let { it to name }
        }
        .toMap()

    return fileNames
        .filter { MAIN_MARKER in it }
        .mapNotNull { mainName ->
            val stem = mainName.substringBefore(MAIN_MARKER)
                .takeIf { it != mainName && it.isNotEmpty() } ?: return@mapNotNull null
            val overlayName = overlayByStem[stem] ?: return@mapNotNull null
            val extension = mainName.extensionOrEmpty()
            val extLc = extension.lowercase()
            val isVideo = extLc in SupportedMediaExtensions.VIDEO
            val outputExt = if (!isVideo && extLc in READ_ONLY_IMAGE_FORMATS) "jpg" else extension
            OverlayPairNames(
                stem = stem,
                mainName = mainName,
                overlayName = overlayName,
                outputName = "$stem.$outputExt",
                isVideo = isVideo,
            )
        }
}
