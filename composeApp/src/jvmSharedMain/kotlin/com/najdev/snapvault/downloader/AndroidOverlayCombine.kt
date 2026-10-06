package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

internal fun combineAndroidOverlayPair(
    dir: File,
    names: OverlayPairNames,
    mainFile: File,
    overlayFile: File,
    deleteOriginals: Boolean,
    mediaProcessor: MediaProcessor,
): CombineResult {
    val outputFile = File(dir, names.outputName)
    val warnings = mutableListOf<String>()
    val status = overlayCombineSkipStatus(names, outputExists = outputFile.exists()) ?: run {
        val staging = openStaging(dir)
        val staged = File(staging, names.outputName)
        try {
            val combined = mediaProcessor.combineImageWithOverlay(
                mainFile.absolutePath,
                overlayFile.absolutePath,
                staged.absolutePath,
                onWarning = { warnings += it },
            )
            when {
                !combined || !staged.isFile || staged.length() == 0L -> OverlayCombineStatus.Failed
                outputFile.exists() -> OverlayCombineStatus.SkippedExistingOutput
                staged.renameTo(outputFile) -> OverlayCombineStatus.Combined
                else -> OverlayCombineStatus.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "Could not combine overlay for ${mainFile.name}: ${e.message}"
            OverlayCombineStatus.Failed
        } finally {
            staged.delete()
            closeStaging(staging)
        }
    }

    if (mayDeleteOriginals(status, deleteOriginals)) {
        if (!mainFile.delete()) warnings += "could not delete original: ${mainFile.name}"
        if (!overlayFile.delete()) warnings += "could not delete overlay: ${overlayFile.name}"
    }

    return overlayCombineResult(
        pair = names,
        mainPath = mainFile.absolutePath,
        overlayPath = overlayFile.absolutePath,
        outputPath = outputFile.absolutePath,
        status = status,
        warnings = warnings,
    )
}
