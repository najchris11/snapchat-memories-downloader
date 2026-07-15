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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

class ZipExtractEngine {

    suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    ) {
        val outDir = File(outputDir).also { it.mkdirs() }
        cleanStalePartFiles(outDir)
        val semaphore = Semaphore(workerCount)

        data class ExtractTask(val entryName: String, val destFileName: String, val uuid: String)

        // Group tasks by zip so each archive is opened exactly once
        val tasksByZip = linkedMapOf<String, MutableList<ExtractTask>>()
        for ((zipPath, entries) in itemsByZip) {
            val list = tasksByZip.getOrPut(zipPath) { mutableListOf() }
            for (entry in entries) {
                list += ExtractTask("memories/${entry.fileName}", entry.fileName, entry.uuid)
                if (entry.hasOverlay && entry.overlayFileName != null) {
                    list += ExtractTask("memories/${entry.overlayFileName}", entry.overlayFileName, entry.uuid)
                }
            }
        }

        val channel = Channel<ExtractResult>(Channel.UNLIMITED)

        coroutineScope {
            // Single consumer — serializes all onProgress calls so callers don't need to be thread-safe
            launch {
                for (result in channel) onProgress(result)
            }

            tasksByZip.map { (zipPath, tasks) ->
                async(Dispatchers.IO) {
                    // ZipFile.getInputStream() returns independent streams; concurrent reads are safe
                    ZipFile(zipPath).use { zf ->
                        tasks.map { task ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val result = extractEntry(zf, task.entryName, task.destFileName, outDir)
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
                    }
                }
            }.awaitAll()

            channel.close()
        }
    }

    // Extraction writes to a unique .part temp file and renames into place only after the
    // full entry is copied and size-verified. A crash or cancellation mid-copy therefore
    // never leaves a truncated file under the final name (which the exists() skip-check
    // would otherwise treat as complete forever).
    private fun extractEntry(zf: ZipFile, entryName: String, destFileName: String, outDir: File): String {
        val destFile = File(outDir, destFileName)
        if (destFile.exists()) return "skipped"

        val entry = zf.getEntry(entryName) ?: return "error: entry not found: $entryName"
        // Unique temp name: the same destFileName can be extracted concurrently from
        // different zips; a shared temp name would let the writers clobber each other.
        val tmpFile = File.createTempFile("$destFileName.", PART_SUFFIX, outDir)
        return try {
            zf.getInputStream(entry).use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (entry.size >= 0 && tmpFile.length() != entry.size) {
                tmpFile.delete()
                return "error: size mismatch extracting $destFileName (${tmpFile.length()} of ${entry.size} bytes)"
            }
            moveIntoPlace(tmpFile, destFile)
        } catch (e: Exception) {
            tmpFile.delete()
            "error: ${e.message}"
        }
    }

    private fun moveIntoPlace(tmpFile: File, destFile: File): String = try {
        try {
            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), destFile.toPath())
        }
        "ok"
    } catch (_: FileAlreadyExistsException) {
        // Another worker extracted the same filename first — not an error.
        tmpFile.delete()
        "skipped"
    }

    private fun cleanStalePartFiles(outDir: File) {
        outDir.listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
