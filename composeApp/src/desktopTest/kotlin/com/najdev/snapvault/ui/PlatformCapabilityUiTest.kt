package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.CapabilityLevel
import com.najdev.snapvault.MediaCapabilities
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlatformCapabilityUiTest {

    // Regression: replacing the Android preview with the capability banner hid the fact
    // that ZIP import and Library scanning are still stubs (#52).
    @Test
    fun androidDashboardStillSaysImportingDoesNotWork() = runComposeUiTest {
        val viewModel = idleDashboardViewModel()
        setContent {
            SnapVaultTheme(darkMode = true) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    windowSize = WindowSize.Compact,
                    mediaCapabilities = MediaCapabilities(
                        imageMetadata = CapabilityLevel.Partial,
                        videoMetadata = CapabilityLevel.Unavailable,
                        imageOverlayCombine = CapabilityLevel.Full,
                        videoOverlayCombine = CapabilityLevel.Unavailable,
                    ),
                    showAndroidPreview = true,
                )
            }
        }

        onNode(hasText("Importing does not work yet", substring = true)).assertIsDisplayed()
        viewModel.dispose()
    }

    @Test
    fun fullyCapablePlatformShowsNoCapabilityBanner() = runComposeUiTest {
        val viewModel = idleDashboardViewModel()
        setContent {
            SnapVaultTheme(darkMode = true) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    windowSize = WindowSize.Compact,
                    mediaCapabilities = MediaCapabilities(
                        imageMetadata = CapabilityLevel.Full,
                        videoMetadata = CapabilityLevel.Full,
                        imageOverlayCombine = CapabilityLevel.Full,
                        videoOverlayCombine = CapabilityLevel.Full,
                    ),
                    showAndroidPreview = false,
                )
            }
        }

        onNodeWithText("Limited media support").assertDoesNotExist()
        onNode(hasText("Importing does not work yet", substring = true)).assertDoesNotExist()
        viewModel.dispose()
    }

    // Regression: Android's banner said only that video combining was absent, without
    // reflecting the photo combining support that had already shipped.
    @Test
    fun dashboardBannerDescribesCapabilitiesInsteadOfNamingAPlatform() = runComposeUiTest {
        val viewModel = idleDashboardViewModel()
        setContent {
            SnapVaultTheme(darkMode = true) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    windowSize = WindowSize.Compact,
                    mediaCapabilities = MediaCapabilities(
                        imageMetadata = CapabilityLevel.Full,
                        videoMetadata = CapabilityLevel.Unavailable,
                        imageOverlayCombine = CapabilityLevel.Full,
                        videoOverlayCombine = CapabilityLevel.Unavailable,
                    ),
                )
            }
        }

        onNodeWithText("Limited media support").assertIsDisplayed()
        onNode(hasText("Photo overlays are combined on this build.", substring = true)).assertIsDisplayed()
        onNode(hasText("Video overlays are not combined on this build.", substring = true)).assertIsDisplayed()
        onNode(hasText("Photo overlays are not combined", substring = true)).assertDoesNotExist()
        viewModel.dispose()
    }

    // Mobile Settings used desktop binary names as proxies for native features. That made
    // Android claim ExifTool existed and made iOS report two irrelevant missing tools.
    @Test
    fun mobileEnvironmentShowsUserCapabilitiesNotDesktopBinaries() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaEnvironmentSection(
                    hasExifTool = false,
                    hasFFmpeg = false,
                    onVerifyDependencies = {},
                    usesDesktopTooling = false,
                    mediaCapabilities = MediaCapabilities(
                        imageMetadata = CapabilityLevel.Full,
                        videoMetadata = CapabilityLevel.Partial,
                        imageOverlayCombine = CapabilityLevel.Unavailable,
                        videoOverlayCombine = CapabilityLevel.Unavailable,
                    ),
                )
            }
        }

        onNodeWithText("Media capabilities").assertIsDisplayed()
        onNodeWithText("Image metadata").assertIsDisplayed()
        onNodeWithText("Video metadata").assertIsDisplayed()
        onNodeWithText("Combine photo overlays").assertIsDisplayed()
        onNodeWithText("Combine video overlays").assertIsDisplayed()
        onNodeWithText("ExifTool").assertDoesNotExist()
        onNodeWithText("FFmpeg").assertDoesNotExist()
    }

    @Test
    fun desktopEnvironmentKeepsActionableBinaryDetection() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaEnvironmentSection(
                    hasExifTool = true,
                    hasFFmpeg = false,
                    onVerifyDependencies = {},
                    usesDesktopTooling = true,
                    mediaCapabilities = MediaCapabilities(
                        imageMetadata = CapabilityLevel.Full,
                        videoMetadata = CapabilityLevel.Full,
                        imageOverlayCombine = CapabilityLevel.Full,
                        videoOverlayCombine = CapabilityLevel.Full,
                    ),
                )
            }
        }

        onNodeWithText("Helper tools").assertIsDisplayed()
        onNodeWithText("ExifTool").assertIsDisplayed()
        onNodeWithText("FFmpeg").assertIsDisplayed()
        onNodeWithText("Image metadata").assertDoesNotExist()
    }
}
