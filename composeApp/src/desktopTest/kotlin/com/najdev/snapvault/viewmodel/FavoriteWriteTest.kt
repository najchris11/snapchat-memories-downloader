package com.najdev.snapvault.viewmodel

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

    private fun viewModel(fs: FileSystem): DashboardViewModel {
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }
        return DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
        ).apply { pickOutputFolder() }
    }

    private fun awaitFavorite(viewModel: DashboardViewModel, id: String) = runBlocking {
        withTimeout(5_000) {
            while (viewModel.favoriteIsPending(id)) delay(5)
        }
    }

    @Test
    fun aFavoriteIsWrittenUnderItsFileNameAndShownImmediately() {
        val fs = FakeFileSystem()
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
    @Test
    fun aFailedWriteRevertsTheHeartAndSaysSo() {
        val fs = FakeFileSystem()
        val viewModel = viewModel(WriteFailingFileSystem(fs))

        viewModel.setFavorite("/out/memory.jpg", true)
        assertEquals(mapOf("/out/memory.jpg" to true), viewModel.favoriteOverrides)

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

    // The overlay bridges the gap between the press and the scan that reflects it. Left in
    // place afterwards it masks the truth on disk — and Refresh is exactly what a user presses
    // to ask whether something saved, so a stale override answers that question wrongly.
    @Test
    fun anOverrideIsDroppedOnceAScanReflectsIt() {
        val fs = FakeFileSystem()
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
        val fs = FakeFileSystem()
        val gate = BlockingWriteFileSystem(fs)
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
        val fs = FakeFileSystem()
        val viewModel = viewModel(fs)

        viewModel.setFavorite("/out/memory.jpg", true)
        awaitFavorite(viewModel, "/out/memory.jpg")

        viewModel.reconcileFavorites(listOf("/out/something-else.jpg"))

        assertEquals(mapOf("/out/memory.jpg" to true), viewModel.favoriteOverrides)
    }

    @Test
    fun aFavoriteWithNoOutputFolderIsNotLeftPendingForever() {
        val fs = FakeFileSystem()
        val viewModel = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
        )

        viewModel.setFavorite("/out/memory.jpg", true)

        assertEquals(false, viewModel.favoriteIsPending("/out/memory.jpg"))
        assertEquals(emptyMap(), viewModel.favoriteOverrides, "there is nowhere to save it, so do not pretend")
    }
}

/**
 * Fails writes of the index, the way a full disk or a revoked permission would.
 *
 * Scoped to that one file so the test's own setup still works — failing every write would
 * take down the fixture before the case under test ran.
 */
private class WriteFailingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    override fun sink(file: Path, mustCreate: Boolean): Sink =
        if (VaultIndex.FILE_NAME in file.name) throw IOException("disk is full")
        else super.sink(file, mustCreate)
}

/**
 * Holds index writes open until released, so a scan can be raced against one.
 *
 * Scoped to the index file for the same reason as above, and with a bounded wait: this blocks
 * while VaultIndex's lock is held, and that lock is one object-level Mutex shared by every
 * test in the JVM — an unbounded wait here would wedge the whole suite rather than fail.
 */
private class BlockingWriteFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    private val gate = java.util.concurrent.CountDownLatch(1)

    fun release() = gate.countDown()

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (VaultIndex.FILE_NAME in file.name) {
            check(gate.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "gate never released" }
        }
        return super.sink(file, mustCreate)
    }
}
