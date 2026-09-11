package com.najdev.snapvault.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop app had no keyboard handling at all: no `onKeyEvent` anywhere, so the media
 * preview could only be dismissed with the mouse, the grid could not be walked, and the
 * search field could only be reached by clicking it.
 *
 * The grid arithmetic is the part with edges worth pinning — a partial last row, the first
 * arrow press with nothing selected yet, an empty grid — so it lives in a pure function and
 * is tested as one. The rest are wiring tests.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryKeyboardTest {

    private fun items(n: Int) = List(n) {
        LibraryItem(
            id = "item-$it",
            date = "2026-01-0${it % 9 + 1}",
            title = "Memory $it",
            type = "photo",
                hasGps = false,
            hasOverlay = false,
        )
    }

    // ── Grid arithmetic ──────────────────────────────────────────────────────

    @Test
    fun arrowsMoveWithinTheGrid() {
        // 9 items, 3 columns.
        assertEquals(1, libraryGridTarget(Key.DirectionRight, current = 0, count = 9, columns = 3))
        assertEquals(0, libraryGridTarget(Key.DirectionLeft, current = 1, count = 9, columns = 3))
        assertEquals(3, libraryGridTarget(Key.DirectionDown, current = 0, count = 9, columns = 3))
        assertEquals(0, libraryGridTarget(Key.DirectionUp, current = 3, count = 9, columns = 3))
    }

    @Test
    fun theSelectionDoesNotFallOffTheEdges() {
        assertEquals(0, libraryGridTarget(Key.DirectionLeft, current = 0, count = 9, columns = 3))
        assertEquals(8, libraryGridTarget(Key.DirectionRight, current = 8, count = 9, columns = 3))
        assertEquals(1, libraryGridTarget(Key.DirectionUp, current = 1, count = 9, columns = 3))
        assertEquals(7, libraryGridTarget(Key.DirectionDown, current = 7, count = 9, columns = 3))
    }

    // A library is almost never a whole number of rows. Moving down out of a full row into a
    // short one must not land past the end.
    @Test
    fun downIntoAPartialLastRowStaysInBounds() {
        // 8 items, 3 columns: last row holds only indices 6 and 7.
        assertEquals(7, libraryGridTarget(Key.DirectionDown, current = 4, count = 8, columns = 3))
        assertEquals(5, libraryGridTarget(Key.DirectionDown, current = 5, count = 8, columns = 3))
    }

    @Test
    fun theFirstArrowPressSelectsTheFirstItem() {
        assertEquals(0, libraryGridTarget(Key.DirectionRight, current = -1, count = 9, columns = 3))
        assertEquals(0, libraryGridTarget(Key.DirectionDown, current = -1, count = 9, columns = 3))
        assertEquals(0, libraryGridTarget(Key.DirectionUp, current = -1, count = 9, columns = 3))
    }

    @Test
    fun homeAndEndJumpToTheEnds() {
        assertEquals(0, libraryGridTarget(Key.MoveHome, current = 5, count = 9, columns = 3))
        assertEquals(8, libraryGridTarget(Key.MoveEnd, current = 5, count = 9, columns = 3))
    }

    // Returning null rather than the current index is what lets the handler report the key
    // as unconsumed, so typing still reaches whatever else wants it.
    @Test
    fun aKeyThatIsNotAMovementIsNotHandled() {
        assertNull(libraryGridTarget(Key.A, current = 0, count = 9, columns = 3))
        assertNull(libraryGridTarget(Key.Enter, current = 0, count = 9, columns = 3))
    }

    @Test
    fun anEmptyGridHasNowhereToMoveTo() {
        assertNull(libraryGridTarget(Key.DirectionRight, current = -1, count = 0, columns = 3))
    }

    // ── Wiring ───────────────────────────────────────────────────────────────

    @Test
    fun arrowKeysMoveTheGridSelection() = runComposeUiTest {
        var selected = -1
        val grid = FocusRequester()
        setContent {
            SnapVaultTheme(darkMode = true) {
                var index by remember { mutableStateOf(-1) }
                LaunchedEffect(Unit) { grid.requestFocus() }
                LibraryGrid(
                    items = items(6),
                    selectedIndex = index,
                    onSelect = { index = it; selected = it },
                    onOpen = {},
                    compact = false,
                    focusRequester = grid,
                    modifier = Modifier.size(400.dp),
                )
            }
        }

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(0, selected, "the first arrow press should select the first item")
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(1, selected)
    }

    @Test
    fun enterOpensTheSelectedItem() = runComposeUiTest {
        var opened = -1
        val grid = FocusRequester()
        setContent {
            SnapVaultTheme(darkMode = true) {
                LaunchedEffect(Unit) { grid.requestFocus() }
                LibraryGrid(
                    items = items(6),
                    selectedIndex = 2,
                    onSelect = {},
                    onOpen = { opened = it },
                    compact = false,
                    focusRequester = grid,
                    modifier = Modifier.size(400.dp),
                )
            }
        }

        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        assertEquals(2, opened)
    }

    // Arrow navigation is only reachable if something in the grid holds focus, and the way a
    // user actually gets there is a mouse click — not a FocusRequester the other tests call
    // by hand. This one deliberately never requests focus itself.
    @Test
    fun clickingACardLeavesTheGridTakingArrowKeys() = runComposeUiTest {
        var selected = -1
        setContent {
            SnapVaultTheme(darkMode = true) {
                var index by remember { mutableStateOf(LIBRARY_NO_SELECTION) }
                LibraryGrid(
                    items = items(6),
                    selectedIndex = index,
                    onSelect = { index = it; selected = it },
                    onOpen = { index = it; selected = it },
                    compact = false,
                    focusRequester = remember { FocusRequester() },
                    modifier = Modifier.size(400.dp),
                )
            }
        }

        onNodeWithText("Memory 0").performClick()
        assertEquals(0, selected, "clicking a card should select it")

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(1, selected, "the arrow keys should work straight after a mouse click")
    }

    // The preview covers the whole window and its only exit was a mouse click on the scrim or
    // the close button.
    @Test
    fun escapeClosesTheMediaPreview() = runComposeUiTest {
        var dismissed = false
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaPreviewDialog(item = items(1).first(), onDismiss = { dismissed = true })
            }
        }

        onNode(isDialog()).performKeyInput { pressKey(Key.Escape) }
        assertTrue(dismissed, "Escape should dismiss the preview dialog")
    }

    // The other wiring tests hold the grid and the dialog apart. This one walks the path a
    // user actually walks — click a card, the preview opens over it, Escape, then arrows —
    // because that path crosses a focus boundary none of them do: the dialog takes focus on
    // open (it must, or Escape would not reach it) and the grid has to get it back.
    @Test
    fun arrowKeysStillWorkAfterThePreviewHasBeenOpenedAndClosed() = runComposeUiTest {
        // scanMediaFiles only stats these, and a thumbnail that fails to decode falls back to
        // the placeholder, so empty files are enough to render a real grid. Named so the
        // scanner's date parser orders them predictably: newest first.
        val folder = createTempDirectory("snapvault-library").toFile()
        listOf("2026-01-04_d", "2026-01-03_c", "2026-01-02_b", "2026-01-01_a")
            .forEach { File(folder, "$it.jpg").createNewFile() }

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
            waitUntil { onAllNodesWithText("2026-01-04_d").fetchSemanticsNodes().isNotEmpty() }

            onNodeWithText("2026-01-04_d").performClick()
            onNode(isDialog()).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            // The inspector renders the selected item's title alongside the grid card, so the
            // selected title is the one appearing twice. Asserted before as well as after, or
            // a count of 2 on the wrong item would read as a pass.
            onAllNodesWithText("2026-01-04_d").assertCountEquals(2)
            onAllNodesWithText("2026-01-03_c").assertCountEquals(1)

            onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }

            onAllNodesWithText("2026-01-03_c").assertCountEquals(2)
            onAllNodesWithText("2026-01-04_d").assertCountEquals(1)
        } finally {
            folder.deleteRecursively()
        }
    }

    // ── Slash-to-search ──────────────────────────────────────────────────────

    // The handler sits on an ancestor of the field, and a preview handler runs before the
    // focused child gets the key — so without this rule, `/` could never be typed into the
    // search box it had just jumped to.
    @Test
    fun slashIsIgnoredWhileTheSearchFieldAlreadyHasFocus() {
        assertTrue(shouldFocusSearch(Key.Slash, KeyEventType.KeyDown, searchFocused = false))
        assertFalse(shouldFocusSearch(Key.Slash, KeyEventType.KeyDown, searchFocused = true))
        assertFalse(shouldFocusSearch(Key.A, KeyEventType.KeyDown, searchFocused = false))
        // One press is one jump, not two.
        assertFalse(shouldFocusSearch(Key.Slash, KeyEventType.KeyUp, searchFocused = false))
    }

    @Test
    fun slashMovesFocusToTheSearchField() = runComposeUiTest {
        val search = FocusRequester()
        val other = FocusRequester()
        var searchFocused = false
        setContent {
            SnapVaultTheme(darkMode = true) {
                LaunchedEffect(Unit) { other.requestFocus() }
                Column(modifier = Modifier.focusSearchOnSlash(search) { searchFocused }) {
                    Text("elsewhere", modifier = Modifier.focusRequester(other).focusable())
                    Text(
                        "search box",
                        modifier = Modifier
                            .focusRequester(search)
                            .onFocusChanged { searchFocused = it.isFocused }
                            .focusable(),
                    )
                }
            }
        }

        onNodeWithText("elsewhere").assertIsFocused()
        onNode(isFocused()).performKeyInput { pressKey(Key.Slash) }
        onNodeWithText("search box").assertIsFocused()
    }
}
