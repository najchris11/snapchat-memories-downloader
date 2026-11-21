package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.VideoStitchResult
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class VideoStitchDialogTest {
    private fun video(name: String) = LibraryItem("/out/$name.mp4", "Jan 01, 2024", name, "video", false, false)

    @Test
    fun theUserChoosesClipOrderBeforeStitching() = runComposeUiTest {
        var selected = emptyList<String>()
        var output: String? = null
        setContent {
            SnapVaultTheme(darkMode = true) {
                VideoStitchDialog(listOf(video("A"), video("B")), "/out", { output = it }, {}, stitch = { paths, _ ->
                    selected = paths
                    VideoStitchResult.Success("/out/stitched.mp4")
                })
            }
        }
        onNodeWithText("Stitch selected clips").assertIsNotEnabled()
        onNodeWithText("Add B").performClick()
        onNodeWithText("Add A").performClick()
        onNodeWithText("Stitch selected clips").performClick()
        waitForIdle()
        assertEquals(listOf("/out/B.mp4", "/out/A.mp4"), selected)
        assertEquals("/out/stitched.mp4", output)
    }

    @Test
    fun incompatibleClipsShowARecoverableError() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                VideoStitchDialog(listOf(video("A"), video("B")), "/out", {}, {}, stitch = { _, _ -> VideoStitchResult.Incompatible })
            }
        }
        onNodeWithText("Add A").performClick()
        onNodeWithText("Add B").performClick()
        onNodeWithText("Stitch selected clips").performClick()
        waitForIdle()
        onNodeWithText("These clips have different video or audio streams. Choose compatible clips from the same recording.").assertExists()
    }
}
