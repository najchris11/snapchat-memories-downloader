package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DeviceLibraryUiTest {
    @Test
    fun dashboardExplainsTheOnDeviceLibraryInsteadOfPromisingAFolderPicker() = runComposeUiTest {
        val vm = idleDashboardViewModel()
        setContent { SnapVaultTheme(darkMode = true) { DashboardScreen(vm, {}, usesDeviceLibrary = true) } }
        onNodeWithText("Use on-device library").assertExists()
        // Android now burns video overlays in (Media3); the banner used to say they stay separate.
        onNodeWithText("ZIP imports and photo and video overlays are available in this preview.", substring = true).assertExists()
        onNodeWithText("Combined videos keep their originals", substring = true).assertExists()
        onNodeWithText("remain separate", substring = true).assertDoesNotExist()
        onNodeWithText("Choose an output folder").assertDoesNotExist()
        onNodeWithText("Imports are processed in SnapVault’s own on-device library.", substring = true).assertExists()
        vm.dispose()
    }
    @Test
    fun anEmptyOnDeviceLibraryOffersRefreshRatherThanAnUnavailableFolderChange() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryEmptyState(LibraryEmptyReason.NoMedia, "/private/library", {}, {}, {}, usesDeviceLibrary = true)
            }
        }
        onNodeWithText("Change output folder").assertDoesNotExist()
        onNodeWithText("/private/library").assertDoesNotExist()
        onNodeWithText("Refresh").assertExists()
    }
    @Test
    fun settingsDoNotOfferToChangeAnAppOwnedWorkingLibrary() = runComposeUiTest {
        setContent { SnapVaultTheme(darkMode = true) {
            SettingsScreen(true, true, {}, "/private/library", {}, {}, com.najdev.snapvault.ThemeMode.DARK, {},
                com.najdev.snapvault.LayoutOverride.Auto, {}, usesDeviceLibrary = true)
        } }
        onNodeWithText("/private/library").assertDoesNotExist()
        onNodeWithText("Change").assertDoesNotExist()
        onNodeWithText("Use on-device library").assertDoesNotExist()
        onNodeWithText("On-device SnapVault library").assertExists()
    }
}
