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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.App
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.viewmodel.DashboardViewModel
import okio.fakefilesystem.FakeFileSystem
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.ThemeMode
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** D12: the Reset control in Settings. */
@OptIn(ExperimentalTestApi::class)
class ResetIndexControlTest {

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun ComposeUiTest.settings(outcome: DashboardViewModel.IndexResetOutcome? = null) = setContent {
        SnapVaultTheme(darkMode = true) {
            SettingsScreen(
                hasExifTool = true,
                hasFFmpeg = true,
                onVerifyDependencies = {},
                downloadFolder = "/out",
                onResetIndex = {},
                onEditOutputPath = {},
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
                layoutOverride = LayoutOverride.Auto,
                onLayoutOverrideChange = {},
                resetOutcome = outcome,
            )
        }
    }

    // It promised that the next run "re-processes all memories from scratch". Nothing in either
    // pipeline reads the index to decide what to do — resume goes by the files on disk — so a
    // user pressing it to force a re-download got nothing, and was told they would. What it
    // really does is forget the Library's recorded badges.
    @Test
    fun resetDescribesWhatItClearsAndDoesNotPromiseAReprocess() = runComposeUiTest {
        settings()

        onAllNodes(hasText("re-process", substring = true, ignoreCase = true) and hasText("next", substring = true))
            .assertCountEquals(0)
        onAllNodes(hasText("from scratch", substring = true)).assertCountEquals(0)
        assertTrue(
            onAllNodes(hasText("badges", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty(),
            "the control has to name what it clears",
        )
        onNode(hasText("Favorites are kept", substring = true)).assertExists()
    }

    // D12: App launched the reset and threw its result away. Pressing the button did something
    // or nothing, and the screen looked the same either way.
    @Test
    fun eachResetOutcomeIsShownInWordsThatSayWhatToDo() {
        val expected = mapOf(
            DashboardViewModel.IndexResetOutcome.Cleared to "Badges cleared",
            DashboardViewModel.IndexResetOutcome.RunInProgress to "a sync is running",
            DashboardViewModel.IndexResetOutcome.NoFolder to "choose an output folder first",
            DashboardViewModel.IndexResetOutcome.Failed to "could not be changed",
        )
        for ((outcome, phrase) in expected) {
            runComposeUiTest {
                settings(outcome)
                assertTrue(
                    onAllNodes(hasText(phrase, substring = true)).fetchSemanticsNodes().isNotEmpty(),
                    "$outcome was not shown as '$phrase'",
                )
            }
        }
    }

    @Test
    fun noOutcomeIsShownBeforeTheButtonIsPressed() = runComposeUiTest {
        settings(outcome = null)
        onAllNodes(hasText("Not cleared", substring = true) or hasText("Badges cleared", substring = true))
            .assertCountEquals(0)
    }

    // Both layouts wire Settings separately; the outcome has to reach it through each.
    @Test
    fun pressingClearBadgesInTheAppReportsTheOutcomeInBothLayouts() = runComposeUiTest {
        var width by mutableStateOf(1280.dp)
        setContent {
            Box(Modifier.width(width).height(900.dp)) {
                App(
                    pickers = object : PlatformPickers {
                        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
                        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
                        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
                        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
                    },
                    mediaProcessor = object : MediaProcessor {
                        override fun checkExifTool() = true
                        override fun checkFFmpeg() = true
                        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
                        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
                        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
                    },
                    zipPipelineRunner = NoOpZipPipelineRunner,
                    fileSystem = FakeFileSystem(),
                    outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
                )
            }
        }
        onNode(hasRole(Role.Tab) and hasText("Settings")).performClick()
        onNode(hasText("Clear Badges") and hasClickAction()).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText("choose an output folder first", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        width = 400.dp
        waitForIdle()
        assertTrue(
            onAllNodes(hasText("choose an output folder first", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "the compact layout's Settings did not show the outcome",
        )
    }
}
