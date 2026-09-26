package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import kotlin.random.Random

class IosZipPipelineRunner(
    private val mediaProcessor: MediaProcessor,
) : ZipPipelineRunner {

    override fun listZipFiles(folderPath: String): List<String> {
        val folder = folderPath.toPath()
        val fileSystem = FileSystem.SYSTEM
        if (!fileSystem.exists(folder)) return emptyList()

        return fileSystem.list(folder)
            .filter { path ->
                val name = path.name.lowercase()
                name.startsWith("mydata~") && name.endsWith(".zip")
            }
            .map { it.toString() }
            .sorted()
    }

    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val outPath = outputDir.toPath()
        val fileSystem = FileSystem.SYSTEM
        fileSystem.createDirectories(outPath)

        val totalItems = itemsByZip.values.sumOf { it.size }
        if (totalItems == 0) return@withContext

        val channel = Channel<ExtractResult>(Channel.UNLIMITED)
        val consumer = launch {
            for (res in channel) {
                onProgress(res)
            }
        }

        try {
            val semaphore = Semaphore(workerCount.coerceAtLeast(1))

            val tasks = itemsByZip.map { (zipPath, entries) ->
                async {
                    val zipFile = zipPath.toPath()
                    val zipFs = try {
                        fileSystem.openZip(zipFile)
                    } catch (e: Exception) {
                        entries.forEach { entry ->
                            channel.trySend(
                                ExtractResult(
                                    uuid = entry.uuid,
                                    fileName = entry.fileName,
                                    outputPath = "",
                                    skipped = false,
                                    error = "Could not open zip archive: $zipPath (${e.message})"
                                )
                            )
                            // Overlay counts as its own item in DashboardViewModel's totalItems, so it
                            // needs its own failed result here too, or progress stalls short of 100%
                            // for a corrupt/unreadable archive that contains overlays.
                            val overlayName = entry.overlayFileName
                            if (entry.hasOverlay && !overlayName.isNullOrBlank()) {
                                channel.trySend(
                                    ExtractResult(
                                        uuid = entry.uuid,
                                        fileName = overlayName,
                                        outputPath = "",
                                        skipped = false,
                                        error = "Could not open zip archive: $zipPath (${e.message})"
                                    )
                                )
                            }
                        }
                        return@async
                    }

                    try {
                        val entryTasks = entries.map { entry ->
                            async {
                                semaphore.withPermit {
                                    var extractedPath = ""
                                    var errorMessage: String? = null
                                    var isSkipped = false

                                    try {
                                        val mainZipEntryPath = findZipEntryPath(zipFs, entry.fileName)
                                        if (mainZipEntryPath == null) {
                                            errorMessage = "Entry not found in ZIP: ${entry.fileName}"
                                        } else {
                                            val destFile = outPath / entry.fileName.substringAfterLast("/")
                                            val alreadyExists = fileSystem.exists(destFile)

                                            if (alreadyExists) {
                                                isSkipped = true
                                                extractedPath = destFile.toString()
                                            } else {
                                                extractToFileAtomic(zipFs, fileSystem, mainZipEntryPath, destFile)
                                                extractedPath = destFile.toString()
                                                // Date metadata is written in DashboardViewModel's later batch
                                                // pass (writeDateMetadataBatch), which supplies a full
                                                // "yyyy-MM-dd 00:00:00 UTC" timestamp; entry.date here is
                                                // date-only and would fail IosMediaProcessor's format check.
                                            }
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Failed to extract ${entry.fileName}: ${e.message}"
                                    }

                                    channel.send(
                                        ExtractResult(
                                            uuid = entry.uuid,
                                            fileName = entry.fileName,
                                            outputPath = extractedPath,
                                            skipped = isSkipped,
                                            error = errorMessage
                                        )
                                    )

                                    // Overlay is counted as a separate unit of work in DashboardViewModel's
                                    // totalItems (see the +1 for entry.hasOverlay), so it needs its own
                                    // ExtractResult here too — otherwise "done" can never reach the total
                                    // for any import with overlays. Mirrors desktop's ZipExtractEngine,
                                    // which extracts the overlay as a second, independently tracked task.
                                    val overlayName = entry.overlayFileName
                                    if (entry.hasOverlay && !overlayName.isNullOrBlank()) {
                                        var overlayPath = ""
                                        var overlayError: String? = null
                                        var overlaySkipped = false
                                        try {
                                            val overlayZipEntryPath = findZipEntryPath(zipFs, overlayName)
                                            if (overlayZipEntryPath == null) {
                                                overlayError = "Overlay entry not found in ZIP: $overlayName"
                                            } else {
                                                val overlayDestFile = outPath / overlayName.substringAfterLast("/")
                                                if (fileSystem.exists(overlayDestFile)) {
                                                    overlaySkipped = true
                                                    overlayPath = overlayDestFile.toString()
                                                } else {
                                                    extractToFileAtomic(zipFs, fileSystem, overlayZipEntryPath, overlayDestFile)
                                                    overlayPath = overlayDestFile.toString()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            overlayError = "Failed to extract overlay $overlayName: ${e.message}"
                                        }
                                        channel.send(
                                            ExtractResult(
                                                uuid = entry.uuid,
                                                fileName = overlayName,
                                                outputPath = overlayPath,
                                                skipped = overlaySkipped,
                                                error = overlayError
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        entryTasks.awaitAll()
                    } finally {
                        try { zipFs.close() } catch (_: Exception) {}
                    }
                }
            }

            tasks.awaitAll()
        } finally {
            channel.close()
            consumer.join()
        }
    }

    private fun findZipEntryPath(zipFs: FileSystem, rawName: String): Path? {
        val clean = rawName.removePrefix("/")
        val candidates = listOf(
            "/memories/$clean".toPath(),
            "/$clean".toPath(),
            if (clean.startsWith("memories/")) "/$clean".toPath() else "/memories/${clean.substringAfterLast("/")}".toPath()
        )
        return candidates.firstOrNull { zipFs.exists(it) }
    }

    private fun extractToFileAtomic(
        zipFs: FileSystem,
        fileSystem: FileSystem,
        zipEntryPath: Path,
        destFile: Path
    ) {
        // Unique per call (not just per destination) — concurrent tasks across different
        // selected archives can legitimately target the same destFile, and a fixed ".tmp"
        // name would let their writes race into the same temp file. Mirrors desktop's
        // ZipExtractEngine, which uses File.createTempFile for the same reason.
        val tmpFile = (destFile.toString() + ".${Random.nextLong().toString(16)}.tmp").toPath()
        try {
            val inputSource = zipFs.source(zipEntryPath).buffer()
            val outputSink = fileSystem.sink(tmpFile).buffer()
            inputSource.use { input ->
                outputSink.use { output ->
                    output.writeAll(input)
                }
            }
            fileSystem.atomicMove(tmpFile, destFile)
        } catch (e: Exception) {
            try { fileSystem.delete(tmpFile) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Burns each memory's overlay into its main image.
     *
     * Deliberately the same shape as AndroidZipPipelineRunner: pair discovery, the delete
     * rule and the result shape are all shared common code (findOverlayPairNames,
     * mayDeleteOriginals, overlayCombineResult), so the two platforms cannot drift the way
     * the two copies of pair discovery did before they were extracted (BUG-18). Only the
     * compositing call itself is platform code.
     *
     * Unlike desktop there is no separate metadata pass: IosMediaProcessor carries the
     * source's properties through the same encode, so onMetaStart reports no work.
     */
    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit
    ) = coroutineScope {
        val fileSystem = FileSystem.SYSTEM
        val dir = outputDir.toPath()
        if (!fileSystem.exists(dir)) {
            onStart(0)
            return@coroutineScope
        }

        val byName = fileSystem.list(dir).associateBy { it.name }
        val pairs = findOverlayPairNames(byName.keys.toList())
        onStart(pairs.size)
        onMetaStart(0)
        if (pairs.isEmpty()) return@coroutineScope

        // Results reach the caller on one coroutine, so onProgress never sees two threads.
        val channel = Channel<CombineResult>(Channel.UNLIMITED)
        val consumer = launch { for (result in channel) onProgress(result) }

        // Serialised, and workerCount is ignored as a result — the same call the Android
        // runner makes for the same reason: compositing holds the main image, the overlay
        // and the output at full resolution simultaneously, and iOS kills an app that runs
        // over its memory limit rather than letting it swap.
        val oneAtATime = Semaphore(1)

        for (names in pairs) {
            val mainPath = byName[names.mainName] ?: continue
            val overlayPath = byName[names.overlayName] ?: continue
            oneAtATime.withPermit {
                channel.send(
                    combineOne(fileSystem, dir, names, mainPath, overlayPath, deleteOriginals)
                )
            }
        }

        channel.close()
        consumer.join()
    }

    private suspend fun combineOne(
        fileSystem: FileSystem,
        dir: Path,
        names: OverlayPairNames,
        mainPath: Path,
        overlayPath: Path,
        deleteOriginals: Boolean,
    ): CombineResult {
        val outputPath = dir / names.outputName
        val warnings = mutableListOf<String>()

        val status = when {
            // Needs an AVAssetExportSession re-encode; never attempted, and never reported
            // as combined, which would badge an untouched file as having its overlay burned in.
            names.isVideo -> OverlayCombineStatus.SkippedVideo
            // CGImageSourceCreateImageAtIndex(source, 0) is frame one of an animation and
            // the destination writes a single frame back, so combining would silently
            // replace the animation with a still.
            names.isAnimatedImage -> OverlayCombineStatus.SkippedAnimated
            withContext(Dispatchers.IO) {
                mediaProcessor.combineImageWithOverlay(
                    mainPath.toString(),
                    overlayPath.toString(),
                    outputPath.toString(),
                    onWarning = { warnings += it },
                )
            } -> OverlayCombineStatus.Combined
            else -> OverlayCombineStatus.Failed
        }

        // Only a confirmed combine has produced a second copy of the pixels; see
        // mayDeleteOriginals, which is where that rule is tested.
        if (mayDeleteOriginals(status, deleteOriginals)) {
            runCatching { fileSystem.delete(mainPath) }
                .onFailure { warnings += "could not delete original: ${names.mainName}" }
            runCatching { fileSystem.delete(overlayPath) }
                .onFailure { warnings += "could not delete overlay: ${names.overlayName}" }
        }

        return overlayCombineResult(
            pair = names,
            mainPath = mainPath.toString(),
            overlayPath = overlayPath.toString(),
            outputPath = outputPath.toString(),
            status = status,
            warnings = warnings,
        )
    }
}
