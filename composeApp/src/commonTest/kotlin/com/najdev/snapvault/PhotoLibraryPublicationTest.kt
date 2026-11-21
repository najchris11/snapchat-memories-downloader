package com.najdev.snapvault

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoLibraryPublicationTest {
    @Test fun deniedPermissionNeverAttemptsToCreateAnAsset() = runTest {
        var copies = 0
        val result = PhotoLibraryPublication.publish("/library/photo.jpg", true, { false }) { copies++; true }
        assertEquals(GalleryExportResult.Failed, result)
        assertEquals(0, copies)
    }
    @Test fun validFileIsCopiedOnlyAfterAuthorizationAndFailureIsReported() = runTest {
        val calls = mutableListOf<String>()
        assertEquals(GalleryExportResult.Saved, PhotoLibraryPublication.publish("/library/clip.mp4", true,
            { calls += "permission"; true }) { calls += it; true })
        assertEquals(listOf("permission", "/library/clip.mp4"), calls)
        assertEquals(GalleryExportResult.Failed, PhotoLibraryPublication.publish("/library/photo.jpg", true, { true }) { false })
    }
    @Test fun missingOrUnsupportedFilesDoNotPromptForPermission() = runTest {
        var prompts = 0
        assertEquals(GalleryExportResult.Failed, PhotoLibraryPublication.publish("/missing.jpg", false, { prompts++; true }) { true })
        assertEquals(GalleryExportResult.Unsupported, PhotoLibraryPublication.publish("/library/data.json", true, { prompts++; true }) { true })
        assertEquals(0, prompts)
    }
}
