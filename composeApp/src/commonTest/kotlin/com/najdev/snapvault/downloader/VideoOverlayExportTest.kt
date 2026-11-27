package com.najdev.snapvault.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VideoOverlayExportTest {
    private class TestStorage : VideoOverlayFiles {
        val files = mutableSetOf("main.mp4", "overlay.png")
        val removed = mutableListOf<String>()
        override fun exists(path: String) = path in files
        override fun stage(output: String) = "$output.unique.mp4"
        override fun publish(stage: String, output: String): Boolean {
            if (output in files || stage !in files) return false
            files += output
            files -= stage
            return true
        }
        override fun discard(stage: String) { removed += stage; files -= stage }
    }
    @Test fun aDestinationCreatedDuringEncodingIsNeverReplaced() = runTest {
        val f = TestStorage()
        assertFalse(exportVideoOverlay("main.mp4", "overlay.png", "combined.mp4", f) { _, _, stage ->
            f.files += stage
            f.files += "combined.mp4"
            true
        })
        assertEquals(setOf("main.mp4", "overlay.png", "combined.mp4"), f.files)
    }
    @Test fun missingSourcesNeverStartTheEncoder() = runTest {
        val f = TestStorage()
        var attempts = 0
        assertFalse(exportVideoOverlay("missing.mp4", "overlay.png", "combined.mp4", f) { _, _, _ -> attempts++; false })
        assertEquals(0, attempts)
        assertEquals(setOf("main.mp4", "overlay.png"), f.files)
    }
    @Test fun successfulVideoPublishesASeparateOutputAndKeepsBothSources() = runTest {
        val f = TestStorage()
        assertTrue(exportVideoOverlay("main.mp4", "overlay.png", "combined.mp4", f) { _, _, stage ->
            f.files += stage; true
        })
        assertEquals(setOf("main.mp4", "overlay.png", "combined.mp4"), f.files)
    }
    @Test fun failuresConflictsAndCancellationCannotPublishOrDeleteSources() = runTest {
        val f = TestStorage()
        assertFalse(exportVideoOverlay("main.mp4", "overlay.png", "combined.mp4", f) { _, _, stage ->
            f.files += stage; false
        })
        f.files += "combined.mp4"
        assertFalse(exportVideoOverlay("main.mp4", "overlay.png", "combined.mp4", f) { _, _, _ -> error("must not export") })
        assertFailsWith<CancellationException> {
            exportVideoOverlay("main.mp4", "overlay.png", "other.mp4", f) { _, _, stage ->
                f.files += stage; throw CancellationException("cancel")
            }
        }
        assertEquals(setOf("main.mp4", "overlay.png", "combined.mp4"), f.files)
    }
}
