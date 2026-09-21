package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.App
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D22: the pipeline switches were composition state inside DashboardScreen, so leaving the
 * Dashboard threw them away and coming back rebuilt the defaults. Observed live: combination
 * was switched off, the user visited Library and came back, and the next run combined anyway —
 * the step that deletes originals. Settings is exactly where a user goes to fix a missing-tool
 * warning before pressing Start.
 */
@OptIn(ExperimentalTestApi::class)
class PipelineOptionsNavigationTest {

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

    private class RecordingRunner : ZipPipelineRunner {
        @Volatile var extracted = false
        @Volatile var combined = false
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
            extracted = true
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
            combined = true
        }
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private fun switch(label: String) = hasText(label) and isToggleable()

    private fun ComposeUiTest.toggleOff(label: String) {
        onNode(switch(label)).performScrollTo().performClick()
        onNode(switch(label)).assertIsOff()
    }

    private fun ComposeUiTest.assertStillOff(vararg labels: String, where: String) {
        labels.forEach { label ->
            runCatching { onNode(switch(label)).assertIsOff() }
                .onFailure { throw AssertionError("'$label' was reset to its default after $where", it) }
        }
    }

    @Test
    fun switchedOffStepsStayOffAcrossNavigationAndAreWhatStartRuns() = runComposeUiTest {
        val disk = FakeFileSystem().apply {
            createDirectories("/out".toPath())
            write("/history.json".toPath()) {
                writeUtf8("""{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}""")
            }
        }
        val runner = RecordingRunner()
        var width by mutableStateOf(1280.dp)
        setContent {
            Box(Modifier.width(width).height(900.dp)) {
                App(pickers = Pickers(), mediaProcessor = Tools(), zipPipelineRunner = runner, fileSystem = disk, outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None, onboardingMemory = com.najdev.snapvault.onboarding.OnboardingMemory.None)
            }
        }

        onNodeWithText(LEGACY).performClick()
        onNodeWithText(HISTORY_PLACEHOLDER).performScrollTo().performClick()
        onNodeWithText(OUTPUT_PLACEHOLDER).performScrollTo().performClick()
        toggleOff(DOWNLOAD)
        toggleOff(METADATA)
        toggleOff(COMBINE)

        onNode(hasRole(Role.Tab) and hasText("Settings")).performClick()
        waitForIdle()
        onNode(hasRole(Role.Tab) and hasText("Dashboard")).performClick()
        waitForIdle()
        assertStillOff(DOWNLOAD, METADATA, COMBINE, where = "visiting Settings")

        // Crossing into the compact root swaps the whole tree, which is its own way to lose
        // composition state.
        width = 400.dp
        waitForIdle()
        assertStillOff(DOWNLOAD, METADATA, COMBINE, where = "switching to the compact layout")
        width = 1280.dp
        waitForIdle()

        // Not scrolled to: the action row is pinned below the cards on purpose.
        onNodeWithText(START).performClick()
        waitUntil(timeoutMillis = 15_000) { runner.extracted }
        waitUntil(timeoutMillis = 15_000) {
            onAllNodes(hasText("Pipeline Complete") or hasText("Completed with warnings") or hasText("Failed"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(false, runner.combined, "Start ran the combine step the user switched off")
    }

    // Preserving a user's choice is not a reason to change what a fresh start offers.
    @Test
    fun aFreshStartStillOffersTheDefaults() = runComposeUiTest {
        setContent {
            Box(Modifier.width(1280.dp).height(900.dp)) {
                App(pickers = Pickers(), mediaProcessor = Tools(), zipPipelineRunner = RecordingRunner(), fileSystem = FakeFileSystem(), outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None, onboardingMemory = com.najdev.snapvault.onboarding.OnboardingMemory.None)
            }
        }
        onNodeWithText(LEGACY).performClick()

        onNode(switch(DOWNLOAD)).assertIsOn()
        onNode(switch(METADATA)).assertIsOn()
        onNode(switch(COMBINE)).assertIsOn()
        onNode(switch(DEDUPE)).assertIsOn()
        onNode(switch(DRY_RUN)).assertIsOn()
    }

    private companion object {
        const val LEGACY = "Legacy (HTML/JSON)"
        const val HISTORY_PLACEHOLDER = "memories_history.json"
        const val OUTPUT_PLACEHOLDER = "Select destination folder"
        const val DOWNLOAD = "Download Memories"
        const val METADATA = "Write Metadata (GPS + Date)"
        const val COMBINE = "Combine photo and video overlays"
        const val DEDUPE = "Clean Duplicate Files"
        const val DRY_RUN = "Preview duplicate removal"
        const val START = "Start Download"
    }
}
