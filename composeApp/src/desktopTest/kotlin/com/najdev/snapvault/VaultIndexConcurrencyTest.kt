package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every mutation of `vault_index.json` is a read-modify-write, and two of them genuinely
 * overlap in this app: the pipeline writes the index at the end of a run, and the Library
 * writes it whenever a favourite is toggled — which a user can do *while* a sync is running.
 *
 * Unsynchronised, two mutators read the same index and whichever writes second discards the
 * other's work. `VaultIndexTest` cannot catch this: its calls are sequential, so each one sees
 * the previous one's write and the interleaving never happens.
 *
 * These tests run on [Dispatchers.IO] with real files rather than a FakeFileSystem, because
 * the failure being pinned is a timing one and a fake single-threaded filesystem would not
 * produce it.
 */
class VaultIndexConcurrencyTest {

    private lateinit var dir: File
    private val fs = FileSystem.SYSTEM

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("vault-index-concurrent", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val folder get() = dir.absolutePath

    // The plain lost-update case. Without the lock every one of these reads an index missing
    // most of the others, and the last writer wins — the observed count lands well below 64
    // and varies run to run.
    @Test
    fun concurrentFavouritesDoNotOverwriteEachOther() = runBlocking {
        val count = 64
        val names = (0 until count).map { "memory-$it.jpg" }

        coroutineScope {
            names.map { name ->
                async(Dispatchers.IO) { VaultIndex.setFavorite(fs, folder, name, true) }
            }.awaitAll()
        }

        val index = VaultIndex.read(fs, folder)
        assertEquals(
            names.toSet(),
            index.keys,
            "${names.size - index.size} favourite(s) were lost to an interleaved write",
        )
        assertTrue(index.values.all { it.favorited })
    }

    // The case the feature exists to protect, run for real rather than in sequence: the
    // pipeline's own index write overlapping a favourite toggle. Neither may erase the other —
    // the pipeline's facts must land, and so must every favourite.
    @Test
    fun aPipelineWriteAndAFavouriteToggleDoNotErodeEachOther() = runBlocking {
        val favourites = (0 until 32).map { "fav-$it.jpg" }
        val processed = (0 until 32).associate {
            "processed-$it.jpg" to FileMeta(hasGps = true, hasOverlay = true)
        }

        coroutineScope {
            favourites.forEach { name ->
                launch(Dispatchers.IO) { VaultIndex.setFavorite(fs, folder, name, true) }
            }
            // Interleaved with the toggles above, the way a run's final write interleaves with
            // a user pressing the heart while it finishes.
            repeat(8) {
                launch(Dispatchers.IO) { VaultIndex.writeMerging(fs, folder, processed) }
            }
        }

        val index = VaultIndex.read(fs, folder)
        assertEquals(
            favourites.toSet(),
            index.filterValues { it.favorited }.keys,
            "a pipeline write erased a favourite set while it was running",
        )
        assertEquals(
            processed.keys,
            index.keys - favourites.toSet(),
            "a favourite toggle erased what the run had recorded",
        )
    }

    // A reader holds no lock — scanMediaFiles is not a coroutine and calls read() on every
    // Library scan. That is only safe because a write replaces the file atomically. Truncating
    // the real file in place let a read land on partial JSON, which read() turns into an empty
    // map without complaint: every item would come back with no GPS, no overlay, and no
    // favourite.
    @Test
    fun aReadDuringAWriteNeverObservesAPartiallyWrittenIndex() = runBlocking {
        // Big enough that serialising it spans more than one write to the underlying file.
        val big = (0 until 800).associate {
            "memory-$it.jpg" to FileMeta(hasGps = true, hasOverlay = true, favorited = it % 2 == 0)
        }
        VaultIndex.write(fs, folder, big)

        val empties = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            val writers = List(16) {
                launch(Dispatchers.IO) { VaultIndex.write(fs, folder, big) }
            }
            val readers = List(16) {
                launch(Dispatchers.IO) {
                    repeat(50) {
                        if (VaultIndex.read(fs, folder).isEmpty()) empties.incrementAndGet()
                    }
                }
            }
            (writers + readers).forEach { it.join() }
        }

        assertEquals(
            0,
            empties.get(),
            "a read saw a half-written index and silently turned it into an empty one",
        )
    }

    // The temp file is an implementation detail and must not be left lying around — the next
    // write would collide with it, and a stray vault_index.json.tmp in the output folder is
    // not something a user should have to wonder about.
    @Test
    fun writingLeavesNoTemporaryFileBehind() = runBlocking {
        VaultIndex.setFavorite(fs, folder, "memory.jpg", true)
        VaultIndex.writeMerging(fs, folder, mapOf("memory.jpg" to FileMeta(hasGps = true, hasOverlay = false)))

        assertEquals(
            listOf(VaultIndex.FILE_NAME),
            dir.list()!!.sorted(),
            "the output folder should hold the index and nothing else",
        )
    }
}
