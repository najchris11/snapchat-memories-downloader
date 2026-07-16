package com.najdev.snapvault.parser

import com.najdev.snapvault.model.MemoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExperimentalZipMetadataMatcherTest {

    @Test
    fun testExperimentalMatcherPairsSameDateFilesOneToOne() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-01_A-main.jpg",
                uuid = "A",
                date = "2024-01-01",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
            ),
            HtmlMemoryEntry(
                fileName = "2024-01-01_B-main.jpg",
                uuid = "B",
                date = "2024-01-01",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
            ),
        )

        val memories = listOf(
            MemoryItem(
                id = "first",
                url = "https://cdn.example.com/a.jpg",
                isGet = true,
                dateStr = "2024-01-01 12:00:00 UTC",
            ),
            MemoryItem(
                id = "second",
                url = "https://cdn.example.com/b.jpg",
                isGet = true,
                dateStr = "2024-01-01 12:05:00 UTC",
                latitude = 48.2,
                longitude = 13.3,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, memories)

        assertEquals(2, plan.targets.size)
        assertFalse(plan.targets[0].hasOverlay)
        assertTrue(plan.targets.any { it.latitude != null && it.longitude != null })
        assertTrue(plan.targets.any { it.latitude == null && it.longitude == null })
        assertEquals("2024-01-01 12:00:00 UTC", plan.targets[0].dateStr)
        assertEquals("2024-01-01 12:05:00 UTC", plan.targets[1].dateStr)
    }

    @Test
    fun testExperimentalMatcherAddsOverlayWithSameMetadata() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-02_C-main.mp4",
                uuid = "C",
                date = "2024-01-02",
                isVideo = true,
                hasOverlay = true,
                overlayFileName = "2024-01-02_C-overlay.png",
            ),
        )

        val memories = listOf(
            MemoryItem(
                id = "video",
                url = "https://cdn.example.com/c.mp4",
                isGet = false,
                dateStr = "2024-01-02 09:00:00 UTC",
                latitude = 1.0,
                longitude = 2.0,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, memories)

        assertEquals(2, plan.targets.size)
        assertEquals("2024-01-02_C-main.mp4", plan.targets[0].fileName)
        assertEquals("2024-01-02_C-overlay.png", plan.targets[1].fileName)
        assertEquals(plan.targets[0].dateStr, plan.targets[1].dateStr)
        assertEquals(plan.targets[0].latitude, plan.targets[1].latitude)
        assertEquals(plan.targets[0].longitude, plan.targets[1].longitude)
    }
}