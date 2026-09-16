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
        val staging = openStaging(outDir)
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
                                    val result = extractEntry(zf, task.entryName, task.destFileName, outDir, staging)
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

        staging.delete() // no-op unless every staged file was moved into place
    }

    // Legacy pipeline: extracts each downloaded memory archive flat into outputDir with
    // -main/-overlay names derived from the archive basename, so OverlayCombiner's stem
    // matching picks the pair up unchanged. Mirrors the legacy Python behavior
    // (extract_and_cleanup_zip): thumbnails are skipped and the archive is deleted after
    // a fully successful extraction, kept for retry otherwise.
    //
    // `archivePaths` is the set of archives *this run* downloaded, not a listing of
    // outputDir. Sweeping the directory (D01) meant choosing Downloads or the export
    // folder as the destination put every unrelated ZIP on disk through flatten-and-delete.
    // Ownership is the caller's knowledge; it cannot be recovered from a file extension.
    suspend fun extractDownloadedArchives(
        outputDir: String,
        archivePaths: List<String>,
        onWarn: (String) -> Unit,
    ): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val outDir = File(outputDir)
        val staging = openStaging(outDir)
        val extracted = mutableListOf<String>()

        for (path in archivePaths) {
            currentCoroutineContext().ensureActive()
            val archive = File(path)
            if (!archive.isFile) {
                onWarn("archive ${archive.name} is no longer present — skipped")
                continue
            }
            val base = archive.nameWithoutExtension
            try {
                // Deleting the archive is only safe once every entry it holds is accounted
                // for, so the whole plan is built and checked before a single byte is
                // written. `deletable` stays false unless that plan ran to completion.
                var deletable = false
                ZipFile(archive).use { zf ->
                    val entries = zf.entries().toList().filter { !it.isDirectory }
                    if (entries.isEmpty()) {
                        onWarn("archive ${archive.name} is empty — kept as-is")
                        return@use
                    }

                    val planned = linkedMapOf<String, String>() // destName -> entry name
                    var unflattenable = false
                    for (entry in entries) {
                        val entryLeaf = entry.name.substringAfterLast('/')
                        val lower = entryLeaf.lowercase()
                        if ("thumbnail" in lower) continue // deliberately not extracted
                        val ext = entryLeaf.substringAfterLast('.', "")
                        if (ext.isEmpty()) {
                            // Dropping it silently and then deleting the archive would
                            // destroy the only copy of a file we chose not to write.
                            onWarn("${archive.name}: entry '$entryLeaf' has no extension — archive kept")
                            unflattenable = true
                            continue
                        }
                        val role = if ("overlay" in lower) "overlay" else "main"
                        val destName = "$base-$role.$ext"
                        val prior = planned.put(destName, entry.name)
                        if (prior != null) {
                            // Two entries want one output name. Extracting either would
                            // report the loser as "skipped", which is indistinguishable
                            // from "already extracted", and the archive would be deleted.
                            onWarn("${archive.name}: '$prior' and '$entryLeaf' both flatten to $destName — archive kept")
                            unflattenable = true
                        }
                    }

                    if (unflattenable) return@use
                    if (planned.isEmpty()) {
                        onWarn("archive ${archive.name} holds no extractable media — kept as-is")
                        return@use
                    }

                    var allOk = true
                    for ((destName, entryName) in planned) {
                        when (val res = extractEntry(zf, entryName, destName, outDir, staging)) {
                            "ok", "skipped" -> extracted.add(File(outDir, destName).absolutePath)
                            else -> {
                                allOk = false
                                onWarn("${archive.name} → $destName: $res")
                            }
                        }
                    }
                    deletable = allOk
                }
                // Outside the use block: Windows will not delete a file that is still open.
                if (deletable && !archive.delete()) {
                    onWarn("could not delete extracted archive ${archive.name}")
                }
            } catch (e: Exception) {
                onWarn("could not extract ${archive.name}: ${e.message}")
            }
        }
        staging.delete() // no-op unless every staged file was moved into place
        extracted
    }

    // Extraction writes to a unique .part temp file and renames into place only after the
    // full entry is copied and size-verified. A crash or cancellation mid-copy therefore
    // never leaves a truncated file under the final name (which the exists() skip-check
    // would otherwise treat as complete forever).
    private fun extractEntry(
        zf: ZipFile,
        entryName: String,
        destFileName: String,
        outDir: File,
        staging: File,
    ): String {
        val destFile = File(outDir, destFileName)
        if (destFile.exists()) return "skipped"

        val entry = zf.getEntry(entryName) ?: return "error: entry not found: $entryName"
        // Unique temp name: the same destFileName can be extracted concurrently from
        // different zips; a shared temp name would let the writers clobber each other.
        // Staged inside our own directory so cleanup never has to guess who owns a file.
        val tmpFile = File.createTempFile("$destFileName.", PART_SUFFIX, staging)
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

    // java.io.File.renameTo's behavior on existing files varies by platform (overwrites on
    // most Unix, fails on Windows), so the exists()-check-then-rename below isn't atomic on
    // its own — two workers can both pass the check before either renames. Serializing with
    // this lock closes that window; a plain synchronized block (rather than
    // java.nio.file.Files.move's ATOMIC_MOVE) keeps this portable to Android API < 26, which
    // lacks NIO.2 file APIs without desugaring.
    private val moveLock = Any()

    private fun moveIntoPlace(tmpFile: File, destFile: File): String = synchronized(moveLock) {
        if (destFile.exists()) {
            tmpFile.delete()
            return@synchronized "skipped"
        }
        if (tmpFile.renameTo(destFile)) {
            "ok"
        } else {
            // If rename failed, it might be because another worker just moved it into place
            if (destFile.exists()) {
                tmpFile.delete()
                "skipped"
            } else {
                "error: could not move ${tmpFile.name} into place"
            }
        }
    }

    // In-progress extractions are staged in a directory of our own rather than beside the
    // user's files. Cleanup used to delete every *.part in the destination, which treated
    // a browser's in-flight download — or another SnapVault instance's live staging file —
    // as ours to remove (D03). An extension is not proof of ownership; the directory is.
    private fun openStaging(outDir: File): File {
        val staging = File(outDir, STAGING_DIR_NAME).also { it.mkdirs() }
        // Leftovers here are unambiguously ours, from a run that crashed mid-copy.
        staging.listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
        return staging
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val STAGING_DIR_NAME = ".snapvault-staging"
    }
}
