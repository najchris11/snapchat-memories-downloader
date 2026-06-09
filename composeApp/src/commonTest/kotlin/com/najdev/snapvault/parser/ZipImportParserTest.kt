package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipImportParserTest {

    private fun block(date: String, mainFile: String, overlayFile: String? = null): String {
        val overlaySrc = if (overlayFile != null) "\n<img src=\".//$overlayFile\">" else ""
        return """
<div class="image-container">
<p class="text-line">$date</p>
<img src=".//memories/$mainFile">$overlaySrc
</div>
</div>
        """.trimIndent()
    }

    @Test
    fun testParseSingleImageEntry() {
        val html = block("2023-10-12", "memories/2023-10-12_ABC123-main.jpg")
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("2023-10-12_ABC123-main.jpg", e.fileName)
        assertEquals("ABC123", e.uuid)
        assertEquals("2023-10-12", e.date)
        assertFalse(e.isVideo)
        assertFalse(e.hasOverlay)
        assertNull(e.overlayFileName)
    }

    @Test
    fun testParseSingleVideoEntry() {
        val html = block("2022-06-01", "memories/2022-06-01_VID999-main.mp4")
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertEquals(1, entries.size)
        assertTrue(entries[0].isVideo)
    }

    @Test
    fun testParseEntryWithOverlay() {
        val html = block(
            "2023-10-12",
            "memories/2023-10-12_ABC123-main.jpg",
            "memories/2023-10-12_ABC123-overlay.png"
        )
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("ABC123", e.uuid)
        assertTrue(e.hasOverlay)
        assertEquals("2023-10-12_ABC123-overlay.png", e.overlayFileName)
    }

    @Test
    fun testDatePreferredFromFilename() {
        // Filename date (2023-08-15) differs from HTML text-line date (2023-10-12)
        val html = block("2023-10-12", "memories/2023-08-15_XYZ456-main.jpg")
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertEquals(1, entries.size)
        assertEquals("2023-08-15", entries[0].date, "Date should come from filename, not text-line")
    }

    @Test
    fun testParseMultipleEntries() {
        val html = block("2023-10-12", "memories/2023-10-12_A1-main.jpg") +
                   block("2022-05-01", "memories/2022-05-01_B2-main.mp4")
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertEquals(2, entries.size)
        assertEquals("A1", entries[0].uuid)
        assertEquals("B2", entries[1].uuid)
    }

    @Test
    fun testEmptyHtml() {
        val entries = ZipImportParser.parseMemoriesHtml("")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun testHtmlWithNoImageContainers() {
        val html = "<html><body><p>No memories here</p></body></html>"
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun testUuidExtractedCorrectly() {
        val html = block("2021-03-04", "memories/2021-03-04_F1A2B3C4-D5E6-7890-ABCD-EF1234567890-main.jpg")
        val entries = ZipImportParser.parseMemoriesHtml(html)
        assertEquals(1, entries.size)
        assertEquals("F1A2B3C4-D5E6-7890-ABCD-EF1234567890", entries[0].uuid)
    }
}
