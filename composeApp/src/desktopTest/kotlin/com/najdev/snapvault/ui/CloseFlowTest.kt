package com.najdev.snapvault.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.App
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.ui.components.UnsavedFavoritesDialog
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CloseFlowTest {

    private class NoopPickers : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
    }

    private class NoopMediaProcessor : MediaProcessor {
        override fun checkExifTool() = true
        override fun checkFFmpeg() = true
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
    }

    // The OS close — Alt+F4, the Dock's Quit, a logout — used to be wired straight to
    // exitApplication in Main, bypassing anything App could do about unsaved favorites. It is
    // now a request App hands to the view model, and the window closes only when that says so.
    @Test
    fun anOsCloseRequestIsRoutedThroughTheAppAndExitsWhenNothingIsPending() = runComposeUiTest {
        var closeRequests by mutableStateOf(0)
        var exits = 0
        setContent {
            App(
                pickers = NoopPickers(),
                mediaProcessor = NoopMediaProcessor(),
                zipPipelineRunner = NoOpZipPipelineRunner,
                fileSystem = FakeFileSystem(),
                showWindowControls = true,
                closeRequests = closeRequests,
                onCloseWindow = { exits++ },
            )
        }
        waitForIdle()
        assertEquals(0, exits, "nothing asked to close yet")

        closeRequests++

        waitUntil(timeoutMillis = 10_000) { exits > 0 }
        assertEquals(1, exits)
    }

    // The dialog has one job: say how much would be lost, and make staying the easy answer.
    @Test
    fun theUnsavedFavoritesDialogSaysHowManyWouldBeLostAndOffersBothChoices() = runComposeUiTest {
        var kept = 0
        var quit = 0
        setContent { UnsavedFavoritesDialog(count = 2, onKeepOpen = { kept++ }, onQuitAnyway = { quit++ }) }

        onNode(hasText("2 favorites have not been saved yet", substring = true)).assertExists()

        onNodeWithText("Keep SnapVault open").performClick()
        assertEquals(1, kept)
        assertEquals(0, quit)

        onNodeWithText("Quit anyway").performClick()
        assertEquals(1, quit)
    }
}
