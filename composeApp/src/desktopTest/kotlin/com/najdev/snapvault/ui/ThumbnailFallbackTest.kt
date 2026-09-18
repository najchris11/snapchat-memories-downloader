package com.najdev.snapvault.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D19: a memory with no thumbnail rendered as a faint icon forever — the same as one still
 * loading, and with nothing to say the file itself was fine and could be opened.
 */
@OptIn(ExperimentalTestApi::class)
class ThumbnailFallbackTest {

    private fun unpreviewable(): LibraryItem {
        // A file with a media name that no decoder can read.
        val file = createTempDirectory("snapvault-fallback").toFile().resolve("2024-06-01_AAA.jpg")
        file.writeText("not an image")
        file.deleteOnExit()
        return LibraryItem(
            id = file.absolutePath,
            date = "JUN 01, 2024",
            title = "2024-06-01_AAA",
            type = "photo",
            hasGps = false,
            hasOverlay = false,
        )
    }

    @Test
    fun aCardThatCannotBePreviewedSaysSo() = runComposeUiTest {
        setContent { SnapVaultTheme(darkMode = true) { MediaCard(item = unpreviewable()) } }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("Preview unavailable", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theInspectorSaysSoToo() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                InspectorItemDetail(item = unpreviewable(), onPreview = {}, onClearSelection = {})
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("Preview unavailable", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // Loading is not unavailable: the fallback must not flash up for a file that is simply
    // still being thumbnailed.
    //
    // The load is held open rather than left to finish on its own. Advancing one frame and
    // hoping the decode has not landed yet is a race against an IO thread: it passed locally
    // and failed on CI on every single run. Holding it open makes "still loading" a state the
    // test creates instead of a window it has to win.
    @Test
    fun theFallbackIsNotShownBeforeLoadingHasFinished() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalThumbnailLoader provides { awaitCancellation() }) {
                SnapVaultTheme(darkMode = true) { MediaCard(item = unpreviewable()) }
            }
        }
        mainClock.advanceTimeByFrame()

        assertTrue(onAllNodes(hasText("Preview unavailable", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
