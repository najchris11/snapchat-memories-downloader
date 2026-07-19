package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import java.io.File

class AndroidZipPipelineRunner(
    private val mediaProcessor: MediaProcessor
) : JvmZipPipelineRunner() {

    private data class OverlayPair(
        val mainFile: File,
        val overlayFile: File,
        val outputFile: File,
        val isVideo: Boolean
    )

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit
    ) {
        val dir = File(outputDir)
        if (!dir.exists() || !dir.isDirectory) {
            onStart(0)
            return
        }

        val allFiles = dir.listFiles() ?: emptyArray()

        val overlayByStem = allFiles
            .filter { "-overlay." in it.name }
            .mapNotNull { f ->
                val stem = f.name.substringBefore("-overlay.").takeIf { it != f.name && it.isNotEmpty() }
                stem?.let { it to f }
            }
            .toMap()

        val pairs = allFiles
            .filter { "-main." in it.name }
            .mapNotNull { mainFile ->
                val stem = mainFile.name.substringBefore("-main.")
                    .takeIf { it != mainFile.name && it.isNotEmpty() } ?: return@mapNotNull null
                val overlayFile = overlayByStem[stem] ?: return@mapNotNull null
                val extLc = mainFile.extension.lowercase()
                val isVideo = extLc in setOf("mp4", "mov", "avi", "mkv")
                val ext = if (isVideo) extLc else "jpg"
                val outputFile = File(dir, "$stem.$ext")
                OverlayPair(mainFile, overlayFile, outputFile, isVideo)
            }

        onStart(pairs.size)

        for (pair in pairs) {
            val stem = pair.mainFile.name.substringBefore("-main.")
            val uuid = stem.substringAfter("_", stem)

            if (pair.isVideo) {
                onProgress(
                    CombineResult(
                        uuid = uuid,
                        outputPath = pair.mainFile.absolutePath,
                        status = "skipped: Video overlay combine on Android will land in Media3 update",
                        warnings = listOf("Video overlay combine on Android will land in Media3 update")
                    )
                )
            } else {
                val ok = mediaProcessor.combineImageWithOverlay(
                    pair.mainFile.absolutePath,
                    pair.overlayFile.absolutePath,
                    pair.outputFile.absolutePath
                )

                if (ok) {
                    if (deleteOriginals) {
                        pair.mainFile.delete()
                        pair.overlayFile.delete()
                    }
                    onProgress(
                        CombineResult(
                            uuid = uuid,
                            outputPath = pair.outputFile.absolutePath,
                            status = "combined",
                            warnings = emptyList()
                        )
                    )
                } else {
                    onProgress(
                        CombineResult(
                            uuid = uuid,
                            outputPath = pair.mainFile.absolutePath,
                            status = "error: Image overlay combination failed",
                            warnings = listOf("Failed to combine overlay for ${pair.mainFile.name}")
                        )
                    )
                }
            }
        }
    }
}
