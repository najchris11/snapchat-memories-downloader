package com.najdev.snapvault.downloader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

class Deduplicator(
    private val fileSystem: FileSystem
) {
    fun calculateSha256(path: Path): String? {
        if (!fileSystem.exists(path)) return null
        return try {
            fileSystem.source(path).use { source ->
                HashingSource.sha256(source).use { hashingSource ->
                    hashingSource.buffer().readAll(blackholeSink())
                    hashingSource.hash.hex()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    data class DedupeResult(
        val folder: String,
        val keptFile: String,
        val deletedFiles: List<String>,
        val failedFiles: List<String> = emptyList()
    )

    // Files the pipeline manages that must never be considered for deletion.
    private fun isProtected(path: Path): Boolean =
        path.name == "vault_index.json" || path.name.endsWith(".part")

    // suspend so a running pipeline can actually be cancelled mid-scan (BUG-07) — this used
    // to be a plain blocking call with no suspension point, so Stop did nothing until the
    // whole folder had been hashed.
    suspend fun deduplicateFolder(folderPath: Path, dryRun: Boolean): List<DedupeResult> {
        if (!fileSystem.metadata(folderPath).isDirectory) return emptyList()

        data class FileEntry(val path: Path, val size: Long?)
        val files = fileSystem.list(folderPath).mapNotNull { p ->
            val m = fileSystem.metadata(p)
            if (m.isRegularFile && !isProtected(p)) FileEntry(p, m.size) else null
        }
        if (files.size < 2) return emptyList()

        // Pre-filter by size: files with unique sizes can't be duplicates, so skip hashing them
        val bySize = files.groupBy { it.size }
        val candidates = bySize.values.filter { it.size >= 2 }.flatten().map { it.path }

        val fileHashes = mutableMapOf<String, MutableList<Path>>()
        for (file in candidates) {
            currentCoroutineContext().ensureActive()
            val hash = calculateSha256(file)
            if (hash != null) {
                fileHashes.getOrPut(hash) { mutableListOf() }.add(file)
            }
        }

        val results = mutableListOf<DedupeResult>()
        for ((_, filepaths) in fileHashes) {
            currentCoroutineContext().ensureActive()
            if (filepaths.size > 1) {
                // Deterministic keep: the lexicographically-first name. Pipeline filenames
                // start with YYYY-MM-DD, so this keeps the earliest-dated copy of the
                // duplicated bytes rather than whichever the filesystem listed first.
                val sorted = filepaths.sortedBy { it.name }
                val primary = sorted.first()
                val toDelete = sorted.drop(1)

                if (toDelete.isNotEmpty()) {
                    val actuallyDeleted = mutableListOf<String>()
                    val failedToDelete = mutableListOf<String>()
                    if (!dryRun) {
                        for (file in toDelete) {
                            try {
                                fileSystem.delete(file)
                                actuallyDeleted.add(file.name)
                            } catch (_: Exception) {
                                failedToDelete.add(file.name)
                            }
                        }
                    } else {
                        actuallyDeleted.addAll(toDelete.map { it.name })
                    }
                    results.add(
                        DedupeResult(
                            folder = folderPath.name,
                            keptFile = primary.name,
                            deletedFiles = actuallyDeleted,
                            failedFiles = failedToDelete
                        )
                    )
                }
            }
        }

        return results
    }

    suspend fun deduplicateAll(rootDirectory: String, dryRun: Boolean): List<DedupeResult> {
        val rootPath = rootDirectory.toPath()
        if (!fileSystem.exists(rootPath) || !fileSystem.metadata(rootPath).isDirectory) return emptyList()

        val subfolders = fileSystem.list(rootPath).filter { fileSystem.metadata(it).isDirectory }
        val allResults = mutableListOf<DedupeResult>()
        for (folder in subfolders) {
            allResults.addAll(deduplicateFolder(folder, dryRun))
        }
        return allResults
    }
}
