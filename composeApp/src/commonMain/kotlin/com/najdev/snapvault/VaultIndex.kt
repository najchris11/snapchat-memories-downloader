package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Reading and writing `vault_index.json`.
 *
 * The index holds two kinds of thing and they have very different value. `hasGps` and
 * `hasOverlay` are facts about what the pipeline did, recoverable at any time by re-running
 * it. `favorited` is the only thing in SnapVault the *user* produced — it is in no export and
 * no re-run will bring it back. Everything here exists to keep the second kind safe from
 * writes that only know about the first.
 */
object VaultIndex {

    const val FILE_NAME = "vault_index.json"

    // Explicit defaults so a favourite is visible in the file rather than encoded as an
    // absence. This file is the only place one lives, and a user looking for it should be
    // able to find it by eye.
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun path(folder: String) = "$folder/$FILE_NAME".toPath()

    /** The index, or an empty map when it is missing or unreadable. */
    fun read(fileSystem: FileSystem, folder: String): Map<String, FileMeta> = runCatching {
        json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(path(folder)) { readUtf8() })
    }.getOrDefault(emptyMap())

    fun write(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        fileSystem.write(path(folder)) { writeUtf8(json.encodeToString(meta)) }
    }

    /**
     * Carries the user-owned fields of [onDisk] into [pipeline], which the pipeline builds
     * from scratch and so knows nothing about.
     *
     * The pipeline's own facts win — it just recomputed them. A file that only [pipeline]
     * knows about cannot have been favourited, since the user has not seen it yet.
     *
     * A union rather than a rewrite of [pipeline]: a run's map is *incremental*, seeded from
     * the index at start and added to as files are processed, so an entry it does not mention
     * is one it never looked at, not one it decided to drop. Rewriting instead of unioning
     * erased every entry written to disk after the run loaded its copy — which is precisely
     * the favourite-set-during-a-run case this is here to protect.
     */
    fun mergeUserFields(
        onDisk: Map<String, FileMeta>,
        pipeline: Map<String, FileMeta>,
    ): Map<String, FileMeta> = onDisk + pipeline.mapValues { (name, fresh) ->
        fresh.copy(favorited = onDisk[name]?.favorited ?: false)
    }

    /**
     * Writes [meta], preserving favourites currently on disk.
     *
     * Re-reads rather than trusting the copy a run loaded at its start: a favourite toggled
     * while the run was in flight exists only on disk, and would otherwise be overwritten by
     * the run's own final write.
     */
    fun writeMerging(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        write(fileSystem, folder, mergeUserFields(read(fileSystem, folder), meta))
    }

    fun setFavorite(fileSystem: FileSystem, folder: String, fileName: String, favorited: Boolean) {
        val current = read(fileSystem, folder)
        val existing = current[fileName] ?: FileMeta(hasGps = false, hasOverlay = false)
        write(fileSystem, folder, current + (fileName to existing.copy(favorited = favorited)))
    }

    /**
     * Clears what the pipeline can recompute and keeps what it cannot.
     *
     * Reset exists so the next run re-processes everything, which only requires dropping
     * `hasGps` and `hasOverlay`. Taking favourites with it would destroy the one thing in
     * here that no re-run can rebuild. Entries with nothing left worth keeping are dropped,
     * and an index with no favourites at all is deleted outright — which is what reset used
     * to do unconditionally.
     */
    fun resetKeepingFavorites(fileSystem: FileSystem, folder: String) {
        val kept = read(fileSystem, folder)
            .filterValues { it.favorited }
            .mapValues { (_, meta) -> FileMeta(hasGps = false, hasOverlay = false, favorited = true) }

        if (kept.isEmpty()) {
            if (fileSystem.exists(path(folder))) fileSystem.delete(path(folder))
        } else {
            write(fileSystem, folder, kept)
        }
    }
}
