package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * The index file exists but could not be parsed.
 *
 * Distinct from "no index yet", which is the ordinary first-run state and means an empty map.
 * A file that is present and unreadable is the opposite: it is data, quite possibly every
 * favorite the user has, in a form this version cannot read — damaged, truncated by something
 * outside the app, or written by a future version. Mutating on top of it replaces it with
 * whatever the caller happened to know, which is how one favorite toggle could reduce a whole
 * index to a single entry (D05). Every mutator throws this instead, leaving the bytes alone.
 */
class VaultIndexUnreadableException(
    val fileName: String,
    cause: Throwable?,
) : Exception("$fileName exists but could not be read; refusing to overwrite it", cause)

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
 * run, and the Library writes it whenever a favorite is toggled — which a user can do while
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
 * favorite.
 */
object VaultIndex {

    const val FILE_NAME = "vault_index.json"

    // Enough to outlast a reader holding the index across a few attempts (~0.5s in total),
    // and short enough that a real permission failure still surfaces promptly.
    private const val MOVE_ATTEMPTS = 10
    private const val INITIAL_MOVE_BACKOFF_MILLIS = 5L
    private const val MAX_MOVE_BACKOFF_MILLIS = 100L

    // Same budget as the move retry above: enough to outlast a writer's in-flight replace,
    // short enough that a genuinely stuck read still surfaces (as emptyMap) promptly.
    private const val READ_ATTEMPTS = 10
    private const val INITIAL_READ_BACKOFF_MILLIS = 5L
    private const val MAX_READ_BACKOFF_MILLIS = 100L

    // Explicit defaults so a favorite is visible in the file rather than encoded as an
    // absence. This file is the only place one lives, and a user looking for it should be
    // able to find it by eye.
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // Guards the read-modify-write in every mutator below. One lock for the object rather
    // than one per folder: the app has a single output folder at a time, and a lock that is
    // occasionally too broad is the right trade against losing a favorite.
    private val lock = Mutex()

    private fun path(folder: String) = "$folder/$FILE_NAME".toPath()

    /**
     * The index key for a media file, given any path to it.
     *
     * The index is keyed by file name because that is what `scanMediaFiles` looks entries up
     * by, while the Library identifies an item by its absolute path. `substringAfterLast('/')`
     * is not the conversion between them: a Windows absolute path (`C:\vault\memory.jpg`)
     * contains no forward slash, so the whole path became the key and the next scan found
     * nothing under the file name — a favorite that appeared and then vanished on restart.
     */
    fun keyOf(path: String): String {
        val cut = path.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (cut == -1) path else path.substring(cut + 1)
    }

    /**
     * The index, or an empty map when it is missing or unreadable.
     *
     * Total by design. This is called from `scanMediaFiles` on every Library scan, which is
     * not a coroutine and has nowhere to put a failure; for a *reader*, "the facts are
     * unknown" and "there are no facts" lead to the same screen. It is mutation that must not
     * treat them alike — see [readForMutation].
     *
     * Retries a transient `IOException` while the target still exists, on the same budget as
     * [moveIntoPlaceRetrying]. Windows opening the index while [writeAtomically] is mid-replace
     * throws `FileNotFoundException` — not `AccessDeniedException` — with the message "the
     * process cannot access the file because it is being used by another process", and Java
     * gives that same exception type for a file that is genuinely absent. The two are told apart
     * by [FileSystem.exists] rather than by matching that message: if the target is gone, this
     * is the ordinary no-index-yet case and there is nothing to retry for; if it is still there,
     * the read hit the reader's own handle racing the writer's replace, and one more attempt a
     * few milliseconds later almost always lands on the writer's finished result. Without this,
     * a Library scan or a favorite toggle could observe a healthy index as empty (confirmed via
     * `VaultIndexConcurrencyTest` under real concurrent Windows I/O — see
     * docs/audits/windows-index-swap-2026-09-19.md).
     */
    fun read(fileSystem: FileSystem, folder: String): Map<String, FileMeta> {
        val target = path(folder)
        var backoffMillis = INITIAL_READ_BACKOFF_MILLIS
        repeat(READ_ATTEMPTS - 1) {
            val outcome = decode(fileSystem, target)
            val failure = outcome.exceptionOrNull()
            if (failure == null) return outcome.getOrThrow()
            if (failure !is IOException || !fileSystem.exists(target)) return outcome.getOrDefault(emptyMap())
            runBlocking { delay(backoffMillis) }
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_READ_BACKOFF_MILLIS)
        }
        // The last attempt is unguarded: whatever it returns (or defaults to) is the answer.
        return decode(fileSystem, target).getOrDefault(emptyMap())
    }

    private fun decode(fileSystem: FileSystem, target: Path): Result<Map<String, FileMeta>> = runCatching {
        json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(target) { readUtf8() })
    }

    /**
     * The index as a mutator must see it: empty when there is no file yet, and a throw when
     * there is one that cannot be parsed.
     *
     * Absence and damage are the same value to [read] and very different to a writer. No file
     * is the ordinary first-run state and an empty map is the truth. A file that exists and
     * will not parse is data — possibly every favorite the user has — and continuing would
     * replace it with whatever this caller happened to know (D05).
     */
    private fun readForMutation(fileSystem: FileSystem, folder: String): Map<String, FileMeta> {
        val target = path(folder)
        if (!fileSystem.exists(target)) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(target) { readUtf8() })
        }.getOrElse { throw VaultIndexUnreadableException(FILE_NAME, it) }
    }

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
    private suspend fun writeAtomically(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        val target = path(folder)
        val temp = "$folder/$FILE_NAME.tmp".toPath()
        fileSystem.write(temp) { writeUtf8(json.encodeToString(meta)) }
        runCatching { moveIntoPlaceRetrying(fileSystem, temp, target) }
            .onFailure { e ->
                // A temp file left behind is one the next write would find in its way, and a
                // stray vault_index.json.tmp in the output folder is not something a user
                // should have to wonder about.
                runCatching { fileSystem.delete(temp) }
                throw e
            }
    }

    /**
     * Replaces [target] with [temp], retrying briefly while the platform refuses.
     *
     * Windows will not replace a file another handle has open, so this move fails with
     * `AccessDeniedException` whenever a reader happens to hold the index — and [read] takes
     * no lock, by design, so that is an ordinary interleaving rather than a rare one. It cost
     * a favorite every time it happened: the write threw, and the heart the user pressed was
     * gone. POSIX `rename(2)` ignores open handles, which is why this only ever appeared on
     * the Windows build.
     *
     * A retry rather than a non-atomic copy: the reader's handle is gone in microseconds, and
     * copying onto [target] in place would reintroduce exactly the torn read the temp file
     * exists to prevent. The budget is bounded so a genuine permission problem still surfaces
     * rather than hanging — and the last failure is rethrown unchanged, so the caller sees the
     * real cause and not a wrapper.
     */
    private suspend fun moveIntoPlaceRetrying(fileSystem: FileSystem, temp: Path, target: Path) {
        var backoffMillis = INITIAL_MOVE_BACKOFF_MILLIS
        repeat(MOVE_ATTEMPTS - 1) {
            val failure = runCatching { fileSystem.atomicMove(temp, target) }.exceptionOrNull()
                ?: return
            // Anything that is not the filesystem refusing is not what the retry is for.
            if (failure !is IOException) throw failure
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_MOVE_BACKOFF_MILLIS)
        }
        // The last attempt is deliberately unguarded: its exception is the caller's answer.
        fileSystem.atomicMove(temp, target)
    }

    suspend fun write(fileSystem: FileSystem, folder: String, meta: Map<String, FileMeta>) {
        lock.withLock { writeAtomically(fileSystem, folder, meta) }
    }

    /**
     * Carries the user-owned fields of [onDisk] into [pipeline], which the pipeline builds
     * from scratch and so knows nothing about.
     *
     * The pipeline's own facts win — it just recomputed them. A file that only [pipeline]
     * knows about cannot have been favorited, since the user has not seen it yet.
     *
     * A union rather than a rewrite of [pipeline]: a run's map is *incremental*, seeded from
     * the index at start and added to as files are processed, so an entry it does not mention
     * is one it never looked at, not one it decided to drop. Rewriting instead of unioning
     * erased every entry written to disk after the run loaded its copy — which is precisely
     * the favorite-set-during-a-run case this is here to protect.
     */
    fun mergeUserFields(
        onDisk: Map<String, FileMeta>,
        pipeline: Map<String, FileMeta>,
    ): Map<String, FileMeta> = onDisk + pipeline.mapValues { (name, fresh) ->
        fresh.copy(favorited = onDisk[name]?.favorited ?: false)
    }

    /**
     * Writes [meta], preserving favorites currently on disk.
     *
     * Re-reads under the lock rather than trusting the copy a run loaded at its start: a
     * favorite toggled while the run was in flight exists only on disk, and would otherwise
     * be overwritten by the run's own final write.
     */
    /*
     * [carryFavorites] maps a source file to a file derived from it, and [remove] names entries
     * for files that no longer exist. Both exist for the combine step, which writes a new file
     * under a new name and then deletes its sources: without them the combined file lost its
     * source's favorite, and the source's entry lived on describing nothing (D11).
     *
     * The favorite is read from disk here, under the lock, rather than from the run's copy —
     * a heart pressed on the source while the run was in flight exists only on disk.
     */
    suspend fun writeMerging(
        fileSystem: FileSystem,
        folder: String,
        meta: Map<String, FileMeta>,
        carryFavorites: Map<String, String> = emptyMap(),
        remove: Set<String> = emptySet(),
    ) {
        lock.withLock {
            val onDisk = readForMutation(fileSystem, folder)
            val merged = mergeUserFields(onDisk, meta).toMutableMap()
            for ((source, derived) in carryFavorites) {
                val entry = merged[derived] ?: continue
                if (onDisk[source]?.favorited == true) merged[derived] = entry.copy(favorited = true)
            }
            remove.forEach { merged.remove(it) }
            writeAtomically(fileSystem, folder, merged)
        }
    }

    suspend fun setFavorite(
        fileSystem: FileSystem,
        folder: String,
        fileName: String,
        favorited: Boolean,
    ) {
        lock.withLock {
            val current = readForMutation(fileSystem, folder)
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
     * `hasGps` and `hasOverlay`. Taking favorites with it would destroy the one thing in
     * here that no re-run can rebuild. Entries with nothing left worth keeping are dropped,
     * and an index with no favorites at all is deleted outright — which is what reset used
     * to do unconditionally.
     */
    suspend fun resetKeepingFavorites(fileSystem: FileSystem, folder: String) {
        lock.withLock {
            val kept = readForMutation(fileSystem, folder)
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
