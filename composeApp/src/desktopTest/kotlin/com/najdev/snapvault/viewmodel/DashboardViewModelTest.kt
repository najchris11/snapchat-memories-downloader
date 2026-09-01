package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
    }
}

private class FakeMediaProcessor : MediaProcessor {
    override fun checkExifTool() = true
    override fun checkFFmpeg() = true
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
}

private class FakePlatformPickers(
    private val htmlPath: String,
    private val outputDir: String,
) : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(htmlPath)
    override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(outputDir)
    override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
}

class DashboardViewModelTest {
    private val historyJson =
        """{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}"""

    private fun newViewModel(combineResults: List<CombineResult>): DashboardViewModel {
        val fs = FakeFileSystem()
        fs.createDirectories("/out".toPath())
        fs.write("/history.json".toPath()) { writeUtf8(historyJson) }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = FakeZipPipelineRunner(combineResults),
            mediaProcessor = FakeMediaProcessor(),
            fileSystem = fs,
            pickers = FakePlatformPickers(htmlPath = "/history.json", outputDir = "/out"),
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
        assertEquals("Pipeline Complete", viewModel.progressText)
        assertEquals(4, viewModel.currentStep)
        assertEquals("[SUCCESS] Sync complete!", viewModel.logs.last())
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
        )

        assertTrue(viewModel.isRiffMislabeledAsPng("/out/overlay.png"))
        assertFalse(viewModel.isRiffMislabeledAsPng("/out/real.png"))
        assertFalse(viewModel.isRiffMislabeledAsPng("/out/does-not-exist.png"))
        assertFalse(viewModel.isRiffMislabeledAsPng("/out/not-a-png.jpg"))
    }
}
