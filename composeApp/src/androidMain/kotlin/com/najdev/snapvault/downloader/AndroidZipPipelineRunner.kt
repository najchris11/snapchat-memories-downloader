package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    ) = coroutineScope {
        val dir = File(outputDir)
        if (!dir.exists() || !dir.isDirectory) {
            onStart(0)
            return@coroutineScope
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

        val channel = Channel<CombineResult>(Channel.UNLIMITED)
        val consumer = launch {
            for (res in channel) {
                onProgress(res)
            }
        }

        val semaphore = Semaphore(workerCount.coerceAtLeast(1))

        pairs.map { pair ->
            async {
                semaphore.withPermit {
                    val stem = pair.mainFile.name.substringBefore("-main.")
                    val uuid = stem.substringAfter("_", stem)

                    if (pair.isVideo) {
                        channel.send(
                            CombineResult(
                                uuid = uuid,
                                outputPath = pair.mainFile.absolutePath,
                                status = "skipped: Video overlay combine on Android will land in Media3 update",
                                warnings = listOf("Video overlay combine on Android will land in Media3 update")
                            )
                        )
                    } else {
                        val warnings = mutableListOf<String>()
                        val ok = withContext(Dispatchers.IO) {
                            mediaProcessor.combineImageWithOverlay(
                                pair.mainFile.absolutePath,
                                pair.overlayFile.absolutePath,
                                pair.outputFile.absolutePath,
                                onWarning = { warnings.add(it) }
                            )
                        }

                        if (ok) {
                            if (deleteOriginals) {
                                pair.mainFile.delete()
                                pair.overlayFile.delete()
                            }
                            channel.send(
                                CombineResult(
                                    uuid = uuid,
                                    outputPath = pair.outputFile.absolutePath,
                                    status = "combined",
                                    warnings = warnings
                                )
                            )
                        } else {
                            channel.send(
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
        }.awaitAll()

        channel.close()
        consumer.join()
    }
}
