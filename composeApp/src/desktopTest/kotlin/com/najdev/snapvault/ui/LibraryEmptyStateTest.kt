package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Library had one empty state for three different situations, and offered a way out of
 * only the first: "Select Download Folder" was guarded by `downloadFolder == null`, so once a
 * folder was set there was no control of any kind — you could not see which folder was being
 * scanned, change it, or refresh it.
 *
 * The third case is an outright bug rather than missing polish: filter to Videos in a library
 * of photos and the screen says "No memories found", of a library that is full.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryEmptyStateTest {

    @Test
    fun theThreeEmptyCasesAreDistinguished() {
        assertEquals(
            LibraryEmptyReason.NoFolder,
            libraryEmptyReason(downloadFolder = null, scanned = 0, filtered = 0),
        )
        assertEquals(
            LibraryEmptyReason.NoMedia,
            libraryEmptyReason(downloadFolder = "/vault", scanned = 0, filtered = 0),
        )
        // The library is full; the filter is what is hiding everything.
        assertEquals(
            LibraryEmptyReason.FilteredOut,
            libraryEmptyReason(downloadFolder = "/vault", scanned = 12, filtered = 0),
        )
    }

    @Test
    fun aLibraryWithVisibleItemsIsNotEmptyAtAll() {
        assertNull(libraryEmptyReason(downloadFolder = "/vault", scanned = 12, filtered = 3))
    }

    // A folder is set and it holds nothing. Previously: an icon, a sentence, and nothing to
    // press — not even a way to see which folder was being scanned.
    @Test
    fun anEmptyFolderShowsItsPathAndOffersAWayOut() = runComposeUiTest {
        var changed = 0
        var refreshed = 0
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryEmptyState(
                    reason = LibraryEmptyReason.NoMedia,
                    downloadFolder = "/home/someone/SnapVault",
                    onOpenFolder = { changed++ },
                    onRefresh = { refreshed++ },
                    onClearFilters = {},
                )
            }
        }

        onNodeWithText("/home/someone/SnapVault", substring = true).assertExists()
        onNodeWithText("Change folder").assertHasClickAction().performClick()
        onNodeWithText("Refresh").assertHasClickAction().performClick()
        assertEquals(1, changed)
        assertEquals(1, refreshed)
    }

    @Test
    fun filteredOutOffersToClearTheFilterRatherThanClaimingTheLibraryIsEmpty() = runComposeUiTest {
        var cleared = 0
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryEmptyState(
                    reason = LibraryEmptyReason.FilteredOut,
                    downloadFolder = "/vault",
                    onOpenFolder = {},
                    onRefresh = {},
                    onClearFilters = { cleared++ },
                )
            }
        }

        onNodeWithText("Clear filters").assertHasClickAction().performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun noFolderChosenStillOffersThePicker() = runComposeUiTest {
        var opened = 0
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryEmptyState(
                    reason = LibraryEmptyReason.NoFolder,
                    downloadFolder = null,
                    onOpenFolder = { opened++ },
                    onRefresh = {},
                    onClearFilters = {},
                )
            }
        }

        onNodeWithText("Select Download Folder").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }

    // Each case has to say something different, or distinguishing them internally buys the
    // user nothing.
    @Test
    fun eachCaseShowsItsOwnMessage() = runComposeUiTest {
        val messages = mutableSetOf<String>()
        LibraryEmptyReason.entries.forEach { reason ->
            runComposeUiTest {
                setContent {
                    SnapVaultTheme(darkMode = true) {
                        messages += libraryEmptyMessage(reason)
                    }
                }
            }
        }
        assertEquals(LibraryEmptyReason.entries.size, messages.size, "shared wording: $messages")
        assertTrue(messages.none { it.isBlank() })
    }
}
