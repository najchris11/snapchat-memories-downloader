package com.najdev.snapvault.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test

/** D12: controls that look live while a run is in progress but can no longer affect it. */
@OptIn(ExperimentalTestApi::class)
class ControlsDuringARunTest {

    private class Pickers : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult("/history.json")
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult("/out")
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
    }

    private class Tools : MediaProcessor {
        override fun checkExifTool() = true
        override fun checkFFmpeg() = true
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
    }

    private class HangingRunner(val started: CompletableDeferred<Unit>) : ZipPipelineRunner {
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
            started.complete(Unit)
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

    private fun runningViewModel(started: CompletableDeferred<Unit>) = DashboardViewModel(
        zipPipelineRunner = HangingRunner(started),
        mediaProcessor = Tools(),
        fileSystem = FakeFileSystem().apply {
            createDirectories("/out".toPath())
            write("/history.json".toPath()) {
                writeUtf8("""{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}""")
            }
        },
        pickers = Pickers(),
        outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
    ).apply {
        changeImportMode(ImportMode.Legacy)
        pickHtmlFile()
        pickOutputFolder()
    }

    private fun ComposeUiTest.switches(vararg labels: String) = labels.map { onNode(hasText(it) and isToggleable()) }

    // D12: a run captures its options when it starts, but every switch stayed live. Turning
    // off combination mid-run looked like it had stopped the step that deletes originals, and
    // had not.
    @Test
    fun pipelineSwitchesAreDisabledWhileARunIsInProgressAndReturnAfter() = runComposeUiTest {
        val started = CompletableDeferred<Unit>()
        val viewModel = runningViewModel(started)
        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }
        val labels = arrayOf("Write Metadata (GPS + Date)", "Merge Video Overlays", "Clean Duplicate Files")

        switches(*labels).forEach { it.assertIsEnabled() }

        viewModel.startSync()
        waitUntil(timeoutMillis = 10_000) { started.isCompleted && viewModel.isRunning }
        waitForIdle()
        switches(*labels).forEach { it.assertIsNotEnabled() }

        viewModel.stopSync()
        waitUntil(timeoutMillis = 10_000) { !viewModel.isRunning }
        waitForIdle()
        switches(*labels).forEach { it.assertIsEnabled() }
        viewModel.dispose()
    }
}
