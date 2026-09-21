package com.najdev.snapvault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/** One source archive an import consumed, and what became of it. */
@Serializable
data class ImportedArchive(
    val name: String,
    val sizeBytes: Long,
    val memories: Int,
    val deleted: Boolean,
    // Why it is still on disk: the check that failed, for an archive the run declined to delete.
    val keptBecause: String? = null,
)

class ImportManifestUnreadableException(cause: Throwable?) :
    Exception("${ImportManifest.FILE_NAME} exists but could not be read; refusing to overwrite it", cause)

/**
 * The record of which archives an import consumed, in `.snapvault/imports.json` inside the
 * library folder (D20).
 *
 * In low-space mode an archive is deleted once its contents are verified, and that deletion is
 * permanent. The record is written first, so what was deleted is knowable after the fact — by a
 * user looking for an archive they no longer have, and by a run that crashed partway. A log
 * lasts a session; this outlives it.
 */
object ImportManifest {

    const val DIR_NAME = ".snapvault"
    const val FILE_NAME = "imports.json"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    private fun path(folder: String) = "$folder/$DIR_NAME/$FILE_NAME".toPath()

    fun read(fileSystem: FileSystem, folder: String): List<ImportedArchive> = runCatching {
        json.decodeFromString<List<ImportedArchive>>(fileSystem.read(path(folder)) { readUtf8() })
    }.getOrDefault(emptyList())

    /**
     * Adds [archive], replacing any earlier record of the same file.
     *
     * Throws rather than overwriting a manifest that will not parse: it may name archives
     * already deleted, which is the one thing here that importing again cannot recreate. A
     * caller that cannot record a deletion must not perform it.
     */
    fun record(fileSystem: FileSystem, folder: String, archive: ImportedArchive) {
        val target = path(folder)
        val existing = if (fileSystem.exists(target)) {
            runCatching { json.decodeFromString<List<ImportedArchive>>(fileSystem.read(target) { readUtf8() }) }
                .getOrElse { throw ImportManifestUnreadableException(it) }
        } else {
            emptyList()
        }

        val updated = existing.filterNot { it.name == archive.name } + archive
        fileSystem.createDirectories(target.parent!!)
        val temp = "$folder/$DIR_NAME/$FILE_NAME.tmp".toPath()
        fileSystem.write(temp) { writeUtf8(json.encodeToString(updated)) }
        runCatching { fileSystem.atomicMove(temp, target) }.onFailure { e ->
            runCatching { fileSystem.delete(temp) }
            throw e
        }
    }
}
