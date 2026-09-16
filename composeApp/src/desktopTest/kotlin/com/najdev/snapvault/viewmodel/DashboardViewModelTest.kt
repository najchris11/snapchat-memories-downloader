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
import okio.ForwardingFileSystem
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

        assertEquals(
            DashboardViewModel.IndexResetOutcome.RunInProgress,
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

        assertEquals(DashboardViewModel.IndexResetOutcome.Cleared, runBlocking { viewModel.resetVaultIndex() })

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

        assertEquals(DashboardViewModel.IndexResetOutcome.Cleared, runBlocking { viewModel.resetVaultIndex() })

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

    // ── Closing must not drop a favorite (Task 2.6) ──────────────────────────

    /**
     * A filesystem whose index commits take [commitDelayMs] of real time.
     *
     * VaultIndex writes a temp file and moves it into place, so slowing the move holds a
     * favorite "in flight" for as long as a test needs — the window a close lands in.
     */
    private class SlowCommitFileSystem(
        delegate: FakeFileSystem,
        private val commitDelayMs: Long,
    ) : ForwardingFileSystem(delegate) {
        override fun atomicMove(source: okio.Path, target: okio.Path) {
            Thread.sleep(commitDelayMs)
            super.atomicMove(source, target)
        }
    }

    private fun favoritesViewModel(
        fileSystem: okio.FileSystem,
        runner: ZipPipelineRunner = NoOpZipPipelineRunner,
        locker: OutputDirectoryLocker = UnenforcedOutputDirectoryLocker,
    ) = DashboardViewModel(
        zipPipelineRunner = runner,
        mediaProcessor = FakeMediaProcessor(),
        fileSystem = fileSystem,
        pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
        outputDirectoryLocker = locker,
    ).also {
        it.changeImportMode(ImportMode.Legacy)
        it.pickHtmlFile()
        it.pickOutputFolder()
    }

    private fun DashboardViewModel.awaitCloseSettled(): DashboardViewModel.CloseState = runBlocking {
        withTimeout(15_000) {
            while (closeState == DashboardViewModel.CloseState.Closing) delay(10)
        }
        closeState
    }

    private fun FakeFileSystem.favoriteOnDisk(name: String) = VaultIndex.read(this, "/out")[name]?.favorited

    // Closing the window called exitApplication directly. Composition disposal then closed
    // the favorites queue and cancelled the scope its writer ran in, in the same breath — so
    // a heart pressed just before closing was killed mid-write and nothing said so. A favorite
    // is in no export and no re-run rebuilds it; there is no other copy.
    @Test
    fun closingWaitsForAPendingFavoriteToSaveBeforeItIsReadyToExit() {
        val disk = FakeFileSystem().apply { createDirectories("/out".toPath()) }
        val viewModel = favoritesViewModel(SlowCommitFileSystem(disk, commitDelayMs = 400))

        viewModel.setFavorite("/out/kept.jpg", true)
        viewModel.requestClose()

        assertEquals(DashboardViewModel.CloseState.ReadyToExit, viewModel.awaitCloseSettled())
        assertEquals(true, disk.favoriteOnDisk("kept.jpg"), "ready to exit before the favorite was on disk")
        viewModel.dispose()
    }

    // Waiting cannot be unbounded — a wedged disk would make the window impossible to close.
    // But running out of patience must not quietly become the old bug: the user is told how
    // many favorites have not saved, and gets to choose.
    @Test
    fun closingReportsFavoritesThatHaveNotSavedInsteadOfExiting() {
        val disk = FakeFileSystem().apply { createDirectories("/out".toPath()) }
        val viewModel = favoritesViewModel(SlowCommitFileSystem(disk, commitDelayMs = 1_500))

        viewModel.setFavorite("/out/kept.jpg", true)
        viewModel.requestClose(timeoutMillis = 200)

        assertEquals(DashboardViewModel.CloseState.UnsavedFavorites(1), viewModel.awaitCloseSettled())
        viewModel.dispose()
    }

    // "Keep open" has to leave an app that still works. Tearing the writer down before asking
    // would make the question meaningless: the favorite would be lost whichever button was
    // pressed.
    @Test
    fun keepingTheWindowOpenLetsTheSlowFavoriteFinishSaving() {
        val disk = FakeFileSystem().apply { createDirectories("/out".toPath()) }
        val viewModel = favoritesViewModel(SlowCommitFileSystem(disk, commitDelayMs = 1_000))

        viewModel.setFavorite("/out/kept.jpg", true)
        viewModel.requestClose(timeoutMillis = 100)
        assertTrue(viewModel.awaitCloseSettled() is DashboardViewModel.CloseState.UnsavedFavorites)

        viewModel.keepOpen()

        assertEquals(DashboardViewModel.CloseState.Open, viewModel.closeState)
        runBlocking {
            withTimeout(10_000) { while (viewModel.favoriteIsPending("/out/kept.jpg")) delay(10) }
        }
        assertEquals(true, disk.favoriteOnDisk("kept.jpg"))
        viewModel.dispose()
    }

    @Test
    fun quittingAnywayAfterTheReportIsReadyToExit() {
        val disk = FakeFileSystem().apply { createDirectories("/out".toPath()) }
        val viewModel = favoritesViewModel(SlowCommitFileSystem(disk, commitDelayMs = 1_500))
        viewModel.setFavorite("/out/kept.jpg", true)
        viewModel.requestClose(timeoutMillis = 100)
        viewModel.awaitCloseSettled()

        viewModel.quitAnyway()

        assertEquals(DashboardViewModel.CloseState.ReadyToExit, viewModel.awaitCloseSettled())
        viewModel.dispose()
    }

    // Companion: nothing pending means nothing to wait for or ask about.
    @Test
    fun closingWithNothingPendingIsReadyToExit() {
        val disk = FakeFileSystem().apply { createDirectories("/out".toPath()) }
        val viewModel = favoritesViewModel(disk)

        viewModel.requestClose()

        assertEquals(DashboardViewModel.CloseState.ReadyToExit, viewModel.awaitCloseSettled())
        viewModel.dispose()
    }

    // A run in progress at close was simply abandoned when the JVM exited: its children were
    // never told to stop and its claim on the library (D07) was never released, so the next
    // launch could find the directory held by a process that was already gone.
    @Test
    fun closingStopsARunningSyncAndReleasesTheLibraryBeforeItIsReadyToExit() {
        val disk = FakeFileSystem().apply {
            createDirectories("/out".toPath())
            write("/history.json".toPath()) { writeUtf8(historyJson) }
        }
        val started = CompletableDeferred<Unit>()
        val locker = RecordingOutputDirectoryLocker()
        val viewModel = favoritesViewModel(disk, HangingZipPipelineRunner(started), locker)
        viewModel.start()
        runBlocking { withTimeout(5_000) { started.await() } }

        viewModel.requestClose()

        assertEquals(DashboardViewModel.CloseState.ReadyToExit, viewModel.awaitCloseSettled())
        assertFalse(viewModel.isRunning, "the run must have finished unwinding, not merely been asked to")
        assertTrue(locker.released, "the library must be released before the process exits")
        viewModel.dispose()
    }

    // ── D11: what the index says about a combined file ───────────────────────

    private fun combineViewModel(
        disk: FakeFileSystem,
        result: CombineResult,
    ) = DashboardViewModel(
        zipPipelineRunner = FakeZipPipelineRunner(listOf(result)),
        mediaProcessor = FakeMediaProcessor(),
        fileSystem = disk,
        pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
        outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
    ).also {
        it.changeImportMode(ImportMode.Legacy)
        it.pickHtmlFile()
        it.pickOutputFolder()
    }

    private fun pairOnDisk(favorited: Boolean): FakeFileSystem = FakeFileSystem().apply {
        createDirectories("/out".toPath())
        write("/history.json".toPath()) { writeUtf8(historyJson) }
        // The combiner has already run: the output exists and the originals are gone.
        write("/out/2023-10-12_AAA.jpg".toPath()) { writeUtf8("combined") }
        write("/out/vault_index.json".toPath()) {
            writeUtf8(
                """{"2023-10-12_AAA-main.jpg":{"hasGps":true,"hasOverlay":true,"favorited":$favorited},""" +
                    """"2023-10-12_AAA-overlay.png":{"hasGps":false,"hasOverlay":false}}""",
            )
        }
    }

    private fun combined(metadataCarried: Boolean = true) = CombineResult(
        uuid = "AAA",
        outputPath = "/out/2023-10-12_AAA.jpg",
        status = "combined",
        sourcePaths = listOf("/out/2023-10-12_AAA-main.jpg", "/out/2023-10-12_AAA-overlay.png"),
        metadataCarried = metadataCarried,
    )

    private fun DashboardViewModel.runCombineOnly() {
        startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion(this)
    }

    // D11: the index is keyed by file name, and the combine step never moved anything to the
    // name it produced. The sources were then deleted, so the combined photo — the one the
    // Library actually shows — came up with no GPS, not combined, and not favorited. A
    // favorite is in no export; losing it to a rename is losing it.
    @Test
    fun aCombinedFileInheritsItsSourcesGpsAndFavoriteUnderItsRealName() {
        val disk = pairOnDisk(favorited = true)
        val viewModel = combineViewModel(disk, combined())

        viewModel.runCombineOnly()

        val index = VaultIndex.read(disk, "/out")
        assertEquals(
            FileMeta(hasGps = true, hasOverlay = true, favorited = true, combined = true),
            index["2023-10-12_AAA.jpg"],
            "the combined file's entry, under the name the Library looks it up by",
        )
        assertEquals(null, index["2023-10-12_AAA-main.jpg"], "an entry for a deleted source is a claim about nothing")
        assertEquals(null, index["2023-10-12_AAA-overlay.png"])
    }

    // GPS is a claim about the file's tags. When the combiner could not copy them across, the
    // combined file has none — whatever its source had.
    @Test
    fun aCombinedFileThatLostItsMetadataDoesNotClaimGps() {
        val disk = pairOnDisk(favorited = true)
        disk.write("/out/2023-10-12_AAA-main.jpg".toPath()) { writeUtf8("kept") } // originals kept
        val viewModel = combineViewModel(disk, combined(metadataCarried = false))

        viewModel.runCombineOnly()

        val entry = VaultIndex.read(disk, "/out")["2023-10-12_AAA.jpg"]
        assertEquals(false, entry?.hasGps, "no tags were carried, so there is no GPS on this file")
        assertEquals(true, entry?.combined)
        assertEquals(true, entry?.favorited, "the favorite belongs to the memory, not to its tags")
        assertEquals(
            true,
            VaultIndex.read(disk, "/out")["2023-10-12_AAA-main.jpg"]?.favorited,
            "a source still on disk keeps its own entry",
        )
    }

    // ── D10: "complete" has to mean something happened, and say what went wrong ─

    private class OutcomeRunner(
        private val zips: List<String> = emptyList(),
        private val archiveWarnings: List<String> = emptyList(),
        private val combineResults: List<CombineResult> = emptyList(),
        private val dateFallbackErrors: List<String> = emptyList(),
    ) : ZipPipelineRunner {
        @Volatile var extractCalled = false
        @Volatile var combineCalled = false
        override fun listZipFiles(folderPath: String): List<String> = zips
        override suspend fun extractAll(
            itemsByZip: Map<String, List<HtmlMemoryEntry>>,
            outputDir: String,
            workerCount: Int,
            onProgress: (ExtractResult) -> Unit,
        ) {
            extractCalled = true
        }
        override suspend fun extractDownloadedArchives(
            outputDir: String,
            archivePaths: List<String>,
            onWarn: (String) -> Unit,
        ): List<String> {
            archiveWarnings.forEach(onWarn)
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
        ) {
            combineCalled = true
            onStart(combineResults.size)
            combineResults.forEach(onProgress)
            if (dateFallbackErrors.isNotEmpty()) {
                onMetaStart(dateFallbackErrors.size)
                dateFallbackErrors.forEach { onMetaError?.invoke(it) }
            }
        }
    }

    private class FolderPickers : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult("/history.json")
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult("/out")
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult("/zips")
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
    }

    private fun realZip(vararg entries: Pair<String, String>): String {
        val file = java.io.File.createTempFile("snapvault-d10-", ".zip").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, text) ->
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return file.absolutePath
    }

    private fun outcomeViewModel(
        runner: ZipPipelineRunner,
        disk: okio.FileSystem = FakeFileSystem().apply {
            createDirectories("/out".toPath())
            write("/history.json".toPath()) { writeUtf8(historyJson) }
        },
        mode: ImportMode = ImportMode.Legacy,
    ) = DashboardViewModel(
        zipPipelineRunner = runner,
        mediaProcessor = FakeMediaProcessor(),
        fileSystem = disk,
        pickers = FolderPickers(),
        outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
    ).also {
        it.changeImportMode(mode)
        it.pickHtmlFile()
        it.pickOutputFolder()
        it.pickZipFolder()
    }

    private fun DashboardViewModel.runWith(combine: Boolean = true) {
        startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = combine,
            runDedupe = false,
            dryRun = true,
        )
        awaitCompletion(this)
    }

    // D10, observed live: a ZIP holding only notes.txt went through metadata, combination and
    // dedupe over the output folder, saved an empty index, and finished at "100% / Pipeline
    // Complete / Done — 0 memories". Nothing was imported, and the one thing the user needed to
    // hear — wrong file — was never said.
    @Test
    fun aZipWithNoMemoriesInItIsAnErrorBeforeAnythingTouchesTheOutputFolder() {
        val runner = OutcomeRunner(zips = listOf(realZip("notes.txt" to "not an export")))
        val viewModel = outcomeViewModel(runner, mode = ImportMode.Zip)

        viewModel.runWith()

        assertEquals("Failed", viewModel.progressText)
        assertTrue(
            viewModel.logs.last().contains("no Snapchat memories", ignoreCase = true),
            "the error has to say what was wrong with the input, was: ${viewModel.logs.last()}",
        )
        assertFalse(runner.extractCalled, "nothing to extract")
        assertFalse(runner.combineCalled, "the output folder must not be processed on behalf of an empty import")
    }

    // Losing the index write loses every badge this run earned, and it was logged as a warning
    // under a "Sync complete!" headline.
    //
    // Both pipelines write the index at their own call site, so both are driven.
    @Test
    fun aVaultIndexThatCouldNotBeSavedIsAFailureNotASuccess() {
        val zip = realZip("memories/2023-10-12_AAA-main.jpg" to "photo")
        for (mode in ImportMode.entries) {
            val disk = object : ForwardingFileSystem(FakeFileSystem().apply {
                createDirectories("/out".toPath())
                write("/history.json".toPath()) { writeUtf8(historyJson) }
            }) {
                override fun atomicMove(source: okio.Path, target: okio.Path) {
                    if (target.name == VaultIndex.FILE_NAME) throw okio.IOException("Permission denied")
                    super.atomicMove(source, target)
                }
            }
            val viewModel = outcomeViewModel(OutcomeRunner(zips = listOf(zip)), disk, mode)

            viewModel.runWith(combine = false)

            assertEquals("Completed with warnings", viewModel.progressText, "$mode pipeline")
            assertEquals(1, viewModel.failureCount, "$mode pipeline")
        }
    }

    // The combiner's warnings — originals kept because metadata did not copy, an output left
    // alone because one already existed — were logged and then forgotten by the outcome.
    @Test
    fun combineWarningsAreCountedInTheOutcome() {
        val runner = OutcomeRunner(
            combineResults = listOf(
                CombineResult(
                    uuid = "AAA",
                    outputPath = "/out/2023-10-12_AAA.jpg",
                    status = "combined",
                    warnings = listOf("[combine] originals kept: metadata is not on 2023-10-12_AAA.jpg"),
                ),
            ),
        )
        val viewModel = outcomeViewModel(runner)

        viewModel.runWith()

        assertEquals("Completed with warnings", viewModel.progressText)
        assertEquals(0, viewModel.failureCount, "the combine itself succeeded")
        assertEquals(1, viewModel.warningCount)
    }

    // A downloaded archive the extractor had to keep is a memory that did not reach the
    // Library, and the run said nothing about it beyond a log line.
    @Test
    fun archiveWarningsAreCountedInTheOutcome() {
        val runner = OutcomeRunner(archiveWarnings = listOf("x.zip: two entries both flatten to x-main.jpg — archive kept"))
        val viewModel = outcomeViewModel(runner)

        viewModel.runWith(combine = false)

        assertEquals("Completed with warnings", viewModel.progressText)
        assertEquals(1, viewModel.warningCount)
    }

    @Test
    fun dateFallbackErrorsAfterCombiningAreFailures() {
        val runner = OutcomeRunner(dateFallbackErrors = listOf("[exiftool] 2023-10-12_AAA.jpg — Error: file not writable"))
        val viewModel = outcomeViewModel(runner)

        viewModel.runWith()

        assertEquals("Completed with warnings", viewModel.progressText)
        assertEquals(1, viewModel.failureCount)
    }

    // Mixed input: some entries import, some are skipped for an unrecognised name. The run is
    // not a failure — but "complete" with files silently left behind is not the whole story.
    @Test
    fun zipEntriesThatCouldNotBeImportedAreCountedInTheOutcome() {
        val zip = realZip(
            "memories/2023-10-12_AAA-main.jpg" to "photo",
            "memories/holiday-photo.jpg" to "unrecognised name",
        )
        val viewModel = outcomeViewModel(OutcomeRunner(zips = listOf(zip)), mode = ImportMode.Zip)

        viewModel.runWith(combine = false)

        assertEquals("Completed with warnings", viewModel.progressText)
        assertEquals(1, viewModel.warningCount)
    }

    // Companion: a run with nothing to report still reports clean success.
    @Test
    fun aRunWithNothingToReportIsStillACleanSuccess() {
        val viewModel = outcomeViewModel(OutcomeRunner())

        viewModel.runWith()

        assertEquals("Pipeline Complete", viewModel.progressText)
        assertEquals(0, viewModel.warningCount)
        assertEquals(0, viewModel.failureCount)
    }

    // D12: the reset's result was a Boolean App discarded. Each way it can not happen needs its
    // own answer, because each has a different fix for the user.
    @Test
    fun aResetThatCannotHappenSaysWhy() {
        val noFolder = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = FakeFileSystem(),
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        assertEquals(DashboardViewModel.IndexResetOutcome.NoFolder, runBlocking { noFolder.resetVaultIndex() })
        assertEquals(DashboardViewModel.IndexResetOutcome.NoFolder, noFolder.lastIndexReset, "the screen reads the outcome from here")

        // A damaged index is refused rather than replaced (D05) — which here must read as a
        // failure, not as a successful clear.
        val disk = FakeFileSystem().apply {
            createDirectories("/out".toPath())
            write("/out/vault_index.json".toPath()) { writeUtf8("{ not json") }
        }
        val damaged = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = disk,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        ).apply { pickOutputFolder() }
        assertEquals(DashboardViewModel.IndexResetOutcome.Failed, runBlocking { damaged.resetVaultIndex() })
        assertEquals("{ not json", disk.read("/out/vault_index.json".toPath()) { readUtf8() })
    }

    // The outcome describes one folder. Left in place after switching, it would report on a
    // folder the user is no longer looking at.
    @Test
    fun choosingAnotherFolderClearsTheLastResetOutcome() {
        val viewModel = DashboardViewModel(
            zipPipelineRunner = NoOpZipPipelineRunner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = FakeFileSystem().apply { createDirectories("/out".toPath()) },
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.pickOutputFolder()
        runBlocking { viewModel.resetVaultIndex() }
        assertEquals(DashboardViewModel.IndexResetOutcome.Cleared, viewModel.lastIndexReset)

        viewModel.pickOutputFolder()

        assertEquals(null, viewModel.lastIndexReset)
    }

    // D04 end to end: the favorite reaches dedupe from the index on disk, not from the copy the
    // run loaded at its start — so a heart pressed on the later copy mid-run still saves it.
    @Test
    fun dedupeKeepsTheCopyFavoritedDuringTheRun() {
        val disk = FakeFileSystem().apply {
            createDirectories("/out".toPath())
            write("/history.json".toPath()) { writeUtf8(historyJson) }
            write("/out/2021-05-01_AAA.jpg".toPath()) { writeUtf8("same photo") }
            write("/out/2023-11-30_ZZZ.jpg".toPath()) { writeUtf8("same photo") }
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = FakeZipPipelineRunner(
                combineResults = emptyList(),
                metaStartTotal = 0,
                // Runs after the index was loaded and before dedupe: the mid-run press.
                onAfterMetaStart = { VaultIndex.setFavorite(disk, "/out", "2023-11-30_ZZZ.jpg", true) },
            ),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = disk,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        ).apply {
            changeImportMode(ImportMode.Legacy)
            pickHtmlFile()
            pickOutputFolder()
        }

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = true,
            dryRun = false,
        )
        awaitCompletion(viewModel)

        assertTrue(disk.exists("/out/2023-11-30_ZZZ.jpg".toPath()), "the favorited copy was deleted")
        assertFalse(disk.exists("/out/2021-05-01_AAA.jpg".toPath()), "the plain duplicate should have gone")
    }
}
