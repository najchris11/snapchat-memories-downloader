package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.SerializedFileSystem
import com.najdev.snapvault.VaultIndex
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A favorite is the only thing in SnapVault no re-run can rebuild, so the one unacceptable
 * outcome is the heart lighting up over a write that never landed.
 *
 * The optimistic overlay and the write that backs it both used to live in `LibraryScreen`, on
 * a `rememberCoroutineScope()`. That put three ways to lose one in the same six lines: the
 * write was wrapped in a bare `runCatching { }` so a failure was swallowed whole, the overlay
 * was never reverted when it failed, and the scope died with the composition — so navigating
 * away right after pressing could cancel the write outright. All three now live here, on a
 * scope that outlives the screen.
 */
class FavoriteWriteTest {

    private val historyJson =
        """{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}"""

    /**
     * The fake these tests share between the view model's writer and the test thread.
     *
     * The writer saves on the IO dispatcher while the test polls the same index, and okio's
     * FakeFileSystem is not thread-safe: under a busy suite a save threw from inside the fake,
     * the writer treated it as a failed save and reverted the heart, and a test failed on a bug
     * the app does not have. Serialized, it is safe to share. Moving onto an open file is
     * allowed because the fake otherwise emulates Windows, where a rename onto a file another
     * thread is reading fails — which these tests are not about, and which is recorded in the
     * remediation plan as its own open question.
     */
    private fun sharedFileSystem(): FileSystem =
        SerializedFileSystem(FakeFileSystem().apply { allowMovingOpenFiles = true })

    private fun viewModel(fs: FileSystem): DashboardViewModel {
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }
        return DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
        ).apply { pickOutputFolder() }
    }

    private fun awaitFavorite(viewModel: DashboardViewModel, id: String) = runBlocking {
        withTimeout(30_000) {
            while (viewModel.favoriteIsPending(id)) delay(5)
        }
    }

    @Test
    fun aFavoriteIsWrittenUnderItsFileNameAndShownImmediately() {
        val fs = sharedFileSystem()
        val viewModel = viewModel(fs)

        viewModel.setFavorite("/out/memory.jpg", true)

        // Optimistic: visible before the write has had a chance to land.
        assertEquals(mapOf("/out/memory.jpg" to true), viewModel.favoriteOverrides)
        awaitFavorite(viewModel, "/out/memory.jpg")
        assertEquals(true, VaultIndex.read(fs, "/out")["memory.jpg"]?.favorited)
    }

    // The swallowed-failure case. A bare runCatching left the heart lit over nothing: no
    // revert, no log, no signal of any kind. Silence is the worst possible answer here,
    // because the user has no other copy of this.
    //
    // The write is gated rather than merely slow: the optimistic state is only observable
    // until the failure lands, so without the gate this assertion depends on which of the two
    // wins the race. It passed locally and failed on CI, which is exactly the shape of test
    // that should not have shipped.
    @Test
    fun aFailedWriteRevertsTheHeartAndSaysSo() {
        val fs = sharedFileSystem()
        val control = IndexWriteControl(fs, blockFrom = 1, failAt = setOf(1))
        val viewModel = viewModel(control)

        viewModel.setFavorite("/out/memory.jpg", true)

        // Deterministic: the write cannot have failed yet, it is held at the gate.
        assertEquals(mapOf("/out/memory.jpg" to true), viewModel.favoriteOverrides)

        control.release()
        awaitFavorite(viewModel, "/out/memory.jpg")

        assertEquals(
            emptyMap(),
            viewModel.favoriteOverrides,
            "a heart left lit over a failed write is a favorite the user believes is saved",
        )
        assertTrue(
            viewModel.logs.any { it.startsWith("[WARN]") && it.contains("favorite", ignoreCase = true) },
            "the failure has to be reported, was: ${viewModel.logs}",
        )
    }

    // ── Rapid toggles on one item ────────────────────────────────────────────

    // Two presses on one item, with the first write held: the newest press is what the user
    // sees immediately, the item stays pending throughout, and the newest value is what lands.
    @Test
    fun rapidTogglesOnOneItemPersistInPressOrder() {
        val fs = sharedFileSystem()
        val control = IndexWriteControl(fs, blockFrom = 1)
        val viewModel = viewModel(control)

        viewModel.setFavorite("/out/memory.jpg", true)
        viewModel.setFavorite("/out/memory.jpg", false)

        // The newest press is what the user sees, straight away.
        assertEquals(mapOf("/out/memory.jpg" to false), viewModel.favoriteOverrides)
        assertTrue(viewModel.favoriteIsPending("/out/memory.jpg"))

        control.release()
        awaitFavorite(viewModel, "/out/memory.jpg")

        assertEquals(
            false,
            VaultIndex.read(fs, "/out")["memory.jpg"]?.favorited,
            "the older press landed last and won",
        )
    }

    // Fifty presses in a row, none gated: the last one has to be the one on disk, and the one
    // press that settles.
    //
    // What this does *not* prove is the single ordered writer. A coroutine per press passes
    // this too, because launching from one thread onto Dispatchers.Default queues FIFO and
    // kotlinx's Mutex is fair, so order survives in practice — verified by reverting to
    // per-press coroutines and watching all of these still pass. Neither of those is a
    // documented guarantee, which is why the writer is still a single consumer; but the
    // guarantee is structural, not something this test discriminates. What it does catch is
    // the generation guard: without it, an earlier press settles the item and the last press's
    // value is not what ends up on disk.
    @Test
    fun manyRapidTogglesEndAtTheLastPress() {
        val fs = sharedFileSystem()
        val viewModel = viewModel(fs)

        repeat(50) { viewModel.setFavorite("/out/memory.jpg", it % 2 == 0) }
        // Press 50 is index 49, so the last thing the user asked for is false.
        assertEquals(mapOf("/out/memory.jpg" to false), viewModel.favoriteOverrides)

        awaitFavorite(viewModel, "/out/memory.jpg")

        assertEquals(
            false,
            VaultIndex.read(fs, "/out")["memory.jpg"]?.favorited,
            "some press other than the last one landed last",
        )
    }

    // pendingFavorites was a Set of ids, so the first write's completion removed the id while
    // the second was still in flight. A scan arriving in that window would then reconcile away
    // an override the disk had not caught up with.
    @Test
    fun anEarlierWriteCompletingDoesNotSettleAStillPendingLaterToggle() {
        val fs = sharedFileSystem()
        val control = IndexWriteControl(fs, blockFrom = 2)
        val viewModel = viewModel(control)

        viewModel.setFavorite("/out/memory.jpg", true)
        viewModel.setFavorite("/out/memory.jpg", false)

        // Let the first write land; the second is held at the gate.
        runBlocking {
            withTimeout(30_000) {
                while (VaultIndex.read(fs, "/out")["memory.jpg"]?.favorited != true) delay(5)
            }
        }

        assertTrue(
            viewModel.favoriteIsPending("/out/memory.jpg"),
            "an older write cleared the pending flag belonging to a newer press",
        )
        // …so a scan arriving now must not reconcile the override away.
        viewModel.reconcileFavorites(listOf("/out/memory.jpg"))
        assertEquals(mapOf("/out/memory.jpg" to false), viewModel.favoriteOverrides)

        control.release()
        awaitFavorite(viewModel, "/out/memory.jpg")
        assertEquals(false, VaultIndex.read(fs, "/out")["memory.jpg"]?.favorited)
    }

    // A failure belongs to the press that caused it. An older write failing must not revert an
    // override the user has since changed — the newer press is still in flight and still true.
    @Test
    fun anOlderFailedWriteDoesNotRevertANewerToggle() {
        val fs = sharedFileSystem()
        val control = IndexWriteControl(fs, blockFrom = 2, failAt = setOf(1))
        val viewModel = viewModel(control)

        viewModel.setFavorite("/out/memory.jpg", true)   // this one fails
        viewModel.setFavorite("/out/memory.jpg", false)  // this one is what the user meant

        // The second write reaching the gate means the first has already failed.
        control.awaitBlocked()
        try {
            assertEquals(
                mapOf("/out/memory.jpg" to false),
                viewModel.favoriteOverrides,
                "a superseded write's failure reverted the press that replaced it",
            )
            assertTrue(viewModel.favoriteIsPending("/out/memory.jpg"))
            assertEquals(
                emptyList(),
                viewModel.logs.filter { it.contains("favorite", ignoreCase = true) },
                "a superseded write's failure is not the user's problem and must not be reported",
            )
        } finally {
            control.release()
        }
        awaitFavorite(viewModel, "/out/memory.jpg")
        assertEquals(false, VaultIndex.read(fs, "/out")["memory.jpg"]?.favorited)
    }

    // The overlay bridges the gap between the press and the scan that reflects it. Left in
    // place afterwards it masks the truth on disk — and Refresh is exactly what a user presses
    // to ask whether something saved, so a stale override answers that question wrongly.
    @Test
    fun anOverrideIsDroppedOnceAScanReflectsIt() {
        val fs = sharedFileSystem()
        val viewModel = viewModel(fs)

        viewModel.setFavorite("/out/memory.jpg", true)
        awaitFavorite(viewModel, "/out/memory.jpg")

        viewModel.reconcileFavorites(listOf("/out/memory.jpg"))

        assertEquals(
            emptyMap(),
            viewModel.favoriteOverrides,
            "after a scan, the disk is the truth and the override is just in the way",
        )
    }

    // …but not while its write is still in flight, or the heart would flicker off between the
    // press and the write landing.
    @Test
    fun anOverrideSurvivesAScanWhileItsWriteIsStillPending() {
        val fs = sharedFileSystem()
        val gate = IndexWriteControl(fs, blockFrom = 1)
        val viewModel = viewModel(gate)

        viewModel.setFavorite("/out/memory.jpg", true)
        viewModel.reconcileFavorites(listOf("/out/memory.jpg"))

        assertEquals(
            mapOf("/out/memory.jpg" to true),
            viewModel.favoriteOverrides,
            "a scan that raced the write must not undo the press",
        )

        gate.release()
        awaitFavorite(viewModel, "/out/memory.jpg")
        viewModel.reconcileFavorites(listOf("/out/memory.jpg"))
        assertEquals(emptyMap(), viewModel.favoriteOverrides)
    }

    // An override for a file the scan did not mention is about some other folder, and a scan
    // of this one says nothing about it either way.
    @Test
    fun aScanOnlyReconcilesTheFilesItActuallySaw() {
        val fs = sharedFileSystem()
        val viewModel = viewModel(fs)

        viewModel.setFavorite("/out/memory.jpg", true)
        awaitFavorite(viewModel, "/out/memory.jpg")

        viewModel.reconcileFavorites(listOf("/out/something-else.jpg"))

        assertEquals(mapOf("/out/memory.jpg" to true), viewModel.favoriteOverrides)
    }

    @Test
    fun aFavoriteWithNoOutputFolderIsNotLeftPendingForever() {
        val fs = sharedFileSystem()
        val viewModel = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
        )

        viewModel.setFavorite("/out/memory.jpg", true)

        assertEquals(false, viewModel.favoriteIsPending("/out/memory.jpg"))
        assertEquals(emptyMap(), viewModel.favoriteOverrides, "there is nowhere to save it, so do not pretend")
    }
}

/**
 * Controls index writes so a test can inspect state at an exact point.
 *
 * Scoped to the index file because the fixture's own setup writes through the same filesystem,
 * and bounded because a blocked write holds VaultIndex's object-level lock — an unbounded wait
 * would wedge every other test in the JVM rather than fail this one.
 */
private class IndexWriteControl(
    delegate: FileSystem,
    /** 1-based index of the first index write to hold at the gate. */
    private val blockFrom: Int = Int.MAX_VALUE,
    /** 1-based indices of index writes that should fail instead of succeeding. */
    private val failAt: Set<Int> = emptySet(),
) : ForwardingFileSystem(delegate) {

    private val writes = java.util.concurrent.atomic.AtomicInteger()
    private val gate = java.util.concurrent.CountDownLatch(1)
    private val reachedGate = java.util.concurrent.CountDownLatch(1)

    fun release() = gate.countDown()

    /** Blocks until a write has reached the gate, so every earlier write has finished. */
    fun awaitBlocked() =
        check(reachedGate.await(60, java.util.concurrent.TimeUnit.SECONDS)) { "no write reached the gate" }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (VaultIndex.FILE_NAME !in file.name) return super.sink(file, mustCreate)

        val n = writes.incrementAndGet()
        if (n >= blockFrom) {
            reachedGate.countDown()
            check(gate.await(60, java.util.concurrent.TimeUnit.SECONDS)) { "gate never released" }
        }
        if (n in failAt) throw IOException("disk is full")
        return super.sink(file, mustCreate)
    }
}
