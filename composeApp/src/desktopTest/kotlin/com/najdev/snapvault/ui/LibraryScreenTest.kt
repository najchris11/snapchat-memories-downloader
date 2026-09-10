package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test

// Regression for N3. LibraryScreen laid out an adaptive grid (160dp minimum cells) beside a
// hard 280dp inspector column inside 24dp padding. On a 400dp window that left roughly 72dp
// for the grid — less than half of one cell — so the screen was unusable on a phone, and
// PhoneRoot rendered it unchanged behind a bottom nav bar.
//
// The inspector is now Expanded-only. These assert that by its heading, which
// InspectorGlobalStats renders whenever the panel is present.
@OptIn(ExperimentalTestApi::class)
class LibraryScreenTest {

    private fun inspectorHeading() = "Inspector"

    @Test
    fun expandedShowsTheInspectorPanel() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryScreen(
                    downloadFolder = null,
                    onOpenFolder = {},
                    windowSize = WindowSize.Expanded,
                )
            }
        }

        onNodeWithText(inspectorHeading()).assertIsDisplayed()
    }

    @Test
    fun compactDropsTheInspectorPanel() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryScreen(
                    downloadFolder = null,
                    onOpenFolder = {},
                    windowSize = WindowSize.Compact,
                )
            }
        }

        onAllNodesWithText(inspectorHeading()).assertCountEquals(0)
    }

    // Medium keeps the sidebar (drawn by App, not here) but cannot also afford a 280dp
    // inspector, so it drops the panel too. This is the case that used to fall through to
    // the Expanded layout entirely.
    @Test
    fun mediumDropsTheInspectorPanelToo() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryScreen(
                    downloadFolder = null,
                    onOpenFolder = {},
                    windowSize = WindowSize.Medium,
                )
            }
        }

        onAllNodesWithText(inspectorHeading()).assertCountEquals(0)
    }

    // With no folder chosen the empty state must still offer a way out, at every width.
    @Test
    fun emptyStateOffersAFolderPickerAtEveryWidth() {
        WindowSize.entries.forEach { size ->
            runComposeUiTest {
                setContent {
                    SnapVaultTheme(darkMode = true) {
                        LibraryScreen(
                            downloadFolder = null,
                            onOpenFolder = {},
                            windowSize = size,
                        )
                    }
                }
                onNodeWithText("Select Download Folder").assertIsDisplayed()
            }
        }
    }
}
