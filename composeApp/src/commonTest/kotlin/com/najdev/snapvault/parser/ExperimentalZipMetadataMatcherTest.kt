package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExperimentalZipMetadataMatcherTest {

    @Test
    fun testExactTimestampMatchAppliesGpsAndDate() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-01_A-main.jpg",
                uuid = "A",
                date = "2024-01-01",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
                captureEpochSecond = 1704110400L, // 2024-01-01 12:00:00 UTC
            ),
        )
        val records = listOf(
            ZipMemoryRecord(
                dateStr = "2024-01-01 12:00:00 UTC",
                epochSecond = 1704110400L,
                mediaType = MediaKind.Image,
                latitude = 48.2,
                longitude = 13.3,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(1, plan.targets.size)
        assertEquals("2024-01-01 12:00:00 UTC", plan.targets[0].dateStr)
        assertEquals(48.2, plan.targets[0].latitude)
        assertEquals(13.3, plan.targets[0].longitude)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun testOverlayInheritsMainFileMetadata() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-02_C-main.mp4",
                uuid = "C",
                date = "2024-01-02",
                isVideo = true,
                hasOverlay = true,
                overlayFileName = "2024-01-02_C-overlay.png",
                captureEpochSecond = 1704186000L, // 2024-01-02 09:00:00 UTC
            ),
        )
        val records = listOf(
            ZipMemoryRecord(
                dateStr = "2024-01-02 09:00:00 UTC",
                epochSecond = 1704186000L,
                mediaType = MediaKind.Video,
                latitude = 1.0,
                longitude = 2.0,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(2, plan.targets.size)
        assertEquals("2024-01-02_C-main.mp4", plan.targets[0].fileName)
        assertEquals("2024-01-02_C-overlay.png", plan.targets[1].fileName)
        assertFalse(plan.targets[1].hasOverlay)
        assertEquals(plan.targets[0].dateStr, plan.targets[1].dateStr)
        assertEquals(plan.targets[0].latitude, plan.targets[1].latitude)
        assertEquals(plan.targets[0].longitude, plan.targets[1].longitude)
    }

    @Test
    fun testNoMatchingTimestampFallsBackToDateOnly() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-01_A-main.jpg",
                uuid = "A",
                date = "2024-01-01",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
                captureEpochSecond = 1704110400L,
            ),
        )
        val records = listOf(
            ZipMemoryRecord(
                dateStr = "2024-06-15 03:00:00 UTC",
                epochSecond = 1718420400L,
                mediaType = MediaKind.Image,
                latitude = 48.2,
                longitude = 13.3,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(1, plan.targets.size)
        assertEquals("2024-01-01 00:00:00 UTC", plan.targets[0].dateStr)
        assertNull(plan.targets[0].latitude)
        assertTrue(plan.warnings.any { it.contains("No files could be matched") })
    }

    @Test
    fun testMissingCaptureTimestampFallsBackToDateOnly() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-01_A-main.jpg",
                uuid = "A",
                date = "2024-01-01",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
                captureEpochSecond = null,
            ),
        )
        val records = listOf(
            ZipMemoryRecord(
                dateStr = "2024-01-01 12:00:00 UTC",
                epochSecond = 1704110400L,
                mediaType = MediaKind.Image,
                latitude = 48.2,
                longitude = 13.3,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(1, plan.targets.size)
        assertEquals("2024-01-01 00:00:00 UTC", plan.targets[0].dateStr)
        assertNull(plan.targets[0].latitude)
    }

    @Test
    fun testAmbiguousLocationCollisionDropsGpsButKeepsDate() {
        // Two photos saved in the exact same second at two different cities: there is no
        // identifier that says which record belongs to which file, so GPS must not be
        // guessed — but the date is safe since it's identical for both records.
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-03-05_A-main.jpg",
                uuid = "A",
                date = "2024-03-05",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
                captureEpochSecond = 1709626530L,
            ),
        )
        val records = listOf(
            ZipMemoryRecord(
                dateStr = "2024-03-05 08:15:30 UTC",
                epochSecond = 1709626530L,
                mediaType = MediaKind.Image,
                latitude = 40.0,
                longitude = -83.0,
            ),
            ZipMemoryRecord(
                dateStr = "2024-03-05 08:15:30 UTC",
                epochSecond = 1709626530L,
                mediaType = MediaKind.Image,
                latitude = 51.5,
                longitude = -0.1,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(1, plan.targets.size)
        assertEquals("2024-03-05 08:15:30 UTC", plan.targets[0].dateStr)
        assertNull(plan.targets[0].latitude)
        assertNull(plan.targets[0].longitude)
        assertTrue(plan.warnings.any { it.contains("conflicting location") })
    }

    @Test
    fun testCollisionWithinOneKilometerKeepsLocation() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-03-05_A-main.jpg",
                uuid = "A",
                date = "2024-03-05",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
                captureEpochSecond = 1709626530L,
            ),
        )
        val records = listOf(
            ZipMemoryRecord(
                dateStr = "2024-03-05 08:15:30 UTC",
                epochSecond = 1709626530L,
                mediaType = MediaKind.Image,
                latitude = 40.0000,
                longitude = -83.0000,
            ),
            ZipMemoryRecord(
                dateStr = "2024-03-05 08:15:30 UTC",
                epochSecond = 1709626530L,
                mediaType = MediaKind.Image,
                latitude = 40.0020,
                longitude = -83.0000,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(1, plan.targets.size)
        assertEquals(40.0000, plan.targets[0].latitude)
    }

    @Test
    fun testNoJsonMetadataFallsBackToDateOnlyForAllFiles() {
        val entries = listOf(
            HtmlMemoryEntry(
                fileName = "2024-01-01_A-main.jpg",
                uuid = "A",
                date = "2024-01-01",
                isVideo = false,
                hasOverlay = false,
                overlayFileName = null,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, emptyList())

        assertEquals(1, plan.targets.size)
        assertEquals("2024-01-01 00:00:00 UTC", plan.targets[0].dateStr)
        assertTrue(plan.warnings.isNotEmpty())
    }

    // Regression test: memories_history.json in the real ZIP export format has no working
    // "Download Link"/"Media Download Url" (both are always empty strings) — only "Date",
    // "Media Type", and "Location". A parser that requires a URL (like the legacy
    // HistoryParser.parseJson, built for the old CDN-link export) silently discards every
    // record here, which made the experimental matcher a permanent no-op. This asserts the
    // dedicated parser reads the real schema instead.
    @Test
    fun testParseZipMemoryRecordsHandlesRealExportSchemaWithoutDownloadLinks() {
        val json = """
            {
              "Saved Media": [
                {
                  "Date": "2015-01-08 17:36:14 UTC",
                  "Media Type": "Image",
                  "Location": "Latitude, Longitude: 40.012688, -83.066986",
                  "Download Link": "",
                  "Media Download Url": ""
                },
                {
                  "Date": "2026-06-05 16:23:27 UTC",
                  "Media Type": "Video",
                  "Location": "Latitude, Longitude: 0.0, 0.0",
                  "Download Link": "",
                  "Media Download Url": ""
                }
              ]
            }
        """.trimIndent()

        val records = parseZipMemoryRecords(json)

        assertEquals(2, records.size)
        val image = records.first { it.mediaType == MediaKind.Image }
        assertEquals(1420738574L, image.epochSecond)
        assertEquals(40.012688, image.latitude)
        assertEquals(-83.066986, image.longitude)

        // 0.0,0.0 means "no location captured", not "null island" — must not be treated as GPS.
        val video = records.first { it.mediaType == MediaKind.Video }
        assertNull(video.latitude)
        assertNull(video.longitude)
    }

    @Test
    fun testParseZipMemoryRecordsIgnoresUnrecognizedMediaType() {
        val json = """
            {
              "Saved Media": [
                { "Date": "2024-01-01 00:00:00 UTC", "Media Type": "Sticker", "Location": null }
              ]
            }
        """.trimIndent()

        val records = parseZipMemoryRecords(json)

        assertEquals(1, records.size)
        assertNull(records[0].mediaType)
    }
}
