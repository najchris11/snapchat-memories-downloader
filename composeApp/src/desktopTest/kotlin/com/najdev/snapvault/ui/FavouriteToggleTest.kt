package com.najdev.snapvault.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.VaultIndex
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MediaCard` has drawn a heart badge for `item.favorited` since the Library existed and
 * nothing could ever set it — the field was populated by nobody and toggled by nothing. This
 * is the control that makes the badge mean something.
 *
 * The label states what pressing does rather than what the current state is, because that is
 * what a button's name is for: "Remove from favourites" on an item that is one, "Add to
 * favourites" on one that is not.
 */
@OptIn(ExperimentalTestApi::class)
class FavouriteToggleTest {

    private fun memory(favorited: Boolean) = LibraryItem(
        id = "/vault/memory.jpg",
        date = "JAN 01, 2026",
        title = "memory",
        type = "photo",
        hasGps = false,
        hasOverlay = false,
        favorited = favorited,
    )

    private fun label(favorited: Boolean) =
        if (favorited) "Remove from favourites" else "Add to favourites"

    // The inspector is a scrolling column and the actions sit below the metadata rows, so in
    // a test-sized window they start off screen.
    private fun ComposeUiTest.toggle(item: LibraryItem, onToggle: (Boolean) -> Unit) {
        setContent {
            SnapVaultTheme(darkMode = true) {
                InspectorItemDetail(
                    item = item,
                    onPreview = {},
                    onClearSelection = {},
                    canRevealFiles = false,
                    onReveal = {},
                    onToggleFavorite = onToggle,
                )
            }
        }
    }

    @Test
    fun anUnfavouritedItemOffersToAddIt() = runComposeUiTest {
        val toggled = mutableListOf<Boolean>()
        toggle(memory(favorited = false)) { toggled += it }

        onNodeWithContentDescription(label(false))
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        assertEquals(listOf(true), toggled, "pressing must ask for the opposite of the current state")
        onNodeWithContentDescription(label(true)).assertDoesNotExist()
    }

    @Test
    fun aFavouritedItemOffersToRemoveIt() = runComposeUiTest {
        val toggled = mutableListOf<Boolean>()
        toggle(memory(favorited = true)) { toggled += it }

        onNodeWithContentDescription(label(true))
            .assertHasClickAction()
            .performScrollTo()
            .performClick()

        assertEquals(listOf(false), toggled)
        onNodeWithContentDescription(label(false)).assertDoesNotExist()
    }

    // The toggle is the one control in the inspector that is not about where the file lives,
    // so it must survive on the platforms that have no file manager to reveal into.
    @Test
    fun theToggleIsPresentWhereRevealIsNot() = runComposeUiTest {
        toggle(memory(favorited = false)) {}

        onNodeWithContentDescription("Reveal in file manager").assertDoesNotExist()
        onNodeWithContentDescription(label(false)).assertExists()
    }

    // The path a user actually walks, and the one that exposed the real hazard: the Library
    // drops `selectedIndex` whenever `filteredItems` changes, because after a filter or sort
    // the held position points at a different memory. `LaunchedEffect` compares structurally,
    // so a favourite toggle — which changes an item's *contents* but not its position —
    // counted as a change and closed the inspector out from under the press that caused it.
    @Test
    fun favouritingFromTheInspectorKeepsTheItemSelectedAndPersists() = runComposeUiTest {
        val folder = createTempDirectory("snapvault-favourites").toFile()
        // scanMediaFiles only stats these; empty files render a real grid.
        File(folder, "2026-01-02_b.jpg").createNewFile()
        File(folder, "2026-01-01_a.jpg").createNewFile()

        try {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    LibraryScreen(
                        downloadFolder = folder.absolutePath,
                        onOpenFolder = {},
                        windowSize = WindowSize.Expanded,
                    )
                }
            }
            waitUntil { onAllNodesWithText("2026-01-02_b").fetchSemanticsNodes().isNotEmpty() }

            // A click on a card both selects it and opens the preview over the inspector.
            onNodeWithText("2026-01-02_b").performClick()
            onNode(isDialog()).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithContentDescription(label(false)).performScrollTo().performClick()
            waitForIdle()

            // The inspector renders the selected item's title alongside its grid card, so the
            // selected title is the one appearing twice.
            onAllNodesWithText("2026-01-02_b").assertCountEquals(2)
            onNodeWithContentDescription(label(true)).assertExists()
            onNodeWithContentDescription(label(false)).assertDoesNotExist()

            assertTrue(
                VaultIndex.read(okio.FileSystem.SYSTEM, folder.absolutePath)["2026-01-02_b.jpg"]
                    ?.favorited == true,
                "the favourite has to reach disk — it is the only copy there is",
            )
            assertEquals(
                null,
                VaultIndex.read(okio.FileSystem.SYSTEM, folder.absolutePath)["2026-01-01_a.jpg"]
                    ?.favorited,
                "an untouched item must not gain an entry",
            )
        } finally {
            folder.deleteRecursively()
        }
    }
}
