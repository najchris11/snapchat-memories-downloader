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
    fun testCollisionWithOneLocatedAndOneUnlocatedRecordOmitsGps() {
        // Two photos saved in the same second, but only one JSON record has a location.
        // There's no way to tell which physical file that location belongs to, so
        // neither file may be tagged with it — applying it to both would risk
        // geotagging the wrong one.
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
            HtmlMemoryEntry(
                fileName = "2024-03-05_B-main.jpg",
                uuid = "B",
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
                latitude = null,
                longitude = null,
            ),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(2, plan.targets.size)
        plan.targets.forEach { target ->
            assertNull(target.latitude, "GPS must not be applied to ${target.fileName} — the collision has an unlocated candidate")
            assertNull(target.longitude)
            assertEquals("2024-03-05 08:15:30 UTC", target.dateStr)
        }
        assertTrue(plan.warnings.any { it.contains("conflicting location") })
    }

    @Test
    fun nearbyButDifferentLocationsOmitGpsRatherThanGuessingTheFirst() {
        // Real exports contain same-second records a few hundred metres apart. The old
        // one-kilometre tolerance assigned the first coordinate to every matching file.
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
        assertNull(plan.targets[0].latitude)
        assertNull(plan.targets[0].longitude)
        assertEquals("2024-03-05 08:15:30 UTC", plan.targets[0].dateStr)
        assertTrue(plan.warnings.any { it.contains("conflicting location") })
    }

    @Test
    fun oneHistoryRecordCannotGeotagTwoFilesWithTheSameSecond() {
        // A second file can share the ZIP timestamp without having a corresponding history
        // record. The old matcher copied the one record's GPS onto both files.
        val entries = listOf("A", "B").map { id ->
            HtmlMemoryEntry("2024-03-05_${id}-main.jpg", id, "2024-03-05", false, false, null, 1709626530L)
        }
        val record = ZipMemoryRecord("2024-03-05 08:15:30 UTC", 1709626530L, MediaKind.Image, 40.0, -83.0)

        val plan = buildExperimentalZipMetadataPlan(entries, listOf(record))

        assertEquals(2, plan.targets.size)
        plan.targets.forEach {
            assertEquals(record.dateStr, it.dateStr)
            assertNull(it.latitude)
            assertNull(it.longitude)
        }
        assertTrue(plan.warnings.any { it.contains("ambiguous") })
    }

    @Test
    fun sameSecondFilesKeepGpsWhenAllHistoryRowsAgreeExactly() {
        val entries = listOf("A", "B").map { id ->
            HtmlMemoryEntry("2024-03-05_${id}-main.jpg", id, "2024-03-05", false, false, null, 1709626530L)
        }
        val records = listOf(
            ZipMemoryRecord("2024-03-05 08:15:30 UTC", 1709626530L, MediaKind.Image, 40.0, -83.0),
            ZipMemoryRecord("2024-03-05 08:15:30 UTC", 1709626530L, MediaKind.Image, 40.0, -83.0),
        )

        val plan = buildExperimentalZipMetadataPlan(entries, records)

        assertEquals(2, plan.targets.size)
        plan.targets.forEach {
            assertEquals(40.0, it.latitude)
            assertEquals(-83.0, it.longitude)
        }
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun matchingSecondWithConflictingFilenameDateUsesTheFilenameDate() {
        // A bad ZIP timestamp could happen to hit a real history record from another day.
        // The old matcher then replaced the file's date and GPS with that unrelated row.
        val entry = HtmlMemoryEntry("2024-03-06_A-main.jpg", "A", "2024-03-06", false, false, null, 1709626530L)
        val record = ZipMemoryRecord("2024-03-05 08:15:30 UTC", 1709626530L, MediaKind.Image, 40.0, -83.0)

        val target = buildExperimentalZipMetadataPlan(listOf(entry), listOf(record)).targets.single()

        assertEquals("2024-03-06 00:00:00 UTC", target.dateStr)
        assertNull(target.latitude)
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
