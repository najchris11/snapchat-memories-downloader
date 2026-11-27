package com.najdev.snapvault.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpsTimeZoneTest {
    @Test
    fun captureOffsetUsesTheLocationAndTheHistoricalCaptureInstant() {
        val winter = gpsCaptureTime("2024-01-15 03:00:00 UTC", 40.7128, -74.0060)!!
        assertEquals("2024:01:14 22:00:00", winter.exifLocalDateTime)
        assertEquals("-05:00", winter.offset)
        val summer = gpsCaptureTime("2024-07-15 03:00:00 UTC", 40.7128, -74.0060)!!
        assertEquals("2024:07:14 23:00:00", summer.exifLocalDateTime)
        assertEquals("-04:00", summer.offset)
        val kathmandu = gpsCaptureTime("2024-01-15 00:00:00 UTC", 27.7172, 85.3240)!!
        assertEquals("2024:01:15 05:45:00", kathmandu.exifLocalDateTime)
        assertEquals("+05:45", kathmandu.offset)
    }

    @Test
    fun incompleteDatesAndInvalidCoordinatesNeverProduceAGuessedOffset() {
        assertNull(gpsCaptureTime("2024-01-15", 40.7128, -74.0060))
        assertNull(gpsCaptureTime("2024-01-15 00:00:00 UTC", 95.0, 0.0))
        assertNull(gpsCaptureTime("2024-01-15 00:00:00 UTC", Double.NaN, 0.0))
        assertNull(gpsCaptureTime(null, 40.7128, -74.0060))
    }
    @Test
    fun gpsPhotoWritesMatchingLocalExifDatesAndOffsetsWhileMtimeStaysUtc() {
        val photo = java.io.File.createTempFile("gps-timezone", ".jpg")
        try {
            var command = emptyList<String>()
            val processor = DesktopMediaProcessor(gpsExiftool = { "test-exiftool" }, writeGpsTags = { args -> command = args; 0 })
            kotlin.test.assertTrue(processor.writeGpsMetadata(photo.path, 40.7128, -74.0060, "2024-01-15 03:00:00 UTC"))
            kotlin.test.assertTrue("-DateTimeOriginal=2024:01:14 22:00:00" in command)
            kotlin.test.assertTrue("-CreateDate=2024:01:14 22:00:00" in command)
            kotlin.test.assertTrue("-OffsetTimeOriginal=-05:00" in command)
            kotlin.test.assertTrue("-OffsetTimeDigitized=-05:00" in command)
            assertEquals(java.time.Instant.parse("2024-01-15T03:00:00Z").toEpochMilli(), photo.lastModified())
        } finally { photo.delete() }
    }
    @Test
    fun dateOnlyRewritesClearAllPreviouslyStoredLocalOffsets() {
        val args = imageDateArguments("2024:01:15 00:00:00")
        kotlin.test.assertTrue("-OffsetTimeOriginal=" in args)
        kotlin.test.assertTrue("-OffsetTimeDigitized=" in args)
        kotlin.test.assertTrue("-OffsetTime=" in args)
    }
}
