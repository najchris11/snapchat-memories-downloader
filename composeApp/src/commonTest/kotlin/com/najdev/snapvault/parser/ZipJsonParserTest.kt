package com.najdev.snapvault.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipJsonParserTest {

    private fun json(vararg entries: String) =
        """{"Saved Media":[${entries.joinToString(",")}]}"""

    private fun entry(
        date: String = "2023-10-12 15:30:00 UTC",
        type: String = "Image",
        location: String = "No location available"
    ) = """{"Date":"$date","Media Type":"$type","Location":"$location"}"""

    @Test
    fun testParseSingleEntryWithGps() {
        val content = json(entry(location = "Latitude, Longitude: 48.26275, 13.296288"))
        val entries = ZipJsonParser.parse(content)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("2023-10-12 15:30:00 UTC", e.date)
        assertEquals("2023-10-12", e.dateOnly)
        assertEquals("Image", e.mediaType)
        assertEquals(48.26275, e.latitude)
        assertEquals(13.296288, e.longitude)
    }

    @Test
    fun testParseSingleEntryNoLocation() {
        val content = json(entry())
        val entries = ZipJsonParser.parse(content)
        assertEquals(1, entries.size)
        assertNull(entries[0].latitude)
        assertNull(entries[0].longitude)
    }

    @Test
    fun testParseZeroCoordinatesTreatedAsNoGps() {
        val content = json(entry(location = "Latitude, Longitude: 0.0, 0.0"))
        val entries = ZipJsonParser.parse(content)
        assertEquals(1, entries.size)
        assertNull(entries[0].latitude, "0,0 should be treated as no GPS")
    }

    @Test
    fun testParseVideoType() {
        val content = json(entry(type = "Video"))
        val entries = ZipJsonParser.parse(content)
        assertEquals(1, entries.size)
        assertEquals("Video", entries[0].mediaType)
    }

    @Test
    fun testDateOnlyExtraction() {
        val content = json(entry(date = "2017-08-13 22:14:05 UTC"))
        val entries = ZipJsonParser.parse(content)
        assertEquals(1, entries.size)
        assertEquals("2017-08-13", entries[0].dateOnly)
    }

    @Test
    fun testParseMultipleEntries() {
        val content = json(
            entry(date = "2023-10-12 15:30:00 UTC", type = "Image"),
            entry(date = "2022-05-01 08:00:00 UTC", type = "Video")
        )
        val entries = ZipJsonParser.parse(content)
        assertEquals(2, entries.size)
        assertEquals("Image", entries[0].mediaType)
        assertEquals("Video", entries[1].mediaType)
    }

    @Test
    fun testEmptySavedMediaArray() {
        val content = """{"Saved Media":[]}"""
        val entries = ZipJsonParser.parse(content)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun testMalformedJsonThrows() {
        assertFailsWith<IllegalArgumentException> {
            ZipJsonParser.parse("not json at all {{{")
        }
    }

    @Test
    fun testMissingSavedMediaKeyReturnsEmpty() {
        val content = """{"SomethingElse":[]}"""
        val entries = ZipJsonParser.parse(content)
        assertTrue(entries.isEmpty())
    }
}
