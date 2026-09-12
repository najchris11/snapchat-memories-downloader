package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * ## Concurrency
 *
 * Two writers genuinely overlap in this app: the pipeline writes the index at the end of a
 * run, and the Library writes it whenever a favourite is toggled — which a user can do while
 * a sync is in progress. Every mutation is a read-modify-write, so two of them interleaving
 * loses whichever landed first. Hence [lock], which every mutator holds for the whole
 * sequence, and which is why they are `suspend`.
 *
 * [read] deliberately does **not** take the lock. It is called from `scanMediaFiles` on every
 * Library scan, which is not a coroutine, and it does not need to: writes replace the file
 * atomically, so a reader sees either the previous complete index or the next one, never a
 * half-written one. Truncating the real file in place was the hazard there — a concurrent
 * read would land on partial JSON, and [read] converts a parse failure into an empty map
 * without complaint, so every item would have come back with no GPS, no overlay and no
 * favourite.
 */
object VaultIndex {

    const val FILE_NAME = "vault_index.json"

    // Explicit defaults so a favourite is visible in the file rather than encoded as an
    // absence. This file is the only place one lives, and a user looking for it should be
    // able to find it by eye.
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // Guards the read-modify-write in every mutator below. One lock for the object rather
    // than one per folder: the app has a single output folder at a time, and a lock that is
    // occasionally too broad is the right trade against losing a favourite.
    private val lock = Mutex()

    private fun path(folder: String) = "$folder/$FILE_NAME".toPath()

    /**
     * The index key for a media file, given any path to it.
     *
     * The index is keyed by file name because that is what `scanMediaFiles` looks entries up
     * by, while the Library identifies an item by its absolute path. `substringAfterLast('/')`
     * is not the conversion between them: a Windows absolute path (`C:\vault\memory.jpg`)
     * contains no forward slash, so the whole path became the key and the next scan found
     * nothing under the file name — a favourite that appeared and then vanished on restart.
     */
    fun keyOf(path: String): String {
        val cut = path.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (cut == -1) path else path.substring(cut + 1)
    }

    /** The index, or an empty map when it is missing or unreadable. */
    fun read(fileSystem: FileSystem, folder: String): Map<String, FileMeta> = runCatching {
        json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(path(folder)) { readUtf8() })
    }.getOrDefault(emptyMap())

    /**
     * Replaces the index with [meta].
     *
     * Writes a sibling temporary file and moves it into place rather than truncating the real
     * one, so [read] — which holds no lock — can never observe a partial file. The move is
     * the only externally visible step, and it is atomic.
     *
     * The temp name is fixed rather than unique per writer, which is safe only because every
     * caller holds [lock]: two writers reaching here at once would write the same temp file.
     * Private, and every entry point takes the lock, so that stays true.
     */
    private fun writeAtomically(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        val target = path(folder)
        val temp = "$folder/$FILE_NAME.tmp".toPath()
        fileSystem.write(temp) { writeUtf8(json.encodeToString(meta)) }
        runCatching { fileSystem.atomicMove(temp, target) }
            .onFailure { e ->
                // A temp file left behind is one the next write would find in its way, and a
                // stray vault_index.json.tmp in the output folder is not something a user
                // should have to wonder about.
                runCatching { fileSystem.delete(temp) }
                throw e
            }
    }

    suspend fun write(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        lock.withLock { writeAtomically(fileSystem, folder, meta) }
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
     * Re-reads under the lock rather than trusting the copy a run loaded at its start: a
     * favourite toggled while the run was in flight exists only on disk, and would otherwise
     * be overwritten by the run's own final write.
     */
    suspend fun writeMerging(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        lock.withLock {
            writeAtomically(fileSystem, folder, mergeUserFields(read(fileSystem, folder), meta))
        }
    }

    suspend fun setFavorite(
        fileSystem: FileSystem,
        folder: String,
        fileName: String,
        favorited: Boolean,
    ) {
        lock.withLock {
            val current = read(fileSystem, folder)
            val existing = current[fileName] ?: FileMeta(hasGps = false, hasOverlay = false)
            writeAtomically(
                fileSystem,
                folder,
                current + (fileName to existing.copy(favorited = favorited)),
            )
        }
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
    suspend fun resetKeepingFavorites(fileSystem: FileSystem, folder: String) {
        lock.withLock {
            val kept = read(fileSystem, folder)
                .filterValues { it.favorited }
                .mapValues { FileMeta(hasGps = false, hasOverlay = false, favorited = true) }

            if (kept.isEmpty()) {
                if (fileSystem.exists(path(folder))) fileSystem.delete(path(folder))
            } else {
                writeAtomically(fileSystem, folder, kept)
            }
        }
    }
}
