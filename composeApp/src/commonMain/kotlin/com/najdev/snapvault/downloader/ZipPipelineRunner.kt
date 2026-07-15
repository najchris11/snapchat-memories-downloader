package com.najdev.snapvault.downloader

import com.najdev.snapvault.parser.HtmlMemoryEntry

data class ExtractResult(
    val uuid: String,
    val fileName: String,
    val outputPath: String,
    val skipped: Boolean,
    val error: String?
)

data class CombineResult(
    val uuid: String,
    val outputPath: String,
    val status: String,
    // Non-fatal issues hit while processing this pair (e.g. could not delete an original).
    // Delivered with the result so the consumer handles them on a single thread.
    val warnings: List<String> = emptyList(),
)

interface ZipPipelineRunner {
    fun listZipFiles(folderPath: String): List<String>

    suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    )

    suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit = {},
        onMetaStart: (total: Int) -> Unit = {},
        onMetaError: ((String) -> Unit)? = null,
        onProgress: (CombineResult) -> Unit
    )
}

object NoOpZipPipelineRunner : ZipPipelineRunner {
    override fun listZipFiles(folderPath: String): List<String> = emptyList()

    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    ) = Unit

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit
    ) = Unit
}
