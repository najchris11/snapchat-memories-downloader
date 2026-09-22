package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The ZIP pipeline on Android.
 *
 * Android shipped with [NoOpZipPipelineRunner] wired in, so the app could pick an export and
 * then silently do nothing with it. This is the working runner, ported from
 * feature/mobile-responsive-ui and rewritten against the current [ZipPipelineRunner] — that
 * branch predates extractDownloadedArchives taking its archive list, and predates
 * extractionBudget, availableSpace and verifyExtraction existing at all.
 *
 * Extraction is shared with desktop through [ZipExtractEngine] (jvmSharedMain). Only the
 * combine step is genuinely platform-specific: desktop has ImageIO and a bundled FFmpeg,
 * Android has neither and composites with android.graphics instead.
 */
class AndroidZipPipelineRunner(
    private val mediaProcessor: MediaProcessor,
) : ZipPipelineRunner {

    private val extractor = ZipExtractEngine()

    override fun listZipFiles(folderPath: String): List<String> {
        val numberedSuffixRegex = Regex("""-(\d+)\.zip$""")
        // Snapchat splits large exports into memories-1.zip … memories-10.zip, which sort
        // wrong as text: -10 lands between -1 and -2.
        val zipSorter = Comparator<File> { a, b ->
            val numA = numberedSuffixRegex.find(a.name)?.groupValues?.get(1)?.toIntOrNull()
            val numB = numberedSuffixRegex.find(b.name)?.groupValues?.get(1)?.toIntOrNull()
            when {
                numA == null && numB == null -> a.name.compareTo(b.name)
                numA == null -> -1
                numB == null -> 1
                else -> numA.compareTo(numB)
            }
        }
        return File(folderPath)
            .listFiles { f -> f.extension.lowercase() == "zip" }
            ?.sortedWith(zipSorter)
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    override fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String) =
        extractor.extractionBudget(itemsByZip, outputDir)

    override fun availableSpace(outputDir: String) = extractor.availableSpace(outputDir)

    override fun verifyExtraction(zipPath: String, entries: List<HtmlMemoryEntry>, outputDir: String) =
        extractor.verifyExtraction(zipPath, entries, outputDir)

    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit,
    ) = extractor.extractAll(itemsByZip, outputDir, workerCount, onProgress)

    override suspend fun extractDownloadedArchives(
        outputDir: String,
        archivePaths: List<String>,
        onWarn: (String) -> Unit,
    ): List<String> = extractor.extractDownloadedArchives(outputDir, archivePaths, onWarn)

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit,
    ) = coroutineScope {
        val dir = File(outputDir)
        if (!dir.isDirectory) {
            onStart(0)
            return@coroutineScope
        }

        val byName = (dir.listFiles() ?: emptyArray()).associateBy { it.name }
        val pairs = findOverlayPairNames(byName.keys.toList())
        onStart(pairs.size)
        if (pairs.isEmpty()) return@coroutineScope

        // Results reach the caller on one coroutine, so onProgress never sees two threads.
        val channel = Channel<CombineResult>(Channel.UNLIMITED)
        val consumer = launch { for (result in channel) onProgress(result) }

        // Serialised deliberately, and workerCount is ignored here as a result. Compositing
        // decodes the main image, the overlay and the output at full resolution at once;
        // running pairs concurrently multiplies that by the worker count and puts the app
        // over its per-process heap limit on exactly the large exports this is for.
        val oneAtATime = Semaphore(1)

        for (names in pairs) {
            val mainFile = byName[names.mainName] ?: continue
            val overlayFile = byName[names.overlayName] ?: continue
            oneAtATime.withPermit {
                channel.send(combineOne(dir, names, mainFile, overlayFile, deleteOriginals))
            }
        }

        channel.close()
        consumer.join()
    }

    private suspend fun combineOne(
        dir: File,
        names: OverlayPairNames,
        mainFile: File,
        overlayFile: File,
        deleteOriginals: Boolean,
    ): CombineResult {
        val outputFile = File(dir, names.outputName)
        val warnings = mutableListOf<String>()

        // Video compositing needs an encoder Android does not ship, so it is never attempted
        // — and never reported as combined, which would badge an untouched file in the
        // Library as having its overlay burned in.
        val status = when {
            names.isVideo -> OverlayCombineStatus.SkippedVideo
            withContext(Dispatchers.IO) {
                mediaProcessor.combineImageWithOverlay(
                    mainFile.absolutePath,
                    overlayFile.absolutePath,
                    outputFile.absolutePath,
                    onWarning = { warnings += it },
                )
            } -> OverlayCombineStatus.Combined
            else -> OverlayCombineStatus.Failed
        }

        // Only a confirmed combine has produced a second copy of the pixels; see
        // mayDeleteOriginals, which is where that rule is tested.
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
}
