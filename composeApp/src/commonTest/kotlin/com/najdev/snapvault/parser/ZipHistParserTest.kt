package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipHistParserTest {

    private fun row(
        datetime: String = "2023-10-12 15:30:00 UTC",
        type: String = "Image",
        location: String = "No location available"
    ) = "<tr><td>$datetime</td><td>$type</td><td>$location</td><td>some-link</td></tr>"

    @Test
    fun testParseSingleRowWithGps() {
        val html = row(location = "Latitude, Longitude: 48.26275, 13.296288")
        val entries = ZipHistParser.parse(html)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("2023-10-12 15:30:00 UTC", e.fullDateTime)
        assertEquals("2023-10-12", e.date)
        assertEquals("Image", e.mediaType)
        assertEquals(48.26275, e.latitude)
        assertEquals(13.296288, e.longitude)
    }

    @Test
    fun testParseSingleRowNoLocation() {
        val html = row(location = "No location available")
        val entries = ZipHistParser.parse(html)
        assertEquals(1, entries.size)
        assertNull(entries[0].latitude)
        assertNull(entries[0].longitude)
    }

    @Test
    fun testParseZeroCoordinatesTreatedAsNoGps() {
        val html = row(location = "Latitude, Longitude: 0.0, 0.0")
        val entries = ZipHistParser.parse(html)
        assertEquals(1, entries.size)
        assertNull(entries[0].latitude, "0,0 coordinates should be treated as no GPS")
        assertNull(entries[0].longitude)
    }

    @Test
    fun testParseVideoType() {
        val html = row(type = "Video")
        val entries = ZipHistParser.parse(html)
        assertEquals(1, entries.size)
        assertEquals("Video", entries[0].mediaType)
    }

    @Test
    fun testParseMultipleRows() {
        val html = row("2023-10-12 15:30:00 UTC", "Image", "Latitude, Longitude: 48.0, 13.0") +
                   row("2022-05-01 08:00:00 UTC", "Video", "No location available")
        val entries = ZipHistParser.parse(html)
        assertEquals(2, entries.size)
        assertEquals("2023-10-12", entries[0].date)
        assertEquals("2022-05-01", entries[1].date)
        assertEquals(48.0, entries[0].latitude)
        assertNull(entries[1].latitude)
    }

    @Test
    fun testDateExtractedFromDatetime() {
        val html = row(datetime = "2017-08-13 22:14:05 UTC")
        val entries = ZipHistParser.parse(html)
        assertEquals(1, entries.size)
        assertEquals("2017-08-13", entries[0].date)
        assertEquals("2017-08-13 22:14:05 UTC", entries[0].fullDateTime)
    }

    @Test
    fun testEmptyHtml() {
        assertTrue(ZipHistParser.parse("").isEmpty())
    }

    @Test
    fun testNegativeCoordinates() {
        val html = row(location = "Latitude, Longitude: -33.8688, 151.2093")
        val entries = ZipHistParser.parse(html)
        assertEquals(1, entries.size)
        assertEquals(-33.8688, entries[0].latitude)
        assertEquals(151.2093, entries[0].longitude)
    }
}
