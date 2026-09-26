package com.najdev.snapvault.downloader

/** What happened to one main/overlay pair. */
enum class OverlayCombineStatus {
    /** Never attempted: the platform cannot composite this kind of media. */
    SkippedVideo,

    /**
     * Never attempted: the main file holds more than one frame, and compositing it would
     * keep only the first. Refusing costs the user a burned-in overlay on one GIF; the
     * alternative cost them every frame after the first, permanently.
     */
    SkippedAnimated,

    /** Attempted and did not produce a usable output. */
    Failed,

    /** The combined file is on disk. */
    Combined,
}

// The marker copyExifTags uses when the pixels landed but the tags did not. Matched rather
// than passed as a flag so the warning the user reads and the metadataCarried the Library
// reads cannot disagree.
internal const val METADATA_NOT_CARRIED_MARKER = "could not carry its metadata"

/**
 * Whether the originals behind a pair may be deleted.
 *
 * The consequential decision in the combine step: only a confirmed [OverlayCombineStatus.Combined]
 * has produced a second copy of the pixels, so it is the only status under which deleting the
 * main file is anything other than data loss. A skipped video was never composited at all.
 */
fun mayDeleteOriginals(status: OverlayCombineStatus, deleteRequested: Boolean): Boolean =
    deleteRequested && status == OverlayCombineStatus.Combined

/**
 * Builds the result for one pair.
 *
 * Common code rather than inline in the Android runner because these decisions have
 * consequences the UI acts on — [CombineResult.status] badges the Library, and
 * [CombineResult.metadataCarried] decides whether the output may claim its source's GPS — and
 * androidMain has no test source set of its own.
 */
fun overlayCombineResult(
    pair: OverlayPairNames,
    mainPath: String,
    overlayPath: String,
    outputPath: String,
    status: OverlayCombineStatus,
    warnings: List<String>,
): CombineResult {
    // "2017-07-13_abc" → "abc"; a stem with no date prefix is its own id.
    val uuid = pair.stem.substringAfter("_", pair.stem)
    val sources = listOf(mainPath, overlayPath)

    return when (status) {
        OverlayCombineStatus.SkippedVideo -> CombineResult(
            uuid = uuid,
            // Points at the original: nothing new was written.
            outputPath = mainPath,
            status = "skipped: video overlays are not combined on this platform yet",
            warnings = warnings + "${pair.mainName}: video overlay combining is not available on this platform yet",
            sourcePaths = sources,
        )

        OverlayCombineStatus.SkippedAnimated -> CombineResult(
            uuid = uuid,
            // Points at the original: nothing new was written, and the original is the only
            // thing holding frames two onward.
            outputPath = mainPath,
            status = "skipped: animated images are not combined",
            warnings = warnings +
                "${pair.mainName}: combining would keep only the first frame, so the animation was left as it is",
            sourcePaths = sources,
        )

        OverlayCombineStatus.Failed -> CombineResult(
            uuid = uuid,
            outputPath = mainPath,
            status = "error: could not combine the overlay",
            warnings = warnings + "${pair.mainName}: overlay combine failed; the original is untouched",
            sourcePaths = sources,
        )

        OverlayCombineStatus.Combined -> CombineResult(
            uuid = uuid,
            outputPath = outputPath,
            status = "combined",
            warnings = warnings,
            sourcePaths = sources,
            metadataCarried = warnings.none { METADATA_NOT_CARRIED_MARKER in it },
        )
    }
}
