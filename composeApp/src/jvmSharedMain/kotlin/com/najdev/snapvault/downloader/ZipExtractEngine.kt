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
import java.nio.file.Files
import java.util.zip.ZipFile

class ZipExtractEngine(
    private val usableSpace: (File) -> Long = { it.usableSpace },
    // The drive a file lives on, as an identity to compare. A parameter so a test can place an
    // archive on a second drive without needing one.
    private val volumeId: (File) -> Any? = { runCatching { Files.getFileStore(it.absoluteFile.toPath()) }.getOrNull() },
) {

    /**
     * What [extractAll] would write for [itemsByZip], and the space [outputDir] has for it.
     *
     * Read from each archive's central directory, so nothing is decompressed to find out. An
     * entry whose destination already holds a finished file costs nothing, matching the skip
     * in [extractEntry]; an empty placeholder is replaced there, so it is counted here. An
     * entry that is missing, or whose size the directory does not record, is left out — the
     * extraction itself reports it.
     */
    fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String): ExtractionBudget {
        val outDir = File(outputDir)
        // The folder may not exist yet; measure the nearest ancestor that does.
        val measured = generateSequence(outDir.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: outDir
        val archives = itemsByZip.map { (zipPath, entries) ->
            val archive = File(zipPath)
            ArchiveSpace(
                path = zipPath,
                requiredBytes = requiredBytesFor(zipPath, entries, outDir),
                archiveBytes = archive.length(),
                onOutputVolume = sameVolume(archive, measured),
            )
        }
        return ExtractionBudget(
            requiredBytes = archives.sumOf { it.requiredBytes },
            availableBytes = usableSpace(measured),
            archives = archives,
        )
    }

    /** Free space in [outputDir] now — the preflight's figure is only true when it was taken. */
    fun availableSpace(outputDir: String): Long {
        val dir = File(outputDir)
        val measured = generateSequence(dir.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: dir
        return usableSpace(measured)
    }

    private fun requiredBytesFor(zipPath: String, entries: List<HtmlMemoryEntry>, outDir: File): Long {
        var required = 0L
        ZipFile(zipPath).use { zf ->
            for (name in entries.flatMap(::expectedNames)) {
                val dest = File(outDir, name)
                if (dest.isFile && dest.length() > 0L) continue
                val size = zf.getEntry("memories/$name")?.size ?: continue
                if (size > 0) required += size
            }
        }
        return required
    }

    // Deleting an archive frees space only where the library is being written. A drive that
    // cannot be identified is treated as a different one: a wrong "yes" here offers a user a
    // mode that permanently deletes their archives and does not make the import fit.
    private fun sameVolume(archive: File, outputDir: File): Boolean {
        val nearest = generateSequence(archive.absoluteFile) { it.parentFile }.firstOrNull { it.exists() }
            ?: return false
        val archiveVolume = volumeId(nearest) ?: return false
        return archiveVolume == volumeId(outputDir)
    }

    private fun expectedNames(entry: HtmlMemoryEntry): List<String> =
        listOfNotNull(entry.fileName, entry.overlayFileName.takeIf { entry.hasOverlay })

    /**
     * What is wrong with the files [entries] should have produced, if anything (D20).
     *
     * Each expected file is checked against the archive it came from: present, and the size
     * that archive says it should be. An archive is deleted only on an empty list, so the check
     * has to be about *these* bytes — a file of the right name that came from somewhere else,
     * or one truncated by a write that ran out of room, must not pass.
     */
    fun verifyExtraction(zipPath: String, entries: List<HtmlMemoryEntry>, outputDir: String): List<String> {
        val outDir = File(outputDir)
        val archive = File(zipPath)
        if (!archive.isFile) return listOf("${archive.name} is no longer present")
        return runCatching {
            ZipFile(archive).use { zf ->
                entries.flatMap(::expectedNames).mapNotNull { name ->
                    val expected = zf.getEntry("memories/$name")
                    val dest = File(outDir, name)
                    when {
                        expected == null -> "$name is not in ${archive.name}"
                        !dest.isFile -> "$name was not written"
                        expected.size >= 0 && dest.length() != expected.size ->
                            "$name is ${dest.length()} bytes, expected ${expected.size}"
                        else -> null
                    }
                }
            }
        }.getOrElse { listOf("${archive.name} could not be read back: ${it.message ?: it::class.simpleName}") }
    }

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

        closeStaging(staging)
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
                            "ok" -> extracted.add(File(outDir, destName).absolutePath)
                            "skipped" -> {
                                // Existence is enough for non-destructive resume, but cannot
                                // prove this archive's content survived a filename collision.
                                allOk = false
                                onWarn("${archive.name}: $destName already exists and was not verified — archive kept")
                            }
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
        closeStaging(staging)
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
        // "Something is at the path" is not "this entry was extracted" (D14). A folder is not
        // ours to remove and holds nothing of this entry, so it is a failure — reporting it as
        // a skip let a legacy archive be deleted with its memory extracted nowhere. An empty
        // file carries no data and is replaced. Anything else is kept: the metadata pass
        // rewrites extracted files in place, so a size that differs from the entry is the
        // normal state of a finished file, not evidence against it.
        if (destFile.isDirectory) return "error: a folder named $destFileName is in the way"
        if (destFile.isFile && destFile.length() > 0L) return "skipped"
        val replacingEmpty = destFile.isFile

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
            moveIntoPlace(tmpFile, destFile, replacingEmpty)
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

    private fun moveIntoPlace(tmpFile: File, destFile: File, replacingEmpty: Boolean): String = synchronized(moveLock) {
        // Only the empty placeholder the caller already judged unfinished may be replaced, and
        // only if it is still empty — another worker may have filled it since.
        if (replacingEmpty && destFile.isFile && destFile.length() == 0L) {
            destFile.delete() // renameTo will not replace an existing file on Windows
        }
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

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
