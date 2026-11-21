package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.GalleryExportResult
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GalleryExportButtonTest {
    @Test
    fun photosActionIsExplicitAndReportsSuccessfulCopies() = runComposeUiTest {
        var calls = 0
        setContent { SnapVaultTheme(darkMode = true) {
            GalleryExportButton("/sample.jpg", supported = true, applePhotos = true,
                publish = { calls++; GalleryExportResult.Saved })
        } }
        waitForIdle()
        assertEquals(0, calls)
        onNodeWithText("Save to Apple Photos").performClick()
        waitForIdle()
        onNodeWithText("Saved to Apple Photos").assertExists()
        assertEquals(1, calls)
    }
    @Test
    fun galleryPublicationWaitsForClickAndAllowsRetryAfterFailure() = runComposeUiTest {
        var calls = 0
        setContent {
            SnapVaultTheme(darkMode = true) {
                GalleryExportButton("/sample.jpg", supported = true, publish = {
                    calls++
                    if (calls == 1) GalleryExportResult.Failed else GalleryExportResult.Saved
                })
            }
        }
        waitForIdle()
        assertEquals(0, calls)
        onNodeWithText("Save to gallery").performClick()
        waitForIdle()
        onNodeWithText("Could not save this copy. The library file is unchanged; try again.").assertExists()
        onNodeWithText("Save to gallery").performClick()
        waitForIdle()
        onNodeWithText("Saved to gallery").assertExists()
        onNodeWithText("Save to gallery").assertIsNotEnabled()
        assertEquals(2, calls)
    }
    @Test
    fun galleryPublicationIsDisabledWhileTheLibraryIsBeingUpdated() = runComposeUiTest {
        var calls = 0
        setContent { SnapVaultTheme(darkMode = true) {
            GalleryExportButton("/sample.jpg", supported = true, enabled = false, publish = { calls++; GalleryExportResult.Saved })
        } }
        onNodeWithText("Save to gallery").assertIsNotEnabled()
        assertEquals(0, calls)
    }
}
