package com.najdev.snapvault.downloader

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
        val deletedFiles: List<String>
    )

    fun deduplicateFolder(folderPath: Path, dryRun: Boolean): List<DedupeResult> {
        if (!fileSystem.metadata(folderPath).isDirectory) return emptyList()

        data class FileEntry(val path: Path, val size: Long?)
        val files = fileSystem.list(folderPath).mapNotNull { p ->
            val m = fileSystem.metadata(p)
            if (m.isRegularFile) FileEntry(p, m.size) else null
        }
        if (files.size < 2) return emptyList()

        // Pre-filter by size: files with unique sizes can't be duplicates, so skip hashing them
        val bySize = files.groupBy { it.size }
        val candidates = bySize.values.filter { it.size >= 2 }.flatten().map { it.path }

        val fileHashes = mutableMapOf<String, MutableList<Path>>()
        for (file in candidates) {
            val hash = calculateSha256(file)
            if (hash != null) {
                fileHashes.getOrPut(hash) { mutableListOf() }.add(file)
            }
        }

        val results = mutableListOf<DedupeResult>()
        for ((_, filepaths) in fileHashes) {
            if (filepaths.size > 1) {
                val folderName = folderPath.name
                val folderUuid = if ("_" in folderName) folderName.split("_").last() else folderName

                var primary: Path? = null
                val toDelete = mutableListOf<Path>()

                for (filepath in filepaths) {
                    val filename = filepath.name
                    if (filename.startsWith(folderUuid)) {
                        primary = filepath
                    } else {
                        toDelete.add(filepath)
                    }
                }

                if (primary == null) {
                    primary = filepaths[0]
                    toDelete.clear()
                    toDelete.addAll(filepaths.subList(1, filepaths.size))
                }

                if (toDelete.isNotEmpty()) {
                    if (!dryRun) {
                        for (file in toDelete) {
                            try {
                                fileSystem.delete(file)
                            } catch (_: Exception) {}
                        }
                    }
                    results.add(
                        DedupeResult(
                            folder = folderName,
                            keptFile = primary.name,
                            deletedFiles = toDelete.map { it.name }
                        )
                    )
                }
            }
        }

        return results
    }

    fun deduplicateAll(rootDirectory: String, dryRun: Boolean): List<DedupeResult> {
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
