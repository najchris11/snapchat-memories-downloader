package com.najdev.snapvault.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D18: on desktop the Library does not play video — it hands the file to the system player.
 * The panel said "Click to Play Video", then "Opening Video..." for two seconds whatever
 * happened, so a file with no associated player, or one deleted since the scan, looked like it
 * had opened.
 */
@OptIn(ExperimentalTestApi::class)
class ExternalVideoPlayerTest {

    @Test
    fun thePanelSaysItOpensAnotherAppRatherThanPlayingHere() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                ExternalVideoPlayer(videoPath = "/v.mp4", modifier = Modifier, openExternally = {})
            }
        }

        onNodeWithText("Open in your video player").assertIsDisplayed()
        assertTrue(onAllNodes(hasTextContaining("Click to Play")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFailedLaunchIsShownAndStaysShown() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                ExternalVideoPlayer(
                    videoPath = "/v.mp4",
                    modifier = Modifier,
                    openExternally = { throw IOException("No application knows how to open this file") },
                )
            }
        }

        onNodeWithText("Open in your video player").performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTextContaining("No application knows how to open this file")).fetchSemanticsNodes().isNotEmpty()
        }
        // The old message reset on a two-second timer regardless of outcome.
        mainClock.advanceTimeBy(10_000)
        onNode(hasTextContaining("Could not open this video")).assertIsDisplayed()
    }

    @Test
    fun openingLastsAsLongAsTheLaunchAndNotAFixedDelay() = runComposeUiTest {
        val release = CompletableDeferred<Unit>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                ExternalVideoPlayer(videoPath = "/v.mp4", modifier = Modifier, openExternally = { release.await() })
            }
        }

        onNodeWithText("Open in your video player").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTextContaining("Opening")).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(10_000)
        onNode(hasTextContaining("Opening")).assertIsDisplayed()

        release.complete(Unit)
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTextContaining("Open in your video player")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(onAllNodes(hasTextContaining("Could not open")).fetchSemanticsNodes().isEmpty())
    }

    // The launcher itself: a file removed since the Library scanned is reported as that, not
    // passed to the OS to fail somewhere the user cannot see.
    @Test
    fun launchingAFileThatNoLongerExistsFailsWithAReason() {
        val missing = File(System.getProperty("java.io.tmpdir"), "snapvault-missing-${System.nanoTime()}.mp4")

        val error = assertFailsWith<IOException> { openInVideoPlayer(missing.path) }

        assertTrue("no longer exists" in error.message.orEmpty(), error.message)
    }

    private fun hasTextContaining(text: String) = androidx.compose.ui.test.hasText(text, substring = true)
}
