package com.najdev.snapvault.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `loadFullImage` was implemented on all four targets and called from nowhere. The preview
 * dialog instead drew the cached thumbnail — a JPEG re-encoded at `scale=320:-1` — with
 * `ContentScale.Fit` into a surface up to 860 × 680, so every photo preview was a 2.7×
 * upscale of a downscaled, recompressed image.
 *
 * The load is progressive rather than a straight swap, so nothing gets slower: the cached
 * thumbnail still appears immediately and the full image replaces it when it resolves.
 */
@OptIn(ExperimentalTestApi::class)
class MediaPreviewDialogTest {

    private fun item(type: String) = LibraryItem(
        id = "/vault/memory.${if (type == "video") "mp4" else "jpg"}",
        date = "2026-01-01",
        title = "Memory",
        type = type,
        hasGps = false,
        hasOverlay = false,
    )

    @Test
    fun aPhotoPreviewAsksForTheFullImage() = runComposeUiTest {
        val requested = mutableListOf<String>()
        val photo = item("photo")
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaPreviewDialog(
                    item = photo,
                    onDismiss = {},
                    loadFull = { path -> requested += path; ImageBitmap(1, 1) },
                )
            }
        }
        waitForIdle()

        assertEquals(listOf(photo.id), requested)
    }

    // Videos are drawn by VideoPlayer, which loads its own frame and hands the file to the
    // system player. Decoding a full image for one would be wasted work on the largest files
    // in the library.
    @Test
    fun aVideoPreviewDoesNotAskForOne() = runComposeUiTest {
        val requested = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaPreviewDialog(
                    item = item("video"),
                    onDismiss = {},
                    loadFull = { path -> requested += path; ImageBitmap(1, 1) },
                )
            }
        }
        waitForIdle()

        assertEquals(emptyList(), requested)
    }
}
