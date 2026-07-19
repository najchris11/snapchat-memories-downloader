package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor

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
        // Overlay combine for Android will be implemented in Phase 2.5.
        onStart(0)
    }
}
