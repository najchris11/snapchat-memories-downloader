package com.najdev.snapvault.ui

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

    private fun ComposeUiTest.settings() = setContent {
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
}
