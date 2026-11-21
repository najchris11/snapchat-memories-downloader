package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.ThemeMode
import com.najdev.snapvault.UpdateCheckResult
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsUpdateTest {
    @Test
    fun updateCheckWaitsForClickAndAllowsRetryAfterFailure() = runComposeUiTest {
        var requests = 0
        setContent {
            SnapVaultTheme(darkMode = true) {
                SettingsScreen(
                    hasExifTool = true, hasFFmpeg = true, onVerifyDependencies = {},
                    downloadFolder = null, onResetIndex = {}, onEditOutputPath = {},
                    themeMode = ThemeMode.DARK, onThemeModeChange = {},
                    layoutOverride = LayoutOverride.Auto, onLayoutOverrideChange = {},
                    checkForUpdates = {
                        requests++
                        if (requests == 1) UpdateCheckResult.Failed else UpdateCheckResult.Available("v9.0.0")
                    },
                )
            }
        }
        waitForIdle()
        assertEquals(0, requests)
        onNodeWithText("Check for updates").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Could not check for updates. Check your connection and try again.").assertExists()
        onNodeWithText("Check for updates").performClick()
        waitForIdle()
        onNodeWithText("Update available: v9.0.0").assertExists()
        onNodeWithText("View release and downloads").assertExists()
        assertEquals(2, requests)
    }
}
