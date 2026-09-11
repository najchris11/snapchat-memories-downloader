package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertTrue

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

    // Regression for the review finding on PR #31. The filter tabs and a 200dp search field
    // sat in one non-wrapping Row: together they want about 384dp, more than Compact (~328dp
    // of content on a 360dp phone) or the narrow end of Medium (~332dp after the 220dp
    // sidebar) can give.
    //
    // Measured rather than assumed, because the symptom is not what it looks like.
    // Modifier.width(200.dp) is coerced by the incoming constraints, so the field does not
    // clip off-screen and does not overlap the tabs — it silently collapses. At a 360dp
    // container it measured 110dp at Compact and 94dp at Medium, against 289dp and 273dp
    // once the row stacks. So the property worth asserting is that the field keeps at least
    // the width it asks for.
    @Test
    fun searchFieldKeepsItsWidthAtNarrowWidths() {
        val intendedWidth = 200.dp
        listOf(
            WindowSize.Compact to 360.dp,
            WindowSize.Medium to 380.dp,
        ).forEach { (size, containerWidth) ->
            runComposeUiTest {
                setContent {
                    SnapVaultTheme(darkMode = true) {
                        Box(Modifier.width(containerWidth).fillMaxHeight()) {
                            LibraryScreen(
                                downloadFolder = null,
                                onOpenFolder = {},
                                windowSize = size,
                            )
                        }
                    }
                }

                val field = onNodeWithText("Search library…").getUnclippedBoundsInRoot()
                val width = field.right - field.left
                assertTrue(
                    width >= intendedWidth,
                    "$size at $containerWidth: search field collapsed to $width, below $intendedWidth",
                )
                assertTrue(
                    field.right <= containerWidth,
                    "$size at $containerWidth: search field right edge ${field.right} is off-screen",
                )
            }
        }
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
