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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.najdev.snapvault.VaultIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.viewmodel.DashboardViewModel
import com.najdev.snapvault.viewmodel.FakeMediaProcessor
import com.najdev.snapvault.viewmodel.FakePlatformPickers
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
 * what a button's name is for: "Remove from favorites" on an item that is one, "Add to
 * favorites" on one that is not.
 */
@OptIn(ExperimentalTestApi::class)
class FavoriteToggleTest {

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
        if (favorited) "Remove from favorites" else "Add to favorites"

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
    fun anUnfavoritedItemOffersToAddIt() = runComposeUiTest {
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
    fun aFavoritedItemOffersToRemoveIt() = runComposeUiTest {
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
    // so a favorite toggle — which changes an item's *contents* but not its position —
    // counted as a change and closed the inspector out from under the press that caused it.
    @Test
    fun favoritingFromTheInspectorKeepsTheItemSelectedAndPersists() = runComposeUiTest {
        val folder = createTempDirectory("snapvault-favorites").toFile()
        // scanMediaFiles only stats these; empty files render a real grid.
        File(folder, "2026-01-02_b.jpg").createNewFile()
        File(folder, "2026-01-01_a.jpg").createNewFile()
        val viewModel = libraryViewModel(folder)

        try {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    WiredLibrary(viewModel, folder, WindowSize.Expanded)
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
                "the favorite has to reach disk — it is the only copy there is",
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

    // ── Reachability at every width ──────────────────────────────────────────

    // The inspector is rendered only at Expanded width (`showInspector = windowSize ==
    // WindowSize.Expanded`), so an inspector-only toggle left Compact and Medium users unable
    // to favorite anything — while still showing them a Favorites filter over state they
    // had no way to create. The preview dialog is the one surface reachable at every width.
    @Test
    fun thePreviewDialogCarriesTheToggleSoItIsReachableWithoutTheInspector() = runComposeUiTest {
        val toggled = mutableListOf<Boolean>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaPreviewDialog(
                    item = memory(favorited = false),
                    onDismiss = {},
                    loadFull = { null },
                    onToggleFavorite = { toggled += it },
                )
            }
        }

        onNodeWithContentDescription(label(false)).assertHasClickAction().performClick()
        assertEquals(listOf(true), toggled)
    }

    @Test
    fun thePreviewDialogShowsTheCurrentFavoriteState() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaPreviewDialog(
                    item = memory(favorited = true),
                    onDismiss = {},
                    loadFull = { null },
                    onToggleFavorite = {},
                )
            }
        }

        onNodeWithContentDescription(label(true)).assertExists()
        onNodeWithContentDescription(label(false)).assertDoesNotExist()
    }

    // Compact is the width with no inspector at all. Driven through LibraryScreen rather than
    // the dialog directly, because what is being pinned is that the path from a grid card to a
    // persisted favorite exists at that width — the finding was about reachability, not about
    // the dialog in isolation.
    @Test
    fun aFavoriteCanBeSetAtCompactWidthWhereThereIsNoInspector() = runComposeUiTest {
        val folder = createTempDirectory("snapvault-compact-favorite").toFile()
        File(folder, "2026-01-02_b.jpg").createNewFile()
        val viewModel = libraryViewModel(folder)

        try {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    WiredLibrary(viewModel, folder, WindowSize.Compact)
                }
            }
            waitUntil { onAllNodesWithText("2026-01-02_b").fetchSemanticsNodes().isNotEmpty() }

            // No inspector at this width, so the card's title appears exactly once.
            onAllNodesWithText("2026-01-02_b").assertCountEquals(1)

            onNodeWithText("2026-01-02_b").performClick()
            onNodeWithContentDescription(label(false)).performClick()
            waitForIdle()

            waitUntil {
                VaultIndex.read(okio.FileSystem.SYSTEM, folder.absolutePath)["2026-01-02_b.jpg"]
                    ?.favorited == true
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    // The write used to run on the screen's own rememberCoroutineScope(), so leaving the
    // Library — which PhoneRoot does by swapping the composable out of a `when` — cancelled it
    // mid-flight and the favorite was simply gone, with the heart having shown it saved.
    //
    // Making that deterministic takes some care. An okio write is blocking, so once it starts
    // it cannot be cancelled and blocking *inside* it proves nothing: the first version of
    // this test did exactly that and passed against a composition-scoped write. The one
    // cancellable suspension point on this path is VaultIndex's lock, so the test holds that
    // lock from another coroutine, leaving the favorite write suspended on it — cancellable,
    // and demonstrably not yet written — while the Library is torn out of composition.
    @Test
    fun aFavoriteSurvivesTheLibraryLeavingCompositionBeforeItsWriteRuns() = runComposeUiTest {
        val folder = createTempDirectory("snapvault-navigate-away").toFile()
        File(folder, "2026-01-02_b.jpg").createNewFile()
        val gate = GatedIndexFileSystem(okio.FileSystem.SYSTEM)
        val viewModel = libraryViewModel(folder)
        var libraryVisible by mutableStateOf(true)

        val holder = CoroutineScope(Dispatchers.IO)
        try {
            // Occupies VaultIndex's lock until the gate is released.
            holder.launch { VaultIndex.write(gate, folder.absolutePath, emptyMap()) }
            // Not just launched — actually holding the lock, or the favorite write below could
            // slip past it and complete before the teardown this test is about.
            waitUntil(timeoutMillis = 5_000) { gate.isHoldingLock() }

            setContent {
                SnapVaultTheme(darkMode = true) {
                    if (libraryVisible) WiredLibrary(viewModel, folder, WindowSize.Compact)
                }
            }
            waitUntil { onAllNodesWithText("2026-01-02_b").fetchSemanticsNodes().isNotEmpty() }

            onNodeWithText("2026-01-02_b").performClick()
            onNodeWithContentDescription(label(false)).performClick()

            // The write is now queued behind the lock rather than done.
            waitUntil { viewModel.favoriteIsPending(File(folder, "2026-01-02_b.jpg").absolutePath) }

            libraryVisible = false
            waitForIdle()
            onAllNodesWithText("2026-01-02_b").assertCountEquals(0)

            gate.release()

            waitUntil(timeoutMillis = 5_000) {
                VaultIndex.read(okio.FileSystem.SYSTEM, folder.absolutePath)["2026-01-02_b.jpg"]
                    ?.favorited == true
            }
        } finally {
            gate.release()
            holder.cancel()
            folder.deleteRecursively()
        }
    }

    private fun libraryViewModel(
        folder: File,
        fileSystem: okio.FileSystem = okio.FileSystem.SYSTEM,
    ) = DashboardViewModel(
        zipPipelineRunner = NoOpZipPipelineRunner,
        mediaProcessor = FakeMediaProcessor(),
        fileSystem = fileSystem,
        pickers = FakePlatformPickers(htmlPath = "", outputDir = folder.absolutePath),
        outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
    ).apply { pickOutputFolder() }

    @Composable
    private fun WiredLibrary(viewModel: DashboardViewModel, folder: File, windowSize: WindowSize) {
        LibraryScreen(
            downloadFolder = folder.absolutePath,
            onOpenFolder = {},
            windowSize = windowSize,
            favoriteOverrides = viewModel.favoriteOverrides,
            onToggleFavorite = { item, favorited -> viewModel.setFavorite(item.id, favorited) },
            onFavoritesScanned = viewModel::reconcileFavorites,
        )
    }
}

/**
 * Blocks index writes until released, so a test can guarantee a write is still in flight when
 * it tears the Library out of composition. Bounded, because this blocks while VaultIndex's
 * object-level lock is held and an unbounded wait would wedge the rest of the suite.
 */
private class GatedIndexFileSystem(
    delegate: okio.FileSystem,
) : okio.ForwardingFileSystem(delegate) {
    private val gate = java.util.concurrent.CountDownLatch(1)
    private val entered = java.util.concurrent.CountDownLatch(1)

    fun release() = gate.countDown()

    /** True once a write has actually reached the gate, and so holds VaultIndex's lock. */
    fun isHoldingLock(): Boolean = entered.count == 0L

    override fun sink(file: okio.Path, mustCreate: Boolean): okio.Sink {
        if (VaultIndex.FILE_NAME in file.name) {
            entered.countDown()
            check(gate.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "gate never released" }
        }
        return super.sink(file, mustCreate)
    }
}
