package com.najdev.snapvault.downloader

import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    // Legacy pipeline: extracts each downloaded memory archive flat into outputDir with
    // -main/-overlay names derived from the archive basename, so OverlayCombiner's stem
    // matching picks the pair up unchanged. Mirrors the legacy Python behavior
    // (extract_and_cleanup_zip): thumbnails are skipped and the archive is deleted after
    // a fully successful extraction, kept for retry otherwise.
    suspend fun extractDownloadedArchives(
        outputDir: String,
        onWarn: (String) -> Unit,
    ): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val outDir = File(outputDir)
        val archives = outDir.listFiles { f -> f.isFile && f.extension.lowercase() == "zip" }
            ?: return@withContext emptyList()
        val extracted = mutableListOf<String>()

        for (archive in archives) {
            currentCoroutineContext().ensureActive()
            val base = archive.nameWithoutExtension
            try {
                var allOk = true
                ZipFile(archive).use { zf ->
                    val entries = zf.entries().toList().filter { !it.isDirectory }
                    if (entries.isEmpty()) {
                        onWarn("archive ${archive.name} is empty — kept as-is")
                        allOk = false
                        return@use
                    }
                    for (entry in entries) {
                        val entryLeaf = entry.name.substringAfterLast('/')
                        val lower = entryLeaf.lowercase()
                        if ("thumbnail" in lower) continue
                        val ext = entryLeaf.substringAfterLast('.', "")
                        if (ext.isEmpty()) continue
                        val role = if ("overlay" in lower) "overlay" else "main"
                        val destName = "$base-$role.$ext"
                        when (val res = extractEntry(zf, entry.name, destName, outDir)) {
                            "ok", "skipped" -> extracted.add(File(outDir, destName).absolutePath)
                            else -> {
                                allOk = false
                                onWarn("${archive.name} → $destName: $res")
                            }
                        }
                    }
                }
                if (allOk && !archive.delete()) {
                    onWarn("could not delete extracted archive ${archive.name}")
                }
            } catch (e: Exception) {
                onWarn("could not extract ${archive.name}: ${e.message}")
            }
        }
        extracted
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

    private fun moveIntoPlace(tmpFile: File, destFile: File): String {
        if (isAndroidBelowApi26()) {
            return if (tmpFile.renameTo(destFile)) "ok" else "error: renameTo failed"
        }
        return try {
            NioMover.move(tmpFile, destFile)
        } catch (t: Throwable) {
            if (tmpFile.renameTo(destFile)) "ok" else "error: renameTo failed"
        }
    }

    private fun isAndroidBelowApi26(): Boolean {
        return try {
            val versionClass = Class.forName("android.os.Build\$VERSION")
            val sdkIntField = versionClass.getField("SDK_INT")
            val sdkInt = sdkIntField.get(null) as Int
            sdkInt < 26
        } catch (e: Exception) {
            false
        }
    }

    private fun cleanStalePartFiles(outDir: File) {
        outDir.listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}

private object NioMover {
    fun move(tmpFile: File, destFile: File): String {
        return try {
            try {
                java.nio.file.Files.move(
                    tmpFile.toPath(),
                    destFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmpFile.toPath(), destFile.toPath())
            }
            "ok"
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            tmpFile.delete()
            "skipped"
        }
    }
}
