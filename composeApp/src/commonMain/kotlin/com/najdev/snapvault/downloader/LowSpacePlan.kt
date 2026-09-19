package com.najdev.snapvault.downloader

/** One source archive of a ZIP import: what extracting it writes, and what deleting it frees. */
data class ArchiveSpace(
    val path: String,
    val requiredBytes: Long,
    val archiveBytes: Long,
    // Deleting an archive on another drive frees nothing where the library is written.
    val onOutputVolume: Boolean,
)

/**
 * Whether an import fits when each archive is deleted as soon as its contents are imported, and
 * in which order to do it (D20).
 *
 * Snapchat media is already compressed, so an archive's contents cost about what the archive
 * itself occupies. Deleting each one as it finishes keeps the total roughly level rather than
 * doubling it, and what the import needs at any moment is room for one archive plus the
 * processing reserve.
 */
data class LowSpacePlan(
    val fits: Boolean,
    val reclaimableBytes: Long,
    // In the order to process them: smallest first, because the tightest moment is the first
    // archive, before anything has been deleted.
    val archives: List<ArchiveSpace>,
)

fun planLowSpaceImport(
    archives: List<ArchiveSpace>,
    availableBytes: Long,
    reserveBytes: Long,
): LowSpacePlan {
    val order = archives.sortedBy { it.requiredBytes }
    val reclaimable = archives.filter { it.onOutputVolume }.sumOf { it.archiveBytes }

    var free = availableBytes
    var fits = archives.isNotEmpty() && archives.all { it.onOutputVolume }
    if (fits) {
        for (archive in order) {
            if (archive.requiredBytes + reserveBytes > free) {
                fits = false
                break
            }
            free -= archive.requiredBytes
            free += archive.archiveBytes
        }
    }

    return LowSpacePlan(fits = fits, reclaimableBytes = reclaimable, archives = order)
}
