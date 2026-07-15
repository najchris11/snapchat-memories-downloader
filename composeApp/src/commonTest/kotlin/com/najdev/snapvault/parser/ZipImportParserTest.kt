package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipImportParserTest {

    // ── parseMemoryEntryNames (production ZIP path) ──────────────────────────

    @Test
    fun testZipMainAndOverlayPaired() {
        val entries = ZipImportParser.parseMemoryEntryNames(
            listOf(
                "memories/",
                "memories/2023-10-12_ABC123-main.jpg",
                "memories/2023-10-12_ABC123-overlay.png",
            )
        )
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("2023-10-12_ABC123-main.jpg", e.fileName)
        assertEquals("ABC123", e.uuid)
        assertEquals("2023-10-12", e.date)
        assertTrue(e.hasOverlay)
        assertEquals("2023-10-12_ABC123-overlay.png", e.overlayFileName)
    }

    // Regression: files without a -main/-overlay suffix must still produce entries.
    // v1.0.5 grouped them but dropped them in the entry-building pass.
    @Test
    fun testZipFileWithoutSuffixIsIncluded() {
        val entries = ZipImportParser.parseMemoryEntryNames(
            listOf(
                "memories/2023-01-15_NOSUFFIX1.jpg",
                "memories/2024-02-20_NOSUFFIX2.mp4",
            )
        )
        assertEquals(2, entries.size)
        val img = entries.first { it.uuid == "NOSUFFIX1" }
        assertEquals("2023-01-15_NOSUFFIX1.jpg", img.fileName)
        assertEquals("2023-01-15", img.date)
        assertFalse(img.isVideo)
        assertFalse(img.hasOverlay)
        assertNull(img.overlayFileName)
        val vid = entries.first { it.uuid == "NOSUFFIX2" }
        assertTrue(vid.isVideo)
    }

    @Test
    fun testZipSuffixlessFileDoesNotFireUnmatched() {
        val unmatched = mutableListOf<String>()
        ZipImportParser.parseMemoryEntryNames(
            listOf("memories/2023-01-15_NOSUFFIX1.jpg"),
        ) { unmatched.add(it) }
        assertTrue(unmatched.isEmpty(), "suffix-less files are valid and must not be reported unmatched")
    }

    // Regression: the same UUID under different dates is two distinct memories.
    // Grouping by UUID alone silently dropped all but one of them.
    @Test
    fun testZipDuplicateUuidAcrossDatesKeepsBoth() {
        val entries = ZipImportParser.parseMemoryEntryNames(
            listOf(
                "memories/2021-05-01_SAMEUUID-main.jpg",
                "memories/2021-05-01_SAMEUUID-overlay.png",
                "memories/2023-11-30_SAMEUUID-main.jpg",
            )
        )
        assertEquals(2, entries.size)
        val first = entries.first { it.date == "2021-05-01" }
        assertTrue(first.hasOverlay)
        assertEquals("2021-05-01_SAMEUUID-overlay.png", first.overlayFileName)
        val second = entries.first { it.date == "2023-11-30" }
        assertFalse(second.hasOverlay, "overlay from a different date must not attach to this memory")
    }

    @Test
    fun testZipOverlayOnlyMemory() {
        val entries = ZipImportParser.parseMemoryEntryNames(
            listOf("memories/2022-07-04_LONELY-overlay.png")
        )
        assertEquals(1, entries.size)
        assertEquals("2022-07-04_LONELY-overlay.png", entries[0].fileName)
        assertFalse(entries[0].hasOverlay)
        assertNull(entries[0].overlayFileName)
    }

    @Test
    fun testZipUnmatchedFilesReported() {
        val unmatched = mutableListOf<String>()
        val entries = ZipImportParser.parseMemoryEntryNames(
            listOf(
                "memories/weird name.dat",
                "memories/2023-10-12_OK-main.jpg",
                "html/memories_history.html",
            )
        ) { unmatched.add(it) }
        assertEquals(1, entries.size)
        assertEquals(listOf("memories/weird name.dat"), unmatched)
    }

    @Test
    fun testZipVideoExtensionsRecognized() {
        val entries = ZipImportParser.parseMemoryEntryNames(
            listOf(
                "memories/2023-01-01_V1-main.mp4",
                "memories/2023-01-02_V2-main.mov",
                "memories/2023-01-03_V3-main.mkv",
                "memories/2023-01-04_I1-main.jpg",
            )
        )
        assertEquals(3, entries.count { it.isVideo })
        assertFalse(entries.first { it.uuid == "I1" }.isVideo)
    }
}
