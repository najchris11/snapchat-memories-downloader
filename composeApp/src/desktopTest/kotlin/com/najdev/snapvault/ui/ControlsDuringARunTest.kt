package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
        outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
    ).apply {
        changeImportMode(ImportMode.Legacy)
        pickHtmlFile()
        pickOutputFolder()
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

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
        val labels = arrayOf("Write Metadata (GPS + Date)", "Combine photo and video overlays", "Clean Duplicate Files")

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

    // D12: the Dashboard's folder picker disabled during a run, but Library and Settings could
    // still change the folder. The run kept writing to the folder it captured while the
    // Library and every new favorite followed the new one — two destinations, one of them
    // invisible. The guard lives on the view model so every entry point shares it.
    @Test
    fun theOutputFolderCannotBeChangedWhileARunIsInProgress() {
        val started = CompletableDeferred<Unit>()
        var nextPick = "/out"
        val viewModel = DashboardViewModel(
            zipPipelineRunner = HangingRunner(started),
            mediaProcessor = Tools(),
            fileSystem = FakeFileSystem().apply {
                createDirectories("/out".toPath())
                write("/history.json".toPath()) {
                    writeUtf8("""{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}""")
                }
            },
            pickers = object : PlatformPickers {
                override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult("/history.json")
                override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(nextPick)
                override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
                override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
            },
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
        ).apply {
            changeImportMode(ImportMode.Legacy)
            pickHtmlFile()
            pickOutputFolder()
        }
        viewModel.startSync()
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { started.await() } }

        nextPick = "/somewhere-else"
        viewModel.pickOutputFolder()

        kotlin.test.assertEquals("/out", viewModel.downloadFolder)
        kotlin.test.assertFalse(viewModel.outputFolderChangeable)

        viewModel.stopSync()
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { while (viewModel.isRunning) kotlinx.coroutines.delay(10) } }
        kotlin.test.assertTrue(viewModel.outputFolderChangeable)
        viewModel.dispose()
    }

    // The screens have to say so too: a button that silently does nothing is the same problem
    // in a different place.
    @Test
    fun settingsDoesNotOfferToChangeTheFolderDuringARun() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                SettingsScreen(
                    hasExifTool = true,
                    hasFFmpeg = true,
                    onVerifyDependencies = {},
                    downloadFolder = "/out",
                    onResetIndex = {},
                    onEditOutputPath = {},
                    themeMode = com.najdev.snapvault.ThemeMode.SYSTEM,
                    onThemeModeChange = {},
                    layoutOverride = com.najdev.snapvault.LayoutOverride.Auto,
                    onLayoutOverrideChange = {},
                    outputFolderChangeable = false,
                )
            }
        }

        onNode(hasText("Edit") and androidx.compose.ui.test.hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun theEmptyLibraryDoesNotOfferToChangeTheFolderDuringARun() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryEmptyState(
                    reason = LibraryEmptyReason.NoMedia,
                    downloadFolder = "/out",
                    onOpenFolder = {},
                    onRefresh = {},
                    onClearFilters = {},
                    folderChangeable = false,
                )
            }
        }

        onNode(hasText("Change output folder")).assertIsNotEnabled()
        onNode(hasText("Refresh")).assertIsEnabled()
    }

    // Both roots have to pass the guard on — the sidebar layout and the compact one each wire
    // Settings themselves, and a screen that is not told defaults to "changeable".
    @Test
    fun bothLayoutsWithholdTheFolderChangeDuringARealRun() = runComposeUiTest {
        val started = CompletableDeferred<Unit>()
        var width by androidx.compose.runtime.mutableStateOf(1280.dp)
        setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.width(width).height(900.dp),
            ) {
                com.najdev.snapvault.App(
                    pickers = Pickers(),
                    mediaProcessor = Tools(),
                    zipPipelineRunner = HangingRunner(started),
                    fileSystem = FakeFileSystem().apply {
                        createDirectories("/out".toPath())
                        write("/history.json".toPath()) {
                            writeUtf8("""{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}""")
                        }
                    },
                    outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
                )
            }
        }
        onNodeWithText("Legacy (HTML/JSON)").performClick()
        onNodeWithText("memories_history.json").performScrollTo().performClick()
        onNodeWithText("Select destination folder").performScrollTo().performClick()
        onNode(hasText("Download Memories") and isToggleable()).performScrollTo().performClick()
        onNodeWithText("Start Download").performClick()
        waitUntil(timeoutMillis = 10_000) { started.isCompleted }

        onNode(hasRole(Role.Tab) and hasText("Settings")).performClick()
        waitForIdle()
        onNode(hasText("Edit") and hasClickAction()).assertIsNotEnabled()
        // "/out" exists only in the fake filesystem, so the Library scan finds nothing and
        // shows its empty state — which is where it offers to change the folder.
        onNode(hasRole(Role.Tab) and hasText("Library")).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Change output folder")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("Change output folder")).assertIsNotEnabled()

        width = 400.dp
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Change output folder")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("Change output folder")).assertIsNotEnabled()
        onNode(hasRole(Role.Tab) and hasText("Settings")).performClick()
        waitForIdle()
        onNode(hasText("Edit") and hasClickAction()).assertIsNotEnabled()
        // No Stop: it is not on this screen, and disposing the composition disposes the view
        // model, which cancels the hanging run.
    }
}
