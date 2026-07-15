package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry

class DesktopZipPipelineRunner(mediaProcessor: MediaProcessor) : ZipPipelineRunner {
    private val extractor = ZipExtractEngine()
    private val combiner = OverlayCombiner(mediaProcessor)

    override fun listZipFiles(folderPath: String): List<String> {
        val numberedSuffixRegex = Regex("""-(\d+)\.zip$""")
        val zipSorter = Comparator<java.io.File> { a, b ->
            val numA = numberedSuffixRegex.find(a.name)?.groupValues?.get(1)?.toIntOrNull()
            val numB = numberedSuffixRegex.find(b.name)?.groupValues?.get(1)?.toIntOrNull()
            when {
                numA == null && numB == null -> a.name.compareTo(b.name)
                numA == null -> -1
                numB == null -> 1
                else -> numA.compareTo(numB)
            }
        }
        return java.io.File(folderPath)
            .listFiles { f -> f.extension.lowercase() == "zip" }
            ?.sortedWith(zipSorter)
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    ) = extractor.extractAll(itemsByZip, outputDir, workerCount, onProgress)

    override suspend fun extractDownloadedArchives(
        outputDir: String,
        onWarn: (String) -> Unit
    ): List<String> = extractor.extractDownloadedArchives(outputDir, onWarn)

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
