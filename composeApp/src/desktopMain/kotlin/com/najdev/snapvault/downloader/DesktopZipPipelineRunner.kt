package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor

class DesktopZipPipelineRunner(mediaProcessor: MediaProcessor) : JvmZipPipelineRunner() {
    private val combiner = OverlayCombiner(mediaProcessor)

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit
    ) = combiner.combineAll(outputDir, deleteOriginals, workerCount, onStart, onMetaStart, onMetaError, onProgress)
}
