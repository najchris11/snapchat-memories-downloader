package com.najdev.snapvault.downloader

import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.zip.ZipFile

class ZipExtractEngine {

    suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    ) {
        val outDir = File(outputDir).also { it.mkdirs() }
        val semaphore = Semaphore(workerCount)

        // Build flat list of (zipPath, entryName, destFileName) tasks
        data class ExtractTask(
            val zipPath: String,
            val entryName: String,
            val destFileName: String,
            val uuid: String
        )

        val tasks = mutableListOf<ExtractTask>()
        for ((zipPath, entries) in itemsByZip) {
            for (entry in entries) {
                tasks.add(ExtractTask(zipPath, "memories/${entry.fileName}", entry.fileName, entry.uuid))
                if (entry.hasOverlay && entry.overlayFileName != null) {
                    tasks.add(ExtractTask(zipPath, "memories/${entry.overlayFileName}", entry.overlayFileName, entry.uuid))
                }
            }
        }

        val channel = Channel<ExtractResult>(Channel.UNLIMITED)

        coroutineScope {
            // Single consumer — serializes all onProgress calls so callers don't need to be thread-safe
            launch {
                for (result in channel) onProgress(result)
            }

            tasks.map { task ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val result = extractEntry(task.zipPath, task.entryName, task.destFileName, outDir)
                        channel.send(
                            ExtractResult(
                                uuid = task.uuid,
                                fileName = task.destFileName,
                                outputPath = File(outDir, task.destFileName).absolutePath,
                                skipped = result == "skipped",
                                error = if (result != "ok" && result != "skipped") result else null
                            )
                        )
                    }
                }
            }.awaitAll()

            channel.close()
        }
    }

    private fun extractEntry(
        zipPath: String,
        entryName: String,
        destFileName: String,
        outDir: File
    ): String {
        val destFile = File(outDir, destFileName)
        if (destFile.exists()) return "skipped"

        return try {
            ZipFile(zipPath).use { zf ->
                val entry = zf.getEntry(entryName) ?: return "error: entry not found: $entryName"
                zf.getInputStream(entry).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
