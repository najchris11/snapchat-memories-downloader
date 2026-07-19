package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import java.io.File

class AndroidZipPipelineRunner(
    private val mediaProcessor: MediaProcessor
) : JvmZipPipelineRunner() {

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
        val allFiles = dir.listFiles() ?: emptyArray()

        val mainFiles = allFiles.filter { "-main." in it.name }
        onStart(mainFiles.size)

        for (mainFile in mainFiles) {
            val stem = mainFile.name.substringBefore("-main.")
            val uuid = stem.substringAfter("_", stem)
            onProgress(
                CombineResult(
                    uuid = uuid,
                    outputPath = mainFile.absolutePath,
                    status = "skipped",
                    warnings = listOf("Overlay combine on Android will be added in Phase 2.5")
                )
            )
        }
    }
}
