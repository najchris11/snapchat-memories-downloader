package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataCorrelatorTest {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun hist(date: String, type: String = "Image", lat: Double? = null, lon: Double? = null) =
        HistMemoryEntry(
            fullDateTime = "$date 12:00:00 UTC",
            date = date,
            mediaType = type,
            latitude = lat,
            longitude = lon
        )

    private fun json(date: String, type: String = "Image", lat: Double? = null, lon: Double? = null) =
        JsonMemoryEntry(
            date = "$date 12:00:00 UTC",
            dateOnly = date,
            mediaType = type,
            latitude = lat,
            longitude = lon
        )

    private fun html(uuid: String, date: String, isVideo: Boolean = false) =
        HtmlMemoryEntry(
            fileName = "${date}_$uuid-main.${if (isVideo) "mp4" else "jpg"}",
            uuid = uuid,
            date = date,
            isVideo = isVideo,
            hasOverlay = false,
            overlayFileName = null
        )

    // ── Primary: hist positional alignment ──────────────────────────────────

    @Test
    fun testHistAlignment_singleEntry() {
        val histEntries = listOf(hist("2023-10-12", lat = 48.0, lon = 13.0))
        val htmlEntries = listOf(html("UUID-A", "2023-10-12"))
        val result = MetadataCorrelator.correlate(histEntries, emptyList(), htmlEntries)

        assertEquals(1, result.size)
        val corr = result["UUID-A"]
        assertNotNull(corr)
        assertEquals("2023-10-12 12:00:00 UTC", corr.fullDateTime)
        assertEquals(48.0, corr.latitude)
        assertEquals(13.0, corr.longitude)
        assertEquals(CorrSource.Hist, corr.source)
    }

    @Test
    fun testHistAlignment_multipleEntriesSameDay_positionalOrder() {
        // hist is newest-first; html is oldest-first
        // After reversing hist, positions should align with html oldest-first
        val histEntries = listOf(
            hist("2023-10-12").copy(fullDateTime = "2023-10-12 22:00:00 UTC"),  // newest
            hist("2023-10-12").copy(fullDateTime = "2023-10-12 10:00:00 UTC"),  // oldest
        )
        val htmlEntries = listOf(
            html("UUID-OLDER", "2023-10-12"),  // position 0 → oldest hist
            html("UUID-NEWER", "2023-10-12"),  // position 1 → newest hist
        )
        val result = MetadataCorrelator.correlate(histEntries, emptyList(), htmlEntries)

        assertEquals(2, result.size)
        assertEquals("2023-10-12 10:00:00 UTC", result["UUID-OLDER"]?.fullDateTime)
        assertEquals("2023-10-12 22:00:00 UTC", result["UUID-NEWER"]?.fullDateTime)
    }

    @Test
    fun testHistAlignment_imageAndVideoKeySeparation() {
        val histEntries = listOf(
            hist("2023-10-12", type = "Image"),
            hist("2023-10-12", type = "Video"),
        )
        val htmlEntries = listOf(
            html("IMG-UUID", "2023-10-12", isVideo = false),
            html("VID-UUID", "2023-10-12", isVideo = true),
        )
        val result = MetadataCorrelator.correlate(histEntries, emptyList(), htmlEntries)
        assertEquals(CorrSource.Hist, result["IMG-UUID"]?.source)
        assertEquals(CorrSource.Hist, result["VID-UUID"]?.source)
    }

    // ── Fallback: JSON bucket-pop ────────────────────────────────────────────

    @Test
    fun testJsonFallback_whenNoHistEntries() {
        val jsonEntries = listOf(json("2023-10-12", lat = 51.5, lon = -0.12))
        val htmlEntries = listOf(html("UUID-A", "2023-10-12"))
        val result = MetadataCorrelator.correlate(emptyList(), jsonEntries, htmlEntries)

        assertEquals(1, result.size)
        val corr = result["UUID-A"]
        assertNotNull(corr)
        assertEquals(CorrSource.Json, corr.source)
        assertEquals(51.5, corr.latitude)
    }

    @Test
    fun testJsonFallback_doesNotOverwriteHistMatch() {
        val histEntries = listOf(hist("2023-10-12", lat = 10.0, lon = 20.0))
        val jsonEntries = listOf(json("2023-10-12", lat = 99.0, lon = 99.0))
        val htmlEntries = listOf(html("UUID-A", "2023-10-12"))
        val result = MetadataCorrelator.correlate(histEntries, jsonEntries, htmlEntries)

        assertEquals(CorrSource.Hist, result["UUID-A"]?.source)
        assertEquals(10.0, result["UUID-A"]?.latitude)
    }

    // ── Mixed: some hist, some JSON ──────────────────────────────────────────

    @Test
    fun testMixed_histForSomeDaysJsonForOthers() {
        val histEntries = listOf(hist("2023-10-12"))
        val jsonEntries = listOf(json("2022-05-01"))
        val htmlEntries = listOf(
            html("UUID-A", "2023-10-12"),
            html("UUID-B", "2022-05-01"),
        )
        val result = MetadataCorrelator.correlate(histEntries, jsonEntries, htmlEntries)

        assertEquals(CorrSource.Hist, result["UUID-A"]?.source)
        assertEquals(CorrSource.Json, result["UUID-B"]?.source)
    }

    // ── Unmatched entries ────────────────────────────────────────────────────

    @Test
    fun testUnmatched_entryNotInHistOrJson() {
        val htmlEntries = listOf(html("UUID-ORPHAN", "2020-01-01"))
        val result = MetadataCorrelator.correlate(emptyList(), emptyList(), htmlEntries)
        assertTrue(result.isEmpty(), "Unmatched UUID should not appear in result")
    }

    @Test
    fun testUnmatched_partialCoverage() {
        val histEntries = listOf(hist("2023-10-12"))
        val htmlEntries = listOf(
            html("UUID-MATCHED", "2023-10-12"),
            html("UUID-UNMATCHED", "2019-03-14"),
        )
        val result = MetadataCorrelator.correlate(histEntries, emptyList(), htmlEntries)
        assertEquals(1, result.size)
        assertNotNull(result["UUID-MATCHED"])
        assertNull(result["UUID-UNMATCHED"])
    }

    // ── GPS null propagation ─────────────────────────────────────────────────

    @Test
    fun testGpsNullWhenHistEntryHasNoLocation() {
        val histEntries = listOf(hist("2023-10-12", lat = null, lon = null))
        val htmlEntries = listOf(html("UUID-A", "2023-10-12"))
        val result = MetadataCorrelator.correlate(histEntries, emptyList(), htmlEntries)
        assertNotNull(result["UUID-A"])
        assertNull(result["UUID-A"]?.latitude)
    }

    // ── Duplicate UUID de-duplication (caller responsibility) ────────────────

    @Test
    fun testCorrelationWithDedupedInput() {
        // Simulate caller passing distinctBy { uuid } result — only one entry per UUID
        val histEntries = listOf(
            hist("2023-10-12").copy(fullDateTime = "2023-10-12 10:00:00 UTC"),
            hist("2023-10-12").copy(fullDateTime = "2023-10-12 22:00:00 UTC"),
        )
        val htmlEntries = listOf(
            html("UUID-A", "2023-10-12"),  // only entry, not a duplicate
        )
        val result = MetadataCorrelator.correlate(histEntries, emptyList(), htmlEntries)
        // Should align position 0 → first reversed hist entry = oldest
        assertEquals(1, result.size)
        assertNotNull(result["UUID-A"])
    }
}
