package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.App
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.Screen
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two layouts were two applications that happened to share screens.
 *
 * `App` held `currentScreen` for the sidebar layout and `PhoneRoot` held a second, unrelated
 * one for the bottom-bar layout, so crossing the width boundary dropped you back on Dashboard.
 * The Layout setting made that trivial to hit: it lives *in* Settings, so switching to Compact
 * navigated you away from the screen you were configuring.
 */
@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

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

    // Driving the real App rather than a stand-in: the defect was that two composables each
    // owned a copy of the navigation state, which only shows when both are mounted in turn.
    @Test
    fun theSelectedScreenSurvivesCrossingTheLayoutBoundary() = runComposeUiTest {
        var width by mutableStateOf(1200.dp)
        setContent {
            Box(modifier = Modifier.width(width)) {
                App(
                    pickers = NoopPickers(),
                    mediaProcessor = NoopMediaProcessor(),
                    zipPipelineRunner = NoOpZipPipelineRunner,
                    fileSystem = FileSystem.SYSTEM,
                )
            }
        }

        // Expanded: navigate to Settings via the sidebar.
        onNode(hasRole(Role.Tab) and hasText("Settings")).performClick()
        onNodeWithText(SETTINGS_MARKER).assertExists()

        // Shrink past the boundary; the bottom-bar layout takes over.
        width = 400.dp
        waitForIdle()

        onNodeWithText(SETTINGS_MARKER).assertExists()
    }

    // The sidebar disabled the Library item during a run; the bottom bar did not. Same app,
    // two rules — and the disabled item explained itself nowhere, rendering at 30% alpha with
    // no tooltip and no cursor change. The Library scan is read-only and off the UI thread, so
    // there was never a correctness reason for the lock.
    @Test
    fun theLibraryStaysReachableWhileARunIsInProgress() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                AppSidebar(
                    currentScreen = Screen.Dashboard,
                    isRunning = true,
                    currentStep = 1,
                    onNavigate = {},
                )
            }
        }

        onNode(hasRole(Role.Tab) and hasText("Library")).assertIsEnabled()
    }

    // Dashboard and Library used matching icons across the two layouts; only Settings
    // diverged, sliders in one and a gear in the other. Both now read the icon off Screen, so
    // this asserts the set is coherent rather than that two call sites happen to agree.
    @Test
    fun everyDestinationHasItsOwnPairOfIcons() {
        val active = Screen.entries.map { it.navIconActive }
        val inactive = Screen.entries.map { it.navIconInactive }

        assertEquals(Screen.entries.size, active.toSet().size, "two destinations share an active icon")
        assertEquals(Screen.entries.size, inactive.toSet().size, "two destinations share an inactive icon")
        Screen.entries.forEach {
            assertEquals(
                false,
                it.navIconActive == it.navIconInactive,
                "$it draws the same icon selected and unselected, so selection is invisible",
            )
        }
    }

    private companion object {
        // Unique to the Settings screen: the sidebar label "Settings" also matches the nav
        // item, and the card heading matches the window title.
        const val SETTINGS_MARKER = "Manage system dependencies and utility preferences."
    }
}
