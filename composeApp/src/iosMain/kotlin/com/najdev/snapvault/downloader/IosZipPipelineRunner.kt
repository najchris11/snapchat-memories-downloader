package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

class IosZipPipelineRunner(
    private val mediaProcessor: MediaProcessor
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

        val semaphore = Semaphore(workerCount.coerceAtLeast(1))

        val tasks = itemsByZip.flatMap { (zipPath, entries) ->
            val zipFile = zipPath.toPath()
            val zipFs: FileSystem = try {
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
                }
                return@flatMap emptyList()
            }

            entries.map { entry ->
                async {
                    semaphore.withPermit {
                        var extractedPath = ""
                        var errorMessage: String? = null
                        var isSkipped = false

                        try {
                            val cleanPath = entry.fileName.removePrefix("/")
                            val zipEntryPath = "/$cleanPath".toPath()

                            if (!zipFs.exists(zipEntryPath)) {
                                errorMessage = "Entry not found in ZIP: ${entry.fileName}"
                            } else {
                                val destFile = outPath / cleanPath.substringAfterLast("/")
                                if (fileSystem.exists(destFile)) {
                                    isSkipped = true
                                    extractedPath = destFile.toString()
                                } else {
                                    val inputSource = zipFs.source(zipEntryPath).buffer()
                                    val outputSink = fileSystem.sink(destFile).buffer()
                                    inputSource.use { input ->
                                        outputSink.use { output ->
                                            output.writeAll(input)
                                        }
                                    }
                                    extractedPath = destFile.toString()

                                    if (entry.date.isNotBlank()) {
                                        mediaProcessor.writeDateMetadata(extractedPath, entry.date)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            errorMessage = "Failed to extract ${entry.fileName}: ${e.message}"
                        }

                        val result = ExtractResult(
                            uuid = entry.uuid,
                            fileName = entry.fileName,
                            outputPath = extractedPath,
                            skipped = isSkipped,
                            error = errorMessage
                        )
                        channel.send(result)
                    }
                }
            }
        }

        tasks.awaitAll()
        channel.close()
        consumer.join()
    }

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit
    ) {
        onStart(0)
    }
}
