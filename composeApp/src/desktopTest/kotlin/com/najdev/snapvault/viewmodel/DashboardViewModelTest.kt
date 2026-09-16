package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.OutputDirectoryInUseException
import com.najdev.snapvault.OutputDirectoryLock
import com.najdev.snapvault.OutputDirectoryLocker
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.VaultIndex
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// These exercise the legacy pipeline with downloads/metadata disabled — that keeps the
// scenario fully in-memory (no real HTTP, no real ZIP files, no real exiftool/ffmpeg)
// while still driving a real DashboardViewModel run through startSync, the combine
// phase, and the terminal-state logic in full.
private class FakeZipPipelineRunner(
    private val combineResults: List<CombineResult>,
    // When set, simulates the real OverlayCombiner's post-combine date-fallback sub-phase:
    // onMetaStart fires only after every onProgress call has been delivered, matching the
    // real ordering the BUG-14 fix depends on.
    private val metaStartTotal: Int? = null,
    private val onAfterMetaStart: (suspend () -> Unit)? = null,
) : ZipPipelineRunner {
    override fun listZipFiles(folderPath: String): List<String> = emptyList()

    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit,
    ) = Unit

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit,
    ) {
        onStart(combineResults.size)
        combineResults.forEach(onProgress)
        metaStartTotal?.let {
            onMetaStart(it)
            onAfterMetaStart?.invoke()
        }
    }
}

internal class FakeMediaProcessor : MediaProcessor {
    override fun checkExifTool() = true
    override fun checkFFmpeg() = true
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
}

internal class FakePlatformPickers(
    private val htmlPath: String,
    private val outputDir: String,
) : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(htmlPath)
    override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(outputDir)
    override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
}

// Signals when it starts, then hangs until cancelled — for tests that just need "a run is
// genuinely still in progress" with no timing precision required.
private class HangingZipPipelineRunner(
    private val startedSignal: CompletableDeferred<Unit>,
) : ZipPipelineRunner {
    override fun listZipFiles(folderPath: String): List<String> = emptyList()
    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit,
    ) = Unit
    override suspend fun extractDownloadedArchives(outputDir: String, archivePaths: List<String>, onWarn: (String) -> Unit): List<String> {
        startedSignal.complete(Unit)
        awaitCancellation()
    }
    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit,
    ) = Unit
}

// Simulates the real BUG-06 timing: the first run's cancellation is acknowledged only
// after a short delay it deliberately doesn't respond to cancellation during (mirroring
// waiting for an in-flight ffmpeg/exiftool child to actually die); the second run takes
// noticeably longer and is never cancelled, giving the test a real window to check its
// state after the first (stale) job's cleanup has already run.
private class RaceZipPipelineRunner(
    private val startedSignal: CompletableDeferred<Unit>,
) : ZipPipelineRunner {
    private var callIndex = 0
    override fun listZipFiles(folderPath: String): List<String> = emptyList()
    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit,
    ) = Unit
    override suspend fun extractDownloadedArchives(outputDir: String, archivePaths: List<String>, onWarn: (String) -> Unit): List<String> {
        if (callIndex++ == 0) {
            startedSignal.complete(Unit)
            withContext(NonCancellable) { delay(50) }
            currentCoroutineContext().ensureActive()
        } else {
            delay(300)
        }
        return emptyList()
    }
    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit,
    ) = Unit
}

class DashboardViewModelTest {
    private val historyJson =
        """{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}"""

    private fun newViewModel(
        combineResults: List<CombineResult>,
        metaStartTotal: Int? = null,
        onAfterMetaStart: (() -> Unit)? = null,
    ): DashboardViewModel {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = FakeZipPipelineRunner(combineResults, metaStartTotal, onAfterMetaStart),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.changeImportMode(ImportMode.Legacy)
        viewModel.pickHtmlFile()
        viewModel.pickOutputFolder()
        return viewModel
    }

    private fun awaitCompletion(viewModel: DashboardViewModel) = runBlocking {
        withTimeout(5_000) {
            while (viewModel.isRunning) delay(10)
        }
    }

    // Regression for BUG-15: a run with a reported combine failure must not end in the
    // same unqualified "[SUCCESS] Sync complete!" state as a clean run — otherwise the
    // failure is invisible to anyone who doesn't expand the log.
    @Test
    fun runWithFailuresEndsInWarningStateNotUnqualifiedSuccess() {
        val viewModel = newViewModel(
            combineResults = listOf(
                CombineResult(uuid = "deadbeef-0001", outputPath = "", status = "error: boom"),
                CombineResult(uuid = "deadbeef-0003", outputPath = "", status = "error: boom"),
            ),
        )

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        assertTrue(viewModel.hasWarnings)
        // The Dashboard banner reports the count, not just that something failed, so the
        // number has to survive to the terminal state and not stay stuck at 0.
        assertEquals(2, viewModel.failureCount)
        assertEquals("Completed with warnings", viewModel.progressText)
        assertEquals(4, viewModel.currentStep)
        assertTrue(
            viewModel.logs.last().startsWith("[WARN] Sync complete"),
            "expected terminal log to flag the failure, was: ${viewModel.logs.last()}",
        )
    }

    // Companion case: a genuinely clean run must still report unqualified success —
    // the fix must not cry wolf on every run.
    @Test
    fun cleanRunStillReportsUnqualifiedSuccess() {
        val viewModel = newViewModel(
            combineResults = listOf(
                CombineResult(uuid = "deadbeef-0002", outputPath = "", status = "combined"),
            ),
        )

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        assertFalse(viewModel.hasWarnings)
        assertEquals(0, viewModel.failureCount)
        assertEquals("Pipeline Complete", viewModel.progressText)
        assertEquals(4, viewModel.currentStep)
        assertEquals("[SUCCESS] Sync complete!", viewModel.logs.last())
    }

    // Regression for BUG-04: the post-combine date-fallback sub-phase has no per-file
    // signal to report, so the UI must show an indeterminate ring instead of a
    // misleadingly precise 0% for however long it runs — and clear it once done.
    @Test
    fun indeterminateIsTrueDuringDateFallbackAndClearedAfter() {
        var indeterminateDuringFallback = false
        lateinit var viewModel: DashboardViewModel
        viewModel = newViewModel(
            combineResults = listOf(CombineResult(uuid = "aaaaaaaa-0001", outputPath = "", status = "combined")),
            metaStartTotal = 1,
            onAfterMetaStart = { indeterminateDuringFallback = viewModel.indeterminate },
        )

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        assertTrue(
            indeterminateDuringFallback,
            "expected indeterminate=true while the date-fallback sub-phase was in progress",
        )
        assertFalse(viewModel.indeterminate, "expected indeterminate to clear once the phase finished")
    }

    // Regression for BUG-14: the combine-phase summary used to log after the whole combine
    // phase returned — which is after the date-fallback sub-phase — so it read as
    // describing work that had already finished minutes earlier. It must now log the
    // moment the per-pair combine loop itself finishes, before the fallback's own log line.
    @Test
    fun combineSummaryLogsBeforeDateFallbackTaggingLine() {
        val viewModel = newViewModel(
            combineResults = listOf(CombineResult(uuid = "aaaaaaaa-0002", outputPath = "", status = "combined")),
            metaStartTotal = 1,
        )

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        val combinedIdx = viewModel.logs.indexOfFirst { it.startsWith("[INFO] Combined") }
        val taggingIdx = viewModel.logs.indexOfFirst { it.startsWith("[INFO] Tagging") }
        assertTrue(combinedIdx >= 0 && taggingIdx >= 0, "expected both log lines present, got: ${viewModel.logs}")
        assertTrue(
            combinedIdx < taggingIdx,
            "BUG-14 regression: 'Combined N overlay pairs' must log before 'Tagging N combined file(s)', got: ${viewModel.logs}",
        )
    }

    // Regression for BUG-12's root cause: Snapchat stores some overlays as WebP but
    // names them ".png"; exiftool rejects them by content, not extension.
    @Test
    fun isRiffMislabeledAsPngDetectsContentNotJustExtension() {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/out/overlay.png".toPath()) {
            write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0))
        }
        fs.write("/out/real.png".toPath()) {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }

        val viewModel = DashboardViewModel(
            zipPipelineRunner = FakeZipPipelineRunner(emptyList()),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )

        assertTrue(viewModel.isRiffMislabeledAsPng("/out/overlay.png"))
        assertFalse(viewModel.isRiffMislabeledAsPng("/out/real.png"))
        assertFalse(viewModel.isRiffMislabeledAsPng("/out/does-not-exist.png"))
        assertFalse(viewModel.isRiffMislabeledAsPng("/out/not-a-png.jpg"))
    }

    // Regression for BUG-06: job.cancel() flips Job.isActive false immediately, well
    // before the cancelled coroutine actually unwinds to its finally block. A run started
    // in that window must not have its isRunning = true clobbered back to false by the
    // stale job's belated cleanup.
    @Test
    fun stopThenImmediateStartDoesNotLetStaleJobClobberNewRun() {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }

        val startedSignal = CompletableDeferred<Unit>()
        val viewModel = DashboardViewModel(
            zipPipelineRunner = RaceZipPipelineRunner(startedSignal),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.changeImportMode(ImportMode.Legacy)
        viewModel.pickHtmlFile()
        viewModel.pickOutputFolder()

        fun startPipeline() = viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )

        startPipeline()
        runBlocking { withTimeout(5_000) { startedSignal.await() } }

        viewModel.stopSync()
        // Immediately start a second run — bypasses the UI's isRunning-gated Start button
        // entirely, exercising the ViewModel API directly the way the race actually happens.
        startPipeline()

        // Give the first (stopped) job time to finish its delayed cancellation and reach
        // its finally block, while the second run is still well inside its own longer delay.
        runBlocking { delay(150) }

        assertTrue(
            viewModel.isRunning,
            "BUG-06 regression: a stale job's belated cleanup clobbered the new run's isRunning state",
        )

        awaitCompletion(viewModel)
        assertFalse(viewModel.isRunning)
    }

    // Regression for BUG-16: resetVaultIndex is reachable from Settings on a separate
    // screen — it must refuse to run while a sync is in progress rather than deleting the
    // index out from under a pipeline that's about to read or write it.
    @Test
    fun resetVaultIndexIsRefusedWhileRunning() {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }
        fs.write("/out/vault_index.json".toPath()) { writeUtf8("{}") }

        val startedSignal = CompletableDeferred<Unit>()
        val viewModel = DashboardViewModel(
            zipPipelineRunner = HangingZipPipelineRunner(startedSignal),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.changeImportMode(ImportMode.Legacy)
        viewModel.pickHtmlFile()
        viewModel.pickOutputFolder()

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        runBlocking { withTimeout(5_000) { startedSignal.await() } }

        assertFalse(
            runBlocking { viewModel.resetVaultIndex() },
            "must refuse to reset the vault index while a run is in progress",
        )
        assertTrue(fs.exists("/out/vault_index.json".toPath()), "vault_index.json must survive a refused reset")

        viewModel.stopSync()
        awaitCompletion(viewModel)
    }

    // Reset used to delete vault_index.json outright. That was right when the file held only
    // what the pipeline could recompute; it stopped being right the moment favorites moved
    // in, because a re-run rebuilds hasGps and hasOverlay and cannot rebuild a favorite.
    @Test
    fun resetVaultIndexKeepsFavoritesAndClearsEverythingElse() {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/out/vault_index.json".toPath()) {
            writeUtf8(
                """{"kept.jpg":{"hasGps":true,"hasOverlay":true,"favorited":true},""" +
                    """"plain.jpg":{"hasGps":true,"hasOverlay":true,"favorited":false}}"""
            )
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.pickOutputFolder()

        assertTrue(runBlocking { viewModel.resetVaultIndex() })

        val after = VaultIndex.read(fs, "/out")
        assertEquals(setOf("kept.jpg"), after.keys, "only the favorite survives a reset")
        assertEquals(
            FileMeta(hasGps = false, hasOverlay = false, favorited = true),
            after["kept.jpg"],
            "the processing state must be cleared so the next run re-processes the file",
        )
    }

    @Test
    fun resetVaultIndexRemovesTheIndexWhenNothingWasFavorited() {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/out/vault_index.json".toPath()) {
            writeUtf8("""{"plain.jpg":{"hasGps":true,"hasOverlay":true}}""")
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.pickOutputFolder()

        assertTrue(runBlocking { viewModel.resetVaultIndex() })

        assertFalse(fs.exists("/out/vault_index.json".toPath()))
    }

    // The hazard the whole VaultIndex indirection exists for. DashboardViewModel builds its
    // FileMeta entries from scratch at five sites and writes the map wholesale at the end of
    // a run, from a copy loaded when the run started — so a favorite toggled while a sync is
    // in progress lives only on disk, and the run's own final write erases it.
    //
    // The favorite is set from inside combineAll, which is the only hook that runs after the
    // index has been loaded and before it is written back.
    @Test
    fun aFavoriteSetDuringARunSurvivesThatRunsIndexWrite() {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }

        val viewModel = DashboardViewModel(
            zipPipelineRunner = FakeZipPipelineRunner(
                combineResults = listOf(
                    CombineResult(uuid = "deadbeef-0002", outputPath = "", status = "combined"),
                ),
                metaStartTotal = 1,
                onAfterMetaStart = { VaultIndex.setFavorite(fs, "/out", "kept.jpg", true) },
            ),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.changeImportMode(ImportMode.Legacy)
        viewModel.pickHtmlFile()
        viewModel.pickOutputFolder()

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        assertEquals(
            true,
            VaultIndex.read(fs, "/out")["kept.jpg"]?.favorited,
            "the run wrote its own map over the index and took the favorite with it",
        )
    }

    // ── D07: one writer per output directory ─────────────────────────────────

    // A refused run must be refused before it touches anything. The point of the lock is that
    // the second window does no work at all, so the runner erroring is the assertion.
    private class NeverRunZipPipelineRunner : ZipPipelineRunner {
        override fun listZipFiles(folderPath: String): List<String> =
            error("a refused run must not reach the pipeline")

        override suspend fun extractAll(
            itemsByZip: Map<String, List<HtmlMemoryEntry>>,
            outputDir: String,
            workerCount: Int,
            onProgress: (ExtractResult) -> Unit,
        ) = error("a refused run must not reach the pipeline")

        override suspend fun extractDownloadedArchives(
            outputDir: String,
            archivePaths: List<String>,
            onWarn: (String) -> Unit,
        ): List<String> = error("a refused run must not reach the pipeline")

        override suspend fun combineAll(
            outputDir: String,
            deleteOriginals: Boolean,
            workerCount: Int,
            onStart: (total: Int) -> Unit,
            onMetaStart: (total: Int) -> Unit,
            onMetaError: ((String) -> Unit)?,
            onProgress: (CombineResult) -> Unit,
        ) = error("a refused run must not reach the pipeline")
    }

    private class RefusingOutputDirectoryLocker : OutputDirectoryLocker {
        override fun lock(folder: String, onWarn: (String) -> Unit): OutputDirectoryLock =
            throw OutputDirectoryInUseException(folder)
    }

    private class RecordingOutputDirectoryLocker : OutputDirectoryLocker {
        var locked: String? = null
            private set

        @Volatile
        var released = false
            private set

        override fun lock(folder: String, onWarn: (String) -> Unit): OutputDirectoryLock {
            locked = folder
            return object : OutputDirectoryLock {
                override fun release() {
                    released = true
                }
            }
        }
    }

    private fun lockingViewModel(
        runner: ZipPipelineRunner,
        locker: OutputDirectoryLocker,
    ): DashboardViewModel {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }
        return DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = locker,
        ).also {
            it.changeImportMode(ImportMode.Legacy)
            it.pickHtmlFile()
            it.pickOutputFolder()
        }
    }

    private fun DashboardViewModel.start() = startSync(
        runDownload = false,
        runMetadata = false,
        experimentalMetadataMatching = false,
        runCombine = true,
        runDedupe = false,
        dryRun = false,
    )

    // D07: two SnapVault windows on one library share no lock, semaphore or mutex — every
    // guard the app has is process-local. The second run has to stop at the door, and say why
    // in words a user can act on rather than failing somewhere deep in the pipeline.
    @Test
    fun aRunIsRefusedWhileAnotherWindowHoldsTheOutputDirectory() {
        val viewModel = lockingViewModel(NeverRunZipPipelineRunner(), RefusingOutputDirectoryLocker())

        viewModel.start()
        awaitCompletion(viewModel)

        assertEquals("Failed", viewModel.progressText)
        assertEquals(0, viewModel.currentStep)
        assertFalse(viewModel.isRunning)
        assertTrue(
            viewModel.logs.last().contains("already being updated"),
            "the refusal must name its reason, was: ${viewModel.logs.last()}",
        )
    }

    // A lock held past the end of a run is a library the user can never sync again — and the
    // one that never gets released is the one whose run failed, so the release cannot live on
    // the success path.
    @Test
    fun theOutputDirectoryIsReleasedWhenTheRunEnds() {
        val locker = RecordingOutputDirectoryLocker()
        val viewModel = lockingViewModel(FakeZipPipelineRunner(emptyList()), locker)

        viewModel.start()
        awaitCompletion(viewModel)

        assertEquals("/out", locker.locked, "the run must claim the directory it writes")
        assertTrue(locker.released, "a finished run must hand the directory back")
    }

    // ── D08: a repeated export row is one file ───────────────────────────────

    private class RecordingArchiveRunner : ZipPipelineRunner {
        var archivePaths: List<String>? = null
            private set

        override fun listZipFiles(folderPath: String): List<String> = emptyList()
        override suspend fun extractAll(
            itemsByZip: Map<String, List<HtmlMemoryEntry>>,
            outputDir: String,
            workerCount: Int,
            onProgress: (ExtractResult) -> Unit,
        ) = Unit

        override suspend fun extractDownloadedArchives(
            outputDir: String,
            archivePaths: List<String>,
            onWarn: (String) -> Unit,
        ): List<String> {
            this.archivePaths = archivePaths
            return emptyList()
        }

        override suspend fun combineAll(
            outputDir: String,
            deleteOriginals: Boolean,
            workerCount: Int,
            onStart: (total: Int) -> Unit,
            onMetaStart: (total: Int) -> Unit,
            onMetaError: ((String) -> Unit)?,
            onProgress: (CombineResult) -> Unit,
        ) = Unit
    }

    // D08's downstream half. Repeated rows now resolve to one file, and everything after the
    // download phase is per *file*: handing the same archive to the extractor twice makes it
    // report the second as missing (it deleted it the first time), and the metadata pass would
    // re-run exiftool over it and inflate its own total.
    @Test
    fun aRepeatedExportRowIsOneFileForThePhasesAfterTheDownload() {
        val repeatedRow = """{"Download Link": "https://example.com/mem.zip?mid=abc-123", """ +
            """"Date": "2024-01-01 00:00:00 UTC"}"""
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8("""{"Saved Media": [$repeatedRow, $repeatedRow]}""") }

        val runner = RecordingArchiveRunner()
        val viewModel = DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            httpClientFactory = {
                HttpClient(MockEngine { respond("zip-bytes", headers = headersOf(HttpHeaders.ContentType, "application/zip")) })
            },
        )
        viewModel.changeImportMode(ImportMode.Legacy)
        viewModel.pickHtmlFile()
        viewModel.pickOutputFolder()

        viewModel.startSync(
            runDownload = true,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = false,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        assertEquals(
            listOf("/out/2024-01-01_000000_abc-123.zip"),
            runner.archivePaths,
            "the same archive must not be handed to the extractor twice",
        )
    }

    // Stopping is the most likely way a long run ends, so it is the path most likely to strand
    // the lock. Cancellation unwinds through `finally`, which is why the release belongs there.
    @Test
    fun theOutputDirectoryIsReleasedWhenTheRunIsCancelled() {
        val startedSignal = CompletableDeferred<Unit>()
        val locker = RecordingOutputDirectoryLocker()
        val viewModel = lockingViewModel(HangingZipPipelineRunner(startedSignal), locker)

        viewModel.start()
        runBlocking { withTimeout(5_000) { startedSignal.await() } }
        viewModel.stopSync()
        awaitCompletion(viewModel)

        assertTrue(locker.released, "a cancelled run must hand the directory back too")
    }
}
