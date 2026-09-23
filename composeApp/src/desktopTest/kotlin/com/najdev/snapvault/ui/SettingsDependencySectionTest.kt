package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.ThemeMode
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsDependencySectionTest {

    // The mobile screens used to describe desktop executables that cannot exist there. The
    // whole section must leave the tree, rather than merely hiding its install advice.
    @Test
    fun mobileSettingsDoNotComposeDesktopDependencies() = runComposeUiTest {
        setContent { settingsScreen(showDependencySection = false) }

        onAllNodesWithText("System Dependencies").assertCountEquals(0)
        onAllNodesWithText("ExifTool").assertCountEquals(0)
        onAllNodesWithText("FFmpeg").assertCountEquals(0)
    }

    @Test
    fun desktopSettingsStillComposeExternalDependencies() = runComposeUiTest {
        setContent { settingsScreen(showDependencySection = true) }

        onNodeWithText("System Dependencies").assertIsDisplayed()
        onNodeWithText("ExifTool").assertIsDisplayed()
        onNodeWithText("FFmpeg").assertIsDisplayed()
    }

    @Test
    fun desktopDefaultUsesTheRealPlatformValue() = runComposeUiTest {
        setContent { settingsScreen() }

        onNodeWithText("System Dependencies").assertIsDisplayed()
    }

    // The old fixed Row squeezed both tiles into roughly half of a phone-width settings card,
    // collapsing every label. A narrow desktop window needs one full-width tile per row.
    @Test
    fun dependencyTilesStackAtNarrowDesktopWidths() = runComposeUiTest {
        setContent {
            Box(Modifier.width(360.dp).fillMaxHeight()) {
                settingsScreen(showDependencySection = true)
            }
        }

        val exifTool = onNodeWithText("ExifTool").getUnclippedBoundsInRoot()
        val ffmpeg = onNodeWithText("FFmpeg").getUnclippedBoundsInRoot()
        assertTrue(
            ffmpeg.top > exifTool.bottom,
            "Expected full-width stacked tiles, but ExifTool $exifTool and FFmpeg $ffmpeg share a row",
        )
    }

    @Test
    fun dependencyTilesRemainSideBySideWhenWidthAllows() = runComposeUiTest {
        setContent {
            Box(Modifier.width(900.dp).fillMaxHeight()) {
                settingsScreen(showDependencySection = true)
            }
        }

        val exifTool = onNodeWithText("ExifTool").getUnclippedBoundsInRoot()
        val ffmpeg = onNodeWithText("FFmpeg").getUnclippedBoundsInRoot()
        assertTrue(
            ffmpeg.left > exifTool.right && ffmpeg.top == exifTool.top,
            "Expected side-by-side tiles, but ExifTool $exifTool and FFmpeg $ffmpeg do not share a row",
        )
    }

    @androidx.compose.runtime.Composable
    private fun settingsScreen(showDependencySection: Boolean? = null) {
        SnapVaultTheme(darkMode = true) {
            if (showDependencySection == null) {
                SettingsScreen(
                    hasExifTool = true,
                    hasFFmpeg = true,
                    onVerifyDependencies = {},
                    downloadFolder = null,
                    onResetIndex = {},
                    onEditOutputPath = {},
                    themeMode = ThemeMode.DARK,
                    onThemeModeChange = {},
                    layoutOverride = LayoutOverride.Auto,
                    onLayoutOverrideChange = {},
                )
            } else {
                SettingsScreen(
                    hasExifTool = true,
                    hasFFmpeg = true,
                    onVerifyDependencies = {},
                    downloadFolder = null,
                    onResetIndex = {},
                    onEditOutputPath = {},
                    themeMode = ThemeMode.DARK,
                    onThemeModeChange = {},
                    layoutOverride = LayoutOverride.Auto,
                    onLayoutOverrideChange = {},
                    showDependencySection = showDependencySection,
                )
            }
        }
    }
}
