package com.najdev.snapvault.downloader

import com.najdev.snapvault.model.MemoryItem
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadEngineTest {

    @Test
    fun testDateParsing() {
        val client = HttpClient(MockEngine { respondOk() })
        val downloader = DownloadEngine(client)

        assertEquals("20231012_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00 UTC"))
        assertEquals("20231012_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00"))
        assertEquals("20231012_000000", downloader.parseDateToFilenamePrefix("2023-10-12"))
        
        assertEquals("20231012_153000", downloader.parseDateToFilenamePrefix("12.10.2023 15:30:00"))
        assertEquals("20231012_000000", downloader.parseDateToFilenamePrefix("12.10.2023"))
        
        assertNull(downloader.parseDateToFilenamePrefix("invalid-date"))
    }

    @Test
    fun testFilenameBuilder() {
        val client = HttpClient(MockEngine { respondOk() })
        val downloader = DownloadEngine(client)

        val item1 = MemoryItem(
            id = "abc-123",
            url = "https://media.com/file.jpg?mid=abc-123",
            isGet = true,
            dateStr = "2023-10-12 15:30:00 UTC"
        )
        assertEquals("20231012_153000_abc-123.jpg", downloader.buildFilename(item1, null))

        val item2 = MemoryItem(
            id = "xyz-789",
            url = "https://media.com/file?mid=xyz-789",
            isGet = true,
            dateStr = null
        )
        assertEquals("xyz-789.mp4", downloader.buildFilename(item2, "video/mp4"))
    }
}
