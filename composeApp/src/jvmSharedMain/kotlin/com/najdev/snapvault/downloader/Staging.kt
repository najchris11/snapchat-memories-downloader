package com.najdev.snapvault.downloader

import java.io.File

/**
 * Work in progress lives here, inside the destination directory, never beside the user's
 * finished files.
 *
 * Two separate failures came from not having this. Extraction cleaned up by deleting every
 * `*.part` in the destination, which is a guess about ownership that a browser's in-flight
 * download loses (D03). Combination wrote straight to the final output path, so a killed
 * ffmpeg left a truncated file exactly where the library scans for finished media, and a
 * re-import overwrote a combined file the user had since edited (D02).
 *
 * Dot-prefixed, and MediaScanner's listing is non-recursive, so it stays out of the library
 * the same way `.thumbnails` does.
 */
internal const val STAGING_DIR_NAME = ".snapvault-staging"

/**
 * Opens the staging directory, clearing anything a previous run left behind. Leftovers here
 * are unambiguously ours — nothing else writes to this directory — which is the whole point:
 * cleanup never has to infer ownership from a filename.
 */
internal fun openStaging(outDir: File): File {
    val staging = File(outDir, STAGING_DIR_NAME).also { it.mkdirs() }
    staging.listFiles()?.forEach { if (it.isFile) it.delete() }
    return staging
}

/** Removes the staging directory if every staged file made it into place. */
internal fun closeStaging(staging: File) {
    staging.delete() // no-op while anything is still in there
}
