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
    // The pair this output was built from, main first. The index is keyed by file name, so
    // without these the combined file could not inherit its source's entry (D11).
    val sourcePaths: List<String> = emptyList(),
    // False when the source's tags did not make it onto the output. GPS is a claim about the
    // file's own tags, so the combined file must not inherit it in that case.
    val metadataCarried: Boolean = true,
)

/** What a ZIP import would write, against the space there is to write it in. */
data class ExtractionBudget(
    val requiredBytes: Long,
    val availableBytes: Long,
    // Per archive, for the low-space plan: an import that does not fit as a whole may still fit
    // one archive at a time, if each is deleted as its contents land (D20).
    val archives: List<ArchiveSpace> = emptyList(),
)

interface ZipPipelineRunner {
    fun listZipFiles(folderPath: String): List<String>

    // Null where the platform cannot tell; the import then proceeds as it always did.
    fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String): ExtractionBudget? = null

    /**
     * What is wrong with the files [entries] should have produced in [outputDir], if anything:
     * each is checked against the archive for presence and size. An empty list means the
     * archive's contents are all on disk, which is the only condition under which the archive
     * itself may be deleted (D20).
     */
    fun verifyExtraction(
        zipPath: String,
        entries: List<HtmlMemoryEntry>,
        outputDir: String,
    ): List<String> = listOf("this platform cannot verify an extraction")

    suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    )

    // Legacy pipeline: memories with overlays download as small .zip archives (media +
    // overlay PNG). Extracts each archive in `archivePaths` flat as <base>-main.<ext> /
    // <base>-overlay.<ext> — the same naming combineAll's pair discovery uses — and
    // deletes an archive once every one of its entries is accounted for. Returns the
    // extracted file paths.
    //
    // The caller passes the archives this run downloaded. It must not list outputDir:
    // an unrelated ZIP the user happens to keep there is not ours to flatten or delete.
    suspend fun extractDownloadedArchives(
        outputDir: String,
        archivePaths: List<String>,
        onWarn: (String) -> Unit = {},
    ): List<String> = emptyList()

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
