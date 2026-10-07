package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.SerializedFileSystem
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.parser.HtmlMemoryEntry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ForwardingFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the progress line actually says, read off a real run.
 *
 * The text moved out of string literals in the ViewModel and into strings.xml, so these
 * assert the resolved wording. A wrong key, a lost placeholder or a reverted edit to
 * strings.xml changes what a user reads, and nothing else would notice.
 *
 * Progress text is overwritten by the next phase, so each test samples it from inside the
 * collaborators the run calls into (the runner and the file system) rather than at the end.
 */
class ProgressTextTest {
    private val seen = mutableListOf<String>()
    private lateinit var viewModel: DashboardViewModel

    // The progress line is overwritten by whatever the run does next, and the download and
    // extraction callbacks have no collaborator to hook. Every later state write (the log line
    // that follows each file, the speed readout) is a chance to read the line the previous
    // write left behind.
    private var writeObserver: ObserverHandle? = null

    @AfterTest
    fun stopObserving() {
        writeObserver?.dispose()
    }

    private fun sample() {
        if (::viewModel.isInitialized) {
            val text = viewModel.progressText
            if (seen.lastOrNull() != text) seen += text
        }
    }

    /** Samples the progress line on every file-system call the run makes. */
    private inner class SamplingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
            sample()
            return path
        }
    }

    private fun sampledFileSystem(delegate: FileSystem): FileSystem = SamplingFileSystem(SerializedFileSystem(delegate))

    // FakeFileSystem's unsynchronised map threw ConcurrentModificationException while two
    // downloads wrote different .part files. Force overlap so this fixture cannot regress.
    @Test
    fun samplingFixtureSerializesConcurrentFakeFileSystemCalls() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val guardedFake = object : ForwardingFileSystem(FakeFileSystem()) {
            override fun metadataOrNull(path: Path): okio.FileMetadata? {
                check(active.incrementAndGet() == 1) { "concurrent fake filesystem access" }
                try {
                    entered.countDown()
                    check(release.await(1, TimeUnit.SECONDS)) { "first filesystem call was not released" }
                    return super.metadataOrNull(path)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val sampled = sampledFileSystem(guardedFake)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit { sampled.metadataOrNull("/out".toPath()) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = pool.submit { sampled.metadataOrNull("/out".toPath()) }
            Thread.sleep(100)
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private class SamplingRunner(
        private val sample: () -> Unit,
        private val combineTotal: Int = 2,
        private val metaTotal: Int = 1,
    ) : ZipPipelineRunner {
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
            sample()
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
            sample()
            onStart(combineTotal)
            sample()
            onProgress(CombineResult(uuid = "aaaaaaaa-0001", outputPath = "", status = "combined"))
            sample()
            onMetaStart(metaTotal)
            sample()
        }
    }

    private fun newViewModel(
        fs: FileSystem,
        runner: ZipPipelineRunner,
        history: String,
    ): DashboardViewModel {
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(history) }
        val sampling = sampledFileSystem(fs)
        return DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = sampling,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            httpClientFactory = {
                HttpClient(MockEngine { respond("zip-bytes", headers = headersOf(HttpHeaders.ContentType, "application/zip")) })
            },
            outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
        ).also {
            viewModel = it
            writeObserver = Snapshot.registerGlobalWriteObserver { sample() }
            it.changeImportMode(ImportMode.Legacy)
            it.pickHtmlFile()
            it.pickOutputFolder()
        }
    }

    private fun awaitCompletion() = runBlocking {
        withTimeout(30_000) { while (viewModel.isRunning) delay(10) }
    }

    private val oneRow =
        """{"Saved Media": [{"Download Link": "https://example.com/mem.zip?mid=abc-123", "Date": "2024-01-01 00:00:00 UTC"}]}"""

    @Test
    fun downloadRunReadsThenDownloadsThenExtractsWithPlainWording() {
        val runner = SamplingRunner(sample = ::sample)
        newViewModel(FakeFileSystem(), runner, oneRow)

        viewModel.startSync(
            runDownload = true,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = false,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion()

        // The count noun agrees with the count: one file, not "1 files".
        val expected = listOf(
            "Reading memories…",
            "Downloading: 1 of 1 file…",
            "Extracting downloaded archives…",
        )
        assertEquals(expected, seen.filter { it in expected }, "all samples: $seen")
        assertEquals("Run complete", viewModel.progressText)
        // The download and badge summaries used to say "1 new" and "0 files".
        assertTrue(viewModel.logs.any { it == "[INFO] Downloads: 1 new file, 0 existing files." }, viewModel.logs.toString())
        assertTrue(viewModel.logs.any { it == "[INFO] Library badges saved (1 file)." }, viewModel.logs.toString())
    }

    @Test
    fun downloadingPluralisesOnTheTotal() {
        val row = """{"Download Link": "https://example.com/%d.zip?mid=m%d", "Date": "2024-01-01 00:00:00 UTC"}"""
        val rows = (1..2).joinToString(",") { row.format(it, it) }
        val runner = SamplingRunner(sample = ::sample)
        newViewModel(FakeFileSystem(), runner, """{"Saved Media": [$rows]}""")

        viewModel.startSync(
            runDownload = true,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = false,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion()

        assertTrue("Downloading: 2 of 2 files…" in seen, "all samples: $seen")
        assertTrue(viewModel.logs.any { it == "[INFO] Downloads: 2 new files, 0 existing files." }, viewModel.logs.toString())
        assertTrue(viewModel.logs.any { it == "[INFO] Library badges saved (2 files)." }, viewModel.logs.toString())
    }

    @Test
    fun combineRunNamesEachPhaseInPlainWords() {
        val runner = SamplingRunner(sample = ::sample)
        newViewModel(FakeFileSystem(), runner, oneRow)

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion()

        val expected = listOf(
            "Combining overlays…",
            "Combining: 0 / 2",
            "Combining: 1 / 2",
            "Tagging combined files…",
        )
        assertEquals(expected, seen.filter { it in expected }, "all samples: $seen")
    }

    @Test
    fun taggingCombinedFilesLogsTheCountWithoutAGuessedPlural() {
        val runner = SamplingRunner(sample = ::sample, metaTotal = 1)
        newViewModel(FakeFileSystem(), runner, oneRow)

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion()

        val log = viewModel.logs.single { it.startsWith("[INFO] Tagging") }
        assertEquals("[INFO] Tagging combined 1 file with date metadata…", log)
    }

    private fun dedupeRun(dryRun: Boolean) {
        val fs = FakeFileSystem()
        newViewModel(fs, SamplingRunner(sample = ::sample), oneRow)
        fs.write("/out/2024-01-01_a-main.jpg".toPath()) { writeUtf8("same bytes") }
        fs.write("/out/2024-01-01_b-main.jpg".toPath()) { writeUtf8("same bytes") }

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = false,
            runDedupe = true,
            dryRun = dryRun,
        )
        awaitCompletion()
    }

    @Test
    fun removingDuplicatesReportsScanningThenTheCount() {
        dedupeRun(dryRun = false)

        assertTrue("Deduplicating: Scanning…" in seen, "all samples: $seen")
        assertTrue("Duplicates: 1 removed" in seen, "all samples: $seen")
    }

    @Test
    fun previewingDuplicatesSaysFoundNotRemoved() {
        dedupeRun(dryRun = true)

        assertTrue("Duplicates: 1 found (preview)" in seen, "all samples: $seen")
        assertTrue(
            viewModel.logs.any { it == "[INFO] Preview complete — 1 duplicate file would be deleted. Turn off Preview duplicate removal to delete them." },
            viewModel.logs.toString(),
        )
    }

    @Test
    fun terminalStatesUsePlainWording() {
        // Cancelled and Stopping… share a path: stop a run that is hanging in the extractor.
        val started = CompletableDeferred<Unit>()
        // Cancellation is held open until the test has looked at "Stopping…". Without this the
        // cancel runs on another thread and can finish, and set "Cancelled", before the
        // assertion reads the text, so the test failed on CI about one run in a few.
        val finishCancelling = CompletableDeferred<Unit>()
        val runner = SamplingRunner(sample = ::sample)
        val hanging = object : ZipPipelineRunner by runner {
            override suspend fun extractDownloadedArchives(
                outputDir: String,
                archivePaths: List<String>,
                onWarn: (String) -> Unit,
            ): List<String> {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { finishCancelling.await() }
                }
            }
        }
        newViewModel(FakeFileSystem(), hanging, oneRow)

        viewModel.startSync(
            runDownload = true,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = false,
            runDedupe = false,
            dryRun = false,
        )
        runBlocking { withTimeout(30_000) { started.await() } }
        viewModel.stopSync()
        assertEquals("Stopping…", viewModel.progressText)
        finishCancelling.complete(Unit)
        awaitCompletion()

        assertEquals("Cancelled", viewModel.progressText)
    }

    @Test
    fun failedRunSaysFailedAndAnEmptySelectionExplainsItself() {
        newViewModel(FakeFileSystem(), SamplingRunner(sample = ::sample), """{"Saved Media": []}""")

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = false,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion()

        assertEquals("Failed", viewModel.progressText)
    }

    // Counts in log lines used to be written "(s)" so one string covered every number.
    @Test
    fun runCompleteLogUsesRealPluralsNotParenthesisedOnes() {
        val failed = listOf(
            CombineResult(uuid = "deadbeef-0001", outputPath = "", status = "error: boom"),
        )
        val fs = FakeFileSystem()
        val runner = object : ZipPipelineRunner by SamplingRunner(sample = ::sample) {
            override suspend fun combineAll(
                outputDir: String,
                deleteOriginals: Boolean,
                workerCount: Int,
                onStart: (total: Int) -> Unit,
                onMetaStart: (total: Int) -> Unit,
                onMetaError: ((String) -> Unit)?,
                onProgress: (CombineResult) -> Unit,
            ) {
                onStart(failed.size)
                failed.forEach(onProgress)
            }
        }
        newViewModel(fs, runner, oneRow)

        viewModel.startSync(
            runDownload = false,
            runMetadata = false,
            experimentalMetadataMatching = false,
            runCombine = true,
            runDedupe = false,
            dryRun = false,
        )
        awaitCompletion()

        assertEquals(
            "[WARN] Run complete — 1 failure and 0 warnings, see above.",
            viewModel.logs.last(),
        )
        assertTrue(viewModel.logs.none { "(s)" in it }, viewModel.logs.toString())
    }
}
